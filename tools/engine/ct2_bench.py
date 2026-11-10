"""CTranslate2 speed/quality for the app's models: converts the same Opus-MT checkpoints to int8 and
translates FLORES with the app's settings (greedy, 4 threads, batches of 16 sentences).
usage: ct2_bench.py <src> <ref>"""
import subprocess, sys, time, os
import ctranslate2, sentencepiece as spm, sacrebleu
from transformers import AutoTokenizer

src = [l.rstrip("\n") for l in open(sys.argv[1], encoding="utf-8")]
ref = [l.rstrip("\n") for l in open(sys.argv[2], encoding="utf-8")]
for name, hf in [("fr-en (best)", "Helsinki-NLP/opus-mt-tc-big-fr-en"), ("fr-en-fast", "Helsinki-NLP/opus-mt-fr-en")]:
    for q in ["int8", "int8_float16"] if False else ["int8"]:
        d = "ct2_" + hf.split("/")[-1] + "_" + q
        if not os.path.isdir(d):
            subprocess.check_call(["ct2-transformers-converter", "--model", hf, "--output_dir", d, "--quantization", q], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        size = sum(os.path.getsize(os.path.join(d, f)) for f in os.listdir(d)) // 2**20
        tok = AutoTokenizer.from_pretrained(hf)
        for threads in [4, 2]:
            tr = ctranslate2.Translator(d, device="cpu", intra_threads=threads, inter_threads=1, compute_type=q)
            toks = [tok.convert_ids_to_tokens(tok.encode(s)) for s in src]
            tr.translate_batch(toks[:8], beam_size=1)  # warm-up
            t = time.time(); out = []
            for i in range(0, len(toks), 16):
                res = tr.translate_batch(toks[i:i + 16], beam_size=1, max_decoding_length=256)
                out += [tok.decode(tok.convert_tokens_to_ids(r.hypotheses[0]), skip_special_tokens=True) for r in res]
            dt = time.time() - t
            bleu = sacrebleu.corpus_bleu(out, [ref]).score
            t0 = time.time(); ctranslate2.Translator(d, device="cpu", intra_threads=threads); load = time.time() - t0
            print(f"CT2 {name} {q} {threads} threads: {len(src) / dt:.1f} lines/s, load {load:.2f}s, {size} MB, BLEU {bleu:.1f}", flush=True)
