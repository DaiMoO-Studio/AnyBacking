"""
App 服務的 API（FastAPI）。上傳、查進度、列出歌曲、取得播放器資料、刪除；還沒有帳號（P3）。

- 身分驗證：除了健康檢查與簽名音軌連結，每個請求都要帶裝置金鑰（`Authorization: Bearer`，見 auth.py）。
- 對外：區網直接連 8000，或放在自己的反向代理（HTTPS）後面，只轉送 /api/*（見 README）。
- 音軌不給直接下載的網址：播放器拿到的是幾分鐘就過期的簽名連結（/api/media/...?exp=&sig=）。
"""
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path

from fastapi import FastAPI, File, Form, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse

import auth
import correction
import db
import lyrics
import storage
from common import clean_title, load_lines, load_waveform
from media import MAX_URL_SECONDS, probe_url, residue_high   # 不能 import media：下面的 media() 端點會蓋掉模組名稱
from messages import UserError

SECRET = os.environ.get("MEDIA_SECRET", "").encode()
URL_IMPORT = os.environ.get("URL_IMPORT", "1") != "0"   # 018：自架的人可以關掉貼網址（yt-dlp）
MEDIA_TTL = 600          # 簽名連結有效秒數；播放器過期前重新拿
MAX_UPLOAD = 300 << 20   # 300 MB
MEDIA_FILES = {"instrumental.m4a", "vocals.m4a", "playback.m4a"}
PLAYBACK_CHANNELS = {"instrumental": [0, 1], "vocals": [2, 3]}   # playback.m4a 的聲道配置（quad）

app = FastAPI(title="隨唱機 AnyBacking")


class ApiFail(UserError):
    """給使用者看的 API 錯誤（014）：HTTP 狀態＋代碼＋參數；回應 {detail（中文）, code, params}。"""

    def __init__(self, status: int, code: str, **params):
        super().__init__(code, **params)
        self.status = status


def fail(status: int, code: str, **params) -> ApiFail:
    return ApiFail(status, code, **params)


@app.exception_handler(UserError)
async def _user_error(request: Request, e: UserError):
    return JSONResponse(e.body(), getattr(e, "status", 422))


_conn = None
_throttle = auth.Throttle()


def conn():
    global _conn
    if _conn is None or _conn.closed:
        _conn = db.connect()
        db.init(_conn)
    return _conn


@app.on_event("startup")
def _startup():
    if not SECRET:
        raise RuntimeError("請設定 MEDIA_SECRET（.env）")
    conn()


def _public(path: str) -> bool:
    """不需要金鑰：非 /api 路徑（Caddy 不轉送）、健康檢查、簽名連結（簽名本身就是限時授權）。"""
    return not path.startswith("/api/") or path == "/api/health" or path.startswith("/api/media/")


@app.middleware("http")
async def _require_key(request: Request, call_next):
    if _public(request.url.path):
        return await call_next(request)
    header = request.headers.get("authorization", "")
    key = header[7:].strip() if header[:7].lower() == "bearer " else ""
    device = auth.verify(conn(), key)
    if device is not None:   # 有效金鑰永遠放行（來源看起來都一樣，見 auth.py）
        request.state.device = device
        return await call_next(request)
    # Caddy 設的 X-Forwarded-For；本機直連就用連線位址
    src = request.headers.get("x-forwarded-for", "").split(",")[0].strip() or request.client.host
    left = _throttle.blocked(src)
    if left:
        return JSONResponse(UserError("rate_limited").body(), 429, {"Retry-After": str(left)})
    if _throttle.fail(src):
        print(f"[auth] blocked src={src} seconds={auth.BLOCK_SECONDS}", flush=True)
    else:
        print(f"[auth] invalid src={src} path={request.url.path}", flush=True)
    return JSONResponse(UserError("unauthorized").body(), 401, {"WWW-Authenticate": "Bearer"})


def _sign(song_id: str, name: str, exp: int) -> str:
    return hmac.new(SECRET, f"{song_id}/{name}/{exp}".encode(), hashlib.sha256).hexdigest()


def _media_url(song_id: str, name: str) -> str:
    exp = int(time.time()) + MEDIA_TTL
    return f"/api/media/{song_id}/{name}?exp={exp}&sig={_sign(song_id, name, exp)}"


def _summary(row: dict) -> dict:
    keys = ["id", "title", "status", "error", "created_at", "finished_at", "duration", "lang",
            "residue_db", "n_lines", "n_unsure", "n_unsung", "playback_ready", "corrected_at",
            "lyrics_source", "lyrics_url", "source_url", "sep_model", "error_code", "error_params"]
    out = {k: (str(row[k]) if k == "id" else row[k]) for k in keys}
    out["residue_high"] = residue_high(row["status"], row["residue_db"])   # 判斷只在伺服器（005）
    return out


