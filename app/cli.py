"""
YouTube / YouTube Music → 無人聲 KTV 影片（伴唱／導唱雙音軌＋逐字變色字幕）

每個步驟的產出都會留在輸出資料夾，重跑時已存在的檔案會跳過；
修改 lyrics.lrc、words.json 或 karaoke.ass 後用 --rerender 只重做受影響的步驟。
"""
import argparse
import json
import os
import re
import shutil
import sys
import zlib
from pathlib import Path

import align
import lyrics
import media
from common import detect_lang, even_words, load_lines, log, media_duration, newer, safe_name, save_lines
from subtitles import build_ass, build_lyric_ass

DEFAULT_SEP_MODEL = "model_bs_roformer_ep_317_sdr_12.9755.ckpt"
# 商用模式只允許權重授權明確可商用的去人聲模型。
# Kim MelBand RoFormer：Hugging Face（KimberleyJSN/melbandroformer）標示 MIT。
# 注意 Demucs 不行：程式碼是 MIT，但作者表示預訓練權重不在 MIT 範圍內、只供研究使用
# （facebookresearch/demucs#327）。新增模型前要確認的是「權重」的授權，不是程式碼的。
COMMERCIAL_SEP_MODELS = ("vocals_mel_band_roformer.ckpt",)


def parse_args():
    p = argparse.ArgumentParser(description="把 YouTube 影片轉成 KTV 伴唱影片")
    p.add_argument("input", nargs="?", help="YouTube / YouTube Music 網址，或本機影音檔路徑")
    p.add_argument("--commercial", action="store_true", default=bool(os.environ.get("ANYBACKING_COMMERCIAL")),
                   help="商用模式：只接受本機檔案、不自動抓歌詞、只用可商用的去人聲模型")
    p.add_argument("--rerender", metavar="DIR", help="重新處理既有的輸出資料夾")
    p.add_argument("--lyrics", metavar="FILE", help="自行提供歌詞（.lrc 有時間，或純文字 .txt）")
    p.add_argument("--query", help="自訂歌詞搜尋關鍵字，例如「告白氣球 周杰倫」")
    p.add_argument("--lang", help="歌詞語言（zh/en/ja/ko…），預設自動判斷")
    p.add_argument("--s2t", action="store_true", help="歌詞簡體轉繁體")
    p.add_argument("--no-align", action="store_true", help="不做 AI 逐字對齊，直接依 LRC 逐句平均分配")
    p.add_argument("--guide-volume", type=float, default=0.3, help="導唱音軌中原唱的音量（0~1，預設 0.3）")
    p.add_argument("--whisper-model", default=os.environ.get("WHISPER_MODEL"), help="Whisper 模型（預設 GPU 用 large-v3、CPU 用 small）")
    p.add_argument("--sep-model", default=os.environ.get("SEP_MODEL"),
                   help=f"去人聲模型檔名（預設 {DEFAULT_SEP_MODEL}，商用模式 {COMMERCIAL_SEP_MODELS[0]}）")
    p.add_argument("--style", choices=["lyric", "karaoke"], default="lyric",
                   help="字幕風格：lyric＝滾動歌詞（類似 YouTube Music，預設）、karaoke＝KTV 逐字變色")
    p.add_argument("--background", default="auto",
                   help="背景：auto＝自動產生的動態背景（預設）、mv＝原影片、或背景圖片路徑")
    p.add_argument("--key", type=int, default=0, help="升降 Key，單位是半音（例如 -2 降兩個半音），只改音高不改速度")
    p.add_argument("--nvenc", action="store_true", help="用 NVIDIA 硬體編碼輸出影片")
    p.add_argument("--cookies", default=os.environ.get("YTDLP_COOKIES"), help="yt-dlp cookies.txt（年齡限制影片用）")
    p.add_argument("--output-dir", default=os.environ.get("OUTPUT_DIR", "output"))
    p.add_argument("--model-dir", default=os.environ.get("MODEL_DIR", "models"))
    p.add_argument("--force", action="store_true", help="忽略快取，歌詞與字幕全部重做")
    a = p.parse_args()
    if not a.input and not a.rerender:
        p.error("請提供網址／檔案，或使用 --rerender")
    if a.commercial:
        a.sep_model = a.sep_model or COMMERCIAL_SEP_MODELS[0]
        if a.sep_model not in COMMERCIAL_SEP_MODELS:
            p.error(f"商用模式只能使用這些去人聲模型：{', '.join(COMMERCIAL_SEP_MODELS)}")
        if a.query:
            p.error("商用模式不自動搜尋歌詞，--query 無效；請用 --lyrics 提供歌詞")
    else:
        a.sep_model = a.sep_model or DEFAULT_SEP_MODEL
    return a


