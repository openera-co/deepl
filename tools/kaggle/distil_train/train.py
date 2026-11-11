# Kaggle job: fine-tune the phone translation models (Opus-MT tc-big, both directions) on school-domain
# teacher translations (carnet-distil-data), with OCR-damaged copies of the French so scanned notes
# translate as well as clean text. Scores before/after on held-out school segments (teacher refs, clean
# and damaged), FLORES and news; saves the model and a CTranslate2 int8 conversion for the app.
import subprocess, os, json, random, time, re, glob, urllib.request
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh('pip install -q "transformers==4.56.2" sacrebleu sentencepiece sacremoses ctranslate2 2>&1 | tail -2')
import torch, sacrebleu
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM, Seq2SeqTrainer, Seq2SeqTrainingArguments, DataCollatorForSeq2Seq
OUT = "/kaggle/working"; log = {}
def save(): json.dump(log, open(f"{OUT}/results.json", "w"), indent=1)
RAW = "https://raw.githubusercontent.com/openera-co/deepl/claude/offline-translator-mobile-app-ea8rbh/tools/eval/data/"
ext = {n: urllib.request.urlopen(RAW + n).read().decode().splitlines() for n in ["flores300.fr", "flores300.en", "news500.fr", "news500.en"]}
src_dir = (glob.glob("/kaggle/input/*/pairs.fr.jsonl") or glob.glob("/kaggle/input/**/pairs.fr.jsonl", recursive=True))[0].rsplit("/", 1)[0]
print("data from", src_dir, flush=True)

R = random.Random(3)
SWAPS = [("é", "e"), ("è", "e"), ("à", "a"), ("ê", "e"), ("ç", "c"), ("l", "I"), ("I", "l"), ("rn", "m"), ("m", "rn"), ("l'", "I'"), ("œ", "oe"), ("ô", "o"), ("û", "u")]
def damage(s, r):
    """OCR-like errors: dropped accents, l/I and rn/m confusions, joined or split words, a stray character."""
    t = s
    for _ in range(r.randint(1, 3)):
        a, b = r.choice(SWAPS)
        idx = [m.start() for m in re.finditer(re.escape(a), t)]
        if idx: i = r.choice(idx); t = t[:i] + b + t[i + len(a):]
    if r.random() < 0.2 and " " in t: i = r.choice([m.start() for m in re.finditer(" ", t)]); t = t[:i] + t[i + 1:]
    if r.random() < 0.15: i = r.randrange(len(t)); t = t[:i] + r.choice(".,'-") + t[i:]
    return t

