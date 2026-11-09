#!/usr/bin/env bash
# Copies an exported model (tools/export_model.py output) into the app's assets.
set -euo pipefail
src="${1:?model dir}"; dst="$(dirname "$0")/../app/src/main/assets/models/opus-fr-en"
mkdir -p "$dst"
for f in encoder_model.int8.onnx decoder_model_merged.int8.onnx source.pieces target.pieces vocab.txt model.json; do cp "$src/$f" "$dst/$f"; done
du -sh "$dst"
