"""Export a TrOCR handwriting model (HF id or folder) for the app: ONNX (merged cached decoder), int8,
vocab.txt (one piece per id) and htr.json. usage: export_htr.py <model> <outDir>"""
import json, os, subprocess, sys
from pathlib import Path
model, out = sys.argv[1], Path(sys.argv[2]); fp32 = out / "fp32"; out.mkdir(parents=True, exist_ok=True)
if not (fp32 / "decoder_model_merged.onnx").exists():
    subprocess.check_call([sys.executable, "-m", "optimum.exporters.onnx", "--model", model, "--task", "image-to-text-with-past", str(fp32)])
from onnxruntime.quantization import quantize_dynamic, QuantType
for n in ["encoder_model.onnx", "decoder_model_merged.onnx"]:
    dst = out / n.replace(".onnx", ".int8.onnx")
    if not dst.exists():
        quantize_dynamic(str(fp32 / n), str(dst), weight_type=QuantType.QInt8, per_channel=True, extra_options={"EnableSubgraph": True})
    print(n, os.path.getsize(fp32 / n) // 2**20, "MB ->", os.path.getsize(dst) // 2**20, "MB")
tok = json.load(open(fp32 / "tokenizer.json", encoding="utf-8"))
pieces = [p for p, _ in tok["model"]["vocab"]] if tok["model"]["type"] == "Unigram" else [p for p, _ in sorted(tok["model"]["vocab"].items(), key=lambda kv: kv[1])]
for a in tok.get("added_tokens", []):
    while len(pieces) <= a["id"]: pieces.append("")
    pieces[a["id"]] = a["content"]
(out / "vocab.txt").write_text("\n".join(p.replace("\n", "\\n") for p in pieces), encoding="utf-8")
cfg = json.load(open(fp32 / "config.json")); gen = json.load(open(fp32 / "generation_config.json")) if (fp32 / "generation_config.json").exists() else {}
pre = json.load(open(fp32 / "preprocessor_config.json")); d = cfg["decoder"]
meta = {"model_id": model, "decoder_start_id": cfg.get("decoder_start_token_id") or gen.get("decoder_start_token_id", 2),
        "eos_id": cfg.get("eos_token_id") or gen.get("eos_token_id", 2), "pad_id": cfg.get("pad_token_id") or gen.get("pad_token_id", 1),
        "num_layers": d["decoder_layers"], "num_heads": d["decoder_attention_heads"], "head_dim": d["d_model"] // d["decoder_attention_heads"],
        "size": pre["size"]["height"], "mean": pre["image_mean"][0], "std": pre["image_std"][0]}
json.dump(meta, open(out / "htr.json", "w"), indent=1); print(meta)
