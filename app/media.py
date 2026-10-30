"""影音處理：下載、去人聲、合成。"""
import re
import shutil
from pathlib import Path

from common import has_real_video, log, media_duration, run


MAX_URL_SECONDS = 15 * 60   # 網址上傳的影片長度上限（避免長時間佔用共用的顯示卡；worker 與預覽共用）


def probe_url(url: str, cookies: str = None) -> dict:
    from yt_dlp import YoutubeDL
    opts = {"noplaylist": True, "quiet": True}
    if cookies:
        opts["cookiefile"] = cookies
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    artists = info.get("artists") or ([info["artist"]] if info.get("artist") else [])
    return {
        "id": info.get("id"),
        "url": info.get("webpage_url") or url,
        "title": info.get("title") or "",
        "track": info.get("track") or "",
        "artist": artists[0].split(",")[0].strip() if artists else "",
        "channel": info.get("channel") or info.get("uploader") or "",
        "duration": info.get("duration") or 0,
    }


def download(url: str, workdir: Path, cookies: str = None) -> Path:
    from yt_dlp import YoutubeDL
    log("下載影片")
    opts = {
        "format": "bv*[height<=1080]+ba/b",
        "merge_output_format": "mp4",
        "outtmpl": str(workdir / "source.%(ext)s"),
        "noplaylist": True,
    }
    if cookies:
        opts["cookiefile"] = cookies
    with YoutubeDL(opts) as ydl:
        ydl.download([url])
    return find_source(workdir)


def download_audio(url: str, outdir: Path) -> Path:
    """App 的網址上傳（009）：只抓音訊（App 不顯示 MV），存成 outdir/source.<副檔名>。"""
    from yt_dlp import YoutubeDL
    log("下載音訊")
    with YoutubeDL({"format": "bestaudio/best", "outtmpl": str(outdir / "source.%(ext)s"),
                    "noplaylist": True, "quiet": True}) as ydl:
        ydl.download([url])
    return find_source(outdir)


def find_source(workdir: Path):
    for p in sorted(workdir.glob("source.*")):
        if p.suffix not in (".part", ".ytdl", ".json"):
            return p
    return None


def extract_audio(src: Path, out: Path) -> None:
    log("擷取音訊")
    run(["ffmpeg", "-y", "-v", "error", "-i", src, "-vn", "-ac", "2", "-ar", "44100", out])


def separate(audio: Path, workdir: Path, model_dir: Path, model_name: str, cache: dict = None,
             overlap: int = None) -> None:
    """
    cache：常駐程式（網頁服務的工作機）傳入同一個 dict，模型只載入一次。
    overlap：Roformer 分段重疊數（audio-separator 預設 8）。viperx 在 4060 上 overlap 8 一首要約 4 分鐘、
    overlap 2 約 1.9 分鐘且人聲與 overlap 8 幾乎相同（差異 SNR 37.8 dB；overlap 1 降到 26 dB，不用）（009，tools/eval）。
    """
    from audio_separator.separator import Separator
    log(f"AI 分離人聲（模型：{model_name}）")
    tmp = workdir / "_sep"
    tmp.mkdir(exist_ok=True)
    sep = cache.get(model_name) if cache is not None else None
    if sep is None:
        extra = {"mdxc_params": {"segment_size": 256, "override_model_segment_size": False, "batch_size": 1,
                                 "overlap": overlap, "pitch_shift": 0}} if overlap else {}
        sep = Separator(output_dir=str(tmp), model_file_dir=str(model_dir), output_format="WAV", **extra)
        sep.load_model(model_filename=model_name)
        if cache is not None:
            cache[model_name] = sep
    else:  # 換到這次的暫存資料夾（audio-separator 自己也是這樣切換輸出位置）
        sep.output_dir = sep.model_instance.output_dir = str(tmp)
    stems = {}
    for f in sep.separate(str(audio)):
        p = Path(f)
        if not p.exists():
            p = tmp / p.name
        m = re.search(r"_\(([^)]+)\)_", p.name)  # 檔名格式：<音檔>_(<音軌>)_<模型>.wav
        if m:
            stems[m.group(1).lower()] = p
    if "vocals" in stems:
        shutil.move(str(stems.pop("vocals")), workdir / "vocals.wav")
    if "instrumental" in stems:
        shutil.move(str(stems.pop("instrumental")), workdir / "instrumental.wav")
    elif len(stems) == 1:  # 人聲＋其他兩軌的模型（例如 Kim MelBand RoFormer 輸出 vocals、other）
        shutil.move(str(stems.popitem()[1]), workdir / "instrumental.wav")
    elif stems:
        # Demucs 等多音軌模型只輸出鼓、貝斯、其他…，把人聲以外的音軌混成伴奏
        mix_stems(list(stems.values()), workdir / "instrumental.wav")
    shutil.rmtree(tmp, ignore_errors=True)
    if not (workdir / "vocals.wav").exists() or not (workdir / "instrumental.wav").exists():
        raise RuntimeError("分離人聲失敗：找不到 Vocals/Instrumental 輸出檔")
    if cache is None:  # 常駐程式要留著模型，不清顯示卡記憶體
        try:
            import torch
            torch.cuda.empty_cache()
        except Exception:
            pass


