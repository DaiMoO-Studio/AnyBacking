"""用 Whisper（stable-ts）做歌詞逐字對齊，或在沒有歌詞時直接辨識。"""
import re
from pathlib import Path

from common import Line, Word, even_words, log, nonspace_len, units
from lyrics import to_traditional

SR = 16000       # Whisper 的取樣率
MIN_DUR = 0.02   # 比這短的字視為 stable-ts 沒對上（零長度）
PIN_BEFORE, PIN_AFTER = 0.8, 0.3   # 校正：手動句的句首可以比使用者點的時間早多少、晚多少（秒）
_CREDIT_HALLUCINATION = re.compile(r"\s*(作[詞词曲]|[編编]曲|演唱|字幕)")


def get_device() -> str:
    try:
        import torch
        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


def load_whisper(name: str, device: str):
    import stable_whisper
    log(f"載入 Whisper 模型 {name}（{device}）")
    return stable_whisper.load_model(name, device=device)


def _result_words(result) -> list:
    if result is None:
        return []
    try:
        return list(result.all_words())
    except AttributeError:
        return [w for s in result.segments for w in (s.words or [])]


def _mostly_instant(ws: list) -> bool:
    """stable-ts 沒真正對上這句：大半的字是零長度，或整句被拉得不合理地長。"""
    if sum(w.end - w.start < MIN_DUR for w in ws) * 2 > len(ws):
        return True
    return ws[-1].end - ws[0].start > 4.0 + 1.2 * sum(nonspace_len(w.text) for w in ws)


def _from_words(text: str, ws: list) -> Line:
    """零長度的字和相鄰的正常字平分時間，並確保每個字的時間單調遞增、長度不為零。"""
    ws = [Word(w.text, w.start, w.end) for w in ws]
    good = [w.end - w.start >= MIN_DUR for w in ws]
    i = 0
    while any(good) and i < len(ws):
        if good[i]:
            i += 1
            continue
        j = i
        while j < len(ws) and not good[j]:
            j += 1
        # 句中的零長度字分前一個字的時間；句首的分後一個字的時間
        group = ws[i - 1:j] if i > 0 else ws[i:j + 1]
        span = group[0] if i > 0 else group[-1]
        s, step = span.start, (span.end - span.start) / len(group)
        for k, w in enumerate(group):
            w.start, w.end = s + step * k, s + step * (k + 1)
        i = j
    fixed, prev = [], ws[0].start
    for w in ws:
        s = max(w.start, prev)
        e = max(w.end, s + 0.05)
        fixed.append(Word(w.text, s, e))
        prev = e
    return Line(text, fixed[0].start, fixed[-1].end, fixed)


def _vocal_db(audio):
    """人聲音軌每 10 ms 的音量（dB，50 ms 平滑）。"""
    import numpy as np
    hop = SR // 100
    n = len(audio) // hop
    env = np.sqrt((audio[:n * hop].reshape(n, hop) ** 2).mean(1))
    return 20 * np.log10(np.convolve(env, np.ones(5) / 5, "same") + 1e-6)


def _active(db):
    """
    有人聲的格子：比整首人聲的 95 百分位音量低不到 25 dB（間奏裡的伴奏殘留通常低得多），
    而且前後 0.5 秒內超過一半的格子都有聲音（濾掉零星的殘留）。
    """
    import numpy as np
    loud = db > np.percentile(db, 95) - 25
    return np.convolve(loud, np.ones(50) / 50, "same") > 0.5


