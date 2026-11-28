package moe.dsh.hpb;

import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * HyperPods 桥接模块（运行在 com.xiaomi.bluetooth 进程内）
 *
 * AirPods 的 AACP 控制通道（L2CAP PSM 0x1001 / channelId 4097）只接受一条连接，
 * 官方 AirPods 支持与 HyperPods 模块会互相抢。本模块：
 *
 *  1) 拦住官方 AACP 建连（a2.b.b），让 HyperPods 独占控制通道；
 *  2) 把官方详情页的降噪/通透/关闭按钮转发成 HyperPods 的 anc_select 广播；
 *  3) 把 HyperPods 通知里的电量写成官方详情页读的 AirpodsModel["<mac>-state"]。
 *
 * 开关（默认 1 = 开）：
 *  settings put global hpb_block_official_aacp 0
 *  settings put global hpb_anc_bridge 0
 *  settings put global hpb_battery_bridge 0
 */
public final class BridgeEntry {

    private BridgeEntry() {
    }

    private static final String TAG = "HyperPodsBridge";
    private static final String GLOBAL_AACP = "hpb_block_official_aacp";
    private static final String GLOBAL_ANC = "hpb_anc_bridge";
    private static final String GLOBAL_BATTERY = "hpb_battery_bridge";

    private static final String SP_AIRPODS = "AirpodsModel";
    private static final String ACTION_ANC_SELECT = "io.github.mo_setian.hyperpods.action.anc_select";
    /** HyperPods 把电量/ANC/佩戴推过来，由本进程写进官方读的 AirpodsModel。 */
    private static final String ANC_CONTROLLER_CLASS = "plugin.settings.java.airpods.AncController";
    private static final String AIRPODS_INFO_CLASS =
            "com.android.bluetooth.ble.app.fastconnect.airpods.AirPodsInfo";
    private static final String ACTION_SYSTEM_SYNC = "io.github.mo_setian.hyperpods.action.system_sync";
    private static final String HYPERPODS_PROC = "com.android.bluetooth";

    private static final String BOLT = "\u26A1";
    private static final Pattern LEFT = Pattern.compile("左[：:]\\s*(\\d{1,3})\\s*%(\\s*\u26A1)?");
    private static final Pattern RIGHT = Pattern.compile("右[：:]\\s*(\\d{1,3})\\s*%(\\s*\u26A1)?");
    private static final Pattern BOX = Pattern.compile("(?:充电盒|盒)[：:]\\s*(\\d{1,3})\\s*%(\\s*\u26A1)?");

    private static volatile Context appContext;
    private static volatile boolean notifyHookInstalled;

    /**
     * 由 HyperPods 的 YukiHookAPI 入口按包调用（原来本模块自己是 Xposed 入口）。
     *
     * @param pkg   当前进程包名
     * @param cl    该进程的 classloader
     * @param ctx   宿主 Context（可能为 null，后续 attach 时补）
     */
    public static void install(String pkg, ClassLoader cl, Context ctx) {
        if (ctx != null && appContext == null) {
            appContext = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        }
        String packageName = pkg;
        ClassLoader classLoader = cl;
        if ("com.android.settings".equals(packageName)) {
            hookSettingsAnc(classLoader);
            hookPluginClassLoad();
            return;
        }
        if ("io.github.mo_setian.hyperpods".equals(packageName)) {
            hookHyperPods(classLoader);
            return;
        }
        if ("com.android.bluetooth".equals(packageName)) {
            hookAncTrust(classLoader);
            return;
        }
        if (!"com.xiaomi.bluetooth".equals(packageName)) {
            return;
        }
        log("loaded in " + packageName);

        hookOfficialAacp(cl);
        hookAncCommand(cl);
        hookApplication(cl);
        hookToastProbe();
        // AirPodsInfo 在 /product/app/LyraSdkSpp/LyraSdkSpp.apk，等它进 classpath 后再说
        final ClassLoader ccl = cl;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 40 && !airPodsInfoHooked; i++) {
                    try {
                        Class<?> info = XposedHelpers.findClass(
                                "com.android.bluetooth.ble.app.fastconnect.airpods.AirPodsInfo", ccl);
                        hookAirPodsInfo(info);
                        return;
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "hpb-airpods-info");
        t.setDaemon(true);
        t.start();
        hookPluginClassLoad();
    }

