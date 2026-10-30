#!/usr/bin/env bash
# =============================================================================
# 還原演練（001）—— 主機 git-bash 執行。把一份備份還原到臨時環境並比對 MANIFEST，不碰運行中的資料。
#   1) db.dump     → 臨時 postgres 容器 pg_restore，比對歌曲數、id 與狀態
#   2) storage.tar → 解開到暫存資料夾，每首 done 的歌都要有 playback.m4a 等播放用檔案
# 任一項不一致 → 退出碼非 0。
#
# 用法：bash deploy/backup/restore-drill.sh [備份資料夾]（預設 BACKUP_DIR 下最新的一份）
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BACKUP_DIR="${BACKUP_DIR:-$(grep '^BACKUP_DIR=' "$ROOT/.env" | cut -d= -f2-)}"
SRC="${1:-$(ls -1d "$BACKUP_DIR"/auto/*/ 2>/dev/null | grep -v '\.partial/$' | sort | tail -1)}"
SRC="${SRC%/}"
[ -f "$SRC/db.dump" ] || { echo "找不到備份：$SRC"; exit 1; }
echo "=== 還原演練：$SRC ==="

PG=anybacking_restoredrill
WORK="$(mktemp -d)"
FAIL=0
cleanup(){ docker rm -f "$PG" >/dev/null 2>&1; rm -rf "$WORK"; }
trap cleanup EXIT

ok(){ echo "  ✓ $*"; }
bad(){ echo "  ✗ $*"; FAIL=1; }

echo "--- [1/2] 資料庫 ---"
docker rm -f "$PG" >/dev/null 2>&1
docker run -d --name "$PG" -e POSTGRES_PASSWORD=drill -e POSTGRES_USER=anybacking -e POSTGRES_DB=anybacking \
  postgres:16.4-alpine >/dev/null
for i in $(seq 1 30); do docker exec "$PG" pg_isready -U anybacking >/dev/null 2>&1 && break; sleep 1; done
sleep 2   # 第一次啟動時 initdb 會重啟一次
docker cp "$SRC/db.dump" "$PG:/tmp/db.dump" >/dev/null
docker exec "$PG" sh -c 'pg_restore -U anybacking -d anybacking --no-owner /tmp/db.dump' || bad "pg_restore 有錯誤"

q(){ docker exec "$PG" psql -U anybacking -d anybacking -AtqF ' ' -c "$1"; }
WANT_SONGS=$(grep '^songs=' "$SRC/MANIFEST.txt" | cut -d= -f2)
GOT_SONGS=$(q "select count(*) from songs" | tr -d '\r')
[ "$WANT_SONGS" = "$GOT_SONGS" ] && ok "歌曲數 $GOT_SONGS" || bad "歌曲數 MANIFEST=$WANT_SONGS 還原=$GOT_SONGS"
WANT_IDS=$(sed -n '/^--- id/,$p' "$SRC/MANIFEST.txt" | tail -n +2 | tr -d '\r')
GOT_IDS=$(q "select id, status, playback_ready from songs order by created_at" | tr -d '\r')
[ "$WANT_IDS" = "$GOT_IDS" ] && ok "每首 id／狀態／播放檔旗標一致" || bad "id 清單不一致"
q "select count(*) from devices" >/dev/null && ok "devices 表可查詢" || bad "devices 表不存在"

echo "--- [2/2] 歌曲檔案 ---"
tar --force-local -xf "$SRC/storage.tar" -C "$WORK" || bad "tar 解不開"   # C:/… 不是遠端主機
WANT_FILES=$(grep '^tar_files=' "$SRC/MANIFEST.txt" | cut -d= -f2)
GOT_FILES=$(find "$WORK" -type f | wc -l | tr -d ' ')
[ "$WANT_FILES" = "$GOT_FILES" ] && ok "檔案數 $GOT_FILES" || bad "檔案數 MANIFEST=$WANT_FILES 解開=$GOT_FILES"
while read -r id status ready; do
  [ "$status" = done ] || continue
  need="instrumental.m4a vocals.m4a words.json lyrics.lrc"
  [ "$ready" = t ] && need="$need playback.m4a"
  for f in $need; do
    [ -s "$WORK/songs/$id/out/$f" ] || bad "$id 缺 out/$f"
  done
  ls "$WORK/songs/$id/upload/"source* >/dev/null 2>&1 || bad "$id 缺上傳原檔"
done <<< "$GOT_IDS"
[ $FAIL = 0 ] && ok "每首 done 的歌播放用檔案齊全"

echo
[ $FAIL = 0 ] && echo "=== 還原演練通過 ===" || echo "=== 還原演練失敗 ==="
exit $FAIL
