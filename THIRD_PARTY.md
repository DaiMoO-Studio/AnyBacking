# Third-party components

AnyBacking itself is AGPL-3.0. It does not include any model weights, songs or lyrics; models are downloaded on first use,
and everything else is installed from the original publishers when you build the Docker image or the app.

## AI models (downloaded at run time)

| Model | Used for | License of the weights |
|---|---|---|
| Kim MelBand RoFormer `vocals_mel_band_roformer.ckpt` | Vocal removal (default for the app) | MIT (as stated on its Hugging Face page). Training data is community-sourced and not published |
| viperx BS-RoFormer `model_bs_roformer_ep_317_sdr_12.9755.ckpt` | Vocal removal (optional; default for the command line) | **No license statement** — treat as personal use only |
| OpenAI Whisper large-v3 / small | Speech recognition, lyric timing | MIT |
| Demucs (htdemucs_ft) | Not used | Code is MIT, but the author states the pretrained weights are for research only |

Models are fetched through [python-audio-separator](https://github.com/nomadkaraoke/python-audio-separator) (MIT) and
[stable-ts](https://github.com/jianfch/stable-ts) (MIT) / [openai-whisper](https://github.com/openai/whisper) (MIT).

## Server

| Component | License | Notes |
|---|---|---|
| PyTorch (base image `pytorch/pytorch`) | BSD-3-Clause | |
| FastAPI, Uvicorn, python-multipart | MIT / BSD-3-Clause / Apache-2.0 | |
| psycopg | LGPL-3.0 | Used as a library, unmodified |
| OpenCC (opencc-python-reimplemented) | Apache-2.0 | Simplified ↔ Traditional Chinese |
| yt-dlp | Unlicense | Video links (`URL_IMPORT`) |
| Deno | MIT | Needed by yt-dlp |
| FFmpeg (incl. librubberband, libass) | GPL | Debian package, run as a separate program, not distributed by this project |
| PostgreSQL 16 (`postgres` image) | PostgreSQL License | |
| SearXNG (`searxng/searxng` image) | AGPL-3.0 | Run unmodified as its own container (`WEB_LYRICS`) |
| Noto Sans CJK | SIL OFL 1.1 | Debian package |
| LXGW WenKai TC | SIL OFL 1.1 | Karaoke video lyrics font |

## Android app

| Component | License |
|---|---|
| AndroidX (Core, Activity, Lifecycle, WorkManager), Jetpack Compose, Material 3 | Apache-2.0 |
| Media3 (ExoPlayer, Session, OkHttp data source) | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Plus Jakarta Sans | SIL OFL 1.1 (`android/app/src/main/res/raw/ofl_plus_jakarta_sans.txt`) |

## Online services

| Service | Used for | Terms |
|---|---|---|
| [LRCLIB](https://lrclib.net) | Synced and plain lyrics | Free public API; lyrics are community-submitted |
| Search engines via SearXNG (Google, Bing, Yahoo, Startpage, Mojeek…) and the lyrics pages they return | Lyrics when LRCLIB has none | Each site's own terms; turn off with `WEB_LYRICS=0` |
| YouTube and other sites supported by yt-dlp | Audio from a pasted link | Each site's own terms; turn off with `URL_IMPORT=0` |
