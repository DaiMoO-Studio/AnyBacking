"""歌詞：LRC 解析／輸出、LRCLIB 搜尋、繁簡轉換。"""
import json
import re
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from common import Line, log, units

_TS_RE = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")
_WORD_TS_RE = re.compile(r"<\d+:\d+(?:\.\d+)?>")
_CREDIT_RE = re.compile(
    r"^\s*(作词|作詞|作曲|编曲|編曲|制作人|製作人|制作|製作|监制|監製|词|詞|曲|演唱|原唱|混音|和声|和聲|"
    r"发行|發行|出品|出品人|录音|錄音|母带|母帶|吉他|贝斯|貝斯|鼓|键盘|鍵盤|弦乐|弦樂|配唱|企划|企劃|统筹|統籌|版权|版權|"
    r"OP|SP|ISRC|lyrics?(\s+by)?|composer|composed by|arranger|arranged by|producer|produced by)\s*[:：]",
    re.I,
)
_NOISE_RE = re.compile(
    r"official\s*(music\s*)?(video|mv|audio|lyric video)?|music\s*video|lyrics?\s*video|"
    r"\bmv\b|\bm/v\b|\bhd\b|\b4k\b|\baudio\b|官方|完整版|高畫質|高画质|歌詞版|动态歌词|動態歌詞|中字|字幕版",
    re.I,
)
UA = "anybacking/0.1 (personal karaoke tool)"


# ---------- LRC ----------

def parse_lrc(text: str) -> list:
    """回傳 [(秒數, 歌詞)]，已排序；同一行有多個時間標記時會展開。"""
    entries = []
    for raw in text.splitlines():
        stamps = _TS_RE.findall(raw)
        if not stamps:
            continue
        content = _WORD_TS_RE.sub("", _TS_RE.sub("", raw)).strip()
        for m, s in stamps:
            entries.append((int(m) * 60 + float(s), content))
    entries.sort(key=lambda e: e[0])
    return entries


def is_credit(text: str) -> bool:
    return bool(_CREDIT_RE.match(text))


def lrc_to_lines(entries: list, duration: float) -> list:
    lines = []
    for i, (t, txt) in enumerate(entries):
        if not txt or is_credit(txt):
            continue
        nxt = entries[i + 1][0] if i + 1 < len(entries) else duration
        # 下一句之前若有長間奏，不要讓這句一直唱到間奏結束
        est = max(3.0, len(units(txt)) * 0.7 + 1.5)
        end = min(nxt - 0.05, t + est)
        lines.append(Line(txt, t, max(end, t + 0.5)))
    return lines


def load_lyrics_file(path: Path, duration: float):
    """讀取 .lrc（有時間）或純文字歌詞（無時間）。回傳 (lines, timed)。"""
    text = path.read_text(encoding="utf-8-sig")
    entries = parse_lrc(text)
    if entries:
        return lrc_to_lines(entries, duration), True
    plain = [l.strip() for l in text.splitlines() if l.strip() and not is_credit(l.strip())]
    return [Line(t, -1, -1) for t in plain], False


def _ts(t: float) -> str:
    t = max(0.0, t)
    return f"[{int(t // 60):02d}:{t % 60:05.2f}]"


def write_lrc(lines: list, path: Path, meta: dict) -> None:
    out = []
    if meta.get("title"):
        out.append(f"[ti:{meta['title']}]")
    if meta.get("artist"):
        out.append(f"[ar:{meta['artist']}]")
    timed = [l for l in lines if l.start >= 0]
    for l in lines:
        if l.start < 0:  # 對不上（這個版本沒唱）的句子不加時間，使用者要的話可以自己補上
            out.append(l.text)
            continue
        i = timed.index(l)
        # 跟下一句之間有空檔時補一個空行標記，代表這句結束
        if i + 1 >= len(timed) or timed[i + 1].start - l.end > 1.0:
            out.append(f"{_ts(l.start)}{l.text}\n{_ts(l.end)}")
        else:
            out.append(f"{_ts(l.start)}{l.text}")
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


# ---------- 繁簡 ----------

_cc = None


def to_traditional(text: str) -> str:
    """簡轉繁（台灣字形）。用 s2tw 而非 s2twp，避免詞彙替換改變字數。"""
    global _cc
    if _cc is None:
        from opencc import OpenCC
        _cc = OpenCC("s2tw")
    return _cc.convert(text)


_cc_s = None


