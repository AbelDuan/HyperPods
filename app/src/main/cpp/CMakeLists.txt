cmake_minimum_required(VERSION 3.22.1)
# 必须声明 ASM：shadowhook 的 arch/arm64/sh_glue.S 定义了
# shadowhook_interceptor_glue，本文件若不含 ASM 语言，CMake 不会编译 .S，
# 链接时就报这个符号未定义。
project("hyperpods_hook" CXX C ASM)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Android 15+ devices can use a 16 KiB memory page size. Align every LOAD
# segment accordingly so the module can be loaded on both 4 KiB and 16 KiB
# page-size devices.
set(PAGE_FLAGS "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# L2CAP hook（HyperPods 原有）
# ---------------------------------------------------------------------------
add_library(${CMAKE_PROJECT_NAME} SHARED hyperpods.cpp)
target_link_options(${CMAKE_PROJECT_NAME} PRIVATE ${PAGE_FLAGS})
target_link_libraries(${CMAKE_PROJECT_NAME} android log)

# ---------------------------------------------------------------------------
# AAC-ELD（移植自 wasdwasd0105/xiaomi-airpods-aac-eld，GPL-2.0）
#
# 原版是 Zygisk 模块，这里只保留「等栈库加载 → 装 hook」的核心逻辑，
# 入口换成 JNI。hook 引擎用 shadowhook 静态链入，不引入外部 .so 依赖。
# ---------------------------------------------------------------------------
set(SH ${CMAKE_CURRENT_SOURCE_DIR}/third_party/shadowhook)

# aaceld 的 hook 只实现了 arm64，架构直接定死；build.gradle 里已经用
# abiFilters 限定 arm64-v8a，这里再断言一次，配错时立刻报出来。
if(DEFINED ANDROID_ABI AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "aaceld only supports arm64-v8a, got '${ANDROID_ABI}'")
endif()
set(SH_ARCH "arm64")

file(GLOB SH_SRC
    ${SH}/*.c
    ${SH}/arch/${SH_ARCH}/*.c
    ${SH}/arch/${SH_ARCH}/*.S
    ${SH}/common/*.c
    ${SH}/third_party/*/*.c
)

add_library(shadowhook_static STATIC ${SH_SRC})
target_include_directories(shadowhook_static PUBLIC
    ${SH} ${SH}/include ${SH}/arch/${SH_ARCH} ${SH}/common
    ${SH}/third_party/xdl ${SH}/third_party/bsd ${SH}/third_party/lss)
# Vendored code, compiled as-is rather than held to this project's warnings.
target_compile_options(shadowhook_static PRIVATE -Os -ffunction-sections -fdata-sections -w)
target_link_libraries(shadowhook_static PUBLIC log)

add_library(aaceld SHARED
    aaceld_entry.cpp
    target.cpp
    hooks.cpp
    eld_encoder.cpp
)

target_include_directories(aaceld PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(aaceld PRIVATE shadowhook_static log dl)

target_compile_options(aaceld PRIVATE
    -Wall -Wextra -Werror
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
    -fno-exceptions -fno-rtti)
target_link_options(aaceld PRIVATE ${PAGE_FLAGS} -Wl,--gc-sections)
