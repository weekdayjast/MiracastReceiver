/*
 * wfdctl —— wpa_supplicant 控制接口客户端，用于注入 Wi-Fi Display Sink 的 WFD IE。
 *
 * 为什么需要它：Windows 的「连接到无线显示器」靠扫描 beacon/probe response 里的
 * WFD IE 来发现设备，而该 IE 只能由 wpa_supplicant 广播。应用层调
 * WifiP2pManager.setWFDInfo() 需要 signature 级的 CONFIGURE_WIFI_DISPLAY 权限，
 * 普通应用必定拿到 SecurityException（实测日志：
 * "Wifi Display Permission denied for uid = 10392"）。
 *
 * 绕过办法是直接和 wpa_supplicant 的控制 socket 对话，但那个 socket 属主是 wifi 用户
 * 且受 SELinux 保护，所以本程序需要由 su 拉起。ROM 通常不带 wpa_cli，故自带一个。
 *
 * 打包方式：编译产物命名为 libwfdctl.so，这样会被 AGP 打进 APK 的 jniLibs，
 * 解压到 nativeLibraryDir 且带可执行权限，可以用 su 直接执行。
 *
 * 协议：控制接口是 UNIX datagram socket，收发纯文本。客户端自己的 socket 必须建在
 * wpa_supplicant 能写回的目录里（继承 wifi_data_file 的 SELinux 标签）并 chown 给 wifi。
 *
 * 用法：libwfdctl.so <ctrl_socket> <命令> [命令...]
 * 例如：libwfdctl.so /data/misc/wifi/sockets/p2p0 "SET wifi_display 1" \
 *                   "WFD_SUBELEM_SET 0 000600111c440032"
 */
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define WIFI_UID 1010 /* AID_WIFI */

static int ctrl_fd = -1;
static char local_path[108];

static int ctrl_open(const char *server_path)
{
    struct sockaddr_un local, dest;
    char dir[108];
    char *dir_end;

    ctrl_fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (ctrl_fd < 0) {
        fprintf(stderr, "socket() failed: %s\n", strerror(errno));
        return -1;
    }

    snprintf(dir, sizeof(dir), "%s", server_path);
    dir_end = strrchr(dir, '/');
    if (dir_end)
        *dir_end = '\0';
    snprintf(local_path, sizeof(local_path), "%s/wfdctl_%d", dir, (int) getpid());

    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    snprintf(local.sun_path, sizeof(local.sun_path), "%s", local_path);
    unlink(local_path);
    if (bind(ctrl_fd, (struct sockaddr *) &local, sizeof(local)) < 0) {
        fprintf(stderr, "bind(%s) failed: %s\n", local_path, strerror(errno));
        return -1;
    }
    /* wpa_supplicant 以 wifi 用户运行，回包要能写进来 */
    if (chown(local_path, WIFI_UID, WIFI_UID) < 0)
        fprintf(stderr, "warn: chown failed: %s\n", strerror(errno));
    if (chmod(local_path, 0770) < 0)
        fprintf(stderr, "warn: chmod failed: %s\n", strerror(errno));

    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    snprintf(dest.sun_path, sizeof(dest.sun_path), "%s", server_path);
    if (connect(ctrl_fd, (struct sockaddr *) &dest, sizeof(dest)) < 0) {
        fprintf(stderr, "connect(%s) failed: %s\n", server_path, strerror(errno));
        return -1;
    }
    return 0;
}

/*
 * 发一条命令并尝试读回复。
 * 注意：SELinux enforcing 下 wpa_supplicant 回包会被拒（scontext=su），
 * 但命令本身已经送达并执行 —— 实测 WFD_SUBELEM_GET 能读回刚设进去的值。
 * 因此读不到回复不算失败，只记录。
 */
static int ctrl_request(const char *cmd)
{
    char reply[4096];
    struct timeval tv;
    ssize_t n;

    if (send(ctrl_fd, cmd, strlen(cmd), 0) < 0) {
        fprintf(stderr, "send(%s) failed: %s\n", cmd, strerror(errno));
        return -1;
    }

    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(ctrl_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    n = recv(ctrl_fd, reply, sizeof(reply) - 1, 0);
    if (n > 0) {
        reply[n] = '\0';
        printf("%s -> %s\n", cmd, reply);
        /* An explicit rejection is different from an SELinux-blocked reply. */
        if (strncmp(reply, "FAIL", 4) == 0 || strncmp(reply, "UNKNOWN COMMAND", 15) == 0)
            return -1;
    } else {
        /* 回包被 SELinux 挡下时走这里，命令通常已生效 */
        printf("%s -> (sent, no reply)\n", cmd);
    }
    return 0;
}

int main(int argc, char *argv[])
{
    int i, rc = 0;

    if (argc < 3) {
        fprintf(stderr, "usage: %s <ctrl_socket> <command> [command...]\n", argv[0]);
        return 2;
    }

    if (ctrl_open(argv[1]) < 0) {
        if (local_path[0])
            unlink(local_path);
        return 1;
    }

    for (i = 2; i < argc; i++) {
        if (ctrl_request(argv[i]) < 0)
            rc = 1;
    }

    if (ctrl_fd >= 0)
        close(ctrl_fd);
    if (local_path[0])
        unlink(local_path);
    return rc;
}
