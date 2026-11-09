"""Translates a test set with a Hugging Face seq2seq model: eval_models.py <model> <src> <out> [beam] [batch]
Prints sentences/s on this CPU. Used to compare candidate models (scores: sacrebleu / comet)."""
import sys, time, torch
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
name, src, out = sys.argv[1:4]
beam = int(sys.argv[4]) if len(sys.argv) > 4 else 1
bs = int(sys.argv[5]) if len(sys.argv) > 5 else 16
torch.set_num_threads(4)
nllb = "nllb" in name or "m2m" in name
tok = AutoTokenizer.from_pretrained(name, src_lang="fra_Latn") if nllb else AutoTokenizer.from_pretrained(name)
model = AutoModelForSeq2SeqLM.from_pretrained(name).eval()
lines = [l.rstrip("\n") for l in open(src)]
order = sorted(range(len(lines)), key=lambda i: len(lines[i]))
res = [None] * len(lines)
t0 = time.time()
with torch.inference_mode():
    for i in range(0, len(order), bs):
        idx = order[i:i + bs]
        enc = tok([lines[j] for j in idx], return_tensors="pt", padding=True, truncation=True, max_length=400)
        kw = dict(num_beams=beam, max_new_tokens=400)
        if nllb: kw["forced_bos_token_id"] = tok.convert_tokens_to_ids("eng_Latn")
        gen = model.generate(**enc, **kw)
        for j, t in zip(idx, tok.batch_decode(gen, skip_special_tokens=True)): res[j] = t.strip()
dt = time.time() - t0
open(out, "w").write("\n".join(res) + "\n")
print(f"{name} beam={beam}: {len(lines)} sentences in {dt:.0f}s = {len(lines)/dt:.2f} sent/s")
