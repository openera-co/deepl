"""Export a Marian (Opus-MT) model to ONNX, quantize to int8, and dump tokenizer assets
in the compact format the app's Kotlin tokenizer reads.

usage: python export_model.py <hf_model_id> <out_dir>
"""
import json, os, struct, subprocess, sys, shutil
from pathlib import Path

model_id, out = sys.argv[1], Path(sys.argv[2])
fp32 = out / "fp32"
out.mkdir(parents=True, exist_ok=True)
if not (fp32 / "decoder_model_merged.onnx").exists():
    subprocess.check_call([sys.executable, "-m", "optimum.exporters.onnx", "--model", model_id,
                           "--task", "text2text-generation-with-past", str(fp32)])

from onnxruntime.quantization import quantize_dynamic, QuantType
for name in ["encoder_model.onnx", "decoder_model_merged.onnx"]:
    dst = out / name.replace(".onnx", ".int8.onnx")
    if not dst.exists():
        quantize_dynamic(str(fp32 / name), str(dst), weight_type=QuantType.QInt8, per_channel=True,
                         extra_options={"EnableSubgraph": True})
    print(name, os.path.getsize(fp32 / name) // 2**20, "MB ->", os.path.getsize(dst) // 2**20, "MB")

# Tokenizer assets: SentencePiece pieces+scores for source and target, plus the shared vocab.
import sentencepiece as spm
from transformers import AutoTokenizer, AutoConfig
tok = AutoTokenizer.from_pretrained(model_id)
cfg = AutoConfig.from_pretrained(model_id)
src_dir = Path(tok.vocab_file).parent if hasattr(tok, "vocab_file") else None

def dump_spm(path, dst):
    sp = spm.SentencePieceProcessor(model_file=str(path))
    # binary: int32 n, then per piece: int32 type, float32 score, int32 len, utf8 bytes
    with open(dst, "wb") as f:
        f.write(struct.pack("<i", sp.get_piece_size()))
        for i in range(sp.get_piece_size()):
            p = sp.id_to_piece(i).encode("utf-8")
            t = 0
            if sp.is_unknown(i): t = 2
            elif sp.is_control(i): t = 3
            elif sp.is_unused(i): t = 5
            elif sp.is_byte(i): t = 6
            f.write(struct.pack("<ifi", t, sp.get_score(i), len(p))); f.write(p)

from huggingface_hub import hf_hub_download
src_spm = hf_hub_download(model_id, "source.spm"); tgt_spm = hf_hub_download(model_id, "target.spm")
dump_spm(src_spm, out / "source.pieces"); dump_spm(tgt_spm, out / "target.pieces")
vocab = json.load(open(hf_hub_download(model_id, "vocab.json"), encoding="utf-8"))
with open(out / "vocab.txt", "w", encoding="utf-8") as f:
    inv = sorted(vocab.items(), key=lambda kv: kv[1])
    assert [i for _, i in inv] == list(range(len(inv)))
    for piece, _ in inv:
        f.write(piece.replace("\n", "\\n") + "\n")
meta = {
    "model_id": model_id,
    "eos_id": cfg.eos_token_id, "pad_id": cfg.pad_token_id,
    "decoder_start_id": cfg.decoder_start_token_id, "unk_id": vocab.get("<unk>"),
    "num_layers": cfg.decoder_layers, "num_heads": cfg.decoder_attention_heads,
    "d_model": cfg.d_model, "max_length": 512, "vocab_size": len(vocab),
}
json.dump(meta, open(out / "model.json", "w"), indent=1)
print(meta)