def prepare_workdir(a, out_root: Path):
    """回傳 (workdir, meta, source)。"""
    if a.rerender:
        workdir = Path(a.rerender).resolve()
        meta = json.loads((workdir / "meta.json").read_text(encoding="utf-8"))
        return workdir, meta, media.find_source(workdir)

    local = Path(a.input)
    if local.exists():
        meta = {"title": local.stem, "track": "", "artist": "", "channel": "", "duration": 0, "url": str(local)}
        # 商用模式另開資料夾，避免沿用一般模式（不同模型）的分離結果，也方便兩者比較
        workdir = out_root / (safe_name(local.stem) + (" [商用]" if a.commercial else ""))
        workdir.mkdir(parents=True, exist_ok=True)
        src = media.find_source(workdir)
        if src is None:
            src = workdir / f"source{local.suffix.lower()}"
            shutil.copy2(local, src)
    elif re.match(r"https?://", a.input):
        if a.commercial:
            sys.exit("商用模式只接受使用者提供的本機影音檔，不支援網址下載")
        meta = media.probe_url(a.input, a.cookies)
        workdir = out_root / f"{safe_name(meta['title'])} [{meta['id']}]"
        workdir.mkdir(parents=True, exist_ok=True)
        src = media.find_source(workdir) or media.download(meta["url"], workdir, a.cookies)
    else:
        sys.exit(f"找不到檔案，也不是網址：{a.input}")

    (workdir / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    return workdir, meta, src


def get_lines(a, workdir: Path, meta: dict, duration: float, device: str) -> list:
    words_json = workdir / "words.json"
    lrc = workdir / "lyrics.lrc"
    txt = workdir / "lyrics.txt"

    if a.lyrics:
        src = Path(a.lyrics)
        dst, other = (lrc, txt) if lyrics.parse_lrc(src.read_text(encoding="utf-8-sig")) else (txt, lrc)
        other.unlink(missing_ok=True)
        shutil.copyfile(src, dst)  # 不保留原始時間戳，讓它比 words.json 新

    lyric_src = lrc if lrc.exists() else (txt if txt.exists() else None)
    if words_json.exists() and not a.force and not (lyric_src and newer(lyric_src, words_json)):
        log("使用已存在的 words.json")
        return load_lines(words_json)

    if lyric_src is None and not a.commercial:  # 商用模式不抓第三方歌詞，只用使用者提供的或 Whisper 辨識
        hay = None if a.query else " ".join(str(meta.get(k) or "") for k in ("title", "track"))
        found = lyrics.find_lyrics(lyrics.build_queries(meta, a.query), duration, must_match=hay,
                                   artist=None if a.query else meta.get("artist"))
        if found:
            lyric_src = lrc if lyrics.parse_lrc(found["text"]) else txt
            lyric_src.write_text(found["text"], encoding="utf-8")

    model_name = a.whisper_model or ("large-v3" if device == "cuda" else "small")
    vocals = workdir / "vocals.wav"

    if lyric_src is None:
        model = align.load_whisper(model_name, device)
        lines, lang = align.transcribe(model, vocals, a.lang, a.s2t)
        if not lines:
            sys.exit("辨識不到任何歌詞，請用 --lyrics 自行提供")
        lyrics.write_lrc(lines, lrc, meta)
        log(f"已將辨識結果寫入 {lrc.name}，可校對後用 --rerender 重新對齊")
    else:
        lines, timed = lyrics.load_lyrics_file(lyric_src, duration)
        if not lines:
            sys.exit(f"{lyric_src.name} 裡沒有歌詞內容")
        if a.s2t:
            for l in lines:
                l.text = lyrics.to_traditional(l.text)
        lang = a.lang or detect_lang("".join(l.text for l in lines))
        if a.no_align and timed:
            for l in lines:
                l.words = even_words(l)
        else:
            model = align.load_whisper(model_name, device)
            lines = align.align_lines(model, vocals, lines, lang, timed)
            if not timed:
                lyrics.write_lrc(lines, lrc, meta)
                log(f"已將對齊結果寫入 {lrc.name}，可校對後用 --rerender 重新對齊")
            lines = [l for l in lines if l.words]  # 這個版本沒唱的句子不上字幕

    save_lines(lines, words_json)
    return lines


def main():
    a = parse_args()
    if a.background not in ("auto", "mv"):
        bg = Path(a.background).resolve()
        if not bg.is_file():
            sys.exit(f"找不到背景圖片：{a.background}")
        a.background = str(bg)
    out_root = Path(a.output_dir).resolve()
    model_dir = Path(a.model_dir).resolve()
    model_dir.mkdir(parents=True, exist_ok=True)
    device = align.get_device()
    log(f"運算裝置：{device}")

    workdir, meta, src = prepare_workdir(a, out_root)
    log(f"輸出資料夾：{workdir}")

    audio = workdir / "audio.wav"
    if not audio.exists():
        if src is None:
            sys.exit("找不到來源影音檔 source.*")
        media.extract_audio(src, audio)
    duration = media_duration(audio)

    # 記錄分離用的模型；換了模型（例如商用模式從 Demucs 改成 MelBand RoFormer）就重新分離。
    # --rerender 一律沿用已分離的音軌（它沒帶 --commercial 時 a.sep_model 是一般模式的預設值）。
    sep_note = workdir / "sep_model.txt"
    stems_ok = (workdir / "vocals.wav").exists() and (workdir / "instrumental.wav").exists()
    used = sep_note.read_text().strip() if sep_note.exists() else ("htdemucs_ft.yaml" if a.commercial else a.sep_model)
    if stems_ok and not a.rerender and used != a.sep_model:  # 沒有紀錄的舊商用資料夾都是 Demucs 分的
        log(f"去人聲模型從 {used} 換成 {a.sep_model}，重新分離")
        stems_ok = False
        (workdir / "words.json").unlink(missing_ok=True)  # 人聲換了，逐字時間要重新對齊
    if not stems_ok:
        media.separate(audio, workdir, model_dir, a.sep_model)
        sep_note.write_text(a.sep_model)
    else:
        log("使用已分離的伴奏／人聲")

    words_json = workdir / "words.json"
    ass = workdir / ("lyrics.ass" if a.style == "lyric" else "karaoke.ass")
    lines = get_lines(a, workdir, meta, duration, device)
    if a.force or not ass.exists() or newer(words_json, ass):
        (build_lyric_ass if a.style == "lyric" else build_ass)(lines, meta, ass)
        log(f"已產生字幕 {ass.name}（{len(lines)} 句）")
    else:
        log(f"{ass.name} 比 words.json 新，保留手動修改的字幕")

    media.make_guide(workdir / "instrumental.wav", workdir / "vocals.wav", workdir / "guide.wav", a.guide_volume)
    key = f"_key{a.key:+d}" if a.key else ""  # 不同 Key 各自一個檔案，不覆蓋原調
    out = workdir / f"{safe_name(meta.get('track') or meta.get('title') or 'song')}_KTV{key}.mp4"
    seed = zlib.crc32((meta.get("track") or meta.get("title") or "").encode())
    media.render(workdir, src, out, a.nvenc, ass.name, a.background, seed, a.key)
    log(f"完成：{out}")


if __name__ == "__main__":
    main()