def to_simplified(text: str) -> str:
    """繁轉簡：只用在查詢（LRCLIB 不做簡繁互通，正體歌名查不到簡體登錄的歌，016）。"""
    global _cc_s
    if _cc_s is None:
        from opencc import OpenCC
        _cc_s = OpenCC("t2s")
    return _cc_s.convert(text)


# ---------- LRCLIB ----------

def clean_title(s: str) -> str:
    s = re.sub(r"[\(\[（【「《].*?[\)\]）】」》]", " ", s)
    s = _NOISE_RE.sub(" ", s)
    s = s.replace("|", " ").replace("｜", " ")
    return re.sub(r"\s+", " ", s).strip(" -")


def _strip_latin(s: str) -> str:
    """有中日韓文字時拿掉英文字（中英並列的歌名、歌手名）；沒有中日韓文字就原樣。"""
    if not re.search(r"[぀-ヿ㐀-鿿가-힯]", s):
        return s
    return re.sub(r"\s+", " ", re.sub(r"[A-Za-z][A-Za-z'&.]*", " ", s)).strip(" -:：")


def build_queries(meta: dict, override: str = None) -> list:
    if override:
        return [{"q": q} for q in dict.fromkeys((override, to_simplified(override)))]
    qs = []
    if meta.get("track") and meta.get("artist"):
        qs.append({"track_name": meta["track"], "artist_name": meta["artist"]})
    title = meta.get("title", "")
    channel = re.sub(r"\s*-\s*Topic$", "", meta.get("channel") or "")
    cleaned = clean_title(title)
    if " - " in cleaned:
        a, t = cleaned.split(" - ", 1)
        qs.append({"track_name": t.strip(), "artist_name": a.strip()})
        qs.append({"track_name": a.strip(), "artist_name": t.strip()})
    for inner in re.findall(r"[【「《](.+?)[】」》]", title):
        inner = clean_title(inner)
        if inner:
            qs.append({"track_name": inner, "artist_name": channel} if channel else {"q": inner})
            qs.append({"q": inner})
    if cleaned:
        qs.append({"q": cleaned})
    # 中英並列的標題（例如「周杰倫 Jay Chou」「告白氣球 Love Confession」）LRCLIB 常查不到：補一份只留中日韓文字的查詢（009）
    for q in list(qs):
        cjk = {k: _strip_latin(v) for k, v in q.items()}
        if cjk != q and all(cjk.values()):
            qs.append(cjk)
    # LRCLIB 不做簡繁互通（「最後我們沒在一起」查不到、「最后我们没在一起」查得到）：每個查詢後面補一份簡體（016）
    qs = [v for q in qs for v in (q, {k: to_simplified(x) for k, x in q.items()})]
    seen, uniq = set(), []
    for q in qs:
        key = json.dumps(q, sort_keys=True, ensure_ascii=False)
        if key not in seen and all(v for v in q.values()):
            seen.add(key)
            uniq.append(q)
    return uniq


def _norm(s: str) -> str:
    """比對歌名用：轉正體、小寫、只留文字與數字。"""
    return re.sub(r"[\W_]+", "", to_traditional(s or "").lower())


def title_matches(track_name: str, hay: str) -> bool:
    """
    LRCLIB 結果的歌名（或括號裡的別名，例如「Through the Night (밤편지)」）有沒有出現在我們的歌名／影片標題裡。
    只用歌手名查詢時會拿到同歌手的別首歌（009 實測：影片標題查成《伊斯坦堡》），要用這個擋掉。
    """
    h = _norm(hay)
    parts = [track_name] + re.findall(r"[\(\[（【「《](.+?)[\)\]）】」》]", track_name or "")
    parts.append(re.sub(r"[\(\[（【「《].*?[\)\]）】」》]", " ", track_name or ""))
    return any(len(n) >= 2 and n in h for n in map(_norm, parts))


_COVER_RE = re.compile(r"翻自|翻唱|cover|伴奏|instrumental|karaoke|remix|\bdj\b|試聽|试听|片段", re.I)
_CJK_RE = re.compile(r"[぀-ヿ㐀-鿿가-힯]")


class _Fetch:
    """GET JSON：同一次找歌詞內相同網址只抓一次；失敗（LRCLIB 常回 503）隔 1、3 秒各重試一次。"""

    def __init__(self):
        self.cache = {}

    def __call__(self, url: str, headers: dict):
        if url not in self.cache:
            for i in range(3):
                try:
                    with urlopen(Request(url, headers=headers), timeout=20) as resp:
                        self.cache[url] = json.load(resp)
                    break
                except Exception as e:
                    if i == 2:
                        raise
                    log(f"  查詢失敗，重試：{e}")
                    time.sleep(1 + 2 * i)
        return self.cache[url]