def encode_web(wav: Path, out: Path) -> None:
    """網頁播放器用的壓縮音軌（AAC，各瀏覽器包括 Safari 都能播；faststart 讓串流可以馬上開始）。"""
    run(["ffmpeg", "-y", "-v", "error", "-i", wav, "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out])


def encode_playback(inst: Path, vocals: Path, out: Path) -> None:
    """
    App 播放用的 4 聲道檔：聲道 0、1＝伴奏左右，2、3＝人聲左右（quad）。兩軌在同一個檔案裡，播放時永遠同步；
    App 即時調人聲那兩個聲道的音量。輸入 wav（新歌）或 m4a（舊歌補產）都可以。
    """
    run(["ffmpeg", "-y", "-v", "error", "-i", inst, "-i", vocals, "-filter_complex",
         "[0:a][1:a]join=inputs=2:channel_layout=quad:map=0.0-FL|0.1-FR|1.0-BL|1.1-BR[a]",
         "-map", "[a]", "-c:a", "aac", "-b:a", "384k", "-movflags", "+faststart", out])


WAVEFORM_POINTS = 240


def waveform(inst: Path, vocals: Path, n: int = WAVEFORM_POINTS) -> list:
    """
    整首的音量起伏（015，App 的波浪時間軸）：伴奏＋人聲混成單聲道 8 kHz，分 n 段算 RMS → dB；
    第 5～98 百分位之間（至少 6 dB）對到 0～1、再取 1.5 次方拉開高低（流行歌壓縮得很平，線性看不出副歌），
    最後對到 0.08～1（靜音段柱子不會貼底）。輸入 wav 或 m4a 都可以。
    """
    import subprocess
    import numpy as np

    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(inst), "-i", str(vocals), "-filter_complex",
         "[0:a][1:a]amix=inputs=2:normalize=0:duration=longest,aformat=sample_fmts=flt:channel_layouts=mono,aresample=8000",
         "-f", "f32le", "-"], capture_output=True, check=True).stdout
    x = np.frombuffer(raw, np.float32)
    if len(x) < n:
        return [0.08] * n
    db = np.array([20 * np.log10(np.sqrt(np.mean(s * s)) + 1e-9) for s in np.array_split(x, n)])
    lo, hi = np.percentile(db, 5), np.percentile(db, 98)
    hi = max(hi, lo + 6)
    v = 0.08 + 0.92 * np.clip((db - lo) / (hi - lo), 0, 1) ** 1.5
    return [round(float(a), 2) for a in v]


# 人聲殘留偏高的門檻（dB，vocal_leak_db 大於它＝偏高）：App 提示「原唱可能沒去乾淨」（005），P3 自動退點沿用同一個值。
# 依據（測試集分布＋已知音量混回原唱的試聽）見 docs/APP_PLAN.md〈決定紀錄〉；只改這裡、重建映像就生效，不必重新處理。
RESIDUE_HIGH_DB = -17.0


def residue_high(status: str, residue_db) -> bool:
    return status == "done" and residue_db is not None and residue_db > RESIDUE_HIGH_DB


def vocal_leak_db(inst: Path, vocals: Path) -> float:
    """
    伴奏裡和人聲相關的能量（dB，越低越乾淨）：每秒把伴奏投影到分離出的人聲上，只看有人聲的秒數。
    只量得到和分離結果相關的殘留，會低估實際殘留，適合當相對指標（自動退點、提示「可能沒去乾淨」）。
    """
    import wave
    import numpy as np

    def load(p):
        with wave.open(str(p)) as w:
            x = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32)
            return x.reshape(-1, w.getnchannels()).mean(1) / 32768, w.getframerate()

    a, sr = load(inst)
    v, _ = load(vocals)
    tot = res = 0.0
    for i in range(0, min(len(a), len(v)) - sr, sr):
        x, r = a[i:i + sr], v[i:i + sr]
        rr = float(r @ r)
        if rr < 1e-3:
            continue
        g = float(x @ r) / rr
        res += g * g * rr
        tot += float(x @ x)
    return 10 * float(np.log10(res / tot)) if res > 0 and tot > 0 else -99.0


