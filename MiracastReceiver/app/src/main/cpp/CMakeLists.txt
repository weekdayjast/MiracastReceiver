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
