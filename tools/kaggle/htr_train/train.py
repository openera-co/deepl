# Kaggle job: train a phone-sized French handwriting recognizer (line level).
# TrOCR-small (62M) fine-tuned on RIMES-2011 lines (real French handwriting, MIT) + synthetic French
# lines rendered in open-licence handwriting fonts. Reports CER/WER on the RIMES test set against
# baselines, then exports int8 ONNX for the app.
import subprocess, os, random, json, time, io, math, urllib.request
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh('pip install -q "transformers==4.56.2" "datasets>=3" jiwer 2>&1 | tail -2')
import torch, jiwer, numpy as np
from datasets import load_dataset, Dataset, concatenate_datasets
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from transformers import (TrOCRProcessor, VisionEncoderDecoderModel, Seq2SeqTrainer, Seq2SeqTrainingArguments, default_data_collator)
OUT = "/kaggle/working"; R = random.Random(0)
log = {}
def save_log(): json.dump(log, open(f"{OUT}/results.json", "w"), indent=1)

# ---- data
rimes = load_dataset("Teklia/RIMES-2011-line")
print(rimes, flush=True)

FONTS = {"Caveat": "ofl/caveat/Caveat%5Bwght%5D.ttf", "Kalam": "ofl/kalam/Kalam-Regular.ttf", "PatrickHand": "ofl/patrickhand/PatrickHand-Regular.ttf",
         "IndieFlower": "ofl/indieflower/IndieFlower-Regular.ttf", "ShadowsIntoLight": "ofl/shadowsintolight/ShadowsIntoLight.ttf",
         "GloriaHallelujah": "ofl/gloriahallelujah/GloriaHallelujah.ttf", "ArchitectsDaughter": "ofl/architectsdaughter/ArchitectsDaughter-Regular.ttf",
         "ReenieBeanie": "ofl/reeniebeanie/ReenieBeanie.ttf", "Handlee": "ofl/handlee/Handlee-Regular.ttf", "NothingYouCouldDo": "ofl/nothingyoucoulddo/NothingYouCouldDo.ttf",
         "CoveredByYourGrace": "ofl/coveredbyyourgrace/CoveredByYourGrace.ttf", "JustAnotherHand": "ofl/justanotherhand/JustAnotherHand-Regular.ttf",
         "Neucha": "ofl/neucha/Neucha.ttf", "SueEllenFrancisco": "ofl/sueellenfrancisco/SueEllenFrancisco.ttf", "WaitingfortheSunrise": "ofl/waitingforthesunrise/WaitingfortheSunrise.ttf",
         "DawningofaNewDay": "ofl/dawningofanewday/DawningofaNewDay.ttf", "Mansalva": "ofl/mansalva/Mansalva-Regular.ttf", "Schoolbell": "apache/schoolbell/Schoolbell-Regular.ttf"}
os.makedirs("/tmp/fonts", exist_ok=True); fonts = []
for n, p in FONTS.items():
    f = f"/tmp/fonts/{n}.ttf"
    try:
        if not os.path.exists(f): urllib.request.urlretrieve("https://raw.githubusercontent.com/google/fonts/main/" + p, f)
        ImageFont.truetype(f, 40); fonts.append(f)
    except Exception as e: print("font", n, e)
print("fonts", len(fonts), flush=True)

# French text: Wikipedia sentences (streamed) cut into handwritten-line lengths.
texts = []
try:
    wiki = load_dataset("wikimedia/wikipedia", "20231101.fr", split="train", streaming=True)
    for art in wiki:
        for para in art["text"].split("\n"):
            words = para.split()
            i = 0
            while i < len(words) and len(words) > 5:
                n = R.randint(4, 11); line = " ".join(words[i:i + n]); i += n
                if 12 <= len(line) <= 70 and not any(c in line for c in "{}|=<>[]\\"): texts.append(line)
        if len(texts) > 26000: break
except Exception as e: print("wiki failed", e)
texts += [t for t in rimes["train"]["text"]]
R.shuffle(texts); print("synthetic texts", len(texts), flush=True)

def render(text, r):
    font = ImageFont.truetype(r.choice(fonts), r.randint(40, 64))
    w = int(font.getlength(text)) + 40; h = int(font.size * 1.6)
    bg = r.choice([255, 250, 245, 240]); img = Image.new("L", (w, h), bg); d = ImageDraw.Draw(img)
    ink = r.randint(0, 80); x = 20
    for ch in text:  # per-character baseline jitter, like a hand
        d.text((x, h * 0.18 + r.uniform(-2, 2)), ch, font=font, fill=ink); x += font.getlength(ch) * r.uniform(0.96, 1.04)
    if r.random() < 0.4:
        for y in range(int(h * 0.85), int(h * 0.9)): d.line([(0, y), (w, y)], fill=min(255, bg - 40) if r.random() < 0.3 else bg)
    img = img.rotate(r.uniform(-1.5, 1.5), expand=True, fillcolor=bg)
    if r.random() < 0.5: img = img.filter(ImageFilter.GaussianBlur(r.uniform(0.2, 1.0)))
    if r.random() < 0.3:
        a = np.array(img).astype(np.int16); a += np.random.default_rng(r.randint(0, 9999)).integers(-18, 18, a.shape); img = Image.fromarray(a.clip(0, 255).astype(np.uint8))
    s = 128 / img.height
    return img.resize((max(8, int(img.width * s)), 128)).convert("RGB")

