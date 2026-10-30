"""
給使用者看的錯誤（014 多國語系）：每種錯誤一個穩定的代碼＋參數，App 依代碼顯示使用者的語言；
中文文字照舊附在回應的 detail／歌曲的 error（舊 App 與舊紀錄用）。
新增代碼時，App 的 res/values/strings.xml（英文）與 res/values-zh/strings.xml（中文）都要加 err_<代碼>，
`python tools/check_messages.py` 會檢查。契約：specs/backend/contracts/errors.md。
"""

MESSAGES = {
    # API
    "unauthorized": "未授權",
    "rate_limited": "嘗試次數過多，請稍後再試",
    "not_found": "找不到這首歌",
    "file_not_found": "找不到檔案",
    "link_expired": "連結已過期",
    "file_too_large": "檔案太大（上限 {max} MB）",
    "bad_format": "格式不正確",
    "url_invalid": "網址格式不正確",
    "url_unreadable": "讀不到這個網址的影片，請確認可以公開播放",
    "url_import_off": "這台伺服器沒有開放貼網址，請上傳檔案",
    "title_required": "請輸入歌名",
    "title_empty": "歌名不能是空的",
    "song_not_ready_fix": "這首歌還在處理中，完成後才能校正",
    "song_not_ready_lyrics": "這首歌還在處理中，完成後才能換歌詞",
    "lyrics_not_found": "找不到這首的同步歌詞，可以改搜尋用的歌名再試，或用「上網找歌詞」",
    # 校正與換歌詞的內容檢查（correction.py）
    "lyrics_empty": "歌詞是空的",
    "too_many_lines": "句數超過 {max}",
    "line_empty": "第 {n} 句是空的",
    "line_too_long": "第 {n} 句太長（上限 {max} 字）",
    "line_time_invalid": "第 {n} 句的時間不正確",
    "line_time_over": "第 {n} 句的時間超過歌曲長度",
    "line_time_order": "第 {n} 句的時間比前一句早",
    "line_src_invalid": "第 {n} 句的來源不正確",
    "lyrics_too_long": "歌詞太長（上限 {max} 字）",
    "lyrics_no_content": "歌詞裡沒有內容",
    # 處理失敗（工作機）
    "audio_unreadable": "讀不到這個檔案的聲音，請確認是影音檔",
    "audio_too_short": "這個檔案太短了（不到 {seconds} 秒）",
    "vocals_missing": "找不到這首歌的人聲音軌，請重新上傳",
    "lyrics_no_match": "對不上任何一句，請確認貼的是這首歌的歌詞",
    "asr_failed": "辨識不到歌詞，請貼上歌詞再試一次",
    "download_failed": "下載失敗，請確認網址可以公開播放",
    "video_too_long": "影片太長（上限 {minutes} 分鐘）",
    "upload_missing": "找不到上傳的檔案，請重新上傳",
    "playback_backfill_failed": "播放檔補產失敗，請刪除後重新上傳",
    "process_error": "處理時發生錯誤，我們會查看原因",
    "realign_error": "重新對齊時發生錯誤，請再送一次",
    "correction_superseded": "已被新的校正取代",
}


def text(code: str, params: dict = None) -> str:
    """代碼 → 中文文字。"""
    return MESSAGES[code].format(**(params or {}))


class UserError(Exception):
    """給使用者看的錯誤：代碼＋參數；str() 是中文文字。"""

    def __init__(self, code: str, **params):
        if code not in MESSAGES:
            raise KeyError(f"未知的錯誤代碼：{code}")
        self.code, self.params = code, params
        super().__init__(text(code, params))

    def body(self) -> dict:
        return {"detail": str(self), "code": self.code, "params": self.params}
