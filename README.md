# Carnet — offline French → English translator for school

Carnet is an Android app that translates French school material into English **without internet**:
typed text, photos of handouts, Word/PowerPoint/Excel/ODT/PDF files (keeping their layout), and live
dictation in class. Everything runs on the phone.

## What it does

| Feature | How |
|---|---|
| **Text** | Translate as you type (~0.1 s per sentence), copy, listen, share. Glossary for school terms (*contrôle → test*). UK/US spelling. |
| **Scan** | Photo or gallery image → French text read on-device (ML Kit, bundled) → English redrawn **in place** over the photo (same position, colour, alignment). Multi-page scans become a PDF. Tabs: Original / English / editable Text. |
| **Documents** | DOCX, PPTX, XLSX, ODT/ODS/ODP, PDF, TXT, Markdown, CSV, SRT, HTML. Formatting is kept: bold/italic/colour/highlight on the right words, tables, lists, images, headers/footers, footnotes, comments, speaker notes, charts. Excel formulas are never touched. Optional bilingual copy (French in grey above each English paragraph). |
| **PDF in place** | Original text is removed from the page content and English is drawn in the same blocks with a matching font (Calibri→Carlito, Times→Liberation Serif…), shrinking only when needed. Scanned PDFs are OCR'd. |
| **Tools** | Word/PowerPoint/Excel/ODT/text → PDF, PDF → Word, Word ↔ ODT, images ↔ PDF, merge, split, compress, rotate, reorder, page numbers — each can also translate. |
| **Record** | Live dictation: the on-device French speech recogniser types as the teacher speaks; spoken punctuation (*virgule*, *point à la ligne*, *ouvrez les guillemets*…) is applied; each sentence is translated live. Audio is kept; tap a timestamp to replay. Export to Word. |
| **Improve** (optional download) | Whisper re-transcribes a recording after class; TranslateGemma 4B reads handwriting and re-translates hard sentences. |
| **Share models** | Send downloaded models to another phone over local Wi-Fi/hotspot with a QR code (checksummed, resumable), or import model files received any other way. "Share the app" sends the APK itself. |

## Project layout

```
core/        Pure Kotlin (JVM) — runs on the phone and in tests
  mt/          SentencePiece tokenizer, ONNX Runtime Marian decoder (greedy + beam, KV cache), text pipeline
  doc/         Format-preserving translators: DOCX, PPTX/DrawingML, XLSX, ODF, plain text; RunMapper (formatting projection)
  layout/      In-place layout for PDFs and photos (blocks, styled word wrap, fitting)
  convert/     Document model, readers (DOCX/ODT/PPTX/XLSX/MD/CSV), Paginator, DOCX/ODT writers
  text/        Segmenter, dictation punctuation, spelling variants
pdfshared/   PDF translator, PDF writer, PDF→Word, PDF tools (compiled against PDFBox for tests and
             auto-rewritten to PdfBox-Android for the app)
app/         Android app (Jetpack Compose): screens, OCR, camera, recorder, jobs, models, sharing
native-*/    Optional JNI engines: llama.cpp (TranslateGemma) and whisper.cpp — enable with -Pcarnet.native=true
tools/       Model export & evaluation, test corpus generator
```

## Building

Requirements: JDK 17+, Android SDK 35, Python 3.10+ (for the one-time model export).

```bash
# 1. Export the translation model (ONNX int8, ~101 MB) into the app's assets
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install "transformers<5" sentencepiece onnx onnxruntime "optimum[onnxruntime]" accelerate
python tools/export_model.py Helsinki-NLP/opus-mt-fr-en build/model
tools/install_model.sh build/model

# 2. Build
./gradlew :app:assembleRelease                         # without native engines
./gradlew :app:assembleRelease -Pcarnet.native=true    # with Whisper/TranslateGemma (needs NDK 27, CMake 3.31)
```

The GitHub workflow (`.github/workflows/android.yml`) does all of this and uploads the APK as an artifact.
Builds are signed with a fixed key in the repo (`app/carnet-shared.keystore`) so new APKs install over old ones;
use your own key for a store release.

## Testing

```bash
./gradlew :core:test                                          # unit + parity tests
python tools/corpus/generate.py /data/corpus                  # 12,000 French school documents with ground truth
./gradlew :core:bench -PbenchArgs="fidelity /data/corpus /data/out fake"        # format fidelity, all files
./gradlew :core:bench -PbenchArgs="fidelity /data/corpus /data/out <modelDir> 25" # real model, timing
./gradlew :core:bench -PbenchArgs="convert /data/corpus /data/conv"             # conversions
./gradlew :core:bench -PbenchArgs="images /data/corpus /data/img fake"          # photo overlay layout
./gradlew :core:bench -PbenchArgs="mt <modelDir> src.fr out.en"                 # throughput for BLEU
```

See [TESTING.md](TESTING.md) for the latest results.

## Limits (honest)

- Translation quality is that of Opus-MT fr-en (BLEU ≈ 39 on WMT newstest2014): good and fluent on school
  text, but below DeepL on idioms and long, complex sentences. "Improve" (TranslateGemma) helps there.
- Photo text reading is excellent on print, weak on messy handwriting without the optional model.
- Live dictation uses Android's on-device French recogniser; on phones without it, Carnet records audio only.
- Conversions use Carnet's own layout engine: close to Word/LibreOffice for typical school documents, not pixel-identical
  for complex layouts (text boxes, columns, SmartArt).
