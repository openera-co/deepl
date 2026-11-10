Live-dictation settings check (mirrors `whisperLive` in native-whisper): `live <model.bin> <mode> <wav…>`
(mode bits: 1 = short encoder window, 2 = temperature fallback, 4 = token cap), scored with
`wer.py <dir with FLEURS fr_fr test.tsv> out.tsv`. 60 FLEURS French test clips, 4 x86 cores:

| model | settings | WER | compute / audio |
|---|---|---|---|
| base q5_1 (60 MB) | greedy, no fallback | 66.0% | 0.19 |
| base | short window | 398% (loops) | 0.12 |
| base | fallback + cap (6) | 35.4% | 0.21 |
| base | short window + fallback + cap (7) | 64.0% | 0.16 |
| **small q5_1 (190 MB)** | **fallback + cap (6)** — shipped | **16.8%** | 0.69 |
