"""
網頁服務的資料庫（PostgreSQL）。工作佇列也直接用資料表：工作機用 FOR UPDATE SKIP LOCKED 搶工作，
不必另外架 Redis。
"""
import os

SCHEMA = """
CREATE TABLE IF NOT EXISTS songs (
    id          uuid PRIMARY KEY,
    title       text NOT NULL,
    status      text NOT NULL DEFAULT 'queued',   -- queued / processing / done / failed
    error       text,                             -- 給使用者看的失敗原因
    lyrics_text text,                             -- 使用者貼的歌詞（LRC 或純文字），沒有就 AI 辨識
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz,
    attempts    int NOT NULL DEFAULT 0,
    worker      text,
    duration    real,
    lang        text,
    residue_db  real,                             -- 人聲殘留（越低越乾淨），自動退點、提示用
    sep_model   text,
    n_lines     int,
    n_unsure    int,                              -- 時間是推估的句數（confident=false）
    n_unsung    int
);
CREATE INDEX IF NOT EXISTS songs_queue ON songs (created_at) WHERE status = 'queued';
-- 001：App 用的 4 聲道播放檔（out/playback.m4a）已產生
ALTER TABLE songs ADD COLUMN IF NOT EXISTS playback_ready boolean NOT NULL DEFAULT false;

-- 001：允許使用服務的裝置（P3 帳號上線前只有開發者自己的手機）；只存金鑰的 SHA-256
CREATE TABLE IF NOT EXISTS devices (
    id           serial PRIMARY KEY,
    name         text NOT NULL,
    key_hash     text NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    revoked_at   timestamptz
);
CREATE UNIQUE INDEX IF NOT EXISTS devices_active_name ON devices (name) WHERE revoked_at IS NULL;

-- 003：歌詞校正。佇列兼紀錄（校正內容只用來改善對齊參數）；同一首只會有一筆排隊中
CREATE TABLE IF NOT EXISTS corrections (
    id          serial PRIMARY KEY,
    song_id     uuid NOT NULL REFERENCES songs (id) ON DELETE CASCADE,
    status      text NOT NULL DEFAULT 'queued',   -- queued / processing / done / failed
    lines       jsonb NOT NULL,                   -- App 送出的完整歌詞（specs/backend/contracts/corrections.md）
    error       text,
    summary     jsonb,
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    finished_at timestamptz,
    worker      text
);
CREATE INDEX IF NOT EXISTS corrections_queue ON corrections (created_at) WHERE status = 'queued';
CREATE UNIQUE INDEX IF NOT EXISTS corrections_one_queued ON corrections (song_id) WHERE status = 'queued';
ALTER TABLE songs ADD COLUMN IF NOT EXISTS corrected_at timestamptz;
-- 004：換一份歌詞也走同一個佇列（kind = 'lyrics'，貼上的文字在 lyrics，lines 為 NULL）
ALTER TABLE corrections ADD COLUMN IF NOT EXISTS kind text NOT NULL DEFAULT 'correction';   -- correction / lyrics
ALTER TABLE corrections ADD COLUMN IF NOT EXISTS lyrics text;
ALTER TABLE corrections ADD COLUMN IF NOT EXISTS timed boolean;                              -- lyrics 是 LRC
ALTER TABLE corrections ALTER COLUMN lines DROP NOT NULL;

-- 005：「我需要更乾淨的版本」集氣（011 起不再使用：個人使用不收集意見；表保留舊資料）
CREATE TABLE IF NOT EXISTS residue_votes (
    song_id     uuid NOT NULL REFERENCES songs (id) ON DELETE CASCADE,
    device_id   int NOT NULL REFERENCES devices (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (song_id, device_id)
);

-- 009：個人使用模式——網址上傳（工作機下載）、歌詞來源（user／lrclib／asr）
ALTER TABLE songs ADD COLUMN IF NOT EXISTS source_url text;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS lyrics_source text;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS lyrics_url text;      -- 017：自動找到的歌詞取自哪個網頁（web）
-- 014：給使用者看的錯誤代碼與參數（messages.py；App 依代碼翻譯，error 留中文給舊紀錄）
ALTER TABLE songs ADD COLUMN IF NOT EXISTS error_code text;
ALTER TABLE songs ADD COLUMN IF NOT EXISTS error_params jsonb;
ALTER TABLE corrections ADD COLUMN IF NOT EXISTS error_code text;
ALTER TABLE corrections ADD COLUMN IF NOT EXISTS error_params jsonb;

-- 008：功能許願池與回報問題（011 起不再使用：個人使用不收集意見；表保留舊資料）
CREATE TABLE IF NOT EXISTS wishes (
    id          serial PRIMARY KEY,
    code        text NOT NULL UNIQUE,
    title       text NOT NULL,
    description text NOT NULL DEFAULT '',
    sort        int NOT NULL DEFAULT 100,
    hidden      boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO wishes (code, title, description, sort) VALUES
    ('scoring',  '唱歌評分', '唱完給分數，看看哪裡走音、哪裡搶拍', 10),
    ('phonetic', '注音／拼音／粵拼／日文假名標註', '歌詞上方標出讀音，不熟的語言也能唱', 20),
    ('record',   '錄下自己唱的版本', '把自己唱的錄起來回放（只存在自己的帳號裡）', 30),
    ('ios',      'iPhone 版', '在 iPhone、iPad 上也能用隨唱機', 40),
    ('cast',     '投放到電視（無線）', '不用接線，直接在電視上播歌詞和伴奏', 50)
ON CONFLICT (code) DO NOTHING;
CREATE TABLE IF NOT EXISTS wish_votes (
    wish_id     int NOT NULL REFERENCES wishes (id),
    device_id   int NOT NULL REFERENCES devices (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (wish_id, device_id)
);
CREATE TABLE IF NOT EXISTS wish_texts (
    id          serial PRIMARY KEY,
    device_id   int NOT NULL REFERENCES devices (id),
    text        text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS reports (
    id          serial PRIMARY KEY,
    device_id   int NOT NULL REFERENCES devices (id),
    kind        text NOT NULL,
    song_id     uuid REFERENCES songs (id) ON DELETE SET NULL,
    song_title  text,                              -- 送出時的歌名（刪歌後還看得懂）
    position    real,                              -- 播放位置（秒，原曲時間）
    text        text NOT NULL DEFAULT '',
    created_at  timestamptz NOT NULL DEFAULT now()
);
"""


