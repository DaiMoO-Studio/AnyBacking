"""
網頁服務用的處理流程：常駐模型（Engine），一次處理一首使用者上傳的歌，產出網頁播放器要的檔案。

個人使用模式（憲章 2.0.0，009）：去人聲用 SEP_MODEL 選 Kim（預設，可商用）或 viperx BS-RoFormer（略乾淨但慢 3 倍多），
沒有歌詞時先查 LRCLIB（016 起也收純文字歌詞）；網址下載在 worker.py。若日後上架，要回到只用可商用元件（cli.COMMERCIAL_SEP_MODELS、不查第三方歌詞）。
輸出（都在 workdir）：
- instrumental.m4a、vocals.m4a：播放器分兩軌即時混音（原唱開關、音量）
- playback.m4a：App 用的 4 聲道檔（伴奏＋人聲在同一個檔，永遠同步；media.encode_playback）
- words.json：逐字時間（含 confident），沒唱的句子不在裡面
- lyrics.lrc：對齊結果，沒唱的句子沒有時間（給之後的校正功能）
"""
import os
import shutil
import time
from pathlib import Path

import align
import lyrics
import media
from common import Line, detect_lang, load_lines, log, media_duration, save_lines, save_waveform
from cli import COMMERCIAL_SEP_MODELS, DEFAULT_SEP_MODEL
from messages import UserError

SEP_MODELS = (DEFAULT_SEP_MODEL, *COMMERCIAL_SEP_MODELS)   # viperx（沒有授權聲明，個人使用）、Kim（權重 MIT）


class PipelineError(UserError):
    """可以直接顯示給使用者看的錯誤（代碼＋參數，014）。"""


