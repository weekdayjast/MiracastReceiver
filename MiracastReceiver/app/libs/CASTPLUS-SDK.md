# Huawei Cast+ SDK binary provenance

This experimental branch uses the unmodified `HuaweiCastPlusEngine.jar` supplied
locally by the repository owner. SHA256:

`27359bfa0d5cced357cc317e411e33e8bd33c0018fd1623ed73acdc977feee3f`

Native libraries were copied without modification from:

- Repository: https://github.com/ljlVink/ScreenCastPlusDemo
- Commit: `6bc1c9b0e93b2329e54aa38bd8d75456fae11745`
- Directory: `app/src/main/jniLibs/`
- ABIs: `armeabi-v7a` and `arm64-v8a`, 11 libraries each.
- Original native binary hashes: `CASTPLUS-SDK-SHA256.json` in this directory.

The demo repository includes an Apache-2.0 license and describes the Huawei SDK
as not publicly open. The supplied JAR contains no license entry. This file
records origin; it does not assign the demo's source-code license to Huawei's
SDK binaries. Confirm the SDK's distribution terms before publishing a release
that includes them. No credentials or authentication checks have been removed.

The application references the original binary API; decompiled Java is retained
only as local analysis material and is not compiled into the application.
