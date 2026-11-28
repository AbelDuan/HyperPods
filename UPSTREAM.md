# 上游更新后的合并与修复手册

上游 `Mo-SeTian/HyperPods` 的 `os4-dev` 更新后，照这个顺序走一遍即可。
所有命令都在仓库根目录执行。

---

## 0. 现状快照

| 项 | 值 |
|---|---|
| 本仓库 | `AbelDuan/HyperPods`，分支 `custom`（默认） |
| 上游 | `Mo-SeTian/HyperPods`，跟踪 `os4-dev` |
| 构建 | 独立仓库 `AbelDuan/hyperpods-ci`，手动 dispatch |
| 产物 | `/sdcard/Download/DSH-Go/builds/hyperpods-bridge/<版本>/HyperPods-bridge.apk` |
| 文档 | `/sdcard/Download/DSH-Go/artifacts/hyperpods-bridge/` |

分支关系：`custom` = 上游 `os4-dev` + 本仓库三条改动。
`os4-dev` / `aap` / `main` 只做镜像，不直接改。

---

## 1. 同步上游

```bash
./tools/sync-upstream.sh          # 默认合并 os4-dev
./tools/sync-upstream.sh aap      # 指定其它分支
```

脚本做四件事：fetch 上游 → 报新增提交数 → 合并 → 跑自检。
合并成功且自检通过才算完；有冲突会停在冲突那一步并打印热点文件。

**没有冲突时**：直接跳到第 3 步。

---

## 2. 解冲突

**原则**：fork 侧的文件一律保留本仓库版本，只在确认上游改了同一处逻辑时才吸收。

上游**没有**这些文件，出现冲突说明是误操作，直接保留本仓库：

```
app/src/main/java/moe/dsh/hpb/            桥接主体
tools/sync-upstream.sh
tools/verify-fork.sh
FORK.md
```

上游**有**这些文件、且 fork 加过东西——冲突时逐处比对，别整文件取一边：

| 文件 | fork 加了什么 |
|---|---|
| `hook/HookEntry.kt` | 三个包都调 `BridgeEntry.install` |
| `res/values/arrays.xml` | `xposedscope` 里的 `com.android.settings` |
| `pods/L2CAPController.kt` | ANC 编码转换、`pushStateToSystem`、佩戴检测落盘、settings 广播放行 |
| `hook/MiBluetoothToastHook.kt` | `islandShownFor`、电量顺序、`PendingIntent.getActivity` |
| `utils/PodsIslandData.kt` | 电量顺序 |
| `utils/AppPreferences.kt` | `publish` 同时发蓝牙进程和本应用 |
| `ui/MainUI.kt` | 注册 `ACTION_APP_SETTINGS_CHANGED` 并刷新开关 |
| `AndroidManifest.xml` | `SYSTEM_ALERT_WINDOW` 权限 |

解完：

```bash
git add -A && git commit
bash tools/verify-fork.sh
```

---

## 3. 自检必须全绿

`./tools/verify-fork.sh` 检查 18 个关键改动点是否还在。任何一项 ✗ 都说明合并时丢代码了，
按提示的文件和模式补回，别跳过。

这项存在的意义：这些改动都藏在别人的方法体里，`git merge` 不冲突也可能被上游重构冲掉，
不检查就只能等装到手机上才发现功能没了。

---

## 4. 推送并构建

```bash
git push fork custom
```

然后触发 CI：

```bash
python3 - <<'PY'
import json, urllib.request
pat = open('/root/.dsh/ACCOUNT.md').read().split('github_pat_')[1].split()[0]
pat = 'github_pat_' + pat
H = {'Authorization': f'token {pat}', 'Accept': 'application/vnd.github+json', 'User-Agent': 'dsh'}
def call(m, u, d=None):
    r = urllib.request.Request(u, headers=H, method=m,
        data=json.dumps(d).encode() if d else None)
    with urllib.request.urlopen(r, timeout=60) as x:
        b = x.read(); return x.status, (json.loads(b) if b else None)
_, w = call('GET', 'https://api.github.com/repos/AbelDuan/hyperpods-ci/actions/workflows')
wid = w['workflows'][0]['id']
print(call('POST', f'https://api.github.com/repos/AbelDuan/hyperpods-ci/actions/workflows/{wid}/dispatches',
           {'ref': 'main'}))
PY
```

构建约 4~5 分钟。**失败的 log 里 `e:` 开头的行才是 Kotlin 错误**，`What went wrong` 只是外壳。

