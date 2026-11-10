"""4-bit weight-only quantization (MatMulNBits, block-wise) of an exported encoder-decoder model, with int8 embeddings.
Works for Marian translation models and TrOCR handwriting models (accuracy level 1 keeps fp32 maths).
The two decoders are quantized separately and then merged (shared weights stored once).
quantize4.py <fp32Dir> <outDir> [block] [accuracyLevel]"""
import sys, pathlib, onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer, DefaultWeightOnlyQuantConfig
from optimum.onnx import merge_decoders
src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]); dst.mkdir(parents=True, exist_ok=True)
block = int(sys.argv[3]) if len(sys.argv) > 3 else 32
acc = int(sys.argv[4]) if len(sys.argv) > 4 else 4
def q4(name, out):
    m = onnx.load(str(src / name))
    q = MatMulNBitsQuantizer(m, algo_config=DefaultWeightOnlyQuantConfig(block_size=block, is_symmetric=True, accuracy_level=acc))
    q.process()
    tmp = dst / ("tmp_" + name)
    q.model.save_model_to_file(str(tmp), use_external_data_format=False)
    if "--no-gather" in sys.argv: tmp.rename(out)
    else: quantize_dynamic(str(tmp), str(out), weight_type=QuantType.QInt8, op_types_to_quantize=["Gather"])
    if tmp.exists(): tmp.unlink()
q4("encoder_model.onnx", dst / "encoder_model.int8.onnx")
q4("decoder_model.onnx", dst / "dec.onnx")
q4("decoder_with_past_model.onnx", dst / "decp.onnx")
merge_decoders(str(dst / "dec.onnx"), str(dst / "decp.onnx"), save_path=str(dst / "decoder_model_merged.int8.onnx"), strict=False)
(dst / "dec.onnx").unlink(); (dst / "decp.onnx").unlink()
for f in ["encoder_model.int8.onnx", "decoder_model_merged.int8.onnx"]: print(f, (dst / f).stat().st_size // 1_000_000, "MB")