def connect():
    import psycopg
    from psycopg.rows import dict_row
    return psycopg.connect(os.environ["DATABASE_URL"], row_factory=dict_row, autocommit=True)


def init(conn) -> None:
    # api 與 worker 同時啟動時都會跑 SCHEMA；ALTER TABLE 互搶鎖會死結（009 加欄位時遇到），所以一次只讓一個跑
    conn.execute("SELECT pg_advisory_lock(20261026)")
    try:
        conn.execute(SCHEMA)
    finally:
        conn.execute("SELECT pg_advisory_unlock(20261026)")


def claim(conn, worker: str):
    """搶一個排隊中的工作；沒有就回傳 None。"""
    return conn.execute("""
        UPDATE songs SET status = 'processing', started_at = now(), attempts = attempts + 1, worker = %s
        WHERE id = (SELECT id FROM songs WHERE status = 'queued' ORDER BY created_at
                    FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING *""", (worker,)).fetchone()


def requeue_stale(conn, worker: str) -> int:
    """工作機重開（電腦重開機、程式當掉）時，把它沒做完的工作（新歌和校正）放回佇列。"""
    n = conn.execute(
        "UPDATE songs SET status = 'queued' WHERE status = 'processing' AND worker = %s", (worker,)).rowcount
    # 同一首在它處理期間又送了新的校正時，舊的那筆直接作廢（新的已經在排隊）
    conn.execute("""UPDATE corrections c SET status = 'failed', finished_at = now(), error = '已被新的校正取代', error_code = 'correction_superseded'
                    WHERE status = 'processing' AND worker = %s AND EXISTS (
                        SELECT 1 FROM corrections q WHERE q.song_id = c.song_id AND q.status = 'queued')""", (worker,))
    return n + conn.execute(
        "UPDATE corrections SET status = 'queued' WHERE status = 'processing' AND worker = %s", (worker,)).rowcount