def _place_missing(lines: list, active) -> int:
    """
    對不上的句子：前後句之間有足夠人聲，就平均放進有人聲的範圍；
    沒有人聲（間奏、尾奏）表示這個版本沒唱這幾句，標成未演唱（start=-1、沒有 words）。
    回傳未演唱的句數。
    """
    import numpy as np
    total, unsung, i = len(active) / 100, 0, 0
    while i < len(lines):
        if lines[i].start >= 0:
            i += 1
            continue
        j = i
        while True:
            while j < len(lines) and lines[j].start < 0:
                j += 1
            lo = lines[i - 1].end if i > 0 else 0.0
            hi = lines[j].start if j < len(lines) else total
            idx = np.flatnonzero(active[int(lo * 100):int(hi * 100)])
            if i == 0:  # 前奏常有零星的殘留：從貼著後一句的那段人聲往前取，夠唱（每個字／詞 0.3 秒）就停
                need = 30 * sum(len(_tokens(lines[k].text)) for k in range(i, j))
                for g in np.flatnonzero(np.diff(idx) > 500)[::-1]:
                    if len(idx) - g - 1 >= need:
                        idx = idx[g + 1:]
                        break
            if len(idx) >= 100 * (j - i) or j >= len(lines) or hi - lo >= 2.0 * (j - i):
                break
            # 前後句之間的空間本身就太擠（不是有空檔但沒人聲）：多半是後一句的時間對錯了
            # （歌詞重複時常對到別次），把它也當成沒對上，一起重新分配
            lines[j] = Line(lines[j].text, -1, -1)
        if len(idx) < 100 * (j - i):  # 有空檔但平均每句不到 1 秒人聲：這個版本沒唱
            unsung += j - i
            for k in range(i, j):
                lines[k] = Line(lines[k].text, -1, -1)
        else:
            # 只沿著有人聲的時間平均分，不要把句子放進中間的間奏
            per = len(idx) / (j - i)
            for k in range(i, j):
                part = idx[int(per * (k - i)):int(per * (k - i + 1))]
                cut = np.flatnonzero(np.diff(part) > 200)  # 跨過 2 秒以上的空白就只取前段
                part = part[:cut[0] + 1] if len(cut) else part
                l = lines[k] = Line(lines[k].text, lo + part[0] / 100, lo + (part[-1] + 1) / 100, confident=False)
                l.words = even_words(l)
        i = j
    return unsung


def _tokens(text: str) -> list:
    """比對用的單位：中日韓逐字、拉丁文逐詞，去掉標點並轉小寫。"""
    return [t for t in (re.sub(r"\W", "", u).lower() for u in units(text)) if t]


def _match_tokens(a: list, b: list) -> list:
    """
    全域序列比對（Needleman-Wunsch），回傳相同字的位置配對 [(i, j)]。
    辨識結果常有同音錯字、漏段和幻覺，所以替換、跳過都只扣少量分數；
    整體依順序比對，重複的副歌不會像找最長相同片段那樣對到別次。
    """
    MATCH, SUB, GAP = 2.0, -0.5, -0.6
    n, m = len(a), len(b)
    score = [[GAP * j for j in range(m + 1)]] + [[GAP * i] + [0.0] * m for i in range(1, n + 1)]
    move = [bytearray(m + 1) for _ in range(n + 1)]  # 0＝斜向、1＝跳過 a、2＝跳過 b
    for i in range(1, n + 1):
        prev, row, mv, ai = score[i - 1], score[i], move[i], a[i - 1]
        mv[0] = 1
        for j in range(1, m + 1):
            d = prev[j - 1] + (MATCH if ai == b[j - 1] else SUB)
            u = prev[j] + GAP
            l = row[j - 1] + GAP
            if d >= u and d >= l:
                row[j] = d
            elif u >= l:
                row[j], mv[j] = u, 1
            else:
                row[j], mv[j] = l, 2
    for j in range(1, m + 1):
        move[0][j] = 2
    pairs, i, j = [], n, m
    while i > 0 or j > 0:
        mv = move[i][j]
        if mv == 0:
            if a[i - 1] == b[j - 1]:
                pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif mv == 1:
            i -= 1
        else:
            j -= 1
    return pairs[::-1]


