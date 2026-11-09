# Test results

All numbers below were produced in this repository's test harness (`./gradlew :core:bench …`) on a
4-core x86 cloud machine. Phone speed will differ (see "Speed" below). Nothing here was run on a real
phone: the app was exercised with a Robolectric UI smoke test, and every engine it uses is tested on the
JVM with the same code.

## Test corpus

`tools/corpus/generate.py` produced **12,000 French school documents** with ground-truth sidecar files:
2,000 DOCX, 1,000 PPTX, 1,000 XLSX, 1,000 ODT, 2,000 PDF, 3,000 photos/scans (JPG/PNG: paper, cream,
lined notebook, slides, blackboard, coloured; random fonts, sizes, slight rotation and blur), and 500 each
of TXT, Markdown, CSV and SRT. Content mixes 122 hand-written school sentences, 1,397 French Wikipedia
paragraphs and WMT news-discussion sentences, with random bold/italic/underline/colour/highlight spans,
lists, tables, images, headers/footers with page fields, hyperlinks, speaker notes, formulas and comments.

## Format fidelity (all 9,000 documents, structural checks)

Run with a deterministic stand-in translator so formatting can be verified word by word.

| Format | Files | Fully correct | Crashes | Median time |
|---|---|---|---|---|
| DOCX | 2,000 | 1,986 (99.3%) | 0 | 31 ms |
| PPTX | 1,000 | 999 (99.9%) | 0 | 15 ms |
| XLSX | 1,000 | 1,000 (100%) | 0 | 4 ms |
| ODT | 1,000 | 985 (98.5%) | 0 | 6 ms |
| PDF | 2,000 | 2,000 (100%) | 0 | 29 ms |
| TXT / MD / CSV / SRT | 2,000 | 2,000 (100%) | 0 | ≤ 2 ms |

Checks per file: same package parts; images/media byte-identical; relationships untouched; all XML
well-formed; paragraph/table/row/cell/image/hyperlink/field counts unchanged; header/footer fields
unchanged; no French left; every formatted span (bold, italic, underline, colour, highlight) carries its
formatting on the right English words; Excel formulas and formula literals untouched; proofing language
switched to English; PDF page count, image count, all text inside the page; subtitle timings and Markdown
structure kept. The remaining ~0.7% are formatted spans that straddle two sentences.

## Photos (3,000 images, layout checks)

Ground-truth line boxes stand in for ML Kit (which only runs on the phone); blocks are translated and
redrawn with the same layout code as the app. 0 crashes; text never leaves the image; no two translated
blocks overlap. French ink is removed pixel-by-pixel, so ruled notebook lines and coloured backgrounds stay.

## Translation quality (real model)

Opus-MT fr-en, int8 ONNX (101 MB), run through the app's Kotlin pipeline:

| Test set | BLEU | chrF |
|---|---|---|
| WMT newstest2014 (3,003 sentences) | 37.2 | 62.5 |
| first 500 of the above (vs. Python reference 38.67) | 38.9 | — |
| newsdiscuss 2015 (500) | 31.6 | 56.2 |
| Tatoeba (500, everyday sentences) | 55.1 | 70.4 |

Larger "tc-big" model: +1.3 BLEU on news but 3× larger and slower — not bundled.

Tokenizer parity with the reference SentencePiece: 5,511 / 5,512 sentences identical. Decoder parity at
full precision: 29/30 greedy, 30/30 beam-4 identical to Hugging Face.

### School-text fixes (review of 122 school sentences)

| French | Before | After |
|---|---|---|
| …ouvert de huit heures à dix-sept heures | 8 a.m. to **7 p.m.** | 8 a.m. to **5 p.m.** |
| Le devoir est à rendre pour lundi | The duty is to be returned | The homework is due |
| Dans un triangle rectangle… | In a rectangle triangle | In a right-angled triangle |
| …la réunion des États généraux | the General States | the Estates-General |
| L'imparfait exprime une action… | Impartial expression is an action… | The imperfect expresses an action… |
| Le contrôle aura lieu jeudi… | The check will take place | The test will take place |
| Bon trimestre dans l'ensemble | Good quarter overall | Good term overall |
| …prévenir la vie scolaire | prevent school life | notify school office |
| Les inscriptions à la sortie scolaire… | out-of-school course | school trip |
| Relevez deux figures de style | style figures | figures of speech |

These come from: spelled-out numbers → digits before units, a built-in school vocabulary, a number guard
(re-translate with beam search if numbers don't survive), and article-insensitive glossary matching.
No regression on news (BLEU 37.13 → 37.17).

## Real-model document runs (225 documents, 25 per format)

0 crashes. Median time per document on the test machine: XLSX 0.6 s, PPTX 1.6 s, SRT/CSV 1–1.4 s,
DOCX 3.1 s, ODT 2.9 s, PDF 3.9 s (documents of 1–3 pages with 20–40 paragraphs). Text throughput
≈ 10–13 sentences/s (greedy), ≈ 4 sentences/s (careful/beam 4).

## Conversions

40 files per format (150 for PowerPoint): every file converted, 0 crashes. Checked: X → PDF keeps ≥ 97% of the
words, PDF → Word keeps ≥ 97%, Word ↔ ODT round-trip keeps ≥ 98%.

| Conversion | Files | Pass | Median |
|---|---|---|---|
| Word → PDF (+ Word → ODT) | 40 | 40 | 0.20 s |
| ODT → PDF (+ ODT → Word) | 40 | 40 | 0.08 s |
| PowerPoint → PDF | 150 | 147* | 0.09 s |
| Excel → PDF | 40 | 40 | 0.04 s |
| Markdown / CSV / text → PDF | 120 | 120 | 0.03 s |
| PDF → Word | 40 | 40 | 0.13 s |

*The 3 are a measurement artefact (tightly shrunk slide text read back as merged words); a per-slide
check finds every word present. Testing found and fixed: slide tables were skipped (they use `p:xfrm`),
layout placeholders without positions, bullets/hanging indents, Word theme fonts, page-number fields.
Word and PowerPoint output was compared visually with LibreOffice's PDF export.

## App

`./gradlew :app:testDebugUnitTest` — Robolectric launches the real app and walks First launch → Home →
Text → Document → Tools (4 tools) → Settings → Glossary → Models → Share → Library → Record → Translate.
This caught and fixed a crash when the translation engine fails to load.

## Not verified here

- On-device speed, camera, ML Kit OCR on real photos, the Android speech recogniser, and model downloads
  (no phone or emulator available in this environment).
- The native engines (whisper.cpp, llama.cpp/TranslateGemma): code is written and wired, but building them
  requires compiling third-party C++, which this environment does not allow. CI builds them with
  `-Pcarnet.native=true`.
