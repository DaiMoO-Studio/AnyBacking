#!/bin/sh
# backup 容器的常駐迴圈：每天 AT（台北時間，容器 TZ=CST-8）執行一次 backup.sh；失敗不中止，隔天再試。
AT="${BACKUP_AT:-03:30}"
echo "[backup] 排程：每天 $AT"
while true; do
  now=$(date +%s)
  next=$(date -d "$(date +%Y-%m-%d) $AT:00" +%s)
  [ "$next" -gt "$now" ] || next=$((next + 86400))
  sleep $((next - now))
  sh /scripts/backup.sh || echo "[backup] 失敗 $(date)"
done