def mix_stems(stems: list, out: Path) -> None:
    log(f"合成伴奏（{len(stems)} 個音軌）")
    inputs = [x for s in stems for x in ("-i", s)]
    run([
        "ffmpeg", "-y", "-v", "error", *inputs,
        "-filter_complex", f"amix=inputs={len(stems)}:normalize=0:duration=longest",
        out,
    ])


def make_guide(inst: Path, vocals: Path, out: Path, volume: float) -> None:
    """導唱音軌＝伴奏＋小聲原唱。"""
    log(f"製作導唱音軌（原唱音量 {volume:.0%}）")
    run([
        "ffmpeg", "-y", "-v", "error", "-i", inst, "-i", vocals,
        "-filter_complex", f"[1:a]volume={volume}[v];[0:a][v]amix=inputs=2:normalize=0:duration=first[a]",
        "-map", "[a]", out,
    ])


# 自動背景的配色（深色系，白字才看得清楚），依歌名固定挑一組
PALETTES = [
    ("0x1b1f3b", "0x4a2c7a", "0x0f4c5c", "0x2a1a3f"),  # 夜空紫綠
    ("0x2b1331", "0x7a2e4f", "0x3b1f5c", "0x1a1030"),  # 玫瑰
    ("0x0b2233", "0x14506b", "0x1f6f6b", "0x0a1a2a"),  # 深海
    ("0x2d1b12", "0x7a3f1f", "0x5c2a3b", "0x1a0f0a"),  # 夕陽
    ("0x10261c", "0x1f5c43", "0x2e4a6b", "0x0b1a14"),  # 森林
    ("0x1a1a2e", "0x3a3a6b", "0x6b3a5c", "0x101020"),  # 霧藍
]
GLOW = [(0.8, 0.7, 1.0), (1.0, 0.65, 0.8), (0.6, 0.9, 1.0), (1.0, 0.75, 0.55), (0.65, 1.0, 0.8), (0.8, 0.8, 1.0)]

# 幾顆緩慢漂移的光暈（在 480x270 上算，再放大，省運算）
_BOKEH = "+".join(
    f"{a}*exp(-(pow(X-{x}-{dx}*sin(T*{sx}+{p}),2)+pow(Y-{y}-{dy}*cos(T*{sy}+{p}),2))/{r})"
    for a, x, y, dx, dy, sx, sy, p, r in [
        (0.45, 120, 90, 90, 40, 0.11, 0.07, 0, 900), (0.35, 360, 170, 80, 50, 0.05, 0.09, 1, 1400),
        (0.3, 250, 60, 120, 30, 0.04, 0.13, 2, 600), (0.25, 420, 230, 30, 40, 0.08, 0.06, 3, 500),
        (0.3, 60, 220, 50, 30, 0.07, 0.05, 4, 700),
    ]
)
# 環狀頻譜：把 showfreqs 的長條圖（低頻在左）轉成極座標，低頻在上、左右對稱
RING = 400
RING_X, RING_Y = 1620, 500  # 環的中心
_RING_R0, _RING_LEN = 112, 80
_RING = (f"if(between(hypot(X-{RING // 2},Y-{RING // 2}),{_RING_R0},{_RING_R0 + _RING_LEN}),"
         f"p((0.03+(1-abs(atan2(X-{RING // 2},Y-{RING // 2}))/PI)*0.67)*(W-1),"  # 跳過直流那格，免得頂端有缺口
         f"H-1-(hypot(X-{RING // 2},Y-{RING // 2})-{_RING_R0})*H/{_RING_LEN}),0)")


