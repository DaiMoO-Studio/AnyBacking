#!/usr/bin/env bash
# 018：把私人 repo 整理成公開版的一個新 commit（不帶歷史）。
#   tools/publish/publish.sh <公開 repo 的資料夾> ["commit 訊息"]
# 1. 只複製 allowlist.txt 列的已提交檔案（git ls-files）；公開 repo 除了 .git 全部換掉
# 2. forbidden.txt 的私人字串（網域、路徑、Email…）與 gitleaks 機密掃描，任何命中就中止、不 commit
# 3. 以 PUBLISH_AUTHOR（預設 DaiMoO Studio）commit；push 由開發者自己決定
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
DEST=${1:?用法：tools/publish/publish.sh <公開 repo 的資料夾> [commit 訊息]}
MSG=${2:-"Update from private repo $(git rev-parse --short HEAD)"}
AUTHOR=${PUBLISH_AUTHOR:-"DaiMoO Studio <noreply@daimoo.invalid>"}
HERE=tools/publish

[ -d "$DEST/.git" ] || { echo "✗ $DEST 不是 git repo（先 git init 或 clone 公開 repo）"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "✗ 私人 repo 有沒提交的改動，先 commit"; exit 1; }

files=()
while IFS= read -r f; do
    keep=0
    while IFS= read -r p; do
        case "$p" in ''|'#'*) continue ;; '!'*) case "$f" in ${p#!}) keep=0 ;; esac ;; *) case "$f" in $p) keep=1 ;; esac ;; esac
    done < "$HERE/allowlist.txt"
    [ "$keep" = 1 ] && files+=("$f")
done < <(git ls-files)
echo "匯出 ${#files[@]} 個檔案 → $DEST"

find "$DEST" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
for f in "${files[@]}"; do mkdir -p "$DEST/$(dirname "$f")"; cp -p "$f" "$DEST/$f"; done

pat=$(grep -vE '^\s*(#|$)' "$HERE/forbidden.txt" | paste -sd'|')
if hits=$(cd "$DEST" && grep -rIinE --exclude-dir=.git "$pat" .); then
    echo "✗ 公開版出現私人字串，中止："; echo "$hits" | cut -c1-200; exit 1
fi
echo "✓ 私人字串 0 筆"

MSYS_NO_PATHCONV=1 docker run --rm -v "$(cd "$DEST" && pwd -W 2>/dev/null || pwd):/repo" zricethezav/gitleaks:v8.21.2 \
    detect --no-git --source /repo --no-banner --redact || { echo "✗ gitleaks 找到疑似機密，中止"; exit 1; }
echo "✓ gitleaks 0 筆"

git -C "$DEST" add -A
if git -C "$DEST" diff --cached --quiet; then echo "沒有變更"; exit 0; fi
git -C "$DEST" -c user.name="${AUTHOR% <*}" -c user.email="$(echo "$AUTHOR" | sed 's/.*<\(.*\)>.*/\1/')" commit -q -m "$MSG"
echo "✓ 已 commit：$(git -C "$DEST" log --oneline -1)（還沒 push）"
