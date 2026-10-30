#!/bin/sh
# =============================================================================
# 每日備份（001）—— 在 backup 容器內執行（postgres:16.4-alpine）。
#
#   /backups/auto/<YYYYMMDD-HHMMSS>/
#     db.dump        pg_dump -Fc 整個 anybacking 資料庫
#     storage.tar    songs/<id>/upload、songs/<id>/out（不含 work/ 中間檔；音訊本來就壓縮過，不 gzip）
#     MANIFEST.txt   歌曲數、id 清單、tar 檔案數與大小、還原方式
#
# 進行中寫在 <時間>.partial/，全部成功才改名；失敗就刪掉 partial，在 /backups/backup.log 記一行，隔天再試。
# 保留最近 KEEP 份（預設 14）。手動執行：docker compose exec backup sh /scripts/backup.sh
# =============================================================================
set -eu

DEST="${BACKUP_DEST:-/backups}"
KEEP="${KEEP:-14}"
STORAGE="${STORAGE_DIR:-/data/storage}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$DEST/auto/$TS"
TMP="$OUT.partial"

log(){ echo "[backup $(date +%H:%M:%S)] $*"; }

fail(){
  rm -rf "$TMP"
  log "失敗：$1"
  echo "$TS FAILED $1" >> "$DEST/backup.log" || true
  exit 1
}

mkdir -p "$DEST/auto" || fail "無法建立 $DEST/auto"
rm -rf "$TMP"
mkdir "$TMP" || fail "無法建立 $TMP"

log "pg_dump …"
pg_dump -Fc -f "$TMP/db.dump" || fail "pg_dump"

log "打包歌曲檔案 …"
cd "$STORAGE" || fail "找不到 $STORAGE"
LIST="$TMP/files.txt"
find songs -mindepth 2 -maxdepth 2 \( -name upload -o -name out \) -type d 2>/dev/null | sort > "$LIST" || true
if [ -s "$LIST" ]; then
  tar -cf "$TMP/storage.tar" -T "$LIST" || fail "tar"
else
  tar -cf "$TMP/storage.tar" -T /dev/null || fail "tar"
fi
rm -f "$LIST"
NFILES=$(tar -tf "$TMP/storage.tar" | grep -vc '/$' || true)

SONGS=$(psql -Atqc "select count(*) from songs") || fail "psql"
DONE=$(psql -Atqc "select count(*) from songs where status = 'done'") || fail "psql"
{
  echo "隨唱機 AnyBacking 自動備份  $TS"
  echo "===================================="
  echo "songs=$SONGS"
  echo "done=$DONE"
  echo "tar_files=$NFILES"
  echo "db.dump      $(wc -c < "$TMP/db.dump") bytes"
  echo "storage.tar  $(wc -c < "$TMP/storage.tar") bytes"
  echo "還原演練：bash deploy/backup/restore-drill.sh <此資料夾>（主機 git-bash）"
  echo "--- id status playback_ready ---"
  psql -AtqF ' ' -c "select id, status, playback_ready from songs order by created_at"
} > "$TMP/MANIFEST.txt" || fail "MANIFEST"

mv "$TMP" "$OUT" || fail "改名"
SIZE=$(du -sk "$OUT" | awk '{print $1*1024}')
echo "$TS ok $OUT $SIZE" >> "$DEST/backup.log"
log "完成 → $OUT（$SIZE bytes）"

# ── 保留輪替：只算完整的備份（partial 不算）────────────────────────────
ls -1d "$DEST"/auto/*/ 2>/dev/null | grep -v '\.partial/$' | sort -r | tail -n +$((KEEP + 1)) | while read -r old; do
  log "刪除舊備份 $old"
  rm -rf "$old"
done