N_SYN = min(20000, len(texts))
syn = {"image": [], "text": []}
for i in range(N_SYN):
    r = random.Random(i); syn["image"].append(render(texts[i], r)); syn["text"].append(texts[i])
syn_test = {"image": [render(t, random.Random(10**6 + i)) for i, t in enumerate(texts[N_SYN:N_SYN + 500])], "text": texts[N_SYN:N_SYN + 500]}
print("rendered", len(syn["text"]), flush=True)

# ---- model
BASE = "microsoft/trocr-small-handwritten"
proc = TrOCRProcessor.from_pretrained(BASE)
def evaluate(model, ds, name, n=None, beams=1, bs=32):
    model.eval(); dev = next(model.parameters()).device
    imgs, refs = ds["image"][:n] if n else ds["image"], ds["text"][:n] if n else ds["text"]
    hyps = []; t0 = time.time()
    for i in range(0, len(imgs), bs):
        pv = proc(images=[im.convert("RGB") for im in imgs[i:i + bs]], return_tensors="pt").pixel_values.to(dev)
        with torch.inference_mode(), torch.autocast("cuda", dtype=torch.float16):
            g = model.generate(pv, max_new_tokens=96, num_beams=beams)
        hyps += proc.batch_decode(g, skip_special_tokens=True)
    cer = jiwer.cer(list(refs), hyps); wer = jiwer.wer(list(refs), hyps)
    log[name] = dict(cer=round(100 * cer, 2), wer=round(100 * wer, 2), lines=len(refs), sec=round(time.time() - t0, 1)); print(name, log[name], flush=True); save_log()
    return hyps

rtest = rimes["test"]
base = VisionEncoderDecoderModel.from_pretrained(BASE).cuda()
evaluate(base, rtest, "trocr-small-handwritten (English, no training) RIMES test")
del base
try:
    big = VisionEncoderDecoderModel.from_pretrained("dj0w/trocr-french-handwriting-v5").cuda()
    p2 = TrOCRProcessor.from_pretrained("dj0w/trocr-french-handwriting-v5")
    _p = proc; proc = p2; evaluate(big, rtest, "dj0w trocr-base French v5 (334M) RIMES test"); proc = _p; del big
except Exception as e: log["dj0w"] = str(e)[:300]
torch.cuda.empty_cache()

model = VisionEncoderDecoderModel.from_pretrained(BASE)
model.config.decoder_start_token_id = proc.tokenizer.cls_token_id if proc.tokenizer.cls_token_id is not None else model.config.decoder.bos_token_id
model.config.pad_token_id = proc.tokenizer.pad_token_id
model.config.eos_token_id = proc.tokenizer.sep_token_id if proc.tokenizer.sep_token_id is not None else proc.tokenizer.eos_token_id
model.config.vocab_size = model.config.decoder.vocab_size
model.generation_config.decoder_start_token_id = model.config.decoder_start_token_id
model.generation_config.pad_token_id = model.config.pad_token_id; model.generation_config.eos_token_id = model.config.eos_token_id
model.generation_config.max_new_tokens = 96

class Lines(torch.utils.data.Dataset):
    def __init__(self, imgs, txts, aug=False): self.i, self.t, self.aug = imgs, txts, aug
    def __len__(self): return len(self.t)
    def __getitem__(self, k):
        im = self.i[k].convert("RGB")
        if self.aug and random.random() < 0.3: im = im.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 0.8)))
        pv = proc(images=im, return_tensors="pt").pixel_values[0]
        lab = proc.tokenizer(self.t[k], max_length=96, truncation=True, padding="max_length").input_ids
        lab = [l if l != proc.tokenizer.pad_token_id else -100 for l in lab]
        return {"pixel_values": pv, "labels": torch.tensor(lab)}
tr_imgs = list(rimes["train"]["image"]) * 2 + syn["image"]; tr_txt = list(rimes["train"]["text"]) * 2 + syn["text"]
train = Lines(tr_imgs, tr_txt, aug=True)
val = Lines(list(rimes["validation"]["image"])[:400], list(rimes["validation"]["text"])[:400])
args = Seq2SeqTrainingArguments(output_dir="/tmp/ckpt", per_device_train_batch_size=24, per_device_eval_batch_size=24, learning_rate=6e-5,
    num_train_epochs=8, warmup_ratio=0.05, weight_decay=0.01, fp16=True, logging_steps=100, save_strategy="no", eval_strategy="epoch",
    predict_with_generate=False, dataloader_num_workers=4, report_to=[], lr_scheduler_type="cosine", label_smoothing_factor=0.05)
trainer = Seq2SeqTrainer(model=model, args=args, train_dataset=train, eval_dataset=val, data_collator=default_data_collator)
t0 = time.time(); trainer.train(); log["train_minutes"] = round((time.time() - t0) / 60, 1); save_log()
model = trainer.model.cuda()
evaluate(model, rtest, "carnet trocr-small French (trained) RIMES test")
evaluate(model, rtest, "carnet trocr-small French (trained) RIMES test beam4", beams=4)
evaluate(model, syn_test, "carnet trocr-small French (trained) synthetic test")
model.save_pretrained(f"{OUT}/carnet-htr-fr"); proc.save_pretrained(f"{OUT}/carnet-htr-fr")

# ONNX export for the phone is done from this checkpoint by tools/export_htr.py.
save_log(); print(json.dumps(log, indent=1))