def same_artist(a: str, b: str) -> bool:
    """歌手名相符（正體、忽略符號；其中一個包含另一個，合唱也算）。"""
    a, b = _norm(a), _norm(b)
    return len(a) >= 2 and len(b) >= 2 and (a in b or b in a)


def _acceptable(name: str, singer: str, dur: float, want: dict) -> bool:
    if want["must_match"] is not None and not title_matches(name, want["must_match"]):
        return False
    if _COVER_RE.search(name or "") and not _COVER_RE.search(want["must_match"] or ""):   # 翻唱、伴奏、DJ 版
        return False
    if want["artist"] and not same_artist(singer, want["artist"]):
        return False
    return not want["duration"] or abs((dur or 0) - want["duration"]) <= want["max_diff"]


def _found(source: str, artist: str, track: str, text: str, diff: float) -> dict:
    log(f"  找到（{source}）：{artist} - {track}（時長差 {diff:.1f}s，{'有' if parse_lrc(text) else '沒有'}時間）")
    return {"source": source, "artist": artist or "", "track": track or "", "text": text}


def _lrclib(params: dict, want: dict, fetch: _Fetch):
    """一個查詢條件 → (同步歌詞, 純文字歌詞)，各取時長最接近的一筆，沒有就 None。"""
    log(f"搜尋歌詞 LRCLIB：{params}")
    out = {}
    for it in fetch("https://lrclib.net/api/search?" + urlencode(params), {"User-Agent": UA}):
        if it.get("instrumental") or not _acceptable(it.get("trackName"), it.get("artistName"), it.get("duration"), want):
            continue
        diff = abs((it.get("duration") or 0) - want["duration"]) if want["duration"] else 0
        for kind, key in (("synced", "syncedLyrics"), ("plain", "plainLyrics")):
            if (it.get(key) or "").strip() and (kind not in out or diff < out[kind][0]):
                out[kind] = (diff, it, it[key])
    return tuple(_found("lrclib", o[1].get("artistName"), o[1].get("trackName"), o[2], o[0]) if o else None
                 for o in (out.get("synced"), out.get("plain")))


def _tidy(found: dict) -> dict:
    """轉正體；拿掉「歌手 - 歌名」「歌名：《…》」這種標題行與前後引號（製作名單交給 is_credit）。"""
    a, t = _norm(found["artist"]), _norm(found["track"])
    out = []
    for raw in to_traditional(found["text"]).splitlines():
        n = _norm(_WORD_TS_RE.sub("", _TS_RE.sub("", raw)))
        if t and t in n and ((len(a) >= 2 and a in n) or re.search(r"歌名|曲名", n)):
            continue
        out.append(raw.strip().strip('"“”'))
    return {**found, "text": "\n".join(out).strip()}


def find_lyrics(queries: list, duration: float, max_diff: float = 15.0, must_match: str = None, artist: str = None):
    """
    找歌詞（016）：LRCLIB 依查詢條件的順序，有同步歌詞就用，全部都沒有才用第一份純文字歌詞（台語歌常只有純文字，回來再跑時間對齊）。
    不用中國的歌詞來源（開發者決定，2026-10-29）。
    只收歌名相符（must_match，見 title_matches）、時長差 ≤ max_diff 的結果；不收翻唱、伴奏版。
    artist（影片資訊的歌手）：先只收同歌手的結果（只用歌名查會拿到同名別首，例如《我相信》吳莫愁版）；
    同歌手的純文字歌詞優先於別的歌手的同步歌詞。歌手名對不上（例如「Ricky Hsiao」與「蕭煌奇」）再不分歌手找一次。
    回傳 {"source": "lrclib", "artist", "track", "text"（正體，LRC 或純文字）}，找不到 None。網路錯誤不中斷流程。
    """
    fetch = _Fetch()

    def run(singer):
        want = {"duration": duration, "max_diff": max_diff, "must_match": must_match, "artist": singer}
        plain = None
        for params in queries:
            try:
                synced, p = _lrclib(params, want, fetch)
            except Exception as e:
                log(f"  查詢失敗：{e}")
                continue
            if synced:
                return synced
            plain = plain or p
        return plain

    singer = _drop_romanized(artist or "")
    found = (run(singer) if singer else None) or run(None)
    return _tidy(found) if found else None