def _get(song_id: str) -> dict:
    try:
        uuid.UUID(song_id)
    except ValueError:
        raise fail(404, "not_found")
    row = conn().execute("SELECT * FROM songs WHERE id = %s", (song_id,)).fetchone()
    if row is None:
        raise fail(404, "not_found")
    return row


@app.get("/api/health")
def health():
    conn().execute("SELECT 1")
    return {"ok": True}


@app.post("/api/songs")
async def upload(file: UploadFile = File(...), lyrics: str = Form(None), title: str = Form(None)):
    sid = str(uuid.uuid4())
    suffix = Path(file.filename or "").suffix.lower()[:8] or ".bin"
    dst = storage.upload_path(sid, suffix)
    size = 0
    try:
        with open(dst, "wb") as f:
            while chunk := await file.read(1 << 20):
                size += len(chunk)
                if size > MAX_UPLOAD:
                    raise fail(413, "file_too_large", max=300)
                f.write(chunk)
        name = clean_title(title) or clean_title(Path(file.filename or "").stem) or "未命名"
        # 資料庫最後才寫：前面任何一步失敗都不會留下卡在佇列裡的紀錄
        conn().execute("INSERT INTO songs (id, title, lyrics_text) VALUES (%s, %s, %s)",
                       (sid, name, (lyrics or "").strip() or None))
    except BaseException:
        storage.delete(sid)   # 不留下半個檔案
        raise
    return {"id": sid}


@app.post("/api/songs/url")
async def upload_url(request: Request):
    """網址上傳（009，個人使用）：只記下網址，工作機下載音訊後照常處理；沒填歌名先放網址，工作機換成影片標題。"""
    if not URL_IMPORT:
        raise fail(403, "url_import_off")
    body = await _json(request)
    url = (body.get("url") or "").strip() if isinstance(body, dict) else ""
    if not url.startswith(("http://", "https://")) or len(url) > 2000:
        raise fail(422, "url_invalid")
    lyrics_text = body.get("lyrics") if isinstance(body.get("lyrics"), str) else None
    title = clean_title(body.get("title")) if isinstance(body.get("title"), str) else ""
    sid = str(uuid.uuid4())
    conn().execute("INSERT INTO songs (id, title, lyrics_text, source_url) VALUES (%s, %s, %s, %s)",
                   (sid, title or url, (lyrics_text or "").strip() or None, url))
    return {"id": sid}


@app.get("/api/url-preview")
def url_preview(url: str = ""):
    """加歌面板貼上網址後先看是哪支影片（013，個人使用）：標題、頻道、長度；不下載。"""
    if not URL_IMPORT:
        raise fail(403, "url_import_off")
    url = url.strip()
    if not url.startswith(("http://", "https://")) or len(url) > 2000:
        raise fail(422, "url_invalid")
    try:
        meta = probe_url(url)
    except Exception:
        raise fail(422, "url_unreadable")
    duration = meta.get("duration") or 0
    return {"title": meta.get("title") or "", "channel": meta.get("channel") or "", "duration": duration,
            "too_long": duration > MAX_URL_SECONDS}


@app.get("/api/songs")
def list_songs():
    rows = conn().execute("SELECT * FROM songs ORDER BY created_at DESC").fetchall()
    return [_summary(r) for r in rows]


@app.get("/api/songs/{song_id}")
def get_song(song_id: str):
    row = _get(song_id)
    out = _summary(row)
    if row["status"] == "queued":
        out["queue_position"] = conn().execute(
            "SELECT count(*) AS n FROM songs WHERE status IN ('queued', 'processing') AND created_at < %s",
            (row["created_at"],)).fetchone()["n"] + 1
    if row["status"] == "done":
        d = storage.output_dir(song_id)
        out["lines"] = json.loads((d / "words.json").read_text(encoding="utf-8"))
        out["waveform"] = load_waveform(d / "waveform.json")   # 015：240 點音量起伏，沒有就是 null
        out["edit_lines"] = correction.edit_lines(_full_lines(d))
        out["correction"] = db.latest_correction(conn(), song_id)
        names = MEDIA_FILES if row["playback_ready"] else MEDIA_FILES - {"playback.m4a"}
        out["media"] = {n.split(".")[0]: _media_url(song_id, n) for n in sorted(names)}
        if row["playback_ready"]:
            out["playback_channels"] = PLAYBACK_CHANNELS
        out["media_expires_in"] = MEDIA_TTL
    return out


def _full_lines(d: Path) -> list:
    lrc = d / "lyrics.lrc"
    return correction.full_lines(load_lines(d / "words.json"), lrc.read_text(encoding="utf-8") if lrc.exists() else "")