def submit_correction(conn, song_id: str, lines: list) -> int:
    """送出校正：這首已有排隊中的就取代它（以最新為準），否則新增一筆。回傳校正 id。"""
    from psycopg.types.json import Jsonb
    row = conn.execute("""UPDATE corrections SET kind = 'correction', lines = %s, lyrics = NULL, timed = NULL, created_at = now()
                          WHERE song_id = %s AND status = 'queued' RETURNING id""", (Jsonb(lines), song_id)).fetchone()
    if row is None:
        row = conn.execute("INSERT INTO corrections (song_id, lines) VALUES (%s, %s) RETURNING id",
                           (song_id, Jsonb(lines))).fetchone()
    return row["id"]


def submit_lyrics(conn, song_id: str, text: str, timed: bool) -> int:
    """換一份歌詞（004）：和校正共用佇列，這首已有排隊中的（不分種類）就取代它。回傳 id。"""
    row = conn.execute("""UPDATE corrections SET kind = 'lyrics', lines = NULL, lyrics = %s, timed = %s, created_at = now()
                          WHERE song_id = %s AND status = 'queued' RETURNING id""", (text, timed, song_id)).fetchone()
    if row is None:
        row = conn.execute("INSERT INTO corrections (song_id, kind, lyrics, timed) VALUES (%s, 'lyrics', %s, %s) RETURNING id",
                           (song_id, text, timed)).fetchone()
    return row["id"]


def claim_correction(conn, worker: str):
    """搶一個排隊中的校正（校正很短、使用者在等，工作機優先處理）；沒有就回傳 None。"""
    return conn.execute("""
        UPDATE corrections SET status = 'processing', started_at = now(), worker = %s
        WHERE id = (SELECT id FROM corrections WHERE status = 'queued' ORDER BY created_at
                    FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING *""", (worker,)).fetchone()


def finish_correction(conn, cid: int, song_id, summary: dict) -> None:
    from psycopg.types.json import Jsonb
    with conn.transaction():
        conn.execute("UPDATE corrections SET status = 'done', finished_at = now(), error = NULL, error_code = NULL, error_params = NULL, summary = %s "
                     "WHERE id = %s", (Jsonb(summary), cid))
        conn.execute("""UPDATE songs SET n_lines = %s, n_unsure = %s, n_unsung = %s, lang = coalesce(%s, lang),
                        corrected_at = now() WHERE id = %s""",
                     (summary["n_lines"], summary["n_unsure"], summary["n_unsung"], summary.get("lang"), song_id))


def fail_correction(conn, cid: int, err) -> None:
    """err：messages.UserError（代碼＋參數＋中文）。"""
    from psycopg.types.json import Jsonb
    conn.execute("UPDATE corrections SET status = 'failed', finished_at = now(), error = %s, error_code = %s, error_params = %s "
                 "WHERE id = %s", (str(err), err.code, Jsonb(err.params), cid))


def latest_correction(conn, song_id: str):
    return conn.execute("""SELECT id, kind, status, error, error_code, error_params, created_at, finished_at FROM corrections
                           WHERE song_id = %s ORDER BY id DESC LIMIT 1""", (song_id,)).fetchone()


def finish(conn, song_id, result: dict) -> None:
    cols = ", ".join(f"{k} = %({k})s" for k in result)
    conn.execute(f"UPDATE songs SET status = 'done', finished_at = now(), error = NULL, error_code = NULL, error_params = NULL, {cols} WHERE id = %(id)s",
                 {**result, "id": song_id})


def fail(conn, song_id, err) -> None:
    """err：messages.UserError（代碼＋參數＋中文）。"""
    from psycopg.types.json import Jsonb
    conn.execute("UPDATE songs SET status = 'failed', finished_at = now(), error = %s, error_code = %s, error_params = %s "
                 "WHERE id = %s", (str(err), err.code, Jsonb(err.params), song_id))