def lrclib_lyrics(queries: list, duration: float, max_diff: float = 15.0, must_match: str = None, artist: str = None):
    """
    App 處理流程用（017）：回傳 (sure, cands)。
    sure：同歌手的 LRCLIB 同步歌詞（可以直接用）；cands：其他都當候選（不分歌手的同步歌詞、純文字歌詞），交給 pick 和實際唱的內容比對——
    只看歌名時同步歌詞也可能是同名別人的歌（《我相信》吳莫愁版）。都已轉正體、清掉標題行。
    """
    fetch = _Fetch()
    singer = _drop_romanized(artist or "")
    cands, seen = [], set()

    def add(found):
        if found:
            found = _tidy(found)
            key = _norm(_TS_RE.sub("", found["text"]))[:300]
            if key and key not in seen:
                seen.add(key)
                cands.append(found)

    for who in ([singer] if singer else []) + [None]:
        want = {"duration": duration, "max_diff": max_diff, "must_match": must_match, "artist": who}
        for params in queries:
            try:
                synced, plain = _lrclib(params, want, fetch)
            except Exception as e:
                log(f"  查詢失敗：{e}")
                continue
            if synced and who:
                return _tidy(synced), []
            add(synced)
            add(plain)
    return None, cands


def web_queries(queries: list) -> list:
    """build_queries 的結果 → 網路搜尋字串「歌手 歌名 歌詞」（正體、去重，最多 3 個）。"""
    out, seen = [], set()
    for q in queries:
        s = q.get("q") or f"{q.get('artist_name', '')} {q.get('track_name', '')}"
        s = re.sub(r"\s+-\s+|\s+", " ", s).strip()
        key = frozenset(_norm(w) for w in s.split())
        if s and to_traditional(s) == s and key not in seen:   # 簡體查詢是給 LRCLIB 的
            seen.add(key)
            out.append(s + " 歌詞")
    return out[:3]


def web_candidates(queries: list) -> list:
    """網路搜尋到的歌詞段落（017）→ 候選（正體、清掉製作名單）。"""
    import weblyrics
    out = []
    for c in weblyrics.candidates(web_queries(queries)):
        lines = [l for l in to_traditional(c["text"]).splitlines() if not _web_junk(l)]
        timed = [l for l in lines if _TS_RE.match(l.strip())]
        # 同一頁同時有純文字和 LRC 兩份（木蘭詞）會連成一段：拆成兩份候選
        parts = [lines] if not timed or len(timed) == len([l for l in lines if l.strip()]) else \
            [[l for l in lines if not _TS_RE.match(l.strip())], timed]
        for p in parts:
            text = _strip_header("\n".join(p))
            if text:
                out.append({"source": "web", "artist": "", "track": "", "text": text, "url": c["url"]})
    return out


_EDITOR_RE = re.compile(r"editor\b|\blrc\b|歌詞(製作|編輯|整理)|～～", re.I)


def _web_junk(line: str) -> bool:
    """網頁歌詞裡的製作名單（LRC 常帶時間：「[00:10.00]作詞：天天」）與「Editor by …」。"""
    body = _WORD_TS_RE.sub("", _TS_RE.sub("", line)).strip()
    return bool(body) and (is_credit(body) or bool(_EDITOR_RE.search(body)))


_HEADER_RE = re.compile(r"[:：《》【】]|歌詞|歌词|lyrics|\s[-–—]\s|^[\(（].*[\)）]$", re.I)


def _strip_header(text: str) -> str:
    """網頁段落頭尾的「一輩子一場夢 - 張衛健」「歌曲：我相信」「填詞：…」這類標題行（最多各 4 行）。"""
    lines = text.strip().splitlines()
    for _ in range(4):
        if lines and (not lines[0].strip() or _HEADER_RE.search(_TS_RE.sub("", lines[0]))):
            lines.pop(0)
        if lines and (not lines[-1].strip() or _HEADER_RE.search(_TS_RE.sub("", lines[-1]))):
            lines.pop()
    return "\n".join(lines).strip()


MAX_CAND_TOKENS = 1500   # 比對是 O(n·m) 的純 Python，太長的網頁段落先截掉
MIN_F1 = 0.25            # 低於這個就不用（017 實測：同名別人的歌 0.06～0.10，台語歌對的 0.44～0.58）
TIE = 0.02             # 同一份歌詞在 LRCLIB 與網頁上分數幾乎一樣（差 ≤0.01）；《今生最愛的人》別的版本只差 0.03


def _line_tokens(text: str) -> list:
    """每行（拿掉 LRC 時間、製作名單）的比對單位（正體、逐字／逐詞）。"""
    import align
    out = []
    for raw in to_traditional(text).splitlines():
        line = _WORD_TS_RE.sub("", _TS_RE.sub("", raw)).strip()
        out.append(align._tokens(line) if line and not is_credit(line) else [])
    return out