签名已固定：`hpb.jks` / `123456` / `hpb` / `123456`，以 secrets 形式存在 CI 仓库里。

---

## 5. 装包——这一步最容易翻车

```bash
# 1) 先拷到 /data/local/tmp 再装（从 /sdcard 装会被 SELinux 的 system_server 域拒绝）
rsh 'cp /sdcard/Download/DSH-Go/builds/hyperpods-bridge/4.0.0-bridgeNN/HyperPods-bridge.apk /data/local/tmp/h.apk'
rsh 'pm install -r /data/local/tmp/h.apk; rm -f /data/local/tmp/h.apk'
rsh 'pm path io.github.mo_setian.hyperpods'
```

**`pm install -r` 会换 APK 路径**，而 LSPosed 的 `modules_config.db` 里存的是绝对路径。
路径失效时模块**静默不注入**——没有任何报错，只在 logcat 里表现为一条桥接日志都没有。

```bash
# 2) 拉库、改 apk_path、推回（root-relay 的 rsh 不转发 stdin，必须走 /sdcard 中转）
rsh 'cp /data/adb/lspd/config/modules_config.db /sdcard/Download/DSH-Go/tmp/c.db; chmod 644 /sdcard/Download/DSH-Go/tmp/c.db'
cp /sdcard/Download/DSH-Go/tmp/c.db /tmp/c.db
NEW=$(rsh 'pm path io.github.mo_setian.hyperpods' | sed 's/package://')
python3 /tmp/sync_lspd_path.py /tmp/c.db io.github.mo_setian.hyperpods "$NEW"
cp /tmp/c.db /sdcard/Download/DSH-Go/tmp/cf.db && chmod 644 /sdcard/Download/DSH-Go/tmp/cf.db
rsh 'cp /sdcard/Download/DSH-Go/tmp/cf.db /data/adb/lspd/config/modules_config.db'
rsh 'chown root:root /data/adb/lspd/config/modules_config.db; chmod 600 /data/adb/lspd/config/modules_config.db'
rsh 'rm -f /data/adb/lspd/config/modules_config.db-wal /data/adb/lspd/config/modules_config.db-shm'
```

```bash
# 3) 软重启（lspd 不能手动重启，会报 system service is not ready，所有模块都不注入）
rsh '/data/adb/ksu/bin/ksud soft-reboot'
```

等约 100 秒，`getprop sys.boot_completed` 返回 1。

---

## 6. 装机验证清单

```bash
rsh 'logcat -d | grep "HyperPodsBridge:" | tail -20'
```

必须能看到这几类：

| 日志 | 说明 |
|---|---|
| `hook settings AncController ok (2)` | 设置页两个 `setAncForUser` 重载挂上 |
| `hook WearDetectionController.setWearDetectionState ok` | 佩戴检测开关挂上 |
| `block official aacp` | 官方通道被拦住，控制权给 HyperPods |
| `sync state anc=N ...` | 电量状态回写官方 SP |

然后手测四项：

1. 系统设置页点**降噪/关闭/通透**，看是否与点击一致（编码对照见 FORK.md）
2. 系统设置拨**佩戴检测**，HyperPods 的「自动入耳检测」跟着变；反向也试
3. 断开再连耳机，超级岛弹**一次**就收；之后电量变化不再重弹
4. 下拉通知栏，电量为 `左：xx%  右：xx%  盒：xx%` 单行

---

## 7. 已知边界

**控制面板压不过安全中心浮窗**。这是框架限制，不是 bug：

| 方案 | mBaseLayer |
|---|---|
| Activity | 21000 |
| Activity + `setType(APPLICATION_OVERLAY)` | 21000（**被忽略**） |
| Service + `addView(APPLICATION_OVERLAY)` | 111000（应用层封顶） |
| 安全中心 `SYSTEM_ALERT` | 121000（需系统签名） |

再往上只有无障碍服务的 `ACCESSIBILITY_OVERLAY`。已确认不接受这个代价，别再重试。

**AACP 本身不稳定**时（`noise_mode sent → timeout`、`retries=6`），降噪点了没反应不是桥接的问题。
`settings put global hpb_block_official_aacp 0` 可以把控制权还给官方支持。

---

## 8. 三个运行期开关

```bash
rsh 'settings put global hpb_block_official_aacp 1'   # 拦住官方 AACP 建连
rsh 'settings put global hpb_anc_bridge 1'            # 系统页面按钮转发
rsh 'settings put global hpb_battery_bridge 1'        # 电量写入
```

任一项排查时可以先关掉对应功能，确认问题是否出在桥接层。
