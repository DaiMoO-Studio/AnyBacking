"""
GPU 工作機：常駐模型，從資料庫佇列拿工作，一次處理一首。
主動連資料庫拿工作，不必對外開連接埠；docker compose 設 restart: unless-stopped，電腦重開機後自動接續。
"""
import os
import socket
import time
import traceback

import db
import lyrics
import media
import storage
from common import clean_title, log, save_waveform
from messages import UserError
from pipeline import Engine, PipelineError

WORKER = os.environ.get("WORKER_NAME") or socket.gethostname()
OUTPUTS = ["instrumental.m4a", "vocals.m4a", "playback.m4a", "waveform.json", "words.json", "lyrics.lrc"]


def backfill_waveform(conn) -> None:
    """015 之前處理好的歌補算波浪（out/ 的兩軌，只用 CPU）。失敗只記錄，下次啟動再試。"""
    rows = conn.execute("SELECT id, title FROM songs WHERE status = 'done' ORDER BY created_at").fetchall()
    for r in rows:
        out = storage.output_dir(str(r["id"]))
        if (out / "waveform.json").exists() or not (out / "vocals.m4a").exists():
            continue
        try:
            save_waveform(media.waveform(out / "instrumental.m4a", out / "vocals.m4a"), out / "waveform.json")
            log(f"補算波浪 {r['id']}（{r['title']}）")
        except Exception as e:
            log(f"補算波浪失敗 {r['id']}：{e}")


def backfill_playback(conn) -> None:
    """001 之前處理好的歌補產 playback.m4a：從 out/ 的兩軌合成，不重新去人聲。失敗的標示原因、繼續下一首。"""
    rows = conn.execute("SELECT id, title FROM songs WHERE status = 'done' AND NOT playback_ready "
                        "ORDER BY created_at").fetchall()
    for r in rows:
        sid = str(r["id"])
        out = storage.output_dir(sid)
        tmp = out / "playback.tmp.m4a"
        try:
            media.encode_playback(out / "instrumental.m4a", out / "vocals.m4a", tmp)
            tmp.replace(out / "playback.m4a")
            conn.execute("UPDATE songs SET playback_ready = true, error = NULL WHERE id = %s", (sid,))
            log(f"補產播放檔 {sid}（{r['title']}）")
        except Exception as e:
            tmp.unlink(missing_ok=True)
            log(f"補產播放檔失敗 {sid}：{e}")
            err = UserError("playback_backfill_failed")
            conn.execute("UPDATE songs SET error = %s, error_code = %s WHERE id = %s", (str(err), err.code, sid))


MAX_URL_SECONDS = media.MAX_URL_SECONDS


def fetch_url(conn, job) -> dict:
    """
    網址上傳（009，個人使用）：取得影片資訊（找歌詞用、沒填歌名就改成影片標題）並只下載音訊到 upload/。
    已經下載過（工作機重開後重做）就不再下載。回傳 meta。
    """
    sid, url = str(job["id"]), job["source_url"]
    try:
        meta = media.probe_url(url)
    except Exception as e:
        log(f"讀取影片資訊失敗 {sid}：{e}")
        raise PipelineError("download_failed")
    if (meta.get("duration") or 0) > MAX_URL_SECONDS:
        raise PipelineError("video_too_long", minutes=MAX_URL_SECONDS // 60)
    if job["title"] == url:   # 上傳時沒填歌名
        title = clean_title(lyrics.video_song_title(meta) or meta.get("title") or "") or "未命名"   # 016：先用「歌手 - 歌名」
        conn.execute("UPDATE songs SET title = %s WHERE id = %s", (title, sid))
    if storage.find_upload(sid) is None:
        try:
            media.download_audio(url, storage.upload_path(sid, "").parent)
        except Exception as e:
            log(f"下載失敗 {sid}：{e}")
            raise PipelineError("download_failed")
    return meta


def run_correction(conn, engine, fix) -> None:
    """
    歌詞校正（003）或換一份歌詞（004，kind = 'lyrics'）：重新對齊一首，不重新去人聲。
    失敗只記在這筆上，歌曲本身照常可以播放。
    """
    sid, cid = str(fix["song_id"]), fix["id"]
    lyrics_job = fix.get("kind") == "lyrics"
    log(f"開始{'換歌詞' if lyrics_job else '校正'} {sid}（#{cid}）")
    try:
        song = conn.execute("SELECT lang, duration FROM songs WHERE id = %s", (sid,)).fetchone()
        if song is None:
            raise PipelineError("not_found")
        if lyrics_job:
            result = engine.relyric(storage.output_dir(sid), storage.work_dir(sid), fix["lyrics"], song["duration"])
        else:
            result = engine.realign(storage.output_dir(sid), fix["lines"], song["lang"])
        db.finish_correction(conn, cid, sid, result)
        if lyrics_job:   # 換成使用者自己貼的歌詞（009：歌詞來源）
            conn.execute("UPDATE songs SET lyrics_source = 'user', lyrics_url = NULL WHERE id = %s", (sid,))
        log(f"校正完成 {sid}（#{cid}）：{result}")
    except PipelineError as e:
        log(f"校正失敗 {sid}（#{cid}）：{e}")
        db.fail_correction(conn, cid, e)
    except Exception:
        traceback.print_exc()
        db.fail_correction(conn, cid, UserError("realign_error"))
    finally:
        engine.release_gpu()


def main():
    conn = db.connect()
    db.init(conn)
    n = db.requeue_stale(conn, WORKER)
    if n:
        log(f"把上次沒做完的 {n} 首放回佇列")
    backfill_playback(conn)
    backfill_waveform(conn)
    engine = Engine(os.environ.get("MODEL_DIR", "/data/models"))
    log("載入模型…")
    engine.warm_up()
    log(f"工作機 {WORKER} 就緒，等待工作")
    while True:
        try:
            fix = db.claim_correction(conn, WORKER)
            job = None if fix else db.claim(conn, WORKER)
        except Exception as e:  # 資料庫重開或連線中斷：等一下再重連
            log(f"資料庫連線問題：{e}，5 秒後重連")
            time.sleep(5)
            try:
                conn = db.connect()
            except Exception:
                pass
            continue
        if fix is not None:
            run_correction(conn, engine, fix)
            continue
        if job is None:
            time.sleep(2)
            continue
        sid = str(job["id"])
        log(f"開始處理 {sid}（{job['title']}）")
        t0 = time.time()
        try:
            meta = {"title": job["title"]}
            if job["source_url"]:
                meta = fetch_url(conn, job)
            src = storage.find_upload(sid)
            if src is None:
                raise PipelineError("upload_missing")
            result = engine.process(src, storage.work_dir(sid), job["lyrics_text"], meta)
            storage.publish(sid, OUTPUTS)
            # 網址上傳沒填歌名：用「歌手 - 歌名」，比影片標題（常帶「Official MV」等字）乾淨（012；016 規則見 lyrics.video_song_title）
            if job["source_url"] and job["title"] == job["source_url"]:
                title = clean_title(lyrics.video_song_title(meta, engine.found))
                if title:
                    result["title"] = title
            db.finish(conn, sid, result)
            log(f"完成 {sid}，{time.time() - t0:.0f} 秒：{result}")
        except PipelineError as e:
            log(f"失敗 {sid}：{e}")
            db.fail(conn, sid, e)
        except Exception:
            traceback.print_exc()
            db.fail(conn, sid, UserError("process_error"))
        finally:
            engine.release_gpu()  # 顯示卡和其他專案共用，閒置時不佔用


if __name__ == "__main__":
    main()
