package moe.dsh.hpb

/**
 * AAC-ELD for AirPods 的本进程入口。
 *
 * 只在 com.android.bluetooth 进程里用：那份代码 hook 的是 libbluetooth_jni.so
 * 里的 A2DP codec 接口，接管 Opus 的 codec slot 改用协议栈自带的 FDK-AAC 输出
 * AAC-ELD。
 *
 * 库加载失败或栈不适配时返回 false，不影响桥接其余功能。
 */
object AacEldBridge {

    @Volatile
    private var loaded = false

    @Volatile
    private var started = false

    private external fun nativeStart(): Boolean

    private external fun nativeIsDryRun(): Boolean

    fun start(): Boolean {
        if (started) return true
        synchronized(this) {
            if (started) return true
            if (!ensureLoaded()) return false
            return try {
                val ok = nativeStart()
                started = ok
                BridgeEntry.log("aaceld start -> $ok")
                ok
            } catch (t: Throwable) {
                BridgeEntry.log("aaceld start failed: $t")
                false
            }
        }
    }

    fun isDryRun(): Boolean = try {
        if (ensureLoaded()) nativeIsDryRun() else false
    } catch (t: Throwable) {
        false
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("aaceld")
            loaded = true
            true
        } catch (t: Throwable) {
            // 只在 arm64 上编译了这个库，别的架构加载失败属正常
            BridgeEntry.log("aaceld load failed: $t")
            false
        }
    }
}
