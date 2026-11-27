#!/usr/bin/env bash
# 把上游 HyperPods 的 os4-dev 合进本仓库 custom。
# 用法: ./tools/sync-upstream.sh [上游分支，默认 os4-dev]
set -euo pipefail
cd "$(dirname "$0")/.."

UPSTREAM_REPO="${UPSTREAM_REPO:-https://gh-proxy.com/https://github.com/Mo-SeTian/HyperPods.git}"
BRANCH="${1:-os4-dev}"

if ! git remote get-url upstream >/dev/null 2>&1; then
  git remote add upstream "$UPSTREAM_REPO"
else
  git remote set-url upstream "$UPSTREAM_REPO"
fi

echo "==> fetch upstream $BRANCH"
git fetch upstream "$BRANCH"

echo "==> 当前分支: $(git rev-parse --abbrev-ref HEAD)"
git merge --no-edit "upstream/$BRANCH" || {
  echo
  echo "!! 有冲突，需要手工解决："
  git diff --name-only --diff-filter=U | sed 's/^/   /'
  echo
  echo "   热点文件："
  echo "     app/src/main/java/moe/chenxy/hyperpods/hook/HookEntry.kt"
  echo "     app/src/main/res/values/arrays.xml"
  echo "   处理完 git add 后 git commit，再推 custom。"
  exit 1
}
echo "==> 合并完成，记得推 custom 并重建 APK"
