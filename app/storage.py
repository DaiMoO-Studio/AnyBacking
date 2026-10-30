"""
網頁服務的檔案儲存。初期跑在同一台電腦，用本機資料夾；對外開放時換成 Cloudflare R2，只改這個檔案。

路徑一律用歌曲 id（UUID），不帶歌名，網址看不出是哪首歌。
"""
import os
import shutil
from pathlib import Path

ROOT = Path(os.environ.get("STORAGE_DIR", "/data/storage"))


def song_dir(song_id: str) -> Path:
    return ROOT / "songs" / song_id


def upload_path(song_id: str, suffix: str) -> Path:
    d = song_dir(song_id) / "upload"
    d.mkdir(parents=True, exist_ok=True)
    return d / f"source{suffix}"


def find_upload(song_id: str) -> Path:
    return next((song_dir(song_id) / "upload").glob("source*"), None)


def work_dir(song_id: str) -> Path:
    """工作機處理用的資料夾（中間檔，處理完會清掉）。"""
    return song_dir(song_id) / "work"


def output_dir(song_id: str) -> Path:
    """播放器要的檔案：instrumental.m4a、vocals.m4a、words.json、lyrics.lrc。"""
    return song_dir(song_id) / "out"


def publish(song_id: str, names: list) -> None:
    """處理完：把播放器要的檔案從工作資料夾搬到輸出資料夾，其他中間檔（wav 等）刪掉省空間。"""
    out, work = output_dir(song_id), work_dir(song_id)
    out.mkdir(parents=True, exist_ok=True)
    for n in names:
        shutil.move(str(work / n), out / n)
    shutil.rmtree(work, ignore_errors=True)


def delete(song_id: str) -> None:
    shutil.rmtree(song_dir(song_id), ignore_errors=True)
