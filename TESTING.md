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

## Real-world PDFs (106 documents, real model, visual + automatic checks)

The synthetic PDFs above were too simple: a real 19-page report made in a browser (WeasyPrint) came out
broken. `tools/realpdf/gen.py` now builds PDFs the way people actually make them — browser/WeasyPrint
reports (justified text, cards, two columns, SVG charts with rotated axis titles, formulas, tables),
Word documents exported by LibreOffice (first-line indents, coloured runs, bordered tables, images,
headers/footers, font fallback for symbols and Chinese), slide decks exported to PDF, and worksheets with
labelled diagrams and fill-in blanks — plus a French replica of that report (`expose_fr.html`).
Each output was compared side by side with its original (`compare.py`) and checked automatically
(`check.py`: page/image/drawing counts, overlapping text, text off the page, French left, shrunk text).

Found and fixed:

| Problem | Cause | Fix |
|---|---|---|
| Justified paragraphs shredded into one-word fragments | text stripper reports such lines word by word | lines collected whole, split only at real gaps |
| Text 33% too large on browser PDFs | page transform (px → pt) ignored | size from the full text-rendering matrix |
| Colours lost (blue headings → black) | colour operators not processed | colour operators registered |
| Two-column pages merged across the gutter | position sorting | content-stream order + fragment re-joining |
| Table cells merged / text spilling over borders | no knowledge of drawn lines | table borders and box edges detected; text never merges or grows across them |
| Table of contents / list items merged into one paragraph | paragraph spacing ignored | line-pitch per box, word-wrap test |
| Bullets lost, hanging indents lost | symbol-font bullets, marker merged with text | bullets kept in place, indents kept |
| Formulas, page numbers redrawn in another font | everything was removed and redrawn | only replaced text is removed; the rest stays untouched |
| Glyphs after removed text shifted | removal changed the text position | removed text replaced by an invisible move |
| Chart labels doubled; rotated axis titles drawn flat | text inside form XObjects; rotation ignored | form text replaced; rotated text drawn rotated |
| x̄, subscripts (uₙ), "∼" misplaced | out-of-order fragments, combining marks | fragments re-joined, marks centred on their letter |
| Dot leaders "Nom : ……" doubled; LaTeX `\frac{}` translated | | leaders and formulas kept verbatim; formula lines translate only their words |
| "Exercice 1" → "Year 1", "participes passés" → "participating pasts", "berlines" → "kernels", headings in CAPS translated badly | model errors | term guard with placeholders, school terms, CAPS translated in sentence case |
| Chinese/Hindi text → hallucinated "(-------"; "energy, energy, energy…" loops; titles copied untranslated | model errors | foreign scripts shielded, loop/invented-number guard with beam retry, titles split at dashes |

Result: 106 documents, 0 crashes, 105 pass every automatic check (the one flagged quotes French
institution names, which stay in French). Median 2.2 s per document on the test machine. News BLEU unchanged (38.5 vs 38.6).

## Photos (3,000 images, layout checks)

Ground-truth line boxes stand in for ML Kit (which only runs on the phone); blocks are translated and
redrawn with the same layout code as the app. 0 crashes; text never leaves the image; no two translated
blocks overlap. French ink is removed pixel-by-pixel, so ruled notebook lines and coloured backgrounds stay.

## Translation models (both directions)

Since 1.2 the app downloads its models (Models screen) instead of bundling them. "Best" is Opus-MT
tc-big with 4-bit weights (224 MB per direction); "fast" is Opus-MT base int8 (109 MB). Scores on 300
FLORES sentences (Wikipedia-style text, professional references); speed is the app's Kotlin pipeline
on a 4-core ARM64 CI runner (same ONNX Runtime kernels as Android; a phone is roughly half as fast).

| System | FR→EN BLEU | FR→EN COMET | EN→FR BLEU | EN→FR COMET | Sentences/s (ARM64) |
|---|---|---|---|---|---|
| App, best | 43.8 | 0.891 | 51.5 | 0.879 | 6.4–8.8 |
| App, fast | 39.8 | — | 45.8 | — | 10–16 |
| tc-big, beam 4 (Careful mode) | 44.4 | 0.892 | 51.8 | 0.881 | — |
| TranslateGemma 4B (Q4, llama.cpp) | 37.1 | 0.887 | — | — | 0.17 (x86 CPU) |
| Qwen3 14B (GPU) | 43.2 | 0.896 | 44.4 | 0.880 | — |
| Mistral Small 24B (GPU) | 43.6 | 0.895 | 48.9 | 0.889 | — |

The phone model is within ~0.5 COMET of models 60–100× larger for FR→EN, and ahead of TranslateGemma
4B while ~40× faster. These large models were measured as possible teachers for further training
(`tools/kaggle/teacher_eval`).

## OCR (the app's real recognizer on an Android emulator)

`.github/workflows/ocr.yml` runs ML Kit inside the app (same code as the Scan screen) on 200 generated
photos with known text (`tools/ocr/make_set.py`): printed pages on white/cream/coloured paper, lined
notebooks, slides and blackboards (blur, rotation), and handwriting-style pages in open-licence
handwriting fonts. Scored by character/word error rate (`tools/ocr/score.py`).

| Category | CER before | CER with French fixes | WER before | WER with French fixes |
|---|---|---|---|---|
| Paper | 0.33% | 0.16% | 2.4% | 1.2% |
| Cream | 0.33% | 0.14% | 2.3% | 1.0% |
| Coloured | 0.34% | 0.22% | 2.1% | 1.0% |
| Blackboard | 0.40% | 0.22% | 3.0% | 1.5% |
| Slide | 0.43% | 0.16% | 2.9% | 1.0% |
| Lined notebook | 0.79% | 0.58% | 5.1% | 3.4% |
| Handwriting-style fonts | 3.44% | 3.34% | 17.3% | 16.6% |

The French fixes (`OcrFix`) correct what the recognizer gets wrong systematically on French: accents
dropped on capitals (États, À, Âge), "I'" for "l'", "ler" for "1er", Roman numerals with l, "oe" for
œ, lost ï, stray capital X, "1O" for 10, foreign diacritics, French spacing before : ; ! ?. English
is never changed. Image preprocessing (invert dark boards, upscale, local contrast) was measured and
didn't help, so it stays off. Real handwriting is harder than handwriting fonts: a dedicated
recognizer is being trained (`tools/kaggle/htr_train`, RIMES-2011 French handwriting lines).

## Real downloaded documents

742 openly licensed French files (`tools/realdocs/fetch.py`): theses, articles and lecture notes from
HAL, and public-sector PDF/Word/Excel/ODF files from data.gouv.fr. 667 supported files translated with
0 crashes (after fixing PDF operators with missing operands and reading PDF pages one by one so a damaged
page can't fail the file). The other 75 are old binary .doc/.xls files, which aren't supported.

## Translation quality, earlier base model (FR→EN)

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
