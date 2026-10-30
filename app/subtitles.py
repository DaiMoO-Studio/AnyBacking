"""
產生 ASS 字幕，兩種風格：
- karaoke：KTV 風格，上下兩行交替、逐字填色、提前預告下一句
- lyric：滾動歌詞（類似 YouTube Music），整句亮起、不逐字變色，對齊有小誤差也不明顯
"""
import re
from pathlib import Path

from common import CJK

W, H = 1920, 1080
FONT = "Noto Sans CJK TC"
FONT_SIZE = 80
SPACING = 2
MARGIN_X = 140
LEAD = 4.0          # 每句最多提前幾秒出現
TAIL = 0.4          # 唱完後停留幾秒
INTERLUDE = 8.0     # 超過這個空檔就視為間奏，重新從上行開始

# ASS 顏色格式為 &HAABBGGRR。Primary＝唱過的顏色，Secondary＝還沒唱的顏色
SUNG = "&H00FFA000"      # 藍
UNSUNG = "&H00FFFFFF"    # 白
OUTLINE = "&H00000000"

HEADER = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Top,{FONT},{FONT_SIZE},{SUNG},{UNSUNG},{OUTLINE},&H80000000,-1,0,0,0,100,100,{SPACING},0,1,5,2,1,{MARGIN_X},{MARGIN_X},170,1
Style: Bottom,{FONT},{FONT_SIZE},{SUNG},{UNSUNG},{OUTLINE},&H80000000,-1,0,0,0,100,100,{SPACING},0,1,5,2,3,{MARGIN_X},{MARGIN_X},60,1
Style: Title,{FONT},96,&H00FFFFFF,&H00FFFFFF,{OUTLINE},&H80000000,-1,0,0,0,100,100,0,0,1,5,2,5,100,100,0,1
Style: Artist,{FONT},60,&H00E0E0E0,&H00E0E0E0,{OUTLINE},&H80000000,0,0,0,0,100,100,0,0,1,4,2,5,100,100,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def _t(sec: float) -> str:
    cs = max(0, round(sec * 100))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def _esc(s: str) -> str:
    return s.replace("\\", "＼").replace("{", "｛").replace("}", "｝")


def _fit_scale(text: str, size: int = FONT_SIZE, margin: int = MARGIN_X) -> int:
    """估算字寬，太長的句子縮小，避免超出畫面。"""
    width = sum(1.0 if re.match(f"[{CJK}]", c) else 0.55 for c in text) * (size + SPACING)
    avail = W - 2 * margin
    return 100 if width <= avail else max(50, int(avail / width * 100))


def _karaoke_text(line, show_start: float) -> str:
    parts, pos = [], 0  # pos：目前累積到第幾個 centisecond（相對於出現時間）
    for i, w in enumerate(line.words):
        start_cs = round((w.start - show_start) * 100)
        end_cs = round((w.end - show_start) * 100)
        if start_cs > pos:
            parts.append(f"{{\\k{start_cs - pos}}}")
            pos = start_cs
        dur = max(1, end_cs - pos)
        parts.append(f"{{\\kf{dur}}}{_esc(w.text.lstrip() if i == 0 else w.text)}")
        pos += dur
    return "".join(parts)


def schedule(lines: list) -> list:
    """決定每句放上行或下行，以及出現／消失時間。回傳 [(slot, show, hide)]。"""
    slots, slot = [], 0
    for i, l in enumerate(lines):
        if i > 0 and l.start - lines[i - 1].end > INTERLUDE:
            slot = 0
        slots.append(slot)
        slot ^= 1

    hides = []
    for i, l in enumerate(lines):
        nxt = next((j for j in range(i + 1, len(lines)) if slots[j] == slots[i]), None)
        hide = l.end + TAIL
        if nxt is not None and lines[nxt].start - 0.1 > l.end:
            hide = min(hide, lines[nxt].start - 0.1)
        hides.append(max(hide, l.end))

    result, slot_free = [], [0.0, 0.0]
    for i, l in enumerate(lines):
        s = slots[i]
        show = min(l.start, max(l.start - LEAD, slot_free[s], 0.0))
        result.append((s, show, hides[i]))
        slot_free[s] = hides[i]
    return result


def build_ass(lines: list, meta: dict, path: Path) -> None:
    events = []
    title, artist = meta.get("track") or meta.get("title") or "", meta.get("artist") or ""
    if lines and title and lines[0].start > 6:
        end = min(6.0, lines[0].start - LEAD - 0.3)
        if end > 1.5:
            events.append(f"Dialogue: 0,{_t(0.5)},{_t(end)},Title,,0,0,0,,{{\\fad(300,300)\\pos({W // 2},{H // 2 - 50})}}{_esc(title)}")
            if artist:
                events.append(f"Dialogue: 0,{_t(0.5)},{_t(end)},Artist,,0,0,0,,{{\\fad(300,300)\\pos({W // 2},{H // 2 + 60})}}{_esc(artist)}")

    for line, (slot, show, hide) in zip(lines, schedule(lines)):
        if not line.words:
            continue
        style = "Top" if slot == 0 else "Bottom"
        scale = _fit_scale(line.text)
        prefix = f"{{\\fscx{scale}\\fscy{scale}}}" if scale < 100 else ""
        events.append(f"Dialogue: 0,{_t(show)},{_t(hide)},{style},,0,0,0,,{prefix}{_karaoke_text(line, show)}")

    path.write_text(HEADER + "\n".join(events) + "\n", encoding="utf-8")


