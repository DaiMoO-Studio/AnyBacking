"""
展開省略的重複段落（019）：歌詞來源常只寫一次副歌（「＊」「＃」「重複＊」或乾脆省略），對齊後後半段整段沒有歌詞。
對齊完成後先用人聲時間涵蓋率初篩（不跑辨識，歌詞完整的歌在這裡就結束、結果不變），
通過的才把沒有歌詞的人聲段單獨辨識、和整份歌詞做區域序列比對，歌詞裡連續好幾句都對上才複製那幾句插進去，再在推估時間附近逐字對齊。
"""
from common import Line, even_words, log
from lyrics import to_traditional

COVER_MAX = 0.7    # 有人聲的時間被句子涵蓋 ≥ 這個比例就不展開（歌曲庫：完整的歌 ≥0.64，《普通朋友》0.42）
GAP_MIN = 20.0     # 沒有句子的區間內至少這麼多秒人聲才處理（歌曲庫完整的歌最長 18 秒，末班車 38.7 秒但歌詞完整）
MIN_RUN = 2        # 歌詞裡至少連續這麼多句對上（擋即興、和聲、單句口號）
MIN_TOKENS = 8     # 一次比對至少對上這麼多字
LINE_HIT = 0.5     # 一句至少對上這個比例的字（且 ≥2 字）才算對上
MAX_ROUNDS = 4     # 每段最多比對幾次（副歌可能連唱兩次以上）


def _gaps(lines: list, active):
    """回傳 (涵蓋率, [(lo, hi, 人聲秒數, 前一句位置)])：以有逐字時間的句子切出沒有句子的區間。"""
    import numpy as np
    cov = np.zeros(len(active), bool)
    sung = [(i, l) for i, l in enumerate(lines) if l.words]
    for _, l in sung:
        cov[int(l.start * 100):int(l.end * 100) + 1] = True
    total = int(active.sum())
    rate = float((cov & active).sum()) / total if total else 1.0
    out, prev_end, prev_i = [], 0.0, -1
    for i, l in sung + [(len(lines), None)]:
        lo, hi = prev_end, (l.start if l else len(active) / 100)
        if hi > lo:
            out.append((lo, hi, float(active[int(lo * 100):int(hi * 100)].sum()) / 100, prev_i))
        if l:
            prev_end, prev_i = max(prev_end, l.end), i
    return rate, out


def _local_match(a: list, b: list) -> list:
    """區域序列比對（Smith-Waterman，計分同 align._match_tokens），回傳最高分那段裡相同字的配對 [(i, j)]。"""
    MATCH, SUB, GAP = 2.0, -0.5, -0.6
    n, m = len(a), len(b)
    score = [[0.0] * (m + 1) for _ in range(n + 1)]
    move = [bytearray(m + 1) for _ in range(n + 1)]  # 0＝起點、1＝斜向、2＝跳過 a、3＝跳過 b
    best, bi, bj = 0.0, 0, 0
    for i in range(1, n + 1):
        prev, row, mv, ai = score[i - 1], score[i], move[i], a[i - 1]
        for j in range(1, m + 1):
            d = prev[j - 1] + (MATCH if ai is not None and ai == b[j - 1] else SUB)
            u = prev[j] + GAP
            l = row[j - 1] + GAP
            s, k = max((0.0, 0), (d, 1), (u, 2), (l, 3))
            row[j], mv[j] = s, k
            if s > best:
                best, bi, bj = s, i, j
    pairs, i, j = [], bi, bj
    while i > 0 and j > 0 and move[i][j]:
        k = move[i][j]
        if k == 1:
            if a[i - 1] == b[j - 1]:
                pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif k == 2:
            i -= 1
        else:
            j -= 1
    return pairs[::-1]


def _heard(model, audio, lo: float, hi: float, lang: str) -> list:
    """切出 lo～hi 單獨辨識，回傳 [(字, 開始, 結束)]（時間已加回 lo）。"""
    import align
    seg = audio[int(lo * align.SR):int(hi * align.SR)]
    result = model.transcribe(seg, language=lang, word_timestamps=True, condition_on_previous_text=False)
    norm = to_traditional if lang == "zh" else (lambda s: s)
    out = []
    for w in align._result_words(result):
        toks = [norm(t) for t in align._tokens(w.word)]
        step = (w.end - w.start) / max(1, len(toks))
        out += [(t, lo + w.start + step * k, lo + w.start + step * (k + 1)) for k, t in enumerate(toks)]
    return out


