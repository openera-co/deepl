"""Reference outputs for Kotlin parity tests: tokenisation of many sentences and greedy/beam translations."""
import sys, json, random
from transformers import AutoTokenizer
mid, mdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
tok = AutoTokenizer.from_pretrained(mid)
lines = []
for n in ["newstest2014", "newsdiscuss2015", "tatoeba"]:
    lines += open(f"/opt/data/eval/{n}.fr").read().splitlines()
random.seed(3)
extra = ["  Espaces   multiples\tet tabulations ", "L’élève a dit : « Bonjour ! »", "CH₄ + 2 O₂ → CO₂ + 2 H₂O", "ﬁn de l’œuvre…", "Prix : 12,50 €", "émoji 😀 test", "ÉCOLE PRIMAIRE", "x² + y² = z²", "Ⅻ siècle"]
lines = extra + lines
with open(f"{out}/tok.jsonl", "w") as f:
    for l in lines:
        f.write(json.dumps({"s": l, "p": tok.convert_ids_to_tokens(tok(l, truncation=True, max_length=256)["input_ids"])}, ensure_ascii=False) + "\n")
from optimum.onnxruntime import ORTModelForSeq2SeqLM
m = ORTModelForSeq2SeqLM.from_pretrained(mdir, encoder_file_name="encoder_model.int8.onnx", decoder_file_name="decoder_model_merged.int8.onnx", use_merged=True)
sample = lines[:9] + random.sample(lines[9:], 60)
res = []
for b in (1, 4):
    outs = []
    for i in range(0, len(sample), 1):
        enc = tok(sample[i:i+1], return_tensors="pt", padding=True, truncation=True, max_length=256)
        outs += tok.batch_decode(m.generate(**enc, num_beams=b, max_new_tokens=256), skip_special_tokens=True)
    res.append(outs)
with open(f"{out}/mt.jsonl", "w") as f:
    for s, g, bm in zip(sample, res[0], res[1]):
        f.write(json.dumps({"s": s, "greedy": g, "beam4": bm}, ensure_ascii=False) + "\n")
print("ok", len(lines), len(sample))
