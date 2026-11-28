#!/usr/bin/env bash
# 把上游 HyperPods 的 os4-dev 合进本仓库 custom，并自检桥接改动是否还在。
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

BEFORE="$(git rev-parse HEAD)"
echo "==> fetch upstream $BRANCH"
git fetch upstream "$BRANCH"
echo "==> 上游最新: $(git log -1 --format='%h %s' upstream/$BRANCH)"
echo "==> 新增 $(git rev-list --count "$BEFORE"..upstream/$BRANCH) 个提交"

echo
echo "==> 合并进 $(git rev-parse --abbrev-ref HEAD)"
if ! git merge --no-edit "upstream/$BRANCH"; then
  echo
  echo "!! 有冲突，需要手工解决："
  git diff --name-only --diff-filter=U | sed 's/^/   /'
  echo
  echo "   fork 侧文件（冲突时一律保留本仓库版本，只在必要时吸收上游的逻辑）："
  echo "     app/src/main/java/moe/dsh/hpb/                  桥接主体，上游没有"
  echo "     app/src/main/java/moe/chenxy/hyperpods/hook/HookEntry.kt"
  echo "     app/src/main/res/values/arrays.xml"
  echo
  echo "   上游改动需要吸收时，重点看这几个文件里 fork 加了什么："
  echo "     L2CAPController.kt      ANC 编码、佩戴检测落盘、pushStateToSystem"
  echo "     MiBluetoothToastHook.kt islandShownFor、电量顺序"
  echo "     PodsIslandData.kt       电量顺序"
  echo "     AppPreferences.kt       publish 双发"
  echo "     MainUI.kt               ACTION_APP_SETTINGS_CHANGED"
  echo
  echo "   处理完 git add 后 git commit，再跑 ./tools/verify-fork.sh"
  exit 1
fi

echo
echo "==> 合并完成，自检"
if ! bash tools/verify-fork.sh; then
  echo
  echo "!! 自检未通过，先补回缺失改动再推"
  exit 1
fi

echo
echo "==> 下一步："
echo "   1. git push fork custom"
echo "   2. 触发 AbelDuan/hyperpods-ci 构建，装包后同步 LSPosed apk_path，再软重启"
echo "      （细节见 FORK.md「每次装包必须同步 LSPosed 的 apk_path」）"