def _runs(lines: list, heard: list, lo: float, hi: float) -> list:
    """
    段內辨識結果裡找歌詞「連續 ≥ MIN_RUN 句」對上的部分，回傳要補的 Line（推估的 start/end、還沒有 words）。
    找到一段就遮掉用過的辨識字再找，最多 MAX_ROUNDS 次。
    """
    import align
    norm = to_traditional
    lyric, first = [], []  # lyric：(句子位置, 字)；first：每句第一個字在 lyric 裡的位置
    for i, l in enumerate(lines):
        first.append(len(lyric))
        lyric += [(i, norm(t)) for t in align._tokens(l.text)]
    toks = [t for t, _, _ in heard]
    found = []
    for _ in range(MAX_ROUNDS):
        hits = {}
        for a, b in _local_match([t for _, t in lyric], toks):
            hits.setdefault(lyric[a][0], []).append((a, b))
        ok = sorted(i for i, h in hits.items() if len(h) >= max(2, LINE_HIT * len(align._tokens(lines[i].text))))
        # 連續的句子（中間只隔沒有字的空行也算連續），取對上字數最多的一串
        runs, cur = [], []
        for i in ok:
            if cur and any(align._tokens(lines[k].text) for k in range(cur[-1] + 1, i)):
                runs.append(cur)
                cur = []
            cur.append(i)
        if cur:
            runs.append(cur)
        runs = [r for r in runs if len(r) >= MIN_RUN and sum(len(hits[i]) for i in r) >= MIN_TOKENS]
        if not runs:
            break
        run = max(runs, key=lambda r: sum(len(hits[i]) for i in r))
        used = [b for i in run for _, b in hits[i]]
        for i in run:
            h, n = hits[i], len(align._tokens(lines[i].text))
            # 句首／句尾沒對上的字，用這句的平均字長往外推（同 align._anchor_lines）
            per = (heard[h[-1][1]][2] - heard[h[0][1]][1]) / (h[-1][0] - h[0][0] + 1)
            start = max(lo, heard[h[0][1]][1] - (h[0][0] - first[i]) * per)
            end = min(hi, heard[h[-1][1]][2] + (first[i] + n - 1 - h[-1][0]) * per)
            if end > start and end - start <= 4.0 + 1.2 * n:
                found.append(Line(lines[i].text, start, end))
        for b in range(min(used), max(used) + 1):
            toks[b] = None
    return sorted(found, key=lambda l: l.start)


def expand(model, vocals, lines: list, lang: str):
    """
    對齊後的 lines（含沒唱的句子）→ (lines, 補上的句數)。不觸發時原樣回傳同一個 list。
    補上的句子在推估時間前後 1 秒內逐字對齊；對不上的平均分配並標 confident=False。原本的句子不動
    （只有和補上的句子重疊時，前一句句尾會被截短）。
    """
    import whisper
    import align
    audio = whisper.load_audio(str(vocals))
    db = align._vocal_db(audio)
    rate, gaps = _gaps(lines, align._active(db))
    gaps = [g for g in gaps if g[2] >= GAP_MIN]
    if rate >= COVER_MAX or not gaps:
        return lines, 0
    log(f"展開重複段落：人聲涵蓋率 {rate:.0%}，{len(gaps)} 段沒有歌詞的人聲，辨識這幾段找重複的歌詞")
    inserts = []  # (前一句位置, [Line])
    for lo, hi, voiced, prev_i in gaps:
        new = _runs(lines, _heard(model, audio, lo, hi, lang), lo, hi)
        if not new:
            log(f"  {lo // 60:.0f}:{lo % 60:04.1f}～{hi // 60:.0f}:{hi % 60:04.1f}（人聲 {voiced:.0f} 秒）：和歌詞不像，維持原樣")
            continue
        out = []
        for l in new:
            s0 = max(0.0, l.start - 1.0)
            ws, complete = align._align_buckets(model, audio[int(s0 * align.SR):int((l.end + 1.0) * align.SR)],
                                                [l], lang, offset=s0)[0]
            if complete and ws and not align._mostly_instant(ws) and abs(ws[0].start - l.start) <= 2.0:
                out.append(align._from_words(l.text, ws))
            else:
                out.append(Line(l.text, l.start, l.end, even_words(l), confident=False))
        log(f"  {lo // 60:.0f}:{lo % 60:04.1f}～{hi // 60:.0f}:{hi % 60:04.1f}（人聲 {voiced:.0f} 秒）：補 {len(out)} 句"
            + "".join(f"\n    {l.start // 60:.0f}:{l.start % 60:05.2f} {l.text}" for l in out))
        inserts.append((prev_i, out))
    if not inserts:
        return lines, 0
    result, only, added = list(lines), set(), 0
    for prev_i, out in sorted(inserts, key=lambda x: -x[0]):  # 從後面插，前面的位置才不會跑掉
        result[prev_i + 1:prev_i + 1] = out
        added += len(out)
    pos = {id(l) for _, out in inserts for l in out}
    only = {i for i, l in enumerate(result) if id(l) in pos}
    align._trim_overlaps(result, only)
    align._snap_onsets(result, db, only)
    log(f"展開重複段落：共補 {added} 句")
    return result, added
