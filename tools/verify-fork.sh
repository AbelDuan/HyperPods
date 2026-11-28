#!/usr/bin/env bash
# 合并上游后自检：确认本仓库的桥接改动没有被冲突解决冲掉。
# 用法: ./tools/verify-fork.sh
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
check() {
    local desc="$1" file="$2" pattern="$3"
    if [ ! -f "$file" ]; then
        echo "  ✗ $desc —— 文件不存在: $file"
        fail=$((fail + 1))
        return
    fi
    if grep -q "$pattern" "$file"; then
        echo "  ✓ $desc"
    else
        echo "  ✗ $desc —— 在 $file 里找不到 /$pattern/"
        fail=$((fail + 1))
    fi
}

echo "== 桥接主体"
check "BridgeEntry 存在" app/src/main/java/moe/dsh/hpb/BridgeEntry.java "install(String pkg"
check "命令码直传（01=关闭 02=降噪）" app/src/main/java/moe/dsh/hpb/BridgeEntry.java 'return 1; // 命令码 01 = 关闭'
check "按钮码转命令码" app/src/main/java/moe/dsh/hpb/BridgeEntry.java "buttonToCommandCode"
check "佩戴检测 hook" app/src/main/java/moe/dsh/hpb/BridgeEntry.java "hookWearDetection"
check "air_wear_state 四位码" app/src/main/java/moe/dsh/hpb/BridgeEntry.java "air_wear_state"

echo "== Hook 接入"
check "HookEntry 调用 BridgeEntry" app/src/main/java/moe/chenxy/hyperpods/hook/HookEntry.kt "BridgeEntry.install"
check "xposedscope 含 settings" app/src/main/res/values/arrays.xml "com.android.settings"
check "manifest 声明悬浮窗权限" app/src/main/AndroidManifest.xml "SYSTEM_ALERT_WINDOW"

echo "== 佩戴检测同步"
check "放行 settings 广播" app/src/main/java/moe/chenxy/hyperpods/pods/L2CAPController.kt "ACTION_EAR_DETECTION_SWITCH_CHANGED"
check "广播后落盘 AppPreferences" app/src/main/java/moe/chenxy/hyperpods/pods/L2CAPController.kt "AppPreferences.save"
check "publish 同时发蓝牙与本应用" app/src/main/java/moe/chenxy/hyperpods/utils/AppPreferences.kt "BuildConfig.APPLICATION_ID)"
check "MainUI 注册设置广播" app/src/main/java/moe/chenxy/hyperpods/ui/MainUI.kt "ACTION_APP_SETTINGS_CHANGED"
check "MainUI 刷新耳检测开关" app/src/main/java/moe/chenxy/hyperpods/ui/MainUI.kt "earDetectionEnable.value = it.earDetection"

echo "== 超级岛与通知"
check "岛屿只弹一次" app/src/main/java/moe/chenxy/hyperpods/hook/MiBluetoothToastHook.kt "islandShownFor"
check "断开清标记" app/src/main/java/moe/chenxy/hyperpods/hook/MiBluetoothToastHook.kt "islandShownFor.remove"
check "通知电量单行（caseTail 拼接）" app/src/main/java/moe/chenxy/hyperpods/hook/MiBluetoothToastHook.kt "val caseTail ="
check "通知顺序 左右盒" app/src/main/java/moe/chenxy/hyperpods/hook/MiBluetoothToastHook.kt "leftEar + rightEar + caseTail"
check "岛屿顺序 左右盒" app/src/main/java/moe/chenxy/hyperpods/utils/PodsIslandData.kt "盒放最后"

echo "== 构建脚本"
check "CI 构建工作流在独立仓库" FORK.md "hyperpods-ci"

echo
if [ "$fail" -eq 0 ]; then
    echo "全部通过（$fail 项失败）"
    exit 0
fi
echo "$fail 项缺失，合并时丢代码了，按上面行号补回"
exit 1
