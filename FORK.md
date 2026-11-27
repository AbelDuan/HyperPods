# Fork 说明（AbelDuan/HyperPods）

本仓库是 [Mo-SeTian/HyperPods](https://github.com/Mo-SeTian/HyperPods) 的 fork，用来把
**系统 AirPods 详情页的桥接**并进 HyperPods 本体，不再单独维护一个 Xposed 模块。

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