    /** HyperPods 侧：类名被 R8 混淆，只能靠 setANCMode 方法名定位；清掉卡死的 pending */
    private static volatile boolean hyperPodsHooked;

    private static void hookHyperPods(final ClassLoader cl) {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (hyperPodsHooked) {
                        return;
                    }
                    Object r = param.getResult();
                    if (!(r instanceof Class)) {
                        return;
                    }
                    Class<?> c = (Class<?>) r;
                    if (c.getClassLoader() != cl) {
                        return;
                    }
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if ("setANCMode".equals(m.getName())) {
                            hookSetAncMode(c, m);
                            return;
                        }
                    }
                }
            });
            log("hyperpods classloader watch installed");
        } catch (Throwable t) {
            log("hyperpods watch failed: " + t);
        }
    }

    private static void hookSetAncMode(Class<?> c, java.lang.reflect.Method m) {
        hyperPodsHooked = true;
        try {
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        java.lang.reflect.Field pm = null;
                        for (java.lang.reflect.Field f : param.thisObject.getClass().getDeclaredFields()) {
                            if (java.util.Map.class.isAssignableFrom(f.getType())) {
                                pm = f;
                                break;
                            }
                        }
                        if (pm == null) {
                            log("setANCMode: no map field");
                            return;
                        }
                        pm.setAccessible(true);
                        Object map = pm.get(param.thisObject);
                        if (map instanceof java.util.Map) {
                            int n = ((java.util.Map<?, ?>) map).size();
                            if (n > 0) {
                                ((java.util.Map<?, ?>) map).clear();
                                log("setANCMode: cleared " + n + " pending from " + pm.getName());
                            }
                        }
                    } catch (Throwable t) {
                        log("setANCMode pre failed: " + t);
                    }
                }
            });
            log("hyperpods setANCMode hooked: " + c.getName() + "#" + m.getName());
        } catch (Throwable t) {
            log("setANCMode hook failed: " + t);
        }
    }

    /** 官方 AACP 建连入口：a2.b.b(BluetoothDevice, int) */
    private static void hookOfficialAacp(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("a2.b", cl),
                    "b", BluetoothDevice.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            BluetoothDevice dev = (BluetoothDevice) param.args[0];
                            if (!isAirPods(dev) || !flag(GLOBAL_AACP)) {
                                return;
                            }
                            log("block official AACP connect for " + dev.getAddress());
                            tryInfoFromLoader(param.thisObject == null ? null
                                    : param.thisObject.getClass().getClassLoader());
                            param.setResult(null);
                        }
                    });
            log("hook a2.b.b ok");
        } catch (Throwable t) {
            log("hook a2.b.b failed: " + t);
        }
    }

    /** 用过路对象的 loader 抓 AirPodsInfo（它不在主 classloader 里） */
    private static void tryInfoFromLoader(ClassLoader loader) {
        if (airPodsInfoHooked || loader == null) {
            return;
        }
        try {
            Class<?> info = XposedHelpers.findClass(
                    "com.android.bluetooth.ble.app.fastconnect.airpods.AirPodsInfo", loader);
            hookAirPodsInfo(info);
        } catch (Throwable t) {
            log("info via loader failed: " + t);
        }
    }

    /** 官方降噪命令：a2.a.r(BluetoothDevice, key, value) */
    private static void hookAncCommand(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(XposedHelpers.findClass("a2.a", cl),
                    "r", BluetoothDevice.class, String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String key = (String) param.args[1];
                                String value = (String) param.args[2];
                                BluetoothDevice dev = (BluetoothDevice) param.args[0];
                                if (!"air_anc".equals(key) || dev == null) {
                                    return;
                                }
                                int status = ancStatus(value);
                                log("official anc " + value + " -> hyperpods " + status);
                                if (flag(GLOBAL_ANC) && status > 0) {
                                    sendAncSelect(status);
                                    writeKey(dev.getAddress(), "-air_anc", value);
                                    param.setResult(0);
                                }
                            } catch (Throwable t) {
                                log("anc bridge failed: " + t);
                            }
                        }
                    });
            log("hook a2.a.r ok");
        } catch (Throwable t) {
            log("hook a2.a.r failed: " + t);
        }
    }

    /** 官方值 -> HyperPods ListeningMode ordinal+1（1=关 2=降噪 3=通透 4=自适应） */
    private static int ancStatus(String value) {
        if (value == null) {
            return 0;
        }
        String v = value.trim();
        if ("01".equals(v) || "1".equals(v)) {
            return 2; // 官方 1 = 降噪
        }
        if ("02".equals(v) || "2".equals(v)) {
            return 1; // 官方 2 = 关闭
        }
        if ("03".equals(v) || "3".equals(v)) {
            return 3; // 官方 3 = 通透
        }
        if ("04".equals(v) || "4".equals(v)) {
            return 4; // 官方 4 = 自适应
        }
        return 0;
    }

    private static void sendAncSelect(int status) {
        try {
            Context ctx = context();
            if (ctx == null) {
                return;
            }
            Intent intent = new Intent(ACTION_ANC_SELECT);
            intent.putExtra("status", status);
            intent.setPackage(HYPERPODS_PROC);
            ctx.sendBroadcast(intent);
            log("anc_select -> " + status);
        } catch (Throwable t) {
            log("sendAncSelect failed: " + t);
        }
    }

    /** Application 起来后安装通知监听，用 HyperPods 通知里的电量回填官方状态 */
    private static void hookApplication(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass("com.android.bluetooth.ble.MiuiApplication", cl),
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            appContext = (Context) param.thisObject;
                            installNotifyHook();
                            installAncWriteReceiver((Context) param.thisObject);
                            installSystemSyncReceiver((Context) param.thisObject);
                        }
                    });
            log("hook MiuiApplication.onCreate ok");
        } catch (Throwable t) {
            log("hook MiuiApplication.onCreate failed: " + t);
        }
    }

    private static void installNotifyHook() {
        if (notifyHookInstalled) {
            return;
        }
        notifyHookInstalled = true;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object n = param.args[param.args.length - 1];
                        if (n instanceof Notification) {
                            inspect((Notification) n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int count = 0;
            for (java.lang.reflect.Method m : NotificationManager.class.getDeclaredMethods()) {
                if (!m.getName().toLowerCase(Locale.ROOT).contains("notify")) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, hook);
                    count++;
                } catch (Throwable ignored) {
                }
            }
            log("notify hook installed on " + count + " methods");
        } catch (Throwable t) {
            log("notify hook failed: " + t);
        }
    }

    private static void inspect(Notification n) {
        Bundle extras = n.extras;
        if (extras == null) {
            return;
        }
        String joined = str(extras.getCharSequence(Notification.EXTRA_TITLE)) + " | "
                + str(extras.getCharSequence(Notification.EXTRA_TEXT)) + " | "
                + str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)) + " | "
                + str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)) + " | "
                + str(n.tickerText);
        if (!joined.contains("%")) {
            return;
        }
        int[] l = pickCharging(LEFT, joined);
        int[] r = pickCharging(RIGHT, joined);
        int[] b = pickCharging(BOX, joined);
        if (l[0] < 0 || r[0] < 0) {
            return;
        }
        if (!flag(GLOBAL_BATTERY)) {
            return;
        }
        String address = findAirPodsAddress();
        if (address == null) {
            return;
        }
        int wearBits = 3;
        // HyperPods 通知里的 ⚡ 恒亮，不代表充电，不能据此置位，否则界面一直显示充电
        int chargeBits = 0;
        String state = l[0] + "|" + r[0] + "|" + Math.max(b[0], 0) + "|" + wearBits + "|" + chargeBits + "|0|0|0";
        sLeftBat = l[0];
        sRightBat = r[0];
        sBoxBat = Math.max(b[0], 0);
        sChargeBits = chargeBits;
        writeKey(address, "-state", state);
        // 官方 -air_battery：04|左|左充|02|右|右充|08|盒|盒充，01=充电 02=未充电
        String bat = String.format(Locale.ROOT, "04%02X%02X02%02X%02X08%02X%02X",
                clamp255(l[0]), 2,
                clamp255(r[0]), 2,
                clamp255(Math.max(b[0], 0)), 2);
        writeKey(address, "-air_battery", bat);
        log("state charged bits=" + chargeBits + " battery=" + bat);
    }

    /** 返回 {电量, 是否充电} */
    private static int[] pickCharging(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) {
            return new int[]{-1, 0};
        }
        int level;
        try {
            level = Integer.parseInt(m.group(1));
        } catch (Throwable t) {
            return new int[]{-1, 0};
        }
        return new int[]{level, m.group(2) != null ? 1 : 0};
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static int pick(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    /** 从官方存储里找已连接的 AirPods 地址 */
    private static long sLastForwardAt;
    private static volatile int sPendingAnc;
    private static volatile boolean sDebounceRunning;
    private static volatile int sLastSentAnc;
    private static volatile Object sCachedAncState;

    private static String findAirPodsAddress() {
        try {
            Context ctx = context();
            if (ctx == null) {
                return null;
            }
            SharedPreferences sp = ctx.getSharedPreferences(SP_AIRPODS, 4);
            for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String key = e.getKey();
                if (!key.endsWith("-model")) {
                    continue;
                }
                String model = String.valueOf(e.getValue());
                if (model.contains("AirPods") || model.contains("Airpods")) {
                    return key.substring(0, key.length() - "-model".length());
                }
            }
        } catch (Throwable t) {
            log("find address failed: " + t);
        }
        return null;
    }

    private static void writeKey(String address, String suffix, String value) {
        try {
            Context ctx = context();
            if (ctx == null || address == null) {
                return;
            }
            SharedPreferences sp = ctx.getSharedPreferences(SP_AIRPODS, 4);
            String key = address + suffix;
            if (value.equals(sp.getString(key, ""))) {
                return;
            }
            sp.edit().putString(key, value).apply();
            log("write " + suffix + " <- " + value);
        } catch (Throwable t) {
            log("writeKey failed: " + t);
        }
    }


    /**
     * com.android.bluetooth 侧：HyperPods 的 broadcastReceiver 用 sentFromUid 校验发送方，
     * 只认它自己 App 的 uid。这里让这个校验对桥接的 anc_select 放行。
     */
    private static void hookAncTrust(ClassLoader cl) {
        try {
            Class<?> trusted = XposedHelpers.findClass(
                    "moe.chenxy.hyperpods.utils.HyperPodsBroadcasts", cl);
            XposedHelpers.findAndHookMethod(trusted, "isTrusted",
                    Context.class, android.content.BroadcastReceiver.class, String[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            log("hook HyperPodsBroadcasts.isTrusted ok");
        } catch (Throwable t) {
            log("hook isTrusted failed (" + t + "), falling back to sentFromUid");
        }
        try {
            XposedHelpers.findAndHookMethod(android.content.BroadcastReceiver.class,
                    "getSentFromUid", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object r = param.getResult();
                            int uid = r instanceof Integer ? (Integer) r : -1;
                            if (uid > 0 && uid != 1002 && uid != 2000) {
                                return;
                            }
                            int appUid = hyperpodsUid();
                            if (appUid > 0) {
                                param.setResult(appUid);
                            }
                        }
                    });
            log("hook getSentFromUid ok");
        } catch (Throwable t) {
            log("hook getSentFromUid failed: " + t);
        }
    }

    private static int hyperpodsUid() {
        try {
            Context ctx = context();
            if (ctx == null) {
                return -1;
            }
            return ctx.getPackageManager().getPackageUid("io.github.mo_setian.hyperpods", 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Context context() {
        Context ctx = appContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (app instanceof Context) {
                appContext = (Context) app;
                return appContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static boolean isAirPods(BluetoothDevice dev) {
        if (dev == null) {
            return false;
        }
        try {
            String name = dev.getAlias();
            if (name == null || name.isEmpty()) {
                name = dev.getName();
            }
            if (name == null) {
                return false;
            }
            return name.toLowerCase(Locale.ROOT).contains("airpods");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean flag(String key) {
        try {
            Context ctx = context();
            if (ctx == null) {
                return true;
            }
            ContentResolver cr = ctx.getContentResolver();
            return Settings.Global.getInt(cr, key, 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }


    /** 设置里的 AirPods 详情页：绕过 checkAncAccess 的佩戴门槛，把模式转给 HyperPods */
    private static void hookSettingsAnc(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("plugin.settings.java.airpods.AncController", cl);
            int hooked = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"setAncForUser".equals(m.getName())) {
                    continue;
                }
                final int intIndex = firstIntParam(m);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!flag(GLOBAL_ANC)) {
                                return;
                            }
                            int mode = intIndex >= 0 ? (Integer) param.args[intIndex] : -1;
                            log("settings setAncForUser mode=" + mode);
                            int status = ancStatusFromSettings(mode);
                            if (status > 0) {
                                // 立即发第一次，300ms 内的连点只合并后续，保证手感不拖
                                sPendingAnc = status;
                                if (!sDebounceRunning) {
                                    sDebounceRunning = true;
                                    sLastSentAnc = status;
                                    log("forward anc " + status);
                                    sendAncSelect(status);
                                    Thread dt = new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(300L);
                                            } catch (InterruptedException e) {
                                                sDebounceRunning = false;
                                                return;
                                            }
                                            sDebounceRunning = false;
                                            int v = sPendingAnc;
                                            if (v > 0 && v != sLastSentAnc) {
                                                sLastSentAnc = v;
                                                log("forward anc (merged) " + v);
                                                sendAncSelect(v);
                                            }
                                        }
                                    }, "hpb-anc-merge");
                                    dt.setDaemon(true);
                                    dt.start();
                                }
                            }
                            param.setResult(null);
                        } catch (Throwable t) {
                            log("settings anc failed: " + t);
                        }
                    }
                });
                hooked++;
            }
            log("hook settings AncController ok (" + hooked + ")");
        } catch (Throwable t) {
            log("hook settings AncController failed: " + t);
        }
    }

    private static int firstIntParam(java.lang.reflect.Method m) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == int.class || ps[i] == Integer.class) {
                return i;
            }
        }
        return -1;
    }

    /** 设置页模式 -> HyperPods（1=关 2=降噪 3=通透 4=自适应）；先按 0/1/2 探测 */
    /**
     * setAncForUser 的 mode 与官方 -air_anc 同一套编码：1=关闭 2=降噪 3=通透
     * （实测点击只会产生 2 和 3，0 未见，按不处理走）
     */
    /**
     * setAncForUser(int mode) 的 mode 是官方 AncController 编码
     * （1=降噪 2=关闭 3=通透 4=自适应），转成 HyperPods 的
     * （1=关闭 2=降噪 3=通透 4=自适应）。
     */
    private static int ancStatusFromSettings(int mode) {
        switch (mode) {
            case 1:
                return 2;
            case 2:
                return 1;
            case 3:
                return 3;
            case 4:
                return 4;
            default:
                return 0;
        }
    }


    private static volatile boolean ancControllerHooked;
    private static volatile boolean airPodsInfoHooked;
    private static volatile int sLeftBat = -1;
    private static volatile int sRightBat = -1;
    private static volatile int sBoxBat = -1;
    private static volatile int sChargeBits = 0;

    /** 官方详情页电池来自 AirPodsInfo（MMA 实时通道），直接改它的 getter */
    private static void hookAirPodsInfo(Class<?> c) {
        if (airPodsInfoHooked) {
            return;
        }
        airPodsInfoHooked = true;
        int n = 0;
        String[] names = {"getLeftBattery", "getRightBattery", "getBoxBattery", "getChargingState"};
        for (String name : names) {
            try {
                XposedBridge.hookAllMethods(c, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int v;
                        switch (name) {
                            case "getLeftBattery": v = sLeftBat; break;
                            case "getRightBattery": v = sRightBat; break;
                            case "getBoxBattery": v = sBoxBat; break;
                            default: v = sChargeBits; break;
                        }
                        if (v >= 0) {
                            param.setResult(v);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        log("AirPodsInfo hooked methods=" + n);
    }


    /** 插件类由设置里的 DexClassLoader 加载，等它出现再挂 hook */
    private static void hookPluginClassLoad() {
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0) {
                        return;
                    }
                    Object loaded = param.args[0];
                    if (loaded instanceof String) {
                        String n = (String) loaded;
                        if (n.contains("airpods") || n.contains("AirPods") || n.contains("fastconnect")) {
                            log("loadClass " + n + " from " + param.thisObject);
                        }
                    }
                    if (param.getResult() == null) {
                        return;
                    }
                    if ("com.android.bluetooth.ble.app.fastconnect.airpods.AirPodsInfo".equals(loaded)) {
                        hookAirPodsInfo((Class<?>) param.getResult());
                        return;
                    }
                    if (ancControllerHooked || !"plugin.settings.java.airpods.AncController".equals(loaded)) {
                        return;
                    }
                    hookAncController((Class<?>) param.getResult());
                }
            };
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", hook);
            // 探测式 loadClass 拿不到结果，真正的加载在 findClass 里
            XC_MethodHook findHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String)) {
                        return;
                    }
                    Object r = param.getResult();
                    if (!(r instanceof Class)) {
                        return;
                    }
                    String name = (String) param.args[0];
                    // 插件用 findClass 加载，之前这里只认 AirPodsInfo，
                    // AncController 直接被 return 掉，setAncForUser 永远 hook 不上。
                    if (ANC_CONTROLLER_CLASS.equals(name)) {
                        if (!ancControllerHooked) {
                            hookAncController((Class<?>) r);
                        }
                        return;
                    }
                    if (airPodsInfoHooked || !AIRPODS_INFO_CLASS.equals(name)) {
                        return;
                    }
                    hookAirPodsInfo((Class<?>) r);
                }
            };
            int installed = 0;
            try {
                XposedBridge.hookAllMethods(ClassLoader.class, "findClass", findHook);
                installed++;
            } catch (Throwable ignored) {
            }
            try {
                XposedBridge.hookAllMethods(
                        Class.forName("dalvik.system.BaseDexClassLoader"), "findClass", findHook);
                installed++;
            } catch (Throwable ignored) {
            }
            log("plugin classloader watch installed findClass=" + installed);
        } catch (Throwable t) {
            log("watch failed: " + t);
        }
    }

    private static void hookAncController(Class<?> c) {
        if (ancControllerHooked) {
            return;
        }
        ancControllerHooked = true;
        int hooked = 0;
        try {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"setAncForUser".equals(m.getName())) {
                    continue;
                }
                final int intIndex = firstIntParam(m);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!flag(GLOBAL_ANC)) {
                                return;
                            }
                            int mode = intIndex >= 0 ? (Integer) param.args[intIndex] : -1;
                            log("settings setAncForUser mode=" + mode);
                            int status = ancStatusFromSettings(mode);
                            if (status > 0) {
                                // debounce：界面连点会连续回调，只发最后一次，别把真实请求节流丢掉
                                sPendingAnc = status;
                                if (!sDebounceRunning) {
                                    sDebounceRunning = true;
                                    Thread dt = new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(700L);
                                            } catch (InterruptedException e) {
                                                sDebounceRunning = false;
                                                return;
                                            }
                                            sDebounceRunning = false;
                                            int v = sPendingAnc;
                                            if (v <= 0) {
                                                return;
                                            }
                                            log("forward anc " + v);
                                            sendAncSelect(v);
                                            // HyperPods 的 pending 窗口 3+2 秒，等它清掉后再补一次
                                            try {
                                                Thread.sleep(7000L);
                                            } catch (InterruptedException e) {
                                                return;
                                            }
                                            if (sPendingAnc == v) {
                                                sendAncSelect(v);
                                            }
                                        }
                                    }, "hpb-anc-forward");
                                    dt.setDaemon(true);
                                    dt.start();
                                }
                                // settings 进程读不到 com.xiaomi.bluetooth 的 SP，交给蓝牙进程写
                                sendAncWrite(mode);
                            }
                            param.setResult(null);
                            // 让图标立刻跟上：updateView(Context,int) 是官方刷新按钮图标的入口，
                            // 参数用官方编码（1 降噪 / 2 关闭 / 3 通透 / 4 自适应）
                            try {
                                java.lang.reflect.Method uv = param.thisObject.getClass()
                                        .getMethod("updateView", android.content.Context.class, int.class);
                                uv.invoke(param.thisObject, context(), mode);
                                log("updateView -> " + mode);
                            } catch (Throwable t) {
                                log("updateView failed: " + t);
                            }
                        } catch (Throwable t) {
                            log("settings anc failed: " + t);
                        }
                    }
                });
                hooked++;
            }
            // 官方自己调 initAncState 时把参数缓存下来，之后转发完 ANC 复用它刷 UI
            try {
                XposedBridge.hookAllMethods(c, "initAncState", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] != null) {
                            if (sCachedAncState == null) {
                                log("cached anc state: " + param.args[0].getClass().getName());
                            }
                            sCachedAncState = param.args[0];
                        }
                    }
                });
            } catch (Throwable t) {
                log("cache initAncState failed: " + t);
            }

            // 佩戴门槛：读不到实时佩戴状态时直接放行
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!"checkAncAccess".equals(m.getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    // 必须在 before 拦：checkAncAccess 内部先 Toast 再 return false，
                    // after 里改返回值时"请佩戴耳机"已经弹出来了。
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                hooked++;
            }
            log("hook settings AncController ok (" + hooked + ")");
        } catch (Throwable t) {
            ancControllerHooked = false;
            log("hook settings AncController failed: " + t);
        }
    }


    /** 让官方详情页重新读一次状态（和 h1.a.d 里 notifyChange 的做法一致） */
    /** 从 settings 进程把要写的 ANC 值广播给 com.xiaomi.bluetooth */
    private static void sendAncWrite(int mode) {
        try {
            Context ctx = context();
            if (ctx == null) {
                return;
            }
            android.content.Intent i = new android.content.Intent(ACTION_SET_ANC);
            i.putExtra("mode", mode);
            i.setPackage("com.xiaomi.bluetooth");
            ctx.sendBroadcast(i);
            log("broadcast anc write " + mode);
        } catch (Throwable t) {
            log("sendAncWrite failed: " + t);
        }
    }

    private static final String ACTION_SET_ANC = "moe.dsh.hpb.SET_ANC";
    private static volatile boolean ancReceiverInstalled;

    /** 在蓝牙进程注册接收器，本地写 -air_anc 并通知 Repository */
    /** 收 HyperPods 推来的完整状态，写进官方 SP 并通知 provider 重读。 */
    private static void installSystemSyncReceiver(Context ctx) {
        if (systemSyncReceiver != null) {
            return;
        }
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        handleSystemSync(context, intent);
                    } catch (Throwable t) {
                        log("system sync failed: " + t);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_SYSTEM_SYNC);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(r, f);
            }
            systemSyncReceiver = r;
            log("system sync receiver installed");
        } catch (Throwable t) {
            log("install system sync receiver failed: " + t);
        }
    }

    private static volatile android.content.BroadcastReceiver systemSyncReceiver;

    private static void handleSystemSync(Context ctx, Intent i) {
        if (i == null || !flag(GLOBAL_BATTERY)) {
            return;
        }
        String address = i.getStringExtra("address");
        if (address == null || address.isEmpty()) {
            return;
        }
        String mac = address.toUpperCase(Locale.US);
        int anc = i.getIntExtra("anc", 0);
        int left = i.getIntExtra("left", -1);
        int right = i.getIntExtra("right", -1);
        int box = i.getIntExtra("box", -1);
        boolean lc = i.getBooleanExtra("left_charging", false);
        boolean rc = i.getBooleanExtra("right_charging", false);
        boolean bc = i.getBooleanExtra("box_charging", false);
        boolean lin = i.getBooleanExtra("left_in_case", false);
        boolean rin = i.getBooleanExtra("right_in_case", false);

        // ANC 只在有效值时才覆盖，避免 0（未知）把界面刷成空
        if (anc >= 1 && anc <= 4) {
            writeKey(mac, "-air_anc", String.format(Locale.US, "%02d", anc));
        }
        // -state：左|右|盒|佩戴位|充电位|0|0|0
        int wearBits = (lin ? 0 : 1) | (rin ? 0 : 2);
        int chargeBits = (lc ? 1 : 0) | (rc ? 2 : 0) | (bc ? 4 : 0);
        String state = left + "|" + right + "|" + box + "|" + wearBits + "|" + chargeBits + "|0|0|0";
        writeKey(mac, "-state", state);
        String battery = String.format(Locale.US, "%02d%02d%02d%02d%02d%02d%02d",
                left < 0 ? 0 : left, lc ? 1 : 2,
                right < 0 ? 0 : right, rc ? 1 : 2,
                box < 0 ? 0 : box, bc ? 1 : 2, 2);
        writeKey(mac, "-air_battery", battery);
        // checkAncAccess 只认两位：00=没戴(弹提示) 01=戴着 02=直接放行。
        // 之前写的是四位的 0101，匹配不上任何分支就落到 return false，"请佩戴耳机"就是这么来的。
        // air_wear_state 是「左两位 + 右两位」的四位码，00 = 在戴，其它 = 在盒。
        // 设备互联 / 设置页都经 Config.parseWearCode 解析：
        //   0000 -> "02" 双耳在戴（直接放行）
        //   一只 00 -> "01" 单耳（还要查单耳ANC/佩戴检测）
        //   都不是 00 -> "00" 判定没戴，弹「请佩戴耳机后使用」
        // 之前写的 0101 就是"两只都在盒"，所以永远提示。
        String leftWear = lin ? "01" : "00";
        String rightWear = rin ? "01" : "00";
        writeKey(mac, "-air_wear_state", leftWear + rightWear);
        log("sync state anc=" + anc + " " + state + " battery=" + battery);
        notifyAirpodsRepository();
    }

    private static void installAncWriteReceiver(Context ctx) {
        if (ancReceiverInstalled) {
            return;
        }
        ancReceiverInstalled = true;
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent intent) {
                    try {
                        int mode = intent.getIntExtra("mode", 0);
                        String addr = findAirPodsAddress();
                        if (addr == null) {
                            log("receiver: no address");
                            return;
                        }
                        writeKey(addr, "-air_anc", String.format(Locale.ROOT, "%02d", mode));
                        log("air_anc <- " + mode + " @" + addr);
                        notifyAirpodsRepository();
                    } catch (Throwable t) {
                        log("receiver anc failed: " + t);
                    }
                }
            }, new android.content.IntentFilter(ACTION_SET_ANC),
                    android.os.Build.VERSION.SDK_INT >= 33
                            ? 2 /* Context.RECEIVER_EXPORTED */
                            : 0);
            log("anc write receiver installed");
        } catch (Throwable t) {
            log("install receiver failed: " + t);
        }
    }

    /** 打印 state 对象里的字段，用来确定官方 ANC 编码 */
    private static void dumpState(Object o) {
        try {
            StringBuilder sb = new StringBuilder("state fields:");
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                try {
                    Object v = f.get(o);
                    String sv = String.valueOf(v);
                    if (sv.length() > 40) {
                        sv = sv.substring(0, 40);
                    }
                    sb.append(" ").append(f.getName()).append("=").append(sv);
                } catch (Throwable ignored) {
                }
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpState failed: " + t);
        }
    }

    private static void notifyAirpodsRepository() {
        Context ctx = context();
        if (ctx == null) {
            return;
        }
        // 官方真实 authority：settingsprovider / whitelistprovider / mihome.provider
        // （headsetdata.provider 是猜的，实测不存在）
        String[] authorities = {
                "com.android.bluetooth.ble.settingsprovider",
                "com.android.bluetooth.ble.whitelistprovider",
                "com.android.bluetooth.ble.app.mihome.provider",
        };
        for (String a : authorities) {
            try {
                ctx.getContentResolver().notifyChange(
                        android.net.Uri.parse("content://" + a), null, false);
                log("notify " + a);
            } catch (Throwable t) {
                log("notify " + a + " failed: " + t);
            }
        }
    }


    /**
     * 让选中图标跟手。AncController 没有 updateView（那是早先猜的），
     * 真实刷新方法是 initAncState(Object)：重读当前状态并刷 UI。
     */
    private static void refreshAncView(Object controller, int mode) {
        try {
            for (Class<?> c = controller.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!"initAncState".equals(m.getName())) {
                        continue;
                    }
                    m.setAccessible(true);
                    Object arg = null;
                    // 参数类型不是 Object 的，尝试用当前 controller 已知的 AirPods 数据
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1) {
                        arg = sCachedAncState != null ? sCachedAncState : readAirPodsInfoFrom(controller);
                    }
                    m.invoke(controller, arg);
                    log("initAncState(" + (arg == null ? "null" : arg.getClass().getSimpleName()) + ") ok");
                    return;
                }
            }
            log("initAncState not found");
        } catch (Throwable t) {
            log("initAncState failed: " + t);
        }
    }

    /** 从 AncController 的字段里挖出 AirPods 信息对象，拿不到就返回 null */
    private static Object readAirPodsInfoFrom(Object controller) {
        for (java.lang.reflect.Field f : controller.getClass().getDeclaredFields()) {
            try {
                String tn = f.getType().getName();
                if (tn.contains("AirPodsInfo") || tn.contains("AirpodsInfo")) {
                    f.setAccessible(true);
                    return f.get(controller);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Toast 探测占位（原实现在重构中替换掉，重新补一个最小版避免编译失败） */
    private static void hookToastProbe() {
        log("toast probe installed");
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
