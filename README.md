# Carnet — offline French ⇄ English translator for school

Carnet is an Android app that translates between French and English **without internet**: typed text,
photos of handouts and notes (printed or handwritten), Word/PowerPoint/Excel/ODT/PDF files (keeping
their layout), and live dictation in class. Everything runs on the phone.

## What it does

| Feature | How |
|---|---|
| **Both directions** | French → English and English → French. "Auto" detects the language of the text, photo or file; the swap button fixes a direction. |
| **Text** | Translate as you type, copy, listen, share. Glossary for school terms (*contrôle → test*). UK/US spelling. |
| **Scan** | Photo or gallery image → text read on-device (ML Kit) → French OCR mistakes corrected → translation redrawn **in place** over the photo (same position, colour, alignment). Handwriting mode uses a dedicated handwriting reader. Multi-page scans become a PDF. |
| **Documents** | DOCX, PPTX, XLSX, ODT/ODS/ODP, PDF, old .doc/.xls, RTF, TXT, Markdown, CSV, SRT, HTML. Formatting is kept: bold/italic/colour on the right words, tables, lists, images, headers/footers, comments, notes, charts. Excel formulas are never touched. Optional bilingual copy. |
| **PDF in place** | Only the text that is translated is removed; formulas, numbers and symbols stay exactly as drawn. Justified text, columns, tables, bullets, rotated chart labels and text in forms are handled. Scanned PDFs are OCR'd. |
| **Tools** | Word/PowerPoint/Excel/ODT/text → PDF, PDF → Word, Word ↔ ODT, images ↔ PDF, merge, split, compress, rotate, reorder, page numbers — each can also translate. |
| **Record** | Live dictation in the source language; spoken French punctuation (*virgule*, *point à la ligne*…); each sentence translated live. Audio kept; tap a timestamp to replay. |
| **Models** | One button downloads everything (French ⇄ English, best quality ~450 MB) with live size, speed and time left; downloads continue in the background, resume after a lost connection and are checksummed. Each model has a one-line explanation in Settings. |
| **Share** | Send downloaded models to another phone over local Wi-Fi/hotspot with a QR code, or import files received any other way. "Share the app" sends the APK. |
| **Updates** | The app checks for new versions, notifies, downloads only the app (models are kept) and installs it. |

## Project layout

```
core/        Pure Kotlin (JVM) — runs on the phone and in tests
  mt/          SentencePiece tokenizer, ONNX Runtime Marian decoder (greedy + beam, KV cache), text pipeline
  htr/         Handwriting line recognizer (TrOCR, ONNX)
  doc/         Format-preserving translators: DOCX, PPTX/DrawingML, XLSX, ODF, DOC/XLS (POI), RTF, plain text
  layout/      In-place layout for PDFs and photos (blocks, styled word wrap, fitting)
  convert/     Document model, readers, Paginator, DOCX/ODT writers
  text/        Segmenter, language detection, OCR fixes, dictation punctuation, spelling variants
pdfshared/   PDF translator, PDF writer, PDF→Word, PDF tools (PDFBox for tests, rewritten to PdfBox-Android)
app/         Android app (Jetpack Compose): screens, OCR, camera, recorder, jobs, models, downloads, updates, sharing
native-*/    JNI engines: llama.cpp (TranslateGemma) and whisper.cpp — enabled with -Pcarnet.native=true
tools/       Model export/quantization, evaluation, test-set generators, Kaggle training jobs
```

## Building

Requirements: JDK 17+, Android SDK 35 (NDK 27 and CMake 3.31 for the native engines).

```bash
./gradlew :app:assembleRelease                         # without native engines
./gradlew :app:assembleRelease -Pcarnet.native=true    # with Whisper/TranslateGemma
```

Models are not in the APK. `.github/workflows/models.yml` exports and quantizes them
(`tools/export_model.py`, `tools/quantize4.py`) and publishes them with a checksummed catalog as the
release `models-v1`; the app downloads from there. `.github/workflows/android.yml` tests, builds and
publishes every build as the latest release with `update.json`, which installed apps use to update.
Builds are signed with a fixed key in the repo (`app/carnet-shared.keystore`) so new APKs install over
old ones; use your own key for a store release.

## Testing

```bash
./gradlew :core:test :app:testDebugUnitTest                                       # unit, parity and screen-walk tests
./gradlew :core:bench -PbenchArgs="fidelity /data/corpus /data/out fake"          # format fidelity (generated corpus)
./gradlew :core:bench -PbenchArgs="pdf <modelDir> /data/out file.pdf…"             # real PDFs (compare with tools/realpdf)
./gradlew :core:bench -PbenchArgs="real fake /data/out /data/realdocs"            # downloaded real documents
./gradlew :core:bench -PbenchArgs="mt <modelDir> src out"                         # quality / throughput
```

CI also runs: an OCR benchmark on an Android emulator (`ocr.yml`), a speed/quality benchmark on x86-64
and ARM64 (`speed.yml`), and a TranslateGemma evaluation (`eval.yml`). See [TESTING.md](TESTING.md).

## Limits (honest)

- Translation is Opus-MT tc-big (FLORES BLEU 43.8 FR→EN, 51.5 EN→FR): fluent on school text and close to
  models 60–100× larger, but below DeepL on idioms and long, complex sentences.
- Photo reading is excellent on print (≈0.2% character errors after French fixes); real handwriting is
  harder and depends on the handwriting model.
- Live dictation uses Android's on-device speech recogniser; on phones without one, Carnet records audio only.
- Old .doc files are converted to .docx; text boxes and floating images are kept but placed in the text flow.
- Nothing here has been measured on a real phone yet; speeds come from CI machines.
