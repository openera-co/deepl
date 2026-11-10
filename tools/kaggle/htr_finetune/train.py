# Kaggle job: make the app's handwriting reader (dj0w/trocr-french-handwriting-v5, MIT) better at
# students' notes: gentle fine-tuning on RIMES-2011 lines (real French handwriting, MIT, keeps what it
# knows) + synthetic school-text lines (Vikidia sentences in open handwriting fonts, rendered like
# phone photos of notebooks). Gates: RIMES test must not get worse; held-out fonts must improve.
import subprocess, os, random, json, time, re, urllib.request
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh('pip install -q "transformers==4.56.2" "datasets>=3" jiwer 2>&1 | tail -2')
import torch, jiwer, numpy as np
from datasets import load_dataset
from huggingface_hub import snapshot_download
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance
from transformers import TrOCRProcessor, VisionEncoderDecoderModel, Seq2SeqTrainer, Seq2SeqTrainingArguments, default_data_collator
OUT = "/kaggle/working"; log = {}
def save_log(): json.dump(log, open(f"{OUT}/results.json", "w"), indent=1)
BASE = "dj0w/trocr-french-handwriting-v5"
rimes = load_dataset("Teklia/RIMES-2011-line")

FONTS = {"Caveat": "ofl/caveat/Caveat%5Bwght%5D.ttf", "Kalam": "ofl/kalam/Kalam-Regular.ttf", "PatrickHand": "ofl/patrickhand/PatrickHand-Regular.ttf",
         "IndieFlower": "ofl/indieflower/IndieFlower-Regular.ttf", "ShadowsIntoLight": "ofl/shadowsintolight/ShadowsIntoLight.ttf",
         "GloriaHallelujah": "ofl/gloriahallelujah/GloriaHallelujah.ttf", "ArchitectsDaughter": "ofl/architectsdaughter/ArchitectsDaughter-Regular.ttf",
         "Handlee": "ofl/handlee/Handlee-Regular.ttf", "CoveredByYourGrace": "ofl/coveredbyyourgrace/CoveredByYourGrace.ttf",
         "JustAnotherHand": "ofl/justanotherhand/JustAnotherHand-Regular.ttf", "Neucha": "ofl/neucha/Neucha.ttf", "Mansalva": "ofl/mansalva/Mansalva-Regular.ttf",
         "Schoolbell": "apache/schoolbell/Schoolbell-Regular.ttf", "DancingScript": "ofl/dancingscript/DancingScript%5Bwght%5D.ttf",
         "Satisfy": "apache/satisfy/Satisfy-Regular.ttf", "ReenieBeanie": "ofl/reeniebeanie/ReenieBeanie.ttf"}
os.makedirs("/tmp/fonts", exist_ok=True); fonts = {}
for n, p in FONTS.items():
    f = f"/tmp/fonts/{n}.ttf"
    try:
        if not os.path.exists(f): urllib.request.urlretrieve("https://raw.githubusercontent.com/google/fonts/main/" + p, f)
        ImageFont.truetype(f, 40); fonts[n] = f
    except Exception as e: print("font", n, e)
HELD_OUT = {"Satisfy", "Mansalva", "Handlee"}  # never seen in training: the synthetic test uses only these
train_fonts = [f for n, f in fonts.items() if n not in HELD_OUT]; test_fonts = [f for n, f in fonts.items() if n in HELD_OUT]
print("fonts", len(train_fonts), "train,", len(test_fonts), "test", flush=True)

# School text: Vikidia (French encyclopedia for students), cut into handwritten-line lengths.
def vikidia():
    """Vikidia (CC BY 3.0, Zenodo 6327828) as one archive: thousands of small Hugging Face files get rate-limited."""
    if not os.path.isdir("/tmp/vk/Vikidia-EnFr-Parsed"):
        os.makedirs("/tmp/vk", exist_ok=True)
        sh("curl -sL https://zenodo.org/api/records/6327828/files/Vikidia-EnFr-Parsed.tar.gz/content | tar xzf - -C /tmp/vk")
    return "/tmp/vk/Vikidia-EnFr-Parsed"