def _anchor_lines(model, audio, lines: list, lang: str) -> int:
    """
    沒有時間的歌詞：先用 Whisper 辨識整首，把歌詞和辨識結果逐字比對，
    寫入每句的大概 start/end（對不上的句子設成 -1）。回傳對上的句數。
    """
    log("辨識人聲，估計每句歌詞的大概時間")
    # 不參考前文：唱歌時參考前文容易整段跳過或重複幻覺
    result = model.transcribe(audio, language=lang, word_timestamps=True, condition_on_previous_text=False)
    norm = to_traditional if lang == "zh" else (lambda s: s)  # 歌詞和辨識結果可能一簡一繁
    heard = []  # (字, 開始, 結束)
    for w in _result_words(result):
        toks = [norm(t) for t in _tokens(w.word)]
        step = (w.end - w.start) / max(1, len(toks))
        heard += [(t, w.start + step * k, w.start + step * (k + 1)) for k, t in enumerate(toks)]
    lyric, first = [], []  # lyric：(句子編號, 字)；first：每句第一個字在 lyric 裡的位置
    for i, l in enumerate(lines):
        first.append(len(lyric))
        lyric += [(i, norm(t)) for t in _tokens(l.text)]

    hits = [[] for _ in lines]  # 每句對上的 (字位置, 開始, 結束)
    for a, b in _match_tokens([t for _, t in lyric], [t for t, _, _ in heard]):
        hits[lyric[a][0]].append((a, heard[b][1], heard[b][2]))

    count, prev_end = 0, 0.0
    for i, l in enumerate(lines):
        h, n = hits[i], len(_tokens(l.text))
        l.start = l.end = -1
        if not n or len(h) < max(2, 0.4 * n):
            continue
        # 句首／句尾沒對上的字，用這句的平均字長往外推
        per = (h[-1][2] - h[0][1]) / (h[-1][0] - h[0][0] + 1)
        start = max(prev_end, h[0][1] - (h[0][0] - first[i]) * per)
        end = h[-1][2] + (first[i] + n - 1 - h[-1][0]) * per
        if end - start > 4.0 + 1.2 * n:
            continue
        l.start, l.end, prev_end = start, end, end
        count += 1
    return count


def align_lines(model, vocals: Path, lines: list, lang: str, timed: bool, tolerance: float = 2.0) -> list:
    """
    把已知歌詞對齊到人聲音軌，取得逐字時間。
    timed=True（LRC 有逐句時間）時，對齊結果和 LRC 差太多的句子會退回「依 LRC 逐句平均分配」。
    沒有時間的歌詞會先用 Whisper 辨識估出每句的大概時間，再照有時間的方式處理；
    這個版本沒唱的句子回傳 start=-1、沒有 words（呼叫端寫進 lyrics.lrc 後再濾掉）。
    """
    import whisper
    audio = whisper.load_audio(str(vocals))
    db = _vocal_db(audio)
    active = _active(db)
    lines = [Line(l.text, l.start, l.end) for l in lines]
    estimated = False  # True：時間是從辨識結果估出來的，不是使用者的 LRC
    if not timed:
        anchored = _anchor_lines(model, audio, lines, lang)
        log(f"辨識結果對上 {anchored}/{len(lines)} 句")
        if anchored * 2 >= len(lines):
            _place_missing(lines, active)
            timed = estimated = True

    sung = [l for l in lines if l.start >= 0 or not timed]
    log(f"逐字對齊 {len(sung)} 句歌詞（語言：{lang}）")
    if timed:  # 已知每句大概時間：每句只在自己前後 1 秒的範圍內對齊，比整首一次對齊穩定
        buckets = [_align_buckets(model, audio[int(max(0.0, l.start - 1.0) * SR):int((l.end + 1.0) * SR)],
                                  [l], lang, offset=max(0.0, l.start - 1.0))[0] for l in sung]
    else:
        buckets = _align_buckets(model, audio, sung, lang)
    out, k, fallback = [], 0, 0
    for line in lines:
        if timed and line.start < 0:
            out.append(line)
            continue
        ws, complete = buckets[k]
        k += 1
        if timed:
            if complete and not _mostly_instant(ws) and abs(ws[0].start - line.start) <= tolerance:
                out.append(_from_words(line.text, ws))
            else:
                fallback += 1
                out.append(Line(line.text, line.start, line.end, even_words(line), line.confident))
        else:
            out.append(_from_words(line.text, ws) if ws and not _mostly_instant(ws) else Line(line.text, -1, -1))
    if estimated:
        out = _merge_global(model, audio, out, lang)
        _place_missing(out, active)
    elif not timed:
        _drop_regressions(out)
        _realign_missing(model, audio, out, lang)
        for l in out:
            if l.start < 0:
                l.confident = False
        _place_missing(out, active)
    _fix_overlaps(out, db)
    _snap_onsets(out, db)
    unsung = sum(not l.words for l in out)
    unsure = sum(bool(l.words) and not l.confident for l in out)
    log(f"對齊完成：{len(out) - fallback - unsung - unsure} 句使用逐字時間，{fallback} 句退回平均分配"
        + (f"，{unsure} 句的時間是推估的（words.json 標為 confident=false，建議確認）" if unsure else "")
        + (f"，{unsung} 句在沒有人聲的段落、當作這個版本沒唱" if unsung else ""))
    return out


