# Building the UxPlay native libraries

The release attachment `uxplay-native-source-v1.2.14.tar.gz` supplies the native project source, its vendored UxPlay/playfair, FFmpeg, libplist and openssl-cmake source, the applied compatibility patches, upstream licenses, and the OpenSSL 3.4.4 source archive. The Android adapter, JNI declarations and renderer changes are in the main repository at tag `v1.2.14`.

## Native project

Use Linux, JDK 21, Android SDK Platform 37, NDK 27.0.12077973 and CMake 3.22.1. The Gradle wrapper and dependency catalog are included. Configure ANDROID_HOME / ANDROID_SDK_ROOT to an SDK with these components installed. The project also needs the usual native build tools: gcc, make, perl, Python 3, git and pkg-config.

The source archive contains an already-patched UxPlay tree and a `.prepatched-source` marker. The packaged Gradle task skips reapplying patches to this snapshot. No private keystore, local.properties, Gradle cache or Git history is included. Gradle/Maven and SDK dependencies are downloaded through their normal repositories; OpenSSL's source archive is supplied under `vendor-archives/` as well.

```sh
cd android-airplay-server
bash gradlew :app:assembleDebug -PnativeAbi=armeabi-v7a --no-daemon
bash gradlew :app:assembleDebug -PnativeAbi=arm64-v8a --no-daemon
```

After each build, copy `libairplay_native.so`, `libcrypto.so`, `liboboe.so` and `libc++_shared.so` from `app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/<ABI>/` into the main application's `app/src/uxplay/jniLibs/<ABI>/`. Preserve the ARM32 files before building ARM64 because Gradle may replace the intermediate output directory.

The build enables INFO-level native logging and excludes temporary patch 0010. UxPlay patches 0001–0009 and 0011–0012 are included. JNI callback changes forward sender subtitle selections. Build configuration and modified bridge/callback source are also retained next to this file in `native-source/` and `native-patches/`.

Pinned upstream sources:

- android-airplay-server: v0.0.36
- UxPlay: df67c212a433cf6dda3676dd40c097900d24e645
- FFmpeg: 38b88335f99e76ed89ff3c93f877fdefce736c13
- libplist: f41b1ea67045e0c09339974d83e389972d84f166
- openssl-cmake: 4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8
- OpenSSL: 3.4.4

## Main application

From `MiracastReceiver/` in the main repository:

```sh
./gradlew :app:assembleRelease :app:testDebugUnitTest -PuseUxPlay
```

This produces unsigned ARM32, ARM64 and universal APKs for `top.jast.miracastreceiver`. Sign them with your own release key. `-PbuildArm32` or `-PbuildArm64` limits the APK to one ABI. Builds without `-PuseUxPlay` retain the legacy Kotlin backend and its separate minimum SDK requirement.