vk = vikidia()
texts = []; R = random.Random(0)
for fo in ["fr", "frsimple"]:
    for f in sorted(os.listdir(os.path.join(vk, fo))):
        for para in open(os.path.join(vk, fo, f), encoding="utf-8", errors="ignore").read().split("\n"):
            words = para.split(); i = 0
            while i < len(words) and len(words) > 4:
                n = R.randint(3, 10); line = " ".join(words[i:i + n]); i += n
                if 8 <= len(line) <= 72 and not re.search(r"[{}|=<>\[\]\\]|https?:", line): texts.append(line)
R.shuffle(texts); print("texts", len(texts), flush=True)

def render(text, r, font_files):
    """A handwritten line as a phone photo of a notebook: ink colour, slant, jitter, ruled line, blur, light, JPEG."""
    font = ImageFont.truetype(r.choice(font_files), r.randint(38, 66))
    w = int(font.getlength(text) * 1.08) + 50; h = int(font.size * 1.7)
    paper = r.choice([(252, 252, 248), (246, 242, 230), (255, 255, 255), (238, 236, 228)])
    img = Image.new("RGB", (w, h), paper); d = ImageDraw.Draw(img)
    if r.random() < 0.6:  # notebook rule under the line
        y = int(h * r.uniform(0.74, 0.86)); d.line([(0, y), (w, y)], fill=(160 + r.randint(-20, 20), 185, 230), width=r.choice([1, 2]))
    ink = r.choice([(25, 40, 140), (20, 20, 25), (30, 60, 160), (40, 40, 60), (110, 20, 30)])
    x = 22; base = h * 0.16
    for ch in text:
        d.text((x, base + r.uniform(-2.5, 2.5)), ch, font=font, fill=ink); x += font.getlength(ch) * r.uniform(0.95, 1.06)
    img = img.crop((0, 0, int(x) + 22, h))
    shear = r.uniform(-0.18, 0.18)  # slant
    img = img.transform(img.size, Image.AFFINE, (1, shear, -shear * h / 2, 0, 1, 0), fillcolor=paper)
    img = img.rotate(r.uniform(-2, 2), expand=True, fillcolor=paper)
    if r.random() < 0.6: img = img.filter(ImageFilter.GaussianBlur(r.uniform(0.2, 1.1)))
    if r.random() < 0.5: img = ImageEnhance.Brightness(img).enhance(r.uniform(0.75, 1.1))
    if r.random() < 0.4:  # uneven light
        a = np.array(img).astype(np.float32); g = np.linspace(r.uniform(0.75, 1.0), r.uniform(0.9, 1.1), a.shape[1])[None, :, None]; img = Image.fromarray((a * g).clip(0, 255).astype(np.uint8))
    if r.random() < 0.5:
        import io; b = io.BytesIO(); img.save(b, "JPEG", quality=r.randint(45, 85)); img = Image.open(io.BytesIO(b.getvalue())).convert("RGB")
    return img

N_SYN = 24000
syn_img = [render(texts[i], random.Random(i), train_fonts) for i in range(N_SYN)]; syn_txt = texts[:N_SYN]
test_txt = texts[N_SYN:N_SYN + 600]; test_img = [render(t, random.Random(10**6 + i), test_fonts) for i, t in enumerate(test_txt)]
print("rendered", len(syn_img), flush=True)

proc = TrOCRProcessor.from_pretrained(BASE)
def evaluate(model, imgs, refs, name, bs=24):
    model.eval(); hyps = []; t0 = time.time()
    for i in range(0, len(imgs), bs):
        pv = proc(images=[im.convert("RGB") for im in imgs[i:i + bs]], return_tensors="pt").pixel_values.cuda()
        with torch.inference_mode(), torch.autocast("cuda", dtype=torch.float16): g = model.generate(pv, max_new_tokens=96, num_beams=1)
        hyps += [h.strip() for h in proc.batch_decode(g, skip_special_tokens=True)]
    log[name] = dict(cer=round(100 * jiwer.cer(list(refs), hyps), 2), wer=round(100 * jiwer.wer(list(refs), hyps), 2), lines=len(refs), sec=round(time.time() - t0, 1))
    print(name, log[name], flush=True); save_log(); return hyps

rt_img, rt_txt = list(rimes["test"]["image"]), list(rimes["test"]["text"])
model = VisionEncoderDecoderModel.from_pretrained(BASE).cuda()
evaluate(model, rt_img, rt_txt, "before: RIMES test")
evaluate(model, test_img, test_txt, "before: school lines, unseen fonts")