def _merge_global(model, audio, out: list, lang: str) -> list:
    """
    再做一次整首對齊（不靠辨識結果），逐句挑比較可信的：
    歌詞和音訊版本一致時整首對齊通常很準；只有它不合理、或和可信的錨點差太多時才用錨點的結果。
    在歌裡重複出現的句子，錨點容易對到別次，以整首對齊為準。
    """
    from collections import Counter
    key = lambda s: re.sub(r"\W", "", s).lower()
    g = [_from_words(l.text, ws) if ws and not _mostly_instant(ws) else Line(l.text, -1, -1)
         for l, (ws, _) in zip(out, _align_buckets(model, audio, out, lang))]
    _drop_regressions(g)
    counts = Counter(key(l.text) for l in out)
    merged = []
    for a, gl in zip(out, g):
        if not a.words or gl.start < 0:  # 錨點判定沒唱（例如貼進來的標題行），或整首對齊不合理
            merged.append(a)
        elif abs(gl.start - a.start) <= 2.0 or counts[key(a.text)] > 1 or not a.confident:
            merged.append(gl)
        else:
            merged.append(a)
    _drop_regressions(merged)  # 混用兩種來源可能前後顛倒，顛倒的句子交給 _place_missing 重新放
    return merged


def _align_buckets(model, audio, lines: list, lang: str, offset: float = 0.0) -> list:
    """對齊一段音訊，回傳每句的 (字列表, 字數是否完整)。"""
    result = model.align(audio, "\n".join(l.text for l in lines), language=lang)
    raw = _result_words(result)

    # stable-ts 回傳的是攤平的字，依照每句的字數把字分回各句
    targets = [nonspace_len(l.text) for l in lines]
    buckets = [[] for _ in lines]
    li, acc = 0, 0
    for w in raw:
        n = nonspace_len(w.word)
        if n == 0:
            continue
        while li < len(lines) and acc >= targets[li]:
            li, acc = li + 1, 0
        if li >= len(lines):
            break
        buckets[li].append(Word(w.word, float(w.start) + offset, float(w.end) + offset))
        acc += n
    return [(ws, sum(nonspace_len(w.text) for w in ws) == t) for ws, t in zip(buckets, targets)]


def _drop_regressions(lines: list) -> None:
    """時間比前一句還早的句子是對錯位置，標成對不上。"""
    prev_end = 0.0
    for l in lines:
        if l.start < 0:
            continue
        if l.start < prev_end - 0.5:
            l.start = l.end = -1
            l.words = []
        else:
            prev_end = l.end


def _realign_missing(model, audio, lines: list, lang: str) -> None:
    """連續對不上的句子，只拿前後句之間的那段音訊再對齊一次。"""
    total = len(audio) / SR
    i = 0
    while i < len(lines):
        if lines[i].start >= 0:
            i += 1
            continue
        j = i
        while j < len(lines) and lines[j].start < 0:
            j += 1
        lo = lines[i - 1].end if i > 0 else 0.0
        hi = lines[j].start if j < len(lines) else total
        if hi - lo >= 1.0:
            chunk = audio[int(lo * SR):int(hi * SR)]
            for k, (ws, _) in enumerate(_align_buckets(model, chunk, lines[i:j], lang, offset=lo)):
                if ws and not _mostly_instant(ws):
                    lines[i + k] = _from_words(lines[i + k].text, ws)
            _drop_regressions(lines)
        i = j