# ---------- 滾動歌詞風格（類似 YouTube Music）：靠左一欄，目前這句亮、其他暗，換句時整欄往上捲 ----------

LYRIC_FONT = "LXGW WenKai TC"   # 霞鶩文楷（OFL，可商用），缺字由 fontconfig 改用 Noto Sans CJK
LYRIC_SIZE = 76
LYRIC_X = 140                   # 歌詞欄左緣；右側留給環狀頻譜
LYRIC_W = 1260                  # 歌詞欄寬度，太長的句子縮小
CUR_Y = 430                     # 目前這句的垂直中心
STEP = 112                      # 行距
ABOVE, BELOW = 2, 4             # 目前這句上面、下面各顯示幾句
SCROLL_MS = 350                 # 捲動動畫長度
LEAD = 0.25                     # 提前幾秒捲到下一句（捲完剛好開始唱）
GAP_MARK = "♪"                  # 長間奏時插入的一行

# 每一行依「距離目前這句幾行」決定透明度（ASS alpha：00 不透明、FF 全透明）
_ALPHA = {0: 0x00, 1: 0x99, -1: 0xA8, 2: 0xA8, -2: 0xD0, 3: 0xB8, 4: 0xD8}


def _alpha(d: int) -> str:
    return f"&H{_ALPHA.get(d, 0xFF):02X}&"


LYRIC_HEADER = f"""[Script Info]
ScriptType: v4.00+
PlayResX: {W}
PlayResY: {H}
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Lyric,{LYRIC_FONT},{LYRIC_SIZE},&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,{SPACING},0,1,2,0,4,0,0,0,1
Style: Info,{LYRIC_FONT},34,&H60FFFFFF,&H60FFFFFF,&HC0000000,&H00000000,0,0,0,0,100,100,1,0,1,1,0,7,0,0,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""


def _rows(lines: list, meta: dict) -> list:
    """捲動的每一行 (文字, 開始成為目前這句的時間)：開頭是歌名，長間奏插入 ♪。"""
    title, artist = meta.get("track") or meta.get("title") or "", meta.get("artist") or ""
    rows = []
    if title:
        rows.append((f"{title}　{artist}".strip(), 0.0))
    for i, l in enumerate(lines):
        prev = lines[i - 1] if i else None
        if prev is not None and l.start - prev.end > INTERLUDE:
            rows.append((GAP_MARK, prev.end + 1.0))
        rows.append((l.text.strip(), max(l.start - LEAD, rows[-1][1] + 0.3 if rows else 0.0)))
    return rows


def build_lyric_ass(lines: list, meta: dict, path: Path) -> None:
    """
    滾動歌詞：每到下一句，整欄往上捲一行，目前這句變亮、上一句變暗。
    每一段「目前這句不變」的時間裡，每個看得到的行各是一個事件，用 \\move 從上一段的位置捲到這一段的位置。
    """
    lines = [l for l in lines if l.words]
    rows = _rows(lines, meta)
    events = []
    title, artist = meta.get("track") or meta.get("title") or "", meta.get("artist") or ""
    if title:
        info = f"{title}　—　{artist}" if artist else title
        events.append(f"Dialogue: 0,{_t(0)},{_t(36000)},Info,,0,0,0,,{{\\pos(80,60)}}{_esc(info)}")
    end_all = (lines[-1].end + 5.0) if lines else 10.0
    for k, (_, on) in enumerate(rows):
        off = rows[k + 1][1] if k + 1 < len(rows) else end_all
        for j in range(k - ABOVE - 1, k + BELOW + 1):
            if not 0 <= j < len(rows):
                continue
            d, d0 = j - k, j - k + 1  # 這一段、上一段距離目前這句幾行
            if _alpha(d) == _alpha(d0) == "&HFF&":
                continue
            text = rows[j][0]
            scale = _fit_scale(text, LYRIC_SIZE, (W - LYRIC_W) // 2)
            fs = f"\\fscx{scale}\\fscy{scale}" if scale < 100 else ""
            y, y0 = CUR_Y + d * STEP, CUR_Y + d0 * STEP
            move = f"\\move({LYRIC_X},{y0},{LYRIC_X},{y},0,{SCROLL_MS})" if k else f"\\pos({LYRIC_X},{y})"
            fade = f"\\alpha{_alpha(d0)}\\t(0,{SCROLL_MS},\\alpha{_alpha(d)})" if k else f"\\alpha{_alpha(d)}"
            events.append(f"Dialogue: 0,{_t(on)},{_t(off)},Lyric,,0,0,0,,{{{move}{fade}{fs}}}{_esc(text)}")
    path.write_text(LYRIC_HEADER + "\n".join(events) + "\n", encoding="utf-8")
