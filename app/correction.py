"""
歌詞校正（003）的純邏輯：給 App 的完整歌詞清單、送出內容的驗證、每句要怎麼重新對齊的分類。
不需要重量級套件，可以直接在本機 import 測試。
"""
import re

from common import Line
from messages import UserError

MAX_LINES = 500
MAX_TEXT = 200
REGRESSION = 0.5   # 句首可以比前面的句子早多少秒（同 align._drop_regressions）
_TAG_RE = re.compile(r"^\[[a-zA-Z]+:.*\]$")          # [ti:…]、[ar:…]
_TS_RE = re.compile(r"^((?:\[\d+:\d+(?:\.\d+)?\])+)(.*)$")


class Invalid(UserError, ValueError):
    """送出的校正內容不合格（代碼＋參數，014）；str() 是給使用者看的中文。"""


def full_lines(words: list, lrc_text: str) -> list:
    """
    完整歌詞（含這個版本沒唱的句子），順序同歌詞。words：words.json 的句子（只有唱的）；
    lyrics.lrc 裡有時間有文字的行依序對應 words，沒有時間的行是沒唱的句子。
    對應數量不一致（例如使用者手動改過檔案）時只用 words。
    """
    out, k = [], 0
    for raw in (lrc_text or "").splitlines():
        raw = raw.strip()
        if not raw or _TAG_RE.match(raw):
            continue
        m = _TS_RE.match(raw)
        if m is None:                       # 沒唱的句子
            out.append(Line(raw, -1, -1, [], False))
        elif m.group(2).strip():            # 有時間的句子；只有時間的行是句尾標記
            if k >= len(words):
                return list(words)
            out.append(words[k])
            k += 1
    return out if k == len(words) else list(words)


def edit_lines(lines: list) -> list:
    """API 的 edit_lines：不含逐字。"""
    return [{"src": i, "text": l.text, "start": round(l.start, 3), "end": round(l.end, 3),
             "confident": bool(l.words) and l.confident} for i, l in enumerate(lines)]


def validate(items, duration: float, n_src: int) -> list:
    """檢查 App 送出的校正（specs/backend/contracts/corrections.md），回傳整理過的清單；不合格丟 Invalid。"""
    if not isinstance(items, list) or not items:
        raise Invalid("lyrics_empty")
    if len(items) > MAX_LINES:
        raise Invalid("too_many_lines", max=MAX_LINES)
    out, seen, prev = [], set(), None
    for n, it in enumerate(items, 1):
        if not isinstance(it, dict):
            raise Invalid("bad_format")
        text = str(it.get("text") or "").strip()
        if not text:
            raise Invalid("line_empty", n=n)
        if len(text) > MAX_TEXT:
            raise Invalid("line_too_long", n=n, max=MAX_TEXT)
        try:
            start = float(it.get("start", -1))
        except (TypeError, ValueError):
            raise Invalid("line_time_invalid", n=n)
        pinned = bool(it.get("pinned"))
        exact = bool(it.get("exact"))
        if exact and not pinned:
            raise Invalid("line_time_invalid", n=n)
        if start < 0:
            if pinned:
                raise Invalid("line_time_invalid", n=n)
            start = -1.0
        elif start >= duration:
            raise Invalid("line_time_over", n=n)
        if start >= 0:
            # 自動對齊的結果本身就容許相鄰句些微倒退（align._drop_regressions 的 0.5 秒），沒動的句子要照樣能送出
            if prev is not None and start < prev - REGRESSION:
                raise Invalid("line_time_order", n=n)
            prev = start if prev is None else max(prev, start)
        src = it.get("src")
        if src is not None:
            if not isinstance(src, int) or isinstance(src, bool) or not 0 <= src < n_src or src in seen:
                raise Invalid("line_src_invalid", n=n)
            seen.add(src)
        out.append({"src": src, "text": text, "start": round(start, 3), "pinned": pinned,
                    "edited": bool(it.get("edited")), "moved": bool(it.get("moved")),
                    "exact": exact, "confirmed": bool(it.get("confirmed"))})
    return out


def classify(item: dict, orig) -> str:
    """
    每句怎麼重新對齊（research R1）：
    pin＝使用者在開唱時點的時間（有反應延遲）；exact＝使用者直接加減或輸入的時間（就是句首）；free＝App 因順序衝突重估了時間、或時間被改過；text＝改了字、原本有時間；
    unsung＝沒唱（沒設時間）；keep＝都沒動，原結果照搬。orig：原本的句子（Line），新句子是 None。
    """
    if item["pinned"]:
        return "exact" if item.get("exact") else "pin"
    if item["start"] < 0:
        return "unsung"
    if item["moved"] or orig is None or not orig.words:
        return "free"
    if abs(item["start"] - orig.start) >= 0.01:
        return "free"
    if item["edited"] or item["text"] != orig.text:
        return "text"
    return "keep"


# ---------- 換一份歌詞（004） ----------

MAX_LYRICS = 20000


def validate_lyrics(text) -> tuple:
    """檢查貼上的歌詞，回傳 (整理過的文字, 是否為 LRC)；判斷方式同首次處理（lyrics.load_lyrics_file）。不合格丟 Invalid。"""
    import lyrics
    text = str(text or "").strip()
    if len(text) > MAX_LYRICS:
        raise Invalid("lyrics_too_long", max=MAX_LYRICS)
    entries = lyrics.parse_lrc(text)
    if entries:
        ok = any(t and not lyrics.is_credit(t) for _, t in entries)
    else:
        ok = any(l.strip() and not lyrics.is_credit(l.strip()) for l in text.splitlines())
    if not ok:
        raise Invalid("lyrics_no_content")
    return text, bool(entries)
