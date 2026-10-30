"""
上網找歌詞（017）：用自架 SearXNG 搜尋，不限定網站，從結果網頁擷取像歌詞的段落當候選。
挑哪一份交給 lyrics.pick（和辨識結果或目前的歌詞比對），這裡只負責找與擷取。
個人使用限定（憲章原則 I）：商用模式不呼叫。
"""
import html
import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, wait
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

from common import log

SEARXNG_URL = os.environ.get("SEARXNG_URL", "")
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36",
      "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.6"}
MAX_PAGES = 10       # 每次查詢抓前幾個網頁
PAGE_TIMEOUT = 8     # 每頁秒數
TOTAL_TIMEOUT = 25   # 搜尋＋抓頁總秒數（FR-1707）
MAX_BYTES = 2_000_000

# 不用中國網站（開發者決定，016）；影音、社群頁沒有可擷取的歌詞文字；KKBOX、Musixmatch 對程式回空頁
_SKIP = ("baidu.com", "kugeci.com", "migeci.com", "qq.com", "163.com", "126.net", "kugou.com", "kuwo.cn", "douban.com", "bilibili.com", "xiami.com",
         "zhihu.com", "sohu.com", "sina.com", "weibo.com", "douyin.com", "toutiao.com", "migu.cn", "xiaohongshu.com",
         "youtube.com", "youtu.be", "spotify.com", "facebook.com", "threads.com", "threads.net", "instagram.com",
         "tiktok.com", "x.com", "twitter.com", "kkbox.com", "musixmatch.com", "apple.com", "smule.com", "lrclib.net")


def enabled() -> bool:
    """自架的人可以用 WEB_LYRICS=0 關掉（018）；沒有搜尋引擎也等於關掉。"""
    return bool(SEARXNG_URL) and os.environ.get("WEB_LYRICS", "1") != "0"


def blocked(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return not host or host.endswith(".cn") or host.startswith("cn.") or any(host == d or host.endswith("." + d) for d in _SKIP)


def search(query: str, timeout: float = 10) -> list:
    """SearXNG 結果網址（已排除不用的網站，去重、保持順序）。"""
    url = f"{SEARXNG_URL.rstrip('/')}/search?" + urlencode({"q": query, "format": "json", "language": "zh-TW"})
    with urlopen(Request(url, headers={"User-Agent": "AnyBacking"}), timeout=timeout) as r:
        results = json.load(r).get("results", [])
    seen, out = set(), []
    for it in results:
        u = it.get("url") or ""
        if u and u not in seen and not blocked(u):
            seen.add(u)
            out.append(u)
    return out


def _fetch(url: str) -> str:
    with urlopen(Request(url, headers=UA), timeout=PAGE_TIMEOUT) as r:
        if "html" not in (r.headers.get("Content-Type") or "html"):
            return ""
        raw = r.read(MAX_BYTES)
        charset = r.headers.get_content_charset()
    m = re.search(rb"<meta[^>]+charset=[\"']?([\w-]+)", raw[:4000], re.I)
    for enc in (charset, m and m.group(1).decode("ascii", "ignore"), "utf-8"):
        if enc:
            try:
                return raw.decode(enc)
            except (LookupError, UnicodeDecodeError):
                pass
    return raw.decode("utf-8", "replace")


_BLOCK_TAGS = r"p|div|li|ul|ol|tr|td|table|h\d|section|article|pre|header|footer|nav|aside|main|blockquote|dd|dt"
_NOT_LYRIC = re.compile(r"https?://|www\.|©|copyright|\||›|»|→|\.com|\.tw|點閱|瀏覽|留言|分享|登入|下一首|上一首|"
                        r"相關歌曲|熱門|排行|搜尋|廣告|cookie", re.I)


def page_lines(page: str) -> list:
    """HTML → 一行一行的文字；換行、區塊標籤當分行，區塊邊界留空行。"""
    page = re.sub(r"(?is)<(script|style|noscript|head|svg|template|iframe|select|button|form)\b.*?</\1\s*>", "\n", page)
    page = re.sub(r"(?is)<!--.*?-->", "", page)
    page = re.sub(r"(?i)<br\s*/?>", "\n", page)
    page = re.sub(rf"(?i)</?(?:{_BLOCK_TAGS})\b[^>]*>", "\n\n", page)
    text = html.unescape(re.sub(r"<[^>]+>", "", page)).replace("　", " ").replace("\xa0", " ")
    return [re.sub(r"[ \t]+", " ", l).strip() for l in text.split("\n")]


def _lyric_like(line: str) -> bool:
    return 0 < len(line) <= 40 and not _NOT_LYRIC.search(line) and bool(re.search(r"\w", line))


def blocks(lines: list, keep: int = 3, min_lines: int = 8) -> list:
    """
    連續像歌詞的短行組成一段（中間最多隔兩個空行，段落之間的空行保留成一個），
    依文字量取最長的 keep 段。導覽選單也長得像歌詞，所以留多段、交給比對挑。
    """
    out, cur, gap = [], [], 0

    def close():
        body = [l for l in cur if l]
        if len(body) >= min_lines:
            out.append("\n".join(cur).strip())

    for l in lines:
        if not l:
            gap += 1
            if gap > 3:
                close()
                cur = []
            elif cur and cur[-1]:
                cur.append("")
            continue
        gap = 0
        if _lyric_like(l):
            cur.append(l)
        else:
            close()
            cur = []
    close()
    out.sort(key=lambda b: -len(re.sub(r"\s", "", b)))
    return out[:keep]


def candidates(queries: list) -> list:
    """
    依序用查詢字串搜尋，平行抓前幾個網頁並擷取段落，回傳 [{"text", "url"}]（每頁最多 3 段）。
    整段限時 TOTAL_TIMEOUT 秒；任何錯誤都只記 log、回傳已有的結果。
    """
    if not enabled():
        return []
    t0, urls = time.monotonic(), []
    for q in queries:
        try:
            for u in search(q):
                if u not in urls:
                    urls.append(u)
        except Exception as e:
            log(f"  網路搜尋失敗（{q}）：{e}")
        if len(urls) >= MAX_PAGES or time.monotonic() - t0 > TOTAL_TIMEOUT / 2:
            break
    urls = urls[:MAX_PAGES]
    if not urls:
        return []
    out = []
    pool = ThreadPoolExecutor(max_workers=len(urls))
    futs = {pool.submit(_fetch, u): u for u in urls}
    done, _ = wait(futs, timeout=max(1.0, TOTAL_TIMEOUT - (time.monotonic() - t0)))
    pool.shutdown(wait=False, cancel_futures=True)
    for f in futs:   # 照搜尋結果的順序
        if f not in done:
            continue
        try:
            for b in blocks(page_lines(f.result())):
                out.append({"text": b, "url": futs[f]})
        except Exception as e:
            log(f"  抓不到 {futs[f]}：{e}")
    log(f"  網路搜尋：{len(urls)} 個網頁、{len(out)} 段候選（{time.monotonic() - t0:.1f} 秒）")
    return out