@app.post("/api/songs/{song_id}/corrections", status_code=202)
async def submit_correction(song_id: str, request: Request):
    """歌詞校正（003）：送出完整歌詞（含使用者設的時間、改的字），工作機重新對齊。"""
    row = _get(song_id)
    if row["status"] != "done":
        raise fail(409, "song_not_ready_fix")
    try:
        body = await request.json()
    except ValueError:
        raise fail(422, "bad_format")
    n_src = len(_full_lines(storage.output_dir(song_id)))
    try:
        lines = correction.validate(body.get("lines") if isinstance(body, dict) else None, row["duration"] or 0, n_src)
    except correction.Invalid as e:
        raise fail(422, e.code, **e.params)
    return {"id": db.submit_correction(conn(), song_id, lines), "status": "queued"}


@app.post("/api/songs/{song_id}/lyrics", status_code=202)
async def replace_lyrics(song_id: str, request: Request):
    """換一份歌詞（004）：貼純文字或 LRC，工作機用保存的人聲軌重新對齊、整份取代（011 起不限次數）。"""
    row = _get(song_id)
    if row["status"] != "done":
        raise fail(409, "song_not_ready_lyrics")
    try:
        body = await request.json()
    except ValueError:
        raise fail(422, "bad_format")
    try:
        text, timed = correction.validate_lyrics(body.get("lyrics") if isinstance(body, dict) else None)
    except correction.Invalid as e:
        raise fail(422, e.code, **e.params)
    return {"id": db.submit_lyrics(conn(), song_id, text, timed), "status": "queued", "timed": timed}


@app.get("/api/songs/{song_id}/lyrics/search")
def search_lyrics(song_id: str, q: str = None):
    """
    換一份歌詞時「自動找歌詞」（012，個人使用）：用歌名（q，沒給就用這首的歌名）與這首的長度查 LRCLIB 同步歌詞，
    規則同首次處理（只收歌名相符、時長差 ≤15 秒），回傳正體 LRC 給 App 填進文字框，使用者看過才送出。
    017：沒有同歌手的同步歌詞時，LRCLIB 其他結果＋網路上找到的歌詞和這首目前的歌詞比對，挑最像的。
    """
    row = _get(song_id)
    name = clean_title(q) if q else row["title"]
    if not name:
        raise fail(422, "title_required")
    artist = name.split(" - ", 1)[0] if " - " in name else None   # 「歌手 - 歌名」：先找同歌手的（016）
    queries = lyrics.build_queries({"title": name})
    found, cands = lyrics.lrclib_lyrics(queries, row["duration"] or 0, must_match=name, artist=artist)
    if not found:
        # 017：其他候選（含網路上找到的）和這首目前唱出來的歌詞比對（AI 辨識的或之前的版本；api 不跑 Whisper）
        words = storage.output_dir(song_id) / "words.json"
        sung = "\n".join(l.text for l in load_lines(words)) if words.exists() else ""
        found = lyrics.pick(cands + lyrics.web_candidates(queries), lyrics.heard_tokens(sung), lyrics.MIN_F1)
    if not found:
        raise fail(404, "lyrics_not_found")
    return {"lrc": found["text"], "source": found["source"], "url": found.get("url")}


@app.patch("/api/songs/{song_id}")
async def rename_song(song_id: str, request: Request):
    """改歌名（006）：任何狀態都可以改；名字照上傳的規則整理成一行、最多 200 字。"""
    _get(song_id)
    try:
        body = await request.json()
    except ValueError:
        raise fail(422, "bad_format")
    raw = body.get("title") if isinstance(body, dict) else None
    title = clean_title(raw) if isinstance(raw, str) else ""
    if not title:
        raise fail(422, "title_empty")
    conn().execute("UPDATE songs SET title = %s WHERE id = %s", (title, song_id))
    return {"id": song_id, "title": title}


async def _json(request: Request):
    try:
        return await request.json()
    except ValueError:
        raise fail(422, "bad_format")


@app.get("/api/media/{song_id}/{name}")
def media(song_id: str, name: str, exp: int, sig: str):
    if name not in MEDIA_FILES or exp < time.time() or not hmac.compare_digest(sig, _sign(song_id, name, exp)):
        raise fail(403, "link_expired")
    path = storage.output_dir(song_id) / name
    if not path.is_file():
        raise fail(404, "file_not_found")
    # 不讓瀏覽器或中間的快取長期保存
    return FileResponse(path, media_type="audio/mp4", headers={"Cache-Control": "private, no-store"})


@app.delete("/api/songs/{song_id}")
def delete_song(song_id: str):
    _get(song_id)
    conn().execute("DELETE FROM songs WHERE id = %s", (song_id,))
    storage.delete(song_id)
    return {"ok": True}