def run(sl, tl):
    pairs = [json.loads(l) for l in open(f"{src_dir}/pairs.{sl}.jsonl", encoding="utf-8")]
    pairs = [p for p in pairs if p["tgt"] and 0.3 < len(p["tgt"]) / max(1, len(p["src"])) < 3.0]
    R.shuffle(pairs); test, train = pairs[:1000], pairs[1000:]
    log[f"{sl}-{tl} pairs"] = len(pairs); save()
    name = f"Helsinki-NLP/opus-mt-tc-big-{sl}-{tl}"
    tok = AutoTokenizer.from_pretrained(name); model = AutoModelForSeq2SeqLM.from_pretrained(name).cuda()
    def translate(srcs, bs=48):
        model.eval(); out = []
        for i in range(0, len(srcs), bs):
            enc = tok(srcs[i:i + bs], return_tensors="pt", padding=True, truncation=True, max_length=256).to("cuda")
            with torch.inference_mode(), torch.autocast("cuda", dtype=torch.float16): g = model.generate(**enc, num_beams=1, max_new_tokens=256)
            out += [x.strip() for x in tok.batch_decode(g, skip_special_tokens=True)]
        return out
    def score(tag):
        res = {}
        sets = [("school", [p["src"] for p in test], [p["tgt"] for p in test])]
        if sl == "fr": sets.append(("school-ocr", [damage(p["src"], random.Random(i)) for i, p in enumerate(test)], [p["tgt"] for p in test]))
        sets.append(("flores", ext[f"flores300.{sl}"], ext[f"flores300.{tl}"]))
        if sl == "fr": sets.append(("news", ext["news500.fr"], ext["news500.en"]))
        for n, s, r in sets:
            h = translate(s); res[n] = round(sacrebleu.corpus_bleu(h, [r]).score, 2)
            open(f"{OUT}/{sl}-{tl}.{tag}.{n}.txt", "w").write("\n".join(h) + "\n")
        log[f"{sl}-{tl} {tag}"] = res; print(sl, tl, tag, res, flush=True); save()
    score("before")
    # The models the app ships now (previous round), same test sets: the new one must beat these.
    try:
        import ctranslate2
        rel = "https://github.com/openera-co/deepl/releases/download/models-v2/"
        cd = f"/tmp/shipped-{sl}-{tl}"; os.makedirs(cd, exist_ok=True)
        for f in ["model.bin", "config.json", "shared_vocabulary.json"]: urllib.request.urlretrieve(f"{rel}{sl}-{tl}__{f}", f"{cd}/{f}")
        ct = ctranslate2.Translator(cd, device="cuda", compute_type="int8_float16")
        def ct_tr(srcs):
            out = []
            for i in range(0, len(srcs), 64):
                toks = [tok.convert_ids_to_tokens(tok.encode(x, truncation=True, max_length=256)) for x in srcs[i:i + 64]]
                out += [tok.convert_tokens_to_string(r.hypotheses[0]).strip() for r in ct.translate_batch(toks, beam_size=1)]
            return out
        res = {}
        sets = [("school", [p["src"] for p in test], [p["tgt"] for p in test])]
        if sl == "fr": sets.append(("school-ocr", [damage(p["src"], random.Random(i)) for i, p in enumerate(test)], [p["tgt"] for p in test]))
        sets.append(("flores", ext[f"flores300.{sl}"], ext[f"flores300.{tl}"]))
        if sl == "fr": sets.append(("news", ext["news500.fr"], ext["news500.en"]))
        for n, s_, r in sets:
            h = ct_tr(s_); res[n] = round(sacrebleu.corpus_bleu(h, [r]).score, 2)
            open(f"{OUT}/{sl}-{tl}.shipped.{n}.txt", "w").write("\n".join(h) + "\n")
        log[f"{sl}-{tl} shipped (round 1)"] = res; save(); print(sl, tl, "shipped", res, flush=True)
        del ct
    except Exception as e: log[f"{sl}-{tl} shipped error"] = str(e)[:300]; save()
    # Training data: teacher pairs; French sources also get an OCR-damaged copy (20%).
    srcs = [p["src"] for p in train]; tgts = [p["tgt"] for p in train]
    if sl == "fr":
        extra = [i for i in range(len(train)) if R.random() < 0.2]
        srcs += [damage(train[i]["src"], random.Random(10**6 + i)) for i in extra]; tgts += [train[i]["tgt"] for i in extra]
    class DS(torch.utils.data.Dataset):
        def __len__(self): return len(srcs)
        def __getitem__(self, k):
            e = tok(srcs[k], text_target=tgts[k], truncation=True, max_length=256)
            return e
    # Sanity check before hours of training: the untouched model should already agree with the teacher.
    model.eval(); probe = []
    for k in range(0, 64, 16):
        b = tok([p["src"] for p in train[k:k + 16]], text_target=[p["tgt"] for p in train[k:k + 16]], return_tensors="pt", padding=True, truncation=True, max_length=256).to("cuda")
        b["labels"][b["labels"] == tok.pad_token_id] = -100
        with torch.inference_mode(): probe.append(model(**b).loss.item())
    log[f"{sl}-{tl} probe loss before"] = round(sum(probe) / len(probe), 3); save()
    assert log[f"{sl}-{tl} probe loss before"] < 4, "labels don't match the model"
    model.train()
    args = Seq2SeqTrainingArguments(output_dir="/tmp/ck", per_device_train_batch_size=24, gradient_accumulation_steps=2, learning_rate=2e-5,
        num_train_epochs=2, warmup_ratio=0.03, weight_decay=0.01, fp16=True, logging_steps=200, save_strategy="no", report_to=[],
        lr_scheduler_type="linear", label_smoothing_factor=0.1, dataloader_num_workers=2, group_by_length=True)
    t0 = time.time()
    Seq2SeqTrainer(model=model, args=args, train_dataset=DS(), data_collator=DataCollatorForSeq2Seq(tok, model=model)).train()
    log[f"{sl}-{tl} train_minutes"] = round((time.time() - t0) / 60, 1); save()
    score("after")
    d = f"/tmp/hf-{sl}-{tl}"; model.save_pretrained(d); tok.save_pretrained(d)
    sh(f"ct2-transformers-converter --model {d} --output_dir {OUT}/ct2-{sl}-{tl} --quantization int8 --force")
    del model; torch.cuda.empty_cache()

for sl, tl in (("fr", "en"), ("en", "fr")):
    try: run(sl, tl)
    except Exception as e: log[f"{sl}-{tl} error"] = str(e)[:500]; save(); print("failed", sl, tl, e, flush=True)
print(json.dumps(log, indent=1))