def _visual_graph(background, duration: float, seed: int, bg_input: int) -> str:
    """背景＋環狀頻譜＋進度條的 filtergraph，輸出 [base]。音訊視覺化用輸入 2（audio.wav）。"""
    d = f"{duration:.2f}"
    if background == "mv":
        return (f"[{bg_input}:v]scale=1920:1080:force_original_aspect_ratio=decrease,"
                "pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black,setsar=1,fps=30[base]")
    if background == "auto":
        c = PALETTES[seed % len(PALETTES)]
        rr, gg, bb = GLOW[seed % len(GLOW)]
        bg = (f"gradients=s=480x270:r=30:d={d}:n=4:c0={c[0]}:c1={c[1]}:c2={c[2]}:c3={c[3]}:speed=0.004:seed={seed},format=gbrp[g];"
              f"nullsrc=s=480x270:r=30:d={d},format=gray,geq=lum='255*({_BOKEH})',format=gbrp,"
              f"colorchannelmixer=rr={rr}:gg={gg}:bb={bb}[b];"
              "[g][b]blend=all_mode=screen,scale=1920:1080:flags=bicubic,vignette=PI/4[bg];")
    else:  # 使用者的圖片：模糊、壓暗、慢慢平移
        bg = (f"[{bg_input}:v]scale=2112:1188:force_original_aspect_ratio=increase,crop=2112:1188,"
              "gblur=sigma=28,eq=brightness=-0.22:saturation=1.1,"
              "crop=1920:1080:x='96+96*sin(t*0.05)':y='54+54*cos(t*0.04)',fps=30,setsar=1[bg];")
    return bg + (
        f"[2:a]showfreqs=s={RING}x{RING}:mode=bar:ascale=cbrt:fscale=log:win_size=2048:colors=white,fps=30,"
        f"format=gray,geq=lum='{_RING}'[m];"
        f"color=c=white:s={RING}x{RING}:r=30:d={d},format=rgba[w];[w][m]alphamerge,colorchannelmixer=aa=0.55[ring];"
        f"color=c=white@0.15:s=1600x6:r=30:d={d}[track];color=c=white@0.8:s=1600x6:r=30:d={d}[bar];"
        f"[track][bar]overlay=x='-1600+1600*t/{d}':shortest=1[prog];"
        # 環狀頻譜放右側，左邊留給滾動歌詞欄
        f"[bg][ring]overlay={RING_X - RING // 2}:{RING_Y - RING // 2}:shortest=1[v1];[v1][prog]overlay=160:1030[base]"
    )


def render(workdir: Path, src: Path, out: Path, nvenc: bool = False, ass: str = "karaoke.ass",
           background: str = "auto", seed: int = 0, key: int = 0) -> None:
    """
    合成：畫面＋燒錄字幕，音軌 1＝伴唱、音軌 2＝導唱。
    background：auto＝自動產生的動態背景、mv＝原影片（沒有影像軌時改用 auto）、其他＝背景圖片路徑。
    key：升降幾個半音（只改音高、不改速度），伴唱和導唱一起變。
    """
    log("合成 KTV 影片")
    if background == "mv" and not (src is not None and has_real_video(src)):
        background = "auto"
    bg_in = []
    if background == "mv":
        bg_in = ["-i", src]
    elif background != "auto":
        bg_in = ["-loop", "1", "-framerate", "30", "-i", Path(background).resolve()]
    duration = media_duration(workdir / "instrumental.wav")
    graph = _visual_graph(background, duration, seed, 3) + f";[base]subtitles={ass},format=yuv420p[v]"
    audio_maps = ["-map", "0:a", "-map", "1:a"]
    if key:
        log(f"升降 Key：{key:+d} 個半音")
        shift = f"rubberband=pitch={2 ** (key / 12):.6f}:pitchq=quality"
        graph += f";[0:a]{shift}[a0];[1:a]{shift}[a1]"
        audio_maps = ["-map", "[a0]", "-map", "[a1]"]
    vcodec = ["-c:v", "h264_nvenc", "-preset", "p5", "-cq", "21"] if nvenc else \
             ["-c:v", "libx264", "-preset", "medium", "-crf", "20"]
    # cwd 設為 workdir，subtitles 濾鏡用相對路徑，避免中文／特殊字元路徑的跳脫問題
    run([
        "ffmpeg", "-y", "-v", "error", "-stats",
        "-i", "instrumental.wav", "-i", "guide.wav", "-i", "audio.wav", *bg_in,
        "-filter_complex", graph, "-map", "[v]", *audio_maps, "-t", f"{duration:.2f}",
        *vcodec, "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k",
        # mp4 不寫入 title，播放器顯示的音軌名稱來自 handler_name
        "-metadata:s:a:0", "title=伴唱", "-metadata:s:a:1", "title=導唱",
        "-metadata:s:a:0", "handler_name=伴唱", "-metadata:s:a:1", "handler_name=導唱",
        "-disposition:a:0", "default", "-disposition:a:1", "0",
        "-movflags", "+faststart", out.name,
    ], cwd=workdir)
