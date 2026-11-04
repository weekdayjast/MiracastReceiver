cmake_minimum_required(VERSION 3.22.1)
project(phairplay C CXX)

# RPiPlay's FairPlay (playfair) implementation + a thin JNI bridge → libplayfair.so.
add_library(playfair SHARED
    fairplay_jni.c
    playfair/playfair.c
    playfair/omg_hax.c
    playfair/hand_garble.c
    playfair/modified_md5.c
    playfair/sap_hash.c)

target_include_directories(playfair PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/playfair)

# The reverse-engineered C triggers benign warnings; keep them non-fatal.
target_compile_options(playfair PRIVATE -w)

# Apple's open-source ALAC decoder + a JNI bridge → libalac.so. Decodes macOS system-audio
# AirPlay (codec type 2 / ALAC), which this TV has no hardware codec for.
add_library(alac SHARED
    alac_jni.cpp
    alac/ALACDecoder.cpp
    alac/ALACBitUtilities.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    alac/EndianPortable.c)

target_include_directories(alac PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/alac)

# Upstream Apple code triggers benign sign/comparison warnings; keep them non-fatal.
target_compile_options(alac PRIVATE -w)

# __android_log_print for native error logging.
find_library(log-lib log)
target_link_libraries(alac ${log-lib})

# wpa_supplicant 控制接口客户端，用于注入 Wi-Fi Display Sink 的 WFD IE
# （应用层 setWFDInfo 需要 signature 级权限，必定失败，只能靠 root 走这条路）。
#
# 这是可执行文件而非库，但刻意命名成 libwfdctl.so —— 只有这个命名才会被 AGP
# 打进 APK 的 jniLibs，解压到 nativeLibraryDir 并带上可执行权限，从而能用 su 拉起。
add_executable(wfdctl wfdctl.c)
set_target_properties(wfdctl PROPERTIES
    PREFIX "lib"
    OUTPUT_NAME "wfdctl.so")

# Export only the legacy SDK network-watcher compatibility symbols.
add_library(castplus_network_compat SHARED castplus_network_compat.c)
set_target_properties(castplus_network_compat PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(castplus_network_compat PRIVATE "-Wl,-z,global")
target_link_libraries(castplus_network_compat ${log-lib})
