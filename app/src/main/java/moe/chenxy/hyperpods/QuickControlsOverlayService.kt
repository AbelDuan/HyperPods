package moe.chenxy.hyperpods

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import moe.chenxy.hyperpods.ui.AppTheme
import moe.chenxy.hyperpods.ui.QuickControls
import moe.chenxy.hyperpods.utils.data.HyperPodsAction

/**
 * 真正的悬浮窗版本的控制面板。
 *
 * QuickControlsActivity 是 Activity，窗口类型被 WindowManagerService 按 window token
 * 固定成 BASE_APPLICATION（mBaseLayer=21000），压不过别的悬浮窗（小米安全中心是 121000）。
 * Activity 上 setType(TYPE_APPLICATION_OVERLAY) 会被直接忽略，这是框架约束，改不了。
 *
 * 所以这里用 Service 自己 addView，类型取 TYPE_APPLICATION_OVERLAY，才能盖在其它浮窗上。
 */
class QuickControlsOverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var rootView: ComposeView? = null
    private var shownAddress: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            dismiss()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS)
        if (address == null) {
            dismiss()
            return START_NOT_STICKY
        }
        // 重复点通知时直接复用现有窗口，不叠加
        if (shownAddress == address && rootView != null) {
            return START_NOT_STICKY
        }
        show(address)
        return START_NOT_STICKY
    }

    private fun show(address: String) {
        removeView()
        val wm = windowManager ?: return
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@QuickControlsOverlayService)
            setViewTreeSavedStateRegistryOwner(this@QuickControlsOverlayService)
            setViewTreeViewModelStoreOwner(this@QuickControlsOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val screenHeight = LocalConfiguration.current.screenHeightDp
                val maxHeight = (screenHeight - 40).coerceAtLeast(120).dp
                AppTheme(colorMode = 1) {
                    // 全屏透明遮罩：点空白处收起，也避免下层应用抢走触摸
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Transparent)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        QuickControls(address, maxHeight, canSend = { rootView != null }) {
                            dismiss()
                            startActivity(
                                Intent(this@QuickControlsOverlayService, MainActivity::class.java)
                                    .setAction(HyperPodsAction.ACTION_SHOW_PODS_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.18f
        }
        try {
            wm.addView(view, params)
            rootView = view
            shownAddress = address
        } catch (t: Throwable) {
            // 没拿到悬浮窗权限时会抛 BadTokenException，退回 Activity 保证还能用
            rootView = null
            shownAddress = null
            runCatching {
                startActivity(
                    Intent(this, QuickControlsActivity::class.java)
                        .setAction(HyperPodsAction.ACTION_SHOW_QUICK_CONTROLS)
                        .putExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS, address)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            stopSelf()
        }
    }

    private fun removeView() {
        val view = rootView ?: return
        rootView = null
        shownAddress = null
        runCatching { windowManager?.removeViewImmediate(view) }
    }

    private fun dismiss() {
        removeView()
        stopSelf()
    }

    override fun onDestroy() {
        removeView()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "moe.chenxy.hyperpods.DISMISS_QUICK_CONTROLS"

        fun intent(context: Context, address: String): Intent =
            Intent(context, QuickControlsOverlayService::class.java)
                .putExtra(HyperPodsAction.EXTRA_CONTROL_ADDRESS, address)
    }
}
