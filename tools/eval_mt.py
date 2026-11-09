"""Score exported ONNX Marian models on held-out FR->EN sets (BLEU/chrF) and time them.

usage: python eval_mt.py <model_dir> <hf_model_id> [n_per_set] [beams...]
"""
import sys, time, json
from pathlib import Path
import sacrebleu, onnxruntime as ort
from optimum.onnxruntime import ORTModelForSeq2SeqLM
from transformers import AutoTokenizer

mdir, mid = Path(sys.argv[1]), sys.argv[2]
n = int(sys.argv[3]) if len(sys.argv) > 3 else 500
beams = [int(x) for x in sys.argv[4:]] or [1, 4]
tok = AutoTokenizer.from_pretrained(mid)
so = ort.SessionOptions(); so.intra_op_num_threads = 4
model = ORTModelForSeq2SeqLM.from_pretrained(mdir, encoder_file_name="encoder_model.int8.onnx",
    decoder_file_name="decoder_model_merged.int8.onnx", use_merged=True, session_options=so)
results = {}
for name in ["newstest2014", "newsdiscuss2015", "tatoeba"]:
    src = open(f"/opt/data/eval/{name}.fr").read().splitlines()[:n]
    ref = open(f"/opt/data/eval/{name}.en").read().splitlines()[:n]
    for b in beams:
        hyp, t0 = [], time.time()
        for i in range(0, len(src), 16):
            batch = tok(src[i:i+16], return_tensors="pt", padding=True, truncation=True, max_length=256)
            out = model.generate(**batch, num_beams=b, max_new_tokens=256)
            hyp += tok.batch_decode(out, skip_special_tokens=True)
        dt = time.time() - t0
        bleu = sacrebleu.corpus_bleu(hyp, [ref]).score; chrf = sacrebleu.corpus_chrf(hyp, [ref]).score
        results[f"{name}/beam{b}"] = dict(bleu=round(bleu, 2), chrf=round(chrf, 2), sec_per_sent=round(dt / len(src), 4))
        print(name, "beam", b, results[f"{name}/beam{b}"], flush=True)
        Path(mdir, f"hyp.{name}.beam{b}.txt").write_text("\n".join(hyp) + "\n")
json.dump(results, open(mdir / "eval.json", "w"), indent=1)
