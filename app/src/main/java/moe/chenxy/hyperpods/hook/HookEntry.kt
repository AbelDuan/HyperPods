package moe.chenxy.hyperpods.hook

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.YukiHookAPI.configs
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import moe.chenxy.hyperpods.BuildConfig

@InjectYukiHookWithXposed(modulePackageName = BuildConfig.APPLICATION_ID)
object HookEntry : IYukiHookXposedInit {
    override fun onHook()  = YukiHookAPI.encase {
        loadApp("com.android.systemui") {
            loadHooker(ModuleStatusHook("com.android.systemui"))
            loadHooker(SystemUIPluginHook)
        }
        loadApp("com.android.bluetooth") {
            loadHooker(ModuleStatusHook("com.android.bluetooth"))
            loadHooker(HeadsetStateDispatcher)
            // 系统蓝牙进程：放行 HyperPods 的广播信任校验
            moe.dsh.hpb.BridgeEntry.install(packageName, appClassLoader, appContext)
        }
        loadApp("com.xiaomi.bluetooth") {
            loadHooker(ModuleStatusHook("com.xiaomi.bluetooth"))
            loadHooker(MiBluetoothToastHook)
            // 官方 AirPods 支持：拦 AACP 建连、写电量、转发 ANC
            moe.dsh.hpb.BridgeEntry.install(packageName, appClassLoader, appContext)
        }
        // 系统设置：官方详情页的降噪按钮转发给 HyperPods
        loadApp("com.android.settings") {
            loadHooker(ModuleStatusHook("com.android.settings"))
            moe.dsh.hpb.BridgeEntry.install(packageName, appClassLoader, appContext)
        }
    }

    override fun onInit() = configs {
        isDebug = BuildConfig.DEBUG
    }
}
