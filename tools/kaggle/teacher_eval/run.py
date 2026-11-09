# Kaggle job: how good are large open translators on our French test sets? (candidate teachers for distillation)
import subprocess, sys, time, urllib.request, os, json
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh("pip install -q vllm==0.11.0 sacrebleu 2>&1 | tail -2")
RAW = "https://raw.githubusercontent.com/openera-co/deepl/claude/offline-translator-mobile-app-ea8rbh/tools/eval/data/"
data = {n: urllib.request.urlopen(RAW + n).read().decode().splitlines() for n in ["news500.fr", "news500.en", "flores300.fr", "flores300.en"]}
PROMPT = ("You are a professional {sl} ({sc}) to {tl} ({tc}) translator. Your goal is to accurately convey the meaning and nuances of the original {sl} text "
          "while adhering to {tl} grammar, vocabulary, and cultural sensitivities.\nProduce only the {tl} translation, without any additional explanations or commentary. "
          "Please translate the following {sl} text into {tl}:\n\n\n")
def prompt(t, sl="French", sc="fr", tl="English", tc="en"):
    return "<start_of_turn>user\n" + PROMPT.format(sl=sl, sc=sc, tl=tl, tc=tc) + t + "<end_of_turn>\n<start_of_turn>model\n"
import sacrebleu
from vllm import LLM, SamplingParams
results = {}
for name, kw in [("Infomaniak-AI/vllm-translategemma-12b-it", dict(dtype="float16", tensor_parallel_size=2, max_model_len=2048, gpu_memory_utilization=0.92)),
                 ("kaitchup/translategemma-27b-it-autoround-w4a16", dict(dtype="float16", tensor_parallel_size=2, max_model_len=2048, gpu_memory_utilization=0.92))]:
    try:
        llm = LLM(model=name, **kw)
        sp = SamplingParams(temperature=0, max_tokens=400)
        for test, sl, tl, a, b in [("news500", "fr", "en", "French", "English"), ("flores300", "fr", "en", "French", "English"), ("flores300", "en", "fr", "English", "French")]:
            src = data[f"{test}.{sl}"]; ref = data[f"{test}.{tl}"]
            t0 = time.time()
            outs = llm.generate([prompt(s, a, sl, b, tl) for s in src], sp)
            hyp = [o.outputs[0].text.strip().replace("\n", " ") for o in outs]
            dt = time.time() - t0
            short = name.split("/")[-1]
            open(f"/kaggle/working/{short}.{test}.{sl}-{tl}.txt", "w").write("\n".join(hyp) + "\n")
            bleu = sacrebleu.corpus_bleu(hyp, [ref]).score; chrf = sacrebleu.corpus_chrf(hyp, [ref]).score
            results[f"{short} {test} {sl}-{tl}"] = dict(bleu=round(bleu, 2), chrf=round(chrf, 2), sent_per_s=round(len(src) / dt, 2))
            print(results, flush=True)
        del llm
        import gc, torch; gc.collect(); torch.cuda.empty_cache()
        from vllm.distributed.parallel_state import destroy_model_parallel
        destroy_model_parallel()
    except Exception as e:
        results[name] = f"failed: {e}"; print(name, "failed", e, flush=True)
json.dump(results, open("/kaggle/working/results.json", "w"), indent=1)
print(json.dumps(results, indent=1))
