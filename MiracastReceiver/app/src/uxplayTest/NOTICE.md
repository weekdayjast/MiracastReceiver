# UxPlay protocol test backend

Enable with `-PbuildArm32 -PuseUxPlayTest`. The test APK advertises the configured receiver name followed by `UxPlay`.

The JNI bridge declarations under `io/github/jqssun/airplay/bridge` come from jqssun/android-airplay-server v0.0.36, licensed under GPL-3.0. The original license is included as `LICENSE.android-airplay-server`.

Source project https://github.com/jqssun/android-airplay-server/tree/v0.0.36

The ARM32 libraries were built locally from that source with its bundled patches 0001–0009. UxPlay is pinned to commit `df67c212a433cf6dda3676dd40c097900d24e645`. The local build enables LOGGER_DEBUG in native_bridge.cpp and adds patch 0010 to log playback-info snapshots and responses.

The native library includes UxPlay, playfair, libplist and static FFmpeg ALAC/AAC decoding. Its dynamic dependencies packaged here are libcrypto, liboboe and libc++_shared. Sources and their notices are in the source project's pinned submodules. The local source checkout and build script are in `_release/android-airplay-server-0.0.36` and `_release/build-airplay-protocol-debug.sh` at the repository root.

This backend supports HLS video delivery from the YouTube in-app AirPlay control and advertises screen mirroring. HLS audio and video are rendered by MiracastReceiver's AirPlayHlsPlayer. Screen mirroring uses the reference project's VideoRenderer, VideoPipeline, EglCore and DecoderSelector (under the same GPL-3.0 license), with H.264 MediaCodec decoding and EGL surface output. Mirroring audio uses the native RAOP audio engine. Independent audio-only playback and HEVC are not enabled by this test adapter.