class Engine:
    def __init__(self, model_dir: Path, sep_model: str = None, whisper_model: str = None):
        sep_model = sep_model or os.environ.get("SEP_MODEL") or COMMERCIAL_SEP_MODELS[0]
        if sep_model not in SEP_MODELS:
            raise ValueError(f"去人聲模型只能是：{', '.join(SEP_MODELS)}")
        self.model_dir = Path(model_dir)
        self.model_dir.mkdir(parents=True, exist_ok=True)
        self.sep_model = sep_model
        self.device = align.get_device()
        self.whisper_name = whisper_model or ("large-v3" if self.device == "cuda" else "small")
        self._whisper = None
        self.found = None   # 最近一次處理自動找到的歌詞（lyrics.find_lyrics），worker 拿來決定歌名（016）
        self.lyrics_url = None   # 自動找到的歌詞取自哪裡（017，web 是網頁網址）

    # 8 GB 的顯示卡放不下 Whisper large-v3＋去人聲模型，同時放會溢出到系統記憶體、慢幾十倍。
    # 所以輪流用：Whisper 常駐但去人聲時先搬到 CPU（搬移 1～2 秒，比重新載入的 45 秒快）；
    # 去人聲模型每次重新載入（約 9 秒），用完就釋放。
    @property
    def whisper(self):
        if self._whisper is None:
            self._whisper = align.load_whisper(self.whisper_name, self.device)
        elif self.device == "cuda" and next(self._whisper.parameters()).device.type != "cuda":
            self._whisper.to("cuda")
        return self._whisper

    def _free_gpu(self) -> None:
        if self.device != "cuda":
            return
        import gc
        import torch
        if self._whisper is not None:
            self._whisper.to("cpu")
        gc.collect()  # 先回收用完的去人聲模型等物件，empty_cache 才釋放得掉它們的顯示卡記憶體
        torch.cuda.empty_cache()

    def warm_up(self) -> None:
        """
        啟動時先載入 Whisper、下載好去人聲模型，第一首歌不用等。
        這台電腦的顯示卡和其他專案（ComfyUI 等）共用，所以閒置時一律不佔顯示卡：Whisper 放系統記憶體。
        """
        from audio_separator.separator import Separator
        Separator(output_dir="/tmp", model_file_dir=str(self.model_dir), output_format="WAV").load_model(
            model_filename=self.sep_model)
        self.whisper
        self.release_gpu()

    def release_gpu(self) -> None:
        """處理完一首、閒置等待時呼叫：把顯示卡記憶體全部還給其他程式。"""
        self._free_gpu()

    def process(self, src: Path, workdir: Path, lyrics_text: str = None, meta: dict = None) -> dict:
        """
        處理一首歌，回傳摘要（寫進資料庫）。lyrics_text：LRC、純文字或 None（先查 LRCLIB，再 AI 辨識）。
        meta：找歌詞用的資訊（網址上傳是影片的 title／track／artist／channel，檔案上傳是 {"title": 歌名}）。
        """
        workdir.mkdir(parents=True, exist_ok=True)
        audio = workdir / "audio.wav"
        try:
            media.extract_audio(src, audio)
        except Exception as e:
            raise PipelineError("audio_unreadable") from e
        duration = media_duration(audio)
        if duration < 10:
            raise PipelineError("audio_too_short", seconds=10)

        self._free_gpu()
        media.separate(audio, workdir, self.model_dir, self.sep_model,   # 不快取：用完釋放顯示卡記憶體
                       overlap=2 if self.sep_model == DEFAULT_SEP_MODEL else None)   # viperx 太慢，見 media.separate
        inst, vocals = workdir / "instrumental.wav", workdir / "vocals.wav"
        leak = media.vocal_leak_db(inst, vocals)
        log(f"人聲殘留 {leak:.1f} dB")

        self.found = self.lyrics_url = None
        lines, lang, source = self._lines(vocals, workdir, duration, lyrics_text, meta)
        shown = [l for l in lines if l.words]
        save_lines(shown, workdir / "words.json")

        media.encode_web(inst, workdir / "instrumental.m4a")
        media.encode_web(vocals, workdir / "vocals.m4a")
        media.encode_playback(inst, vocals, workdir / "playback.m4a")
        save_waveform(media.waveform(inst, vocals), workdir / "waveform.json")
        return {
            "duration": round(duration, 2),
            "lang": lang,
            "residue_db": round(leak, 1),
            "sep_model": self.sep_model,
            "lyrics_source": source,
            "lyrics_url": self.lyrics_url,
            "n_lines": len(shown),
            "n_unsure": sum(not l.confident for l in shown),
            "n_unsung": len(lines) - len(shown),
            "playback_ready": True,
        }

    def realign(self, out: Path, items: list, lang: str) -> dict:
        """
        歌詞校正（003）：依 App 送出的校正（correction.validate 整理過）重新對齊，不重新去人聲（用 out/vocals.m4a）。
        原地更新 out/words.json、out/lyrics.lrc；第一次校正前把自動結果另存成 *.auto.*。回傳摘要。
        """
        import correction
        vocals = out / "vocals.m4a"
        if not vocals.is_file():
            raise PipelineError("vocals_missing")
        words, lrc = out / "words.json", out / "lyrics.lrc"
        orig = correction.full_lines(load_lines(words), lrc.read_text(encoding="utf-8") if lrc.exists() else "")
        typed = []
        for it in items:
            o = orig[it["src"]] if it["src"] is not None else None
            kind = correction.classify(it, o)
            if kind == "keep":
                line = o
            elif kind == "text":  # 只改字：時間還是原本的，對不上時沿用原本是否推估
                line = Line(it["text"], o.start, o.end, confident=o.confident)
            else:  # pin 的句尾沿用原句長，給對齊範圍估計用
                length = o.end - o.start if o is not None and o.words else 0.0
                line = Line(it["text"], it["start"], it["start"] + length)
            typed.append((kind, line))
        t0 = time.time()
        lines = align.realign(self.whisper, vocals, typed, lang or detect_lang("".join(l.text for _, l in typed)))
        for it, l in zip(items, lines):  # 使用者設的時間、確認過的句子，時間就是使用者認可的，不再算推估
            if l.words and (it["pinned"] or it.get("confirmed")):
                l.confident = True
        return {
            **self._publish_lines(out, lines),
            "n_pinned": sum(it["pinned"] for it in items),
            "n_confirmed": sum(bool(it.get("confirmed")) and not it["pinned"] for it in items),
            "n_edited": sum(k == "text" or it["edited"] for it, (k, _) in zip(items, typed)),
            "n_deleted": len(orig) - len({it["src"] for it in items if it["src"] is not None}),
            "seconds": round(time.time() - t0, 1),
        }

    def relyric(self, out: Path, workdir: Path, text: str, duration: float) -> dict:
        """
        換一份歌詞（004）：用 out/vocals.m4a（不重新去人聲）走首次處理同一套歌詞解析與對齊（_lines），整份取代 out 的歌詞。
        workdir 只放暫存，結束就刪。回傳摘要。
        """
        vocals = out / "vocals.m4a"
        if not vocals.is_file():
            raise PipelineError("vocals_missing")
        t0 = time.time()
        workdir.mkdir(parents=True, exist_ok=True)
        try:
            lines, lang, _ = self._lines(vocals, workdir, duration, text)
            if not any(l.words for l in lines):
                raise PipelineError("lyrics_no_match")
            summary = self._publish_lines(out, lines)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        return {**summary, "lang": lang, "timed": bool(lyrics.parse_lrc(text)), "seconds": round(time.time() - t0, 1)}

    @staticmethod
    def _publish_lines(out: Path, lines: list) -> dict:
        """校正、換歌詞的結果寫回 out：第一次前把自動結果另存成 *.auto.*，再原子取代（播放中的 App 不會讀到半個檔）。"""
        words, lrc = out / "words.json", out / "lyrics.lrc"
        for name in ("words.json", "lyrics.lrc"):
            auto = out / name.replace(".", ".auto.")
            if (out / name).exists() and not auto.exists():
                shutil.copyfile(out / name, auto)
        shown = [l for l in lines if l.words]
        save_lines(shown, words.with_suffix(".tmp"))
        lyrics.write_lrc(lines, lrc.with_suffix(".tmp"), {})
        words.with_suffix(".tmp").replace(words)
        lrc.with_suffix(".tmp").replace(lrc)
        return {"n_lines": len(shown), "n_unsure": sum(not l.confident for l in shown), "n_unsung": len(lines) - len(shown)}

    # LRCLIB 的 LRC 可能是別的版本（整首位移、段落不同）：照 LRC 時間對齊後推估句超過這個比例，就改用文字走純文字對齊
    LRCLIB_UNSURE_MAX = 0.5

    def _lines(self, vocals: Path, workdir: Path, duration: float, text: str, meta: dict = None):
        """回傳 (lines, lang, 歌詞來源 user／lrclib／asr)。"""
        lrc = workdir / "lyrics.lrc"
        if not text or not text.strip():
            # 只接受歌名出現在我們的歌名／影片標題裡的結果（只用歌手名查會拿到別首歌）
            hay = " ".join(str(meta.get(k) or "") for k in ("title", "track")) if meta else ""
            # 017：同歌手的 LRCLIB 同步歌詞直接用；其他（不分歌手的同步、純文字、網路上找到的）先辨識一次，挑和實際唱的內容最像的
            found, heard_lines, lang = None, None, None
            if hay.strip():
                queries = lyrics.build_queries(meta)
                found, cands = lyrics.lrclib_lyrics(queries, duration, must_match=hay, artist=meta.get("artist"))
                if not found:
                    heard_lines, lang = align.transcribe(self.whisper, vocals)
                    heard = lyrics.heard_tokens("\n".join(l.text for l in heard_lines))
                    found = lyrics.pick(cands + lyrics.web_candidates(queries), heard, lyrics.MIN_F1)
            self.found = found if found and found["source"] == "lrclib" else None   # 網址上傳沒填歌名時，worker 拿來當歌名（012、016）
            self.lyrics_url = (found or {}).get("url")
            if found:
                timed = bool(lyrics.parse_lrc(found["text"]))
                lines, lang = self._align_text(vocals, workdir, duration, found["text"])
                unsure = sum(not l.confident for l in lines) / max(1, len(lines))
                if timed and unsure > self.LRCLIB_UNSURE_MAX:
                    log(f"歌詞照時間對齊有 {unsure:.0%} 句是推估的，可能是別的版本，改用純文字對齊")
                    plain = "\n".join(l.text for l in lines)
                    lines, lang = self._align_text(vocals, workdir, duration, plain)
                lyrics.write_lrc(lines, lrc, {})
                return lines, lang, found["source"]
            lines, lang = (heard_lines, lang) if heard_lines is not None else align.transcribe(self.whisper, vocals)
            if not lines:
                raise PipelineError("asr_failed")
            lyrics.write_lrc(lines, lrc, {})
            return lines, lang, "asr"
        lines, lang = self._align_text(vocals, workdir, duration, text)
        lyrics.write_lrc(lines, lrc, {})
        return lines, lang, "user"

    def _align_text(self, vocals: Path, workdir: Path, duration: float, text: str):
        """LRC 或純文字 → 逐字對齊後的 lines（含這版沒唱的句子）。"""
        src = workdir / ("lyrics_input.lrc" if lyrics.parse_lrc(text) else "lyrics_input.txt")
        src.write_text(text, encoding="utf-8")
        lines, timed = lyrics.load_lyrics_file(src, duration)
        if not lines:
            raise PipelineError("lyrics_no_content")
        lang = detect_lang("".join(l.text for l in lines))
        return align.align_lines(self.whisper, vocals, lines, lang, timed), lang
