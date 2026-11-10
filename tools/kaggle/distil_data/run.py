# Kaggle job: school-domain training data for the phone translation models.
# Source text: Vikidia (French encyclopedia for school students) + matching Wikipedia/English articles
# (CC BY-SA, FrancophonIA/Vikidia-EnFr). Sentences and headings are translated by the strongest open
# teacher measured (Mistral-Small-24B, AWQ) with vLLM on 2x T4. Output: pairs.<dir>.jsonl
import subprocess, time, json, re, random, os, sys
def sh(c): print("+", c, flush=True); subprocess.run(c, shell=True, check=False)
sh("pip install -q vllm==0.11.0 \"transformers>=4.56,<5\" huggingface_hub 2>&1 | tail -2")
def vikidia():
    """Vikidia (CC BY 3.0, Zenodo 6327828) as one archive: thousands of small Hugging Face files get rate-limited."""
    if not os.path.isdir("/tmp/vk/Vikidia-EnFr-Parsed"):
        os.makedirs("/tmp/vk", exist_ok=True)
        sh("curl -sL https://zenodo.org/api/records/6327828/files/Vikidia-EnFr-Parsed.tar.gz/content | tar xzf - -C /tmp/vk")
    return "/tmp/vk/Vikidia-EnFr-Parsed"
d = vikidia()
OUT = "/kaggle/working"
BUDGET = float(os.environ.get("HOURS", "10.5")) * 3600
T0 = time.time()

def segments(folder):
    segs = []
    for f in sorted(os.listdir(folder)):
        txt = open(os.path.join(folder, f), encoding="utf-8", errors="ignore").read()
        for line in txt.split("\n"):
            line = re.sub(r"\s+", " ", line).strip()
            if len(line) < 3 or len(line) > 1200: continue
            if re.search(r"(https?://|\{|\}|\[\d+\]|^\W+$)", line): continue
            # Headings and short labels are kept whole (the phone model must handle titles well).
            if len(line) < 70 and not line.endswith("."):
                segs.append(line); continue
            for s in re.split(r"(?<=[.!?])\s+(?=[A-ZÀ-Ý«\"(])", line):
                if 3 <= len(s.split()) <= 60: segs.append(s)
    return segs

data = {}
for lang, folders in [("fr", ["fr", "frsimple"]), ("en", ["en", "ensimple"])]:
    seen = set(); out = []
    for fo in folders:
        p = os.path.join(d, fo)
        if not os.path.isdir(p): continue
        for s in segments(p):
            k = s.lower()
            if k not in seen: seen.add(k); out.append(s)
    random.Random(7).shuffle(out)
    data[lang] = out
    print(lang, len(out), "segments", flush=True)

from vllm import LLM, SamplingParams
NAME = {"fr": "French", "en": "English"}
def msg(t, sl, tl):
    return [{"role": "user", "content": f"Translate this {NAME[sl]} text from a school encyclopedia into {NAME[tl]}. "
             f"It may be a sentence, a heading or a short label: translate it as such, naturally and fluently, "
             f"keeping names, numbers and formatting. Output only the translation.\n\n{t}"}]
llm = None
for eager in (False, True):
    try:
        llm = LLM(model="stelterlab/Mistral-Small-24B-Instruct-2501-AWQ", dtype="float16", quantization="awq", tensor_parallel_size=2,
                  max_model_len=1536, gpu_memory_utilization=0.92, enforce_eager=eager, max_num_seqs=128)
        break
    except Exception as e: print("load failed eager=%s: %s" % (eager, str(e)[:300]), flush=True)
sp = SamplingParams(temperature=0, max_tokens=400)
# Alternate directions in chunks so both get data whatever the time budget allows.
pos = {"fr": 0, "en": 0}; files = {l: open(f"{OUT}/pairs.{l}.jsonl", "a", encoding="utf-8") for l in pos}
CH = 2000
while time.time() - T0 < BUDGET:
    progressed = False
    for sl, tl in (("fr", "en"), ("en", "fr")):
        src = data[sl][pos[sl]:pos[sl] + CH]
        if not src: continue
        t = time.time()
        outs = llm.chat([msg(s, sl, tl) for s in src], sp, use_tqdm=False)
        for s, o in zip(src, outs):
            hyp = o.outputs[0].text.strip()
            if hyp and "\n" not in hyp.strip() or len(s) > 200:
                files[sl].write(json.dumps({"src": s, "tgt": hyp.replace("\n", " ")}, ensure_ascii=False) + "\n")
        files[sl].flush(); pos[sl] += len(src); progressed = True
        print(f"{sl}->{tl}: {pos[sl]} done, {len(src) / (time.time() - t):.1f} seg/s, elapsed {(time.time() - T0) / 3600:.2f} h", flush=True)
        if time.time() - T0 > BUDGET: break
    if not progressed: break
for f in files.values(): f.close()
json.dump(pos, open(f"{OUT}/progress.json", "w"))
print("done", pos)
