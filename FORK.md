# Fork 说明（AbelDuan/HyperPods）

本仓库是 [Mo-SeTian/HyperPods](https://github.com/Mo-SeTian/HyperPods) 的 fork，用来把
**系统 AirPods 详情页的桥接**并进 HyperPods 本体，不再单独维护一个 Xposed 模块。

> 上游更新后的合并与装机流程见 **[UPSTREAM.md](UPSTREAM.md)**。
> 合并完务必跑 `./tools/verify-fork.sh`，它检查 18 个关键改动点是否被冲掉。

## 分支

| 分支 | 用途 |
|---|---|
| `custom` | **默认分支**，上游 `os4-dev` + 本仓库的桥接改动 |
| `os4-dev` / `aap` / `main` | 上游镜像，只用来对齐，不直接改 |

## 本仓库的唯一改动：系统 UI 桥接

HyperOS 的系统蓝牙详情页只认官方 AACP 通道（L2CAP PSM 0x1001 / channelId 4097），
而这条通道同一时刻只接受一个连接，官方 AirPods 支持与 HyperPods 会互相抢。桥接做三件事：

1. **`com.xiaomi.bluetooth`**：拦住官方 AACP 建连（`a2.b.b`），控制通道留给 HyperPods；
2. **`com.android.settings`**：官方详情页的降噪/通透/关闭按钮，转发成
   `io.github.mo_setian.hyperpods.action.anc_select` 广播；
3. **电量**：把 HyperPods 通知里的电量写进官方读的
   `AirpodsModel["<mac>-state"]`。

三个开关（`settings put global`，默认 1）：

```
hpb_block_official_aacp   拦住官方 AACP 建连
hpb_anc_bridge           系统页面按钮转发
hpb_battery_bridge       电量写入
```

### 代码位置

| 文件 | 说明 |
|---|---|
| `app/src/main/java/moe/dsh/hpb/BridgeEntry.java` | 桥接主体，`install(pkg, cl, ctx)` 静态入口 |
| `app/src/main/java/moe/chenxy/hyperpods/hook/HookEntry.kt` | 按包调用 `BridgeEntry.install` |
| `app/src/main/res/values/arrays.xml` | `xposedscope` 里打开了 `com.android.settings` |

桥接原本是独立模块 `moe.dsh.hpb`（自带 `IXposedHookLoadPackage` 入口），合进来时只改了
入口形式：签名从 `handleLoadPackage(lp)` 变成 `install(pkg, classLoader, context)`，
内部逻辑一行没动。

## 拉上游新代码

上游活跃分支是 `Mo-SeTian/HyperPods` 的 **`os4-dev`**（原仓库 `Art-Chen/HyperPods` 已停在 8 月）。

```bash
./tools/sync-upstream.sh
```

脚本做的事：fetch 上游 `os4-dev` → 合到 `custom` → 有冲突就停下并列出冲突文件。

冲突只可能出现在这三处（我们的改动集中在这里）：

- `app/src/main/java/moe/chenxy/hyperpods/hook/HookEntry.kt`（上游常加 hooker）
- `app/src/main/res/values/arrays.xml`（上游可能重排 scope）
- 新增的 `moe/dsh/hpb/` 目录（上游不会碰）

`HookEntry.kt` 冲突时的处理：保留上游新增的 `loadHooker(...)` 行，只保留我们新增的
`moe.dsh.hpb.BridgeEntry.install(...)` 调用。

## 构建

上游自带的 `.github/workflows/build.yml` 在 fork 里不会被注册（GitHub 限制），
所以构建走独立仓库 [AbelDuan/hyperpods-ci](https://github.com/AbelDuan/hyperpods-ci)：
它 clone 本仓库 `custom` 分支、用 `hpb` keystore 签名、产出 artifact `hyperpods-bridge-apk`。

签名和已安装的官方 HyperPods 不同，覆盖安装即可（本机允许跨签名覆盖、保数据）。

## 编码对照（踩过的坑，改前必看）

官方 `AncController` 里有两套编码，方向相反，混用会让降噪和关闭互换：

| 场景 | 编码 |
|---|---|
| 按钮 / `setAncForUser(int)` / `updateView(int)` | `1`=降噪 `2`=关闭 `3`=通透 `4`=自适应 |
| `air_anc` 存储值 / AACP 命令码 | `01`=关闭 `02`=降噪 `03`=通透 `04`=自适应 |
| HyperPods `NoiseControlMode.ordinal + 1` | `1`=关闭 `2`=降噪 `3`=通透 `4`=自适应 |

`AncController.toCommandCode()` 是官方唯一定义：按钮 1 → `"02"`，按钮 2 → `"01"`。

**所以**：`-air_anc` 一律写命令码；按钮码只在 `ancStatusFromSettings()` 里转换一次。
`BridgeEntry.ancStatus(String)` 收的是命令码，**必须 1:1 直传**，不能互换。

## 佩戴检测同步

系统 `WearDetectionController` 与 HyperPods 的「自动入耳检测」是同一件事的两个副本：

| 位置 | 键 | 值 |
|---|---|---|
| 系统 SP | `AirpodsModel["<mac>-local_ear_detection"]` | `01` 开 / `02` 关 |
| HyperPods 模块侧 | `files/app_settings.json` 的 `ear_detection` | boolean |
| HyperPods App 侧 | 同上，但**独立进程独立文件** | boolean |

链路里两个坑：

1. `L2CAPController.onReceive` 开头按来源做 `isTrusted` 校验，默认只认自己的包名，
   `com.android.settings` 发来的广播会被整个丢掉 → `ACTION_EAR_DETECTION_SWITCH_CHANGED` 单独放行；
2. `updateFeatureToggle` 每次都从 `AppPreferences`（filesDir 的 JSON）重读快照，
   只改内存变量会被下次刷新覆盖 → 收到广播必须先 `AppPreferences.save()` + `publish()`；
   `publish` 要同时发给蓝牙进程和 App 进程，`MainUI` 才能刷新界面。

## 超级岛与通知栏

- 超级岛：`islandShownFor` 按 MAC 记录，**只在连接时弹一次**；之后电量变化只
  `notifyAsUser` 刷新通知，不再 `sendFocus`（否则电量一变岛就被重新拉起）。
  断开时清标记，下次连接可再弹。
- 通知栏电量单行：`buildNotification` 里的 `setContentText`，顺序 **左 → 右 → 盒**。
  充电盒合盖后不再上报，放最后才不会有位置跳动。`PodsIslandData.expandedText` 同步了同样顺序。

## 控制面板窗口层级的上限（实测结论）

`QuickControlsActivity` 是普通 Activity，窗口类型由 WindowManagerService 按 window token
强制设定，**`setType(TYPE_APPLICATION_OVERLAY)` 会被完全忽略**：

```
mAttrs = ty=BASE_APPLICATION     mBaseLayer = 21000
```

改成 `WindowManager.addView` + `TYPE_APPLICATION_OVERLAY` 能到 `111000`，但小米安全中心的
`com.miui.securitycenter/.FloatingWindow` 用的是 `SYSTEM_ALERT`，`mBaseLayer = 121000`，
仍然更高。`SYSTEM_ALERT` 需要系统签名，第三方拿不到。

**应用层封顶就是 `APPLICATION_OVERLAY`(111000)**，再往上只有无障碍服务的
`ACCESSIBILITY_OVERLAY`。当前按用户选择保留 Activity 方案。

## 每次装包必须同步 LSPosed 的 apk_path

`pm install -r` 会换 APK 路径，而 LSPosed 的 `modules_config.db` 里存的是绝对路径，
路径失效时模块**静默不注入**（没有任何报错）。装完必须：

1. 拉 `modules_config.db`，把 `moe.dsh.hpb` / `io.github.mo_setian.hyperpods` 的 `apk_path` 改成新路径；
2. 推回 `/data/adb/lspd/config/modules_config.db`，`chown root:root`、`chmod 600`，删掉 `-wal`/`-shm`；
3. `ksud soft-reboot`（`lspd` 不能手动重启，会报 `system service is not ready`）。
