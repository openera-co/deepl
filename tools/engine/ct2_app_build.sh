#!/bin/bash
# Builds CTranslate2 the way the app does (Ruy, OpenMP, no MKL), then measures it on FLORES with the
# app's model conversion. usage: ct2_app_build.sh <src> <ref>
set -e
src=$1; ref=$2
[ -d ct2src ] || git clone -q --depth 1 --branch v4.8.5 https://github.com/OpenNMT/CTranslate2.git ct2src
(cd ct2src && git submodule update --init --depth 1 third_party/ruy third_party/cpu_features third_party/spdlog > /dev/null 2>&1 && git -C third_party/ruy submodule update --init --depth 1 > /dev/null 2>&1)
cmake -S ct2src -B ct2build -DCMAKE_BUILD_TYPE=Release -DWITH_MKL=OFF -DWITH_RUY=ON -DOPENMP_RUNTIME=COMP -DBUILD_CLI=OFF -DBUILD_SHARED_LIBS=OFF -DENABLE_CPU_DISPATCH=OFF -DCMAKE_INSTALL_PREFIX=$PWD/ct2inst > /dev/null
cmake --build ct2build -j$(nproc) > /dev/null && cmake --install ct2build > /dev/null
g++ -O2 -std=c++17 tools/engine/ct2_cli.cpp -Ict2inst/include -Wl,--start-group ct2build/libctranslate2.a $(find ct2build -name "*.a" ! -name libctranslate2.a | tr '\n' ' ') -Wl,--end-group -fopenmp -lpthread -o ct2_cli
python - "$src" > src.tok <<'PY'
import sys
from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained("Helsinki-NLP/opus-mt-tc-big-fr-en")
for l in open(sys.argv[1], encoding="utf-8"): print(" ".join(tok.convert_ids_to_tokens(tok.encode(l.strip()))))
PY
for t in 1 2 4; do ./ct2_cli ct2_opus-mt-tc-big-fr-en_int8 $t src.tok out.tok; done
python - out.tok > hyp.txt <<'PY'
import sys
from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained("Helsinki-NLP/opus-mt-tc-big-fr-en")
for l in open(sys.argv[1], encoding="utf-8"): print(tok.convert_tokens_to_string(l.split()).strip())
PY
echo "app-build CT2 BLEU $(sacrebleu $ref -i hyp.txt -m bleu -b)"
