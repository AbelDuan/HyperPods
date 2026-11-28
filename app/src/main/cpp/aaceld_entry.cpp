// SPDX-License-Identifier: GPL-2.0-only
// AAC-ELD for AirPods，改成 LSPosed 模块内的 JNI 入口。
//
// 原版是 Zygisk 模块，靠 zygisk_module_entry 拿到 api 表、在 preAppSpecialize
// 里按进程名过滤。这里的宿主已经是 com.android.bluetooth 进程（BridgeEntry 注入），
// 所以进程过滤那层不需要了，只剩「等栈库加载 → 装上 hook」这一步。
//
// 关键：libbluetooth_jni.so 是 AdapterService 启动时才加载的，比注入点晚得多。
// 原代码用轮询等它，这里沿用同一套逻辑。
#include "target.h"
#include "log.h"

#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <strings.h>
#include <sys/system_properties.h>
#include <time.h>

#include <shadowhook.h>

bool hooks_install();
bool hooks_dry_run();

namespace {

constexpr const char* kPropEnabled = "persist.aaceld.enabled";
constexpr const char* kPropDryRun = "persist.aaceld.dryrun";
constexpr const char* kAacLibName = "libFraunhoferAAC.so";

// 与实际加载时机对齐：AdapterService 起得晚，轮询两分钟足够。
constexpr long kPollIntervalMs = 50;
// libFraunhoferAAC.so 要等 A2DP 真正开始软件编码才进场，可能几分钟到几十分钟。
// 先跑 30 分钟；target_resolve 里有快速预检，库没来的时候不会做完整解析。
constexpr int kPollAttempts = 36000;

bool prop_bool(const char* name, bool fallback) {
    char value[PROP_VALUE_MAX] = {};
    if (__system_property_get(name, value) <= 0) return fallback;
    return strcmp(value, "1") == 0 || strcasecmp(value, "true") == 0;
}

bool g_dry_run = false;
// 只允许装一次，防止 Java 侧重复调用叠加 hook。
bool g_started = false;

void run_once() {
    if (g_dry_run) {
        hooks_dry_run();
    } else {
        hooks_install();
    }
    target_startup_done();
}

void* waiter(void*) {
    // 第一步：等主库出现，装 hook。这一步不等 FDK-AAC ——
    // 改 codec 优先级靠的是 OpusSourceCtor，它随蓝牙栈启动就走，等不得。
    bool hooked = false;
    for (int i = 0; i < kPollAttempts; i++) {
        if (target_resolve()) {
            run_once();
            hooked = true;
            break;
        }
        if (g_target_unusable) return nullptr;  // 加载了，但不是这个模块适配的栈
        struct timespec ts = {.tv_sec = 0, .tv_nsec = kPollIntervalMs * 1000000L};
        nanosleep(&ts, nullptr);
    }
    if (!hooked) {
        LOGW("%s never appeared, giving up", TARGET_LIB_NAME);
        return nullptr;
    }

    // 第二步：hook 已就位，继续等 FDK-AAC 把编码器符号补上。
    for (int i = 0; i < kPollAttempts; i++) {
        if (target_fill_aac()) return nullptr;
        struct timespec ts = {.tv_sec = 0, .tv_nsec = kPollIntervalMs * 1000000L};
        nanosleep(&ts, nullptr);
    }
    LOGW("%s never appeared; the codec slot stays without an encoder", kAacLibName);
    return nullptr;
}

jboolean start() {
    if (g_started) return JNI_TRUE;
    g_started = true;

    if (!prop_bool(kPropEnabled, true)) {
        LOGI("%s is false, leaving the stack untouched", kPropEnabled);
        return JNI_FALSE;
    }

    g_dry_run = prop_bool(kPropDryRun, false);
    LOGI("starting, dry run %s", g_dry_run ? "on" : "off");

    if (!g_dry_run) {
        int err = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, true);
        if (err != SHADOWHOOK_ERRNO_OK) {
            LOGE("shadowhook_init failed: %s", shadowhook_to_errmsg(err));
            return JNI_FALSE;
        }
        shadowhook_set_debuggable(true);
    }

    if (target_resolve()) {
        run_once();
        return JNI_TRUE;
    }
    if (g_target_unusable) return JNI_FALSE;

    pthread_t tid;
    if (pthread_create(&tid, nullptr, waiter, nullptr) == 0) {
        pthread_detach(tid);
        LOGI("waiting for %s to load", TARGET_LIB_NAME);
    } else {
        LOGE("could not start the waiter thread");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_dsh_hpb_AacEldBridge_nativeStart(JNIEnv*, jclass) {
    return start();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_moe_dsh_hpb_AacEldBridge_nativeIsDryRun(JNIEnv*, jclass) {
    return g_dry_run ? JNI_TRUE : JNI_FALSE;
}