def score(text: str, heard: list) -> dict:
    """
    候選歌詞和實際唱的內容（heard：辨識結果或目前歌詞的比對單位）有多像（017）。
    逐行切開後全域序列比對；算精確時不計前後完全沒對上的行（網頁的導覽、廣告）——但歌詞本身不修：
    辨識常漏掉第一段（《告白氣球》的「塞納河畔…」），修掉會少歌詞。
    分數＝F1（召回＝對上字數／唱的字數，精確＝對上字數／前後修掉後的候選字數）。回傳 {"f1", "recall", "precision"}。
    """
    import align
    per = _line_tokens(text)
    flat, owner = [], []
    for i, toks in enumerate(per):
        for t in toks:
            if len(flat) < MAX_CAND_TOKENS:
                flat.append(t)
                owner.append(i)
    hits = [0] * len(per)
    for a, _ in align._match_tokens(flat, heard):
        hits[owner[a]] += 1
    good = [i for i, toks in enumerate(per) if toks and hits[i] >= max(2, 0.3 * len(toks))]
    if not good or not heard:
        return {"f1": 0.0, "recall": 0.0, "precision": 0.0}
    lo, hi = good[0], good[-1]
    m = sum(hits[lo:hi + 1])
    n = sum(len(t) for t in per[lo:hi + 1])
    recall, precision = m / len(heard), m / max(1, n)
    f1 = 2 * recall * precision / max(1e-9, recall + precision)
    return {"f1": f1, "recall": recall, "precision": precision}


def heard_tokens(text: str) -> list:
    return [t for toks in _line_tokens(text) for t in toks]


def pick(cands: list, heard: list, min_f1: float):
    """
    候選裡挑和 heard 最像的一份（017）；最像的也低於 min_f1 → None。
    分數和最高的差 TIE 以內時，有時間的（LRC）優先、再來 LRCLIB（同一份歌詞常同時在 LRCLIB 與網頁上，LRC 對齊比較準）。
    先用字的集合重疊粗篩（比對很慢），只對前 8 份做全域序列比對。回傳候選 dict（加 score）。
    """
    if not cands or not heard:
        return None
    hs = set(heard)
    rough = sorted(cands, key=lambda c: -len(hs & set(heard_tokens(c["text"])[:MAX_CAND_TOKENS])) / max(1, len(hs)))
    scored = []
    for c in rough[:8]:
        s = score(c["text"], heard)
        where = c.get("url") or f"{c.get('artist')} - {c.get('track')}"
        log(f"  候選 {c['source']}（{where}）：F1 {s['f1']:.2f}（召回 {s['recall']:.2f}、精確 {s['precision']:.2f}）")
        scored.append({**c, "score": s["f1"]})
    top = max(c["score"] for c in scored)
    if top < min_f1:
        return None
    close = [c for c in scored if c["score"] >= top - TIE]
    return max(close, key=lambda c: (bool(parse_lrc(c["text"])), c["source"] == "lrclib", c["score"]))


def found_title(it: dict) -> str:
    """歌詞來源的「歌手 - 歌名」（正體），給網址上傳沒填歌名時當歌名（012、016）。"""
    return to_traditional(" - ".join(x for x in (it.get("artist"), it.get("track")) if x))


def _drop_romanized(s: str) -> str:
    """「翁立友(WENG LI YOU)」「行棋 (XING QI)」：中文旁邊括號裡的拼音拿掉。"""
    if not _CJK_RE.search(s or ""):
        return (s or "").strip()
    return re.sub(r"\s*[\(（][A-Za-z0-9 .,'&-]+[\)）]", "", s).strip()


def video_song_title(meta: dict, found: dict = None) -> str:
    """
    網址上傳沒填歌名時的歌名（016）：影片資訊有歌手與歌名（YouTube Music 都有）而且是中日韓文字 → 「歌手 - 歌名」；
    否則歌詞來源找到的「歌手 - 歌名」（有些唱片公司給 YouTube 的是拼音，例如「Apar Zhuo - Zai Jian Yan Huo」）；
    再不然影片資訊的「歌手 - 歌名」（西洋歌）；都沒有回傳 ""（呼叫端用影片標題）。
    """
    artist, track = _drop_romanized(meta.get("artist") or ""), _drop_romanized(meta.get("track") or "")
    video = to_traditional(f"{artist} - {track}") if artist and track else ""
    if video and _CJK_RE.search(video):
        return video
    if found:
        return found_title(found)
    return video