# This transformers version computes VisionEncoderDecoder's loss as a causal-LM loss, which shifts the
# labels a second time: the model learns to predict the token after next (two earlier runs: loss fell
# while RIMES CER went 5.7% → 76–97%). The loss is computed here instead: decoder input = the start
# token the model generates with (2) + labels[:-1], cross-entropy on labels directly.
gc = model.generation_config
START = gc.decoder_start_token_id if gc.decoder_start_token_id is not None else model.config.decoder_start_token_id
model.config.decoder_start_token_id = START; model.config.pad_token_id = proc.tokenizer.pad_token_id
tok = proc.tokenizer
def ids_for(t): return tok(t, max_length=95, truncation=True).input_ids  # <s> text </s>, as the model generates
def seq_loss(m, pv, lab):
    dec = torch.cat([torch.full((lab.size(0), 1), START, device=lab.device), lab[:, :-1]], 1).masked_fill(torch.cat([torch.zeros((lab.size(0), 1), dtype=torch.bool, device=lab.device), lab[:, :-1] == -100], 1), tok.pad_token_id)
    logits = m(pixel_values=pv, decoder_input_ids=dec).logits
    return torch.nn.functional.cross_entropy(logits.reshape(-1, logits.size(-1)).float(), lab.reshape(-1), ignore_index=-100)
probe_i = list(rimes["validation"]["image"])[:32]; probe_t = list(rimes["validation"]["text"])[:32]
with torch.inference_mode():
    pv = proc(images=[im.convert("RGB") for im in probe_i], return_tensors="pt").pixel_values.cuda()
    labs = [ids_for(t) for t in probe_t]; mx = max(map(len, labs))
    probe = seq_loss(model.eval(), pv, torch.tensor([l + [-100] * (mx - len(l)) for l in labs]).cuda()).item()
log["probe loss before (should be < 1)"] = round(probe, 3); save_log(); print("probe loss", probe, flush=True)
assert probe < 1.5, "labels still don't match the model"

class CTrainer(Seq2SeqTrainer):
    def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
        loss = seq_loss(model, inputs["pixel_values"], inputs["labels"])
        return (loss, None) if return_outputs else loss

class Lines(torch.utils.data.Dataset):
    def __init__(self, imgs, txts): self.i, self.t = imgs, txts
    def __len__(self): return len(self.t)
    def __getitem__(self, k):
        pv = proc(images=self.i[k].convert("RGB"), return_tensors="pt").pixel_values[0]
        ids = ids_for(self.t[k]); lab = ids + [-100] * (96 - len(ids))
        return {"pixel_values": pv, "labels": torch.tensor(lab)}
tr = Lines(list(rimes["train"]["image"]) + syn_img, list(rimes["train"]["text"]) + syn_txt)
args = Seq2SeqTrainingArguments(output_dir="/tmp/ckpt", per_device_train_batch_size=8, gradient_accumulation_steps=4, learning_rate=1e-5,
    num_train_epochs=2, warmup_ratio=0.05, weight_decay=0.01, fp16=True, logging_steps=100, save_strategy="no", eval_strategy="no",
    dataloader_num_workers=4, report_to=[], lr_scheduler_type="cosine", max_grad_norm=1.0)
trainer = CTrainer(model=model, args=args, train_dataset=tr, data_collator=default_data_collator)
t0 = time.time(); trainer.train(); log["train_minutes"] = round((time.time() - t0) / 60, 1); save_log()
model = trainer.model.cuda()
evaluate(model, rt_img, rt_txt, "after: RIMES test")
evaluate(model, test_img, test_txt, "after: school lines, unseen fonts")
# Only a model that is better on both tests is kept.
if log["after: RIMES test"]["cer"] <= log["before: RIMES test"]["cer"] and log["after: school lines, unseen fonts"]["cer"] < log["before: school lines, unseen fonts"]["cer"]:
    model.save_pretrained(f"{OUT}/htr-fr-school"); proc.save_pretrained(f"{OUT}/htr-fr-school"); log["kept"] = True
else: log["kept"] = False
save_log(); print(json.dumps(log, indent=1))