def _fix_overlaps(lines: list, db) -> None:
    """相鄰兩句時間重疊時，以重疊範圍內人聲最小聲的地方當分界，兩邊的字都不越過分界。"""
    import numpy as np
    prev = None
    for l in lines:
        if not l.words:
            continue
        if prev and l.start < prev.end:
            a, b = int(l.start * 100), int(prev.end * 100)
            cut = (a + int(np.argmin(db[a:b + 1]))) / 100 if b > a else l.start
            for w in prev.words:
                w.start, w.end = min(w.start, cut - MIN_DUR), min(w.end, cut)
            for w in l.words:
                w.start, w.end = max(w.start, cut), max(w.end, cut + MIN_DUR)
            prev.words = _from_words(prev.text, prev.words).words
            l.words = _from_words(l.text, l.words).words
            prev.end, l.start, l.end = prev.words[-1].end, l.words[0].start, l.words[-1].end
        prev = l


def _late_onset(db, s: float, t: float):
    """在 [s, t] 秒裡找最後一個「人聲低谷→起音」，回傳 (低谷時間, 起音時間)；沒有就回傳 None。"""
    a, b = int(s * 100), int(t * 100)
    seg = db[a:b + 1]
    for v in range(len(seg) - 5, 0, -1):
        after = seg[v:].max()
        if seg[v] < after - 15 and seg[v] <= seg[max(0, v - 3):v + 4].min():
            on = v + int((seg[v:] >= after - 10).argmax())
            return (a + v) / 100, (a + on) / 100
    return None


def _snap_onsets(lines: list, db, only: set = None) -> None:
    """
    句首的字常把上一句的尾音（中文歌句尾拉長音）也算進去，看起來提早變色；
    在第一個字的範圍內找人聲低谷，把開始時間移到低谷後真正起音的地方。
    only：只處理這些位置的句子（校正時只動重新對齊過的句子）。
    """
    prev = None
    for i, l in enumerate(lines):
        if not l.words:
            continue
        if only is not None and i not in only:
            prev = l
            continue
        w = l.words[0]
        found = _late_onset(db, w.start, w.end - 0.08)  # 至少留 80 ms 給這個字
        if found:
            valley, onset = found
            if prev and prev.words and abs(prev.end - w.start) <= 0.05:  # 只延長緊接著這句的上一句
                last = prev.words[-1]
                last.end = prev.end = max(last.end, valley)
            w.start = l.start = onset
        prev = l


