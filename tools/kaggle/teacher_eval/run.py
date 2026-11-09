# Kaggle job: how good are large open models at French<->English on our test sets?
# (Candidate "teachers" to distil into the phone model.) Runs on 2x T4 (fp16, no bf16).
import subprocess, time, urllib.request, json, gc
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh("pip install -q vllm==0.11.0 sacrebleu 2>&1 | tail -2")
RAW = "https://raw.githubusercontent.com/openera-co/deepl/claude/offline-translator-mobile-app-ea8rbh/tools/eval/data/"
data = {n: urllib.request.urlopen(RAW + n).read().decode().splitlines() for n in ["news500.fr", "news500.en", "flores300.fr", "flores300.en"]}
TESTS = [("news500", "fr", "en"), ("flores300", "fr", "en"), ("flores300", "en", "fr")]
NAME = {"fr": "French", "en": "English"}
import sacrebleu, torch
results = {}

def score(tag, test, sl, tl, hyp, dt):
    ref = data[f"{test}.{tl}"]
    open(f"/kaggle/working/{tag}.{test}.{sl}-{tl}.txt", "w").write("\n".join(hyp) + "\n")
    results[f"{tag} {test} {sl}-{tl}"] = dict(bleu=round(sacrebleu.corpus_bleu(hyp, [ref]).score, 2),
                                              chrf=round(sacrebleu.corpus_chrf(hyp, [ref]).score, 2), sent_per_s=round(len(hyp) / dt, 2))
    print(json.dumps(results, indent=1), flush=True)

# 1) Baseline: the phone's "best" model (Opus-MT tc-big), same test sets, on GPU.
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
for sl, tl in [("fr", "en"), ("en", "fr")]:
    name = f"Helsinki-NLP/opus-mt-tc-big-{sl}-{tl}"
    tok = AutoTokenizer.from_pretrained(name); m = AutoModelForSeq2SeqLM.from_pretrained(name, torch_dtype=torch.float16).cuda().eval()
    for test, a, b in TESTS:
        if (a, b) != (sl, tl): continue
        src = data[f"{test}.{sl}"]; out = []; t0 = time.time()
        for i in range(0, len(src), 32):
            enc = tok(src[i:i + 32], return_tensors="pt", padding=True, truncation=True).to("cuda")
            with torch.inference_mode(): g = m.generate(**enc, num_beams=4, max_new_tokens=400)
            out += [t.strip() for t in tok.batch_decode(g, skip_special_tokens=True)]
        score("tc-big-beam4", test, sl, tl, out, time.time() - t0)
    del m; gc.collect(); torch.cuda.empty_cache()

# 2) LLM teachers through vLLM (AWQ 4-bit weights, fp16 compute).
from vllm import LLM, SamplingParams
def msgs(t, sl, tl):
    return [{"role": "user", "content": f"Translate the following {NAME[sl]} text into {NAME[tl]}. Keep the meaning, tone and formatting; "
                                         f"use natural, fluent {NAME[tl]}. Output only the translation.\n\n{t}"}]
for name, extra in [("Qwen/Qwen3-14B-AWQ", {"chat_template_kwargs": {"enable_thinking": False}}),
                    ("stelterlab/Mistral-Small-24B-Instruct-2501-AWQ", {})]:
    try:
        llm = LLM(model=name, dtype="float16", quantization="awq", tensor_parallel_size=2, max_model_len=4096, gpu_memory_utilization=0.9, enforce_eager=True)
        sp = SamplingParams(temperature=0, max_tokens=512)
        for test, sl, tl in TESTS:
            src = data[f"{test}.{sl}"]; t0 = time.time()
            outs = llm.chat([msgs(s, sl, tl) for s in src], sp, use_tqdm=False, **extra)
            hyp = [o.outputs[0].text.strip().replace("\n", " ") for o in outs]
            score(name.split("/")[-1], test, sl, tl, hyp, time.time() - t0)
        del llm; gc.collect(); torch.cuda.empty_cache()
        try:
            from vllm.distributed.parallel_state import destroy_model_parallel, destroy_distributed_environment
            destroy_model_parallel(); destroy_distributed_environment()
        except Exception as e: print("cleanup", e)
    except Exception as e:
        results[name] = f"failed: {str(e)[:400]}"; print(name, "failed", e, flush=True)
json.dump(results, open("/kaggle/working/results.json", "w"), indent=1)
print(json.dumps(results, indent=1))
