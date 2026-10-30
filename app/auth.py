"""
裝置金鑰：只有開發者核發過的裝置能使用 API（P3 帳號上線前）。

- 金鑰是 `ab_` 加 32 bytes 亂數，只在核發時顯示一次；資料庫只存 SHA-256（金鑰本身夠長，不需要慢雜湊）。
- 每個請求都查資料庫，撤銷立即生效。
- 錯誤嘗試依來源節流。Docker Desktop 下所有外部來源看起來都是同一個閘道 IP，所以**有效金鑰永遠放行**，
  只擋無效的請求。

命令列（在伺服器上）：
    docker compose exec api python /app/auth.py issue "My phone"
    docker compose exec api python /app/auth.py list
    docker compose exec api python /app/auth.py revoke "My phone"
"""
import hashlib
import secrets
import sys
import time

PREFIX = "ab_"
TOUCH_EVERY = 60          # last_used_at 最多每 60 秒更新一次

FAIL_LIMIT = 10           # 同一來源 FAIL_WINDOW 秒內失敗這麼多次
FAIL_WINDOW = 600
BLOCK_SECONDS = 900       # 就封鎖這麼久（只擋無效請求）


def new_key() -> str:
    return PREFIX + secrets.token_urlsafe(32)


def hash_key(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


def verify(conn, key: str):
    """有效就回傳裝置 id，否則 None。"""
    if not key or not key.startswith(PREFIX) or len(key) > 100:
        return None
    row = conn.execute(
        "SELECT id, last_used_at < now() - make_interval(secs => %s) OR last_used_at IS NULL AS stale "
        "FROM devices WHERE key_hash = %s AND revoked_at IS NULL", (TOUCH_EVERY, hash_key(key))).fetchone()
    if row is None:
        return None
    if row["stale"]:
        conn.execute("UPDATE devices SET last_used_at = now() WHERE id = %s", (row["id"],))
    return row["id"]


class Throttle:
    """記在 API 行程的記憶體裡（只有一個行程；重啟就清空）。"""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.fails = {}       # 來源 → 失敗時間
        self.until = {}       # 來源 → 封鎖到期

    def blocked(self, src: str) -> int:
        """封鎖中回傳剩餘秒數，否則 0。"""
        left = self.until.get(src, 0) - self.clock()
        if left <= 0:
            self.until.pop(src, None)
            return 0
        return int(left) + 1

    def fail(self, src: str) -> bool:
        """記一次失敗；這次剛好觸發封鎖就回傳 True。"""
        now = self.clock()
        recent = [t for t in self.fails.get(src, []) if now - t < FAIL_WINDOW] + [now]
        if len(recent) >= FAIL_LIMIT:
            self.fails.pop(src, None)
            self.until[src] = now + BLOCK_SECONDS
            return True
        self.fails[src] = recent
        return False


def _find(conn, who: str):
    if who.isdigit():
        return conn.execute("SELECT * FROM devices WHERE id = %s AND revoked_at IS NULL", (int(who),)).fetchone()
    return conn.execute("SELECT * FROM devices WHERE name = %s AND revoked_at IS NULL", (who,)).fetchone()


def main(argv):
    import db
    usage = "用法：auth.py issue <名稱> | list | revoke <名稱或 id>"
    if not argv:
        sys.exit(usage)
    conn = db.connect()
    db.init(conn)
    cmd = argv[0]
    if cmd == "issue" and len(argv) == 2:
        name = argv[1].strip()
        if not name:
            sys.exit("名稱不能是空的")
        if _find(conn, name):
            sys.exit(f"「{name}」已經有有效的金鑰；要重新核發請先 revoke")
        key = new_key()
        conn.execute("INSERT INTO devices (name, key_hash) VALUES (%s, %s)", (name, hash_key(key)))
        print(f"已核發「{name}」的金鑰（只顯示這一次，請直接設定到 App）：\n{key}")
    elif cmd == "list" and len(argv) == 1:
        rows = conn.execute("SELECT * FROM devices ORDER BY id").fetchall()
        if not rows:
            print("還沒有核發任何金鑰")
        t = lambda v: f"{v.astimezone():%Y-%m-%d %H:%M}"   # 容器 TZ（compose 設台北時間）
        for r in rows:
            state = f"已撤銷 {t(r['revoked_at'])}" if r["revoked_at"] else "有效"
            used = t(r["last_used_at"]) if r["last_used_at"] else "未使用"
            print(f"{r['id']:>3}  {r['name']}  核發 {t(r['created_at'])}  最後使用 {used}  {state}")
    elif cmd == "revoke" and len(argv) == 2:
        row = _find(conn, argv[1])
        if row is None:
            sys.exit(f"找不到有效的「{argv[1]}」")
        conn.execute("UPDATE devices SET revoked_at = now() WHERE id = %s", (row["id"],))
        print(f"已撤銷「{row['name']}」（id {row['id']}），立即生效")
    else:
        sys.exit(usage)


if __name__ == "__main__":
    main(sys.argv[1:])