def realign(model, vocals: Path, items: list, lang: str) -> list:
    """
    歌詞校正（003）：只重新對齊使用者改動的句子，沒動的句子原封不動（只可能為了不和下一句重疊而截短句尾）。
    items：[(分類, Line)]，分類見 correction.classify；Line 帶 App 送來的文字與句首，keep 的 Line 是原本的結果（含逐字）。
    - pin：使用者在聽到開唱時點的時間（通常晚 0.2～0.4 秒）。在點的時間前 0.8 秒到後 0.3 秒內找句首；找不到合理結果就以點的時間為準平均分配。
    - exact：使用者直接加減或輸入的時間，句首就用它，只從這裡往後對齊逐字（不吸附起音點）。
    - text：改了字。照首次處理有 LRC 時間的做法，在原時間前後 1 秒內對齊。
    - free：App 因為順序衝突重估了時間。等其他句子都定了，在前後句之間的空檔裡對齊。
    回傳完整句子清單（沒唱的 start=-1、沒有 words）。
    """
    import whisper
    audio = whisper.load_audio(str(vocals))
    db = _vocal_db(audio)
    total = len(audio) / SR
    kinds = [k for k, _ in items]
    out = [Line(l.text, l.start, l.end, [Word(w.text, w.start, w.end) for w in l.words], l.confident)
           for _, l in items]
    for i, l in enumerate(out):
        if kinds[i] == "unsung":
            out[i] = Line(l.text, -1, -1, [], False)

    def next_start(i):  # 後面第一句時間已知（不是 free、沒唱）的句首
        return next((out[j].start for j in range(i + 1, len(out)) if kinds[j] in ("keep", "pin", "exact", "text")), total)

    for i, kind in enumerate(kinds):
        l = out[i]
        if kind in ("pin", "exact"):
            n = nonspace_len(l.text)
            est = max(l.end - l.start, 0.45 * n + 1.5)
            end = min(next_start(i) - 0.05, l.start + est)
            if end < l.start + 0.3 * n:  # 下一句貼太近：照字數估，讓重疊處理去切
                end = l.start + est
        if kind == "exact":  # 使用者直接加減或輸入的時間就是句首：只從這裡開始對齊逐字
            ws, complete = _align_buckets(model, audio[int(l.start * SR):int(min(total, end + 1.0) * SR)], [l], lang,
                                          offset=l.start)[0]
            if complete and not _mostly_instant(ws):
                out[i] = _from_words(l.text, ws)
                out[i].start = out[i].words[0].start = l.start
            else:
                out[i] = Line(l.text, l.start, end)
                out[i].words = even_words(out[i])
        elif kind == "pin":
            lo = max(0.0, l.start - 1.0)
            ws, complete = _align_buckets(model, audio[int(lo * SR):int(min(total, end + 1.0) * SR)], [l], lang,
                                          offset=lo)[0]
            # 使用者是聽到才點：真正的句首在點的時間之前（反應時間），不會晚太多。
            # 貼在時間窗開頭的結果通常是抓到上一句的尾音，不採用
            if (complete and not _mostly_instant(ws) and ws[0].start > lo + 0.05
                    and l.start - PIN_BEFORE <= ws[0].start <= l.start + PIN_AFTER):
                out[i] = _from_words(l.text, ws)
            else:
                out[i] = Line(l.text, l.start, end, confident=False)
                out[i].words = even_words(out[i])
        elif kind == "text":
            lo = max(0.0, l.start - 1.0)
            ws, complete = _align_buckets(model, audio[int(lo * SR):int(min(total, l.end + 1.0) * SR)], [l], lang,
                                          offset=lo)[0]
            if complete and not _mostly_instant(ws) and abs(ws[0].start - l.start) <= 2.0:
                out[i] = _from_words(l.text, ws)
            else:
                out[i] = Line(l.text, l.start, l.end, confident=l.confident)   # 只改字：時間的可信度沿用原本
                out[i].words = even_words(out[i])
    for i, kind in enumerate(kinds):
        if kind == "free":
            out[i].start = out[i].end = -1
            out[i].words = []
    _realign_free(model, audio, out, [k == "free" for k in kinds], lang)
    redo = {i for i, k in enumerate(kinds) if k in ("pin", "exact", "text", "free")}
    _trim_overlaps(out, only=redo)
    pins = {i: (items[i][1].start, out[i].start) for i, k in enumerate(kinds) if k == "pin"}
    _snap_onsets(out, db, only={i for i in redo if kinds[i] != "exact"})   # 使用者指定的句首不吸附
    for i, (pin, before) in pins.items():  # 起音點吸附也不能讓句首晚於點的時間太多
        l = out[i]
        if l.words and l.start > pin + PIN_AFTER:
            l.start = l.words[0].start = before
    unsure = sum(bool(l.words) and not l.confident for l in out)
    log(f"校正對齊完成：{len(redo)} 句重新對齊、{len(out) - len(redo)} 句沿用，{unsure} 句推估")
    return out


