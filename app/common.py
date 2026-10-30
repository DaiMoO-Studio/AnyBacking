"""共用資料結構與工具函式。"""
import json
import re
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path

# 日文假名、中日韓漢字、韓文：這些文字一個字就是一個卡拉 OK 單位
CJK = "぀-ヿ㐀-䶿一-鿿豈-﫿가-힯"
_UNIT_RE = re.compile(rf"[{CJK}]\s*|[^\s{CJK}]+\s*")


@dataclass
class Word:
    text: str
    start: float
    end: float


@dataclass
class Line:
    text: str
    start: float
    end: float
    words: list = field(default_factory=list)
    confident: bool = True  # False：時間是推估的（退回平均分配或依人聲位置推測），介面上要提示使用者確認


def log(msg: str) -> None:
    print(f"[anybacking] {msg}", flush=True)


def run(cmd, cwd=None) -> None:
    cmd = [str(c) for c in cmd]
    log("$ " + " ".join(cmd))
    subprocess.run(cmd, cwd=cwd, check=True)


def ffprobe_streams(path: Path) -> dict:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)],
        capture_output=True, check=True, text=True,
    ).stdout
    return json.loads(out)


def media_duration(path: Path) -> float:
    return float(ffprobe_streams(path)["format"]["duration"])


def has_real_video(path: Path) -> bool:
    """有影像軌且不是 mp3 內嵌的封面圖。"""
    for s in ffprobe_streams(path).get("streams", []):
        if s.get("codec_type") == "video" and not s.get("disposition", {}).get("attached_pic"):
            return True
    return False


def units(text: str) -> list:
    """把一句歌詞切成卡拉 OK 變色單位：中日韓逐字、拉丁文逐詞。"""
    return _UNIT_RE.findall(text)


def nonspace_len(s: str) -> int:
    return len(re.sub(r"\s", "", s))


def even_words(line: Line) -> list:
    """沒有逐字時間時，依字數把整句時間平均分給每個字。"""
    us = units(line.text.strip())
    if not us:
        return []
    weights = [max(1, nonspace_len(u)) for u in us]
    total = sum(weights)
    step = (line.end - line.start) / total
    words, t = [], line.start
    for u, w in zip(us, weights):
        words.append(Word(u, t, t + step * w))
        t += step * w
    return words


def detect_lang(text: str) -> str:
    if re.search(r"[぀-ヿ]", text):
        return "ja"
    if re.search(r"[가-힯]", text):
        return "ko"
    if re.search(r"[一-鿿]", text):
        return "zh"
    return "en"


def safe_name(s: str, limit: int = 80) -> str:
    s = re.sub(r'[\\/:*?"<>|\x00-\x1f]+', "_", s).strip(" .")
    return s[:limit] or "untitled"


def clean_title(s: str, limit: int = 200) -> str:
    """歌名一律一行（006）：換行、tab 轉空格，連續空白合一，去頭尾，最多 limit 字。上傳與改名共用。"""
    return re.sub(r"\s+", " ", s or "").strip()[:limit].strip()


def save_lines(lines: list, path: Path) -> None:
    path.write_text(json.dumps([asdict(l) for l in lines], ensure_ascii=False, indent=1), encoding="utf-8")


def save_waveform(values: list, path: Path) -> None:
    """波浪資料（015）：先寫暫存檔再改名，API 不會讀到寫一半的檔案。"""
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(values), encoding="utf-8")
    tmp.replace(path)


def load_waveform(path: Path):
    """沒有或壞掉時回傳 None（App 顯示一樣高的柱子）。"""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def load_lines(path: Path) -> list:
    data = json.loads(path.read_text(encoding="utf-8"))
    return [Line(d["text"], d["start"], d["end"], [Word(**w) for w in d["words"]], d.get("confident", True))
            for d in data]


def newer(a: Path, b: Path) -> bool:
    """a 存在且比 b 新（或 b 不存在）。"""
    return a.exists() and (not b.exists() or a.stat().st_mtime > b.stat().st_mtime)
