# UxPlay AirPlay backend

Build with -PuseUxPlay (both ARM ABIs/universal), -PbuildArm32 -PuseUxPlay, or -PbuildArm64 -PuseUxPlay. The packaged native libraries require Android 6 / API 23 or newer and cover ARM32 and ARM64. Builds without -PuseUxPlay retain the legacy Kotlin backend.

The JNI bridge and mirror renderer originate from [jqssun/android-airplay-server v0.0.36](https://github.com/jqssun/android-airplay-server/tree/v0.0.36), licensed under GPL-3.0. The original license is included as LICENSE.android-airplay-server.

UxPlay is pinned to df67c212a433cf6dda3676dd40c097900d24e645. The native library includes UxPlay/playfair, libplist and static FFmpeg ALAC/AAC decoding, with packaged dynamic dependencies libcrypto, liboboe and libc++_shared. The source project's pinned submodules retain their own licenses and notices.

native-patches/ contains upstream integration patches 0001–0009 and local compatibility patches 0011–0012. Patch 0011 tolerates property requests after playlist removal. Patch 0012 forwards sender-selected subtitles to Android. native-source/ retains the modified JNI bridge/callback files. The repository-local source checkout is _release/android-airplay-server-0.0.36; its helper is _release/build-airplay-protocol-debug.sh (the helper name is historical).

This build uses native INFO logging. Temporary playback-info snapshot/response probes (patch 0010) are excluded. SHA-256 of ARM32 libairplay_native.so: 06f4782734a02e1d5572eb196b822a6e2eb12106f73351db0c1b885516fa90e8.

HLS video uses Media3 with sender-selected subtitles. Other URLs retain the baseline Android MediaPlayer path with Media3 fallback for unsupported/malformed media. Screen mirroring uses the imported H.264 MediaCodec/EGL renderer and native mirror audio. HEVC mirroring and independent audio-only output are not enabled by this adapter.

These source files retain their upstream license obligations. This optional native integration is not covered solely by the root project's MIT license.

ARM64 native library SHA-256: 49cb0e6126acfe18147514531ef90e2293de25738cfaa9e535acdc006eae08dd.