def _realign_free(model, audio, lines: list, free: list, lang: str) -> None:
    """free 的句子：在前後句之間的空檔裡對齊；對不上的在空檔裡依字數平均分配（推估）。"""
    total = len(audio) / SR
    i = 0
    while i < len(lines):
        if not free[i]:
            i += 1
            continue
        j = i
        while j < len(lines) and free[j]:
            j += 1
        lo = next((lines[k].end for k in range(i - 1, -1, -1) if lines[k].words), 0.0)
        hi = next((lines[k].start for k in range(j, len(lines)) if lines[k].words), total)
        if hi - lo >= 1.0:
            chunk = audio[int(lo * SR):int(hi * SR)]
            prev = lo
            for k, (ws, complete) in enumerate(_align_buckets(model, chunk, lines[i:j], lang, offset=lo)):
                if complete and not _mostly_instant(ws) and ws[0].start >= prev - 0.05 and ws[-1].end <= hi + 0.05:
                    lines[i + k] = _from_words(lines[i + k].text, ws)
                    prev = lines[i + k].end
        # 沒對上的連續幾句：在前後已定的句子之間依字數平均分配
        k = i
        while k < j:
            if lines[k].words:
                k += 1
                continue
            m = k
            while m < j and not lines[m].words:
                m += 1
            a = next((lines[x].end for x in range(k - 1, -1, -1) if lines[x].words), 0.0)
            b = next((lines[x].start for x in range(m, len(lines)) if lines[x].words), total)
            weights = [max(1, nonspace_len(lines[x].text)) for x in range(k, m)]
            step = max(0.0, b - a) / sum(weights)
            t = a
            for x, w in zip(range(k, m), weights):
                dur = min(step * w, 0.5 * w + 1.0)
                l = lines[x] = Line(lines[x].text, t, t + max(dur, 0.1), confident=False)
                l.words = even_words(l)
                t += step * w
            k = m
        i = j


def _trim_overlaps(lines: list, only: set = None) -> None:
    """
    校正用的重疊處理：後一句的句首不動（使用者校正的就是句首），前一句的字依比例壓縮到後一句句首之前。
    首次處理用的 _fix_overlaps 取人聲最小聲處當分界，會移動手動設定的句首，所以不用。
    only：相鄰兩句至少一句在這些位置才處理（兩句都沒動、原本就重疊的維持原樣）。
    """
    prev, pi = None, -1
    for i, l in enumerate(lines):
        if not l.words:
            continue
        if prev and l.start < prev.end and (only is None or i in only or pi in only):
            cut = max(l.start - MIN_DUR, prev.start + 0.05 * len(prev.words))
            scale = (cut - prev.start) / max(prev.end - prev.start, 1e-6)
            for w in prev.words:
                w.start = prev.start + (w.start - prev.start) * scale
                w.end = prev.start + (w.end - prev.start) * scale
            prev.end = prev.words[-1].end
            if cut - prev.start < 0.1 * len(prev.words):  # 壓到不合理：前一句的時間多半本來就錯了
                prev.confident = False
        prev, pi = l, i


def _detect_language(model, audio) -> str:
    """
    Whisper 預設只聽前 30 秒判斷語言，前奏沒人聲時常判錯（例如把中文歌判成英文）；
    改聽人聲最密集的 30 秒。
    """
    import numpy as np
    import whisper
    active = _active(_vocal_db(audio))
    win = 30 * 100
    counts = np.convolve(active, np.ones(win), "valid") if len(active) > win else [0]
    s = int(np.argmax(counts)) * (SR // 100)
    mel = whisper.log_mel_spectrogram(whisper.pad_or_trim(audio[s:s + 30 * SR]), model.dims.n_mels)
    _, probs = model.detect_language(mel.to(model.device))
    lang = max(probs, key=probs.get)
    log(f"偵測語言：{lang}（第 {s // SR} 秒起的 30 秒人聲）")
    return lang


def transcribe(model, vocals: Path, lang: str = None, s2t: bool = False):
    """沒有歌詞時直接辨識人聲。回傳 (lines, 語言)。"""
    import whisper
    log("找不到歌詞，改用 Whisper 辨識人聲（中文可能有錯字，建議人工校對 lyrics.lrc）")
    audio = whisper.load_audio(str(vocals))
    lang = lang or _detect_language(model, audio)
    result = model.transcribe(audio, language=lang, word_timestamps=True, condition_on_previous_text=False)
    detected = getattr(result, "language", None) or lang or ""
    conv = to_traditional if (s2t or detected.startswith("zh")) else (lambda s: s)
    lines = []
    for seg in result.segments:
        ws = [Word(conv(w.word), float(w.start), float(w.end)) for w in (seg.words or []) if w.word.strip()]
        # Whisper 在沒唱或聽不清楚的地方常幻覺出「作曲 某某某」這類製作名單
        if ws and not _CREDIT_HALLUCINATION.match("".join(w.text for w in ws)):
            lines.append(_from_words("".join(w.text for w in ws).strip(), ws))
    return lines, detected
