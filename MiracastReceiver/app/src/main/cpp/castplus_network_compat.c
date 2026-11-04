#include <android/log.h>

/*
 * The legacy Huawei discovery engine has two network-change paths: Java's
 * NearFieldManagerImpl notifications and SmartGenius' NETLINK_ROUTE watcher.
 * Android 11+ disallows the latter for modern-target apps. The Java path already
 * supplies the interface address/mask and drives UpdateLocalNetworkInterface.
 *
 * This library is loaded globally BEFORE the SDK, on Android 11+ only. Replace
 * just the SDK's two interposable watcher entry points, never libc socket/bind
 * or any discovery/authentication function. Original SDK files stay unchanged.
 * No socket exists to clean up when the Java notification path owns monitoring.
 */
__attribute__((visibility("default")))
int SmartGeniusInit(int epollFd) {
    (void) epollFd;
    __android_log_print(ANDROID_LOG_INFO, "CastPlusNetworkCompat",
                        "Using SDK Java network notifications instead of NETLINK_ROUTE");
    return 0;
}

__attribute__((visibility("default")))
void SmartGeniusClean(void) {
    __android_log_print(ANDROID_LOG_DEBUG, "CastPlusNetworkCompat",
                        "Java network notification path stopped");
}
