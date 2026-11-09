"""Translates a file with TranslateGemma through a running llama-server (same prompt as the app).
gemma_translate.py <src> <out> [parallel]"""
import sys, json, time, urllib.request, concurrent.futures as cf
PROMPT = ("You are a professional French (fr) to English (en) translator. Your goal is to accurately convey the meaning and nuances "
          "of the original French text while adhering to English grammar, vocabulary, and cultural sensitivities.\n"
          "Produce only the English translation, without any additional explanations or commentary. Please translate the following French text into English:\n\n\n")
def tr(text):
    body = json.dumps({"prompt": "<start_of_turn>user\n" + PROMPT + text + "<end_of_turn>\n<start_of_turn>model\n",
                       "n_predict": 400, "temperature": 0, "cache_prompt": True}).encode()
    r = urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8080/completion", body, {"Content-Type": "application/json"}), timeout=600)
    return json.load(r)["content"].strip().replace("\n", " ")
src, out = sys.argv[1], sys.argv[2]
par = int(sys.argv[3]) if len(sys.argv) > 3 else 2
lines = [l.rstrip("\n") for l in open(src)]
t0 = time.time()
with cf.ThreadPoolExecutor(par) as ex: res = list(ex.map(tr, lines))
dt = time.time() - t0
open(out, "w").write("\n".join(res) + "\n")
print(f"{src}: {len(lines)} sentences in {dt:.0f}s = {len(lines)/dt:.2f} sent/s")
