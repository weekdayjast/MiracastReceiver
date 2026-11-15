package top.jast.miracastreceiver.service

/** Process-local service state; the custom sender's mDNS record is not a readiness check. */
object ReceiverStatus {
    enum class Service { STARTING, WAITING_NETWORK, RUNNING, STOPPED }
    enum class Miracast { CHECKING, ADVERTISING, SYSTEM_MANAGED, ROOT_REQUIRED, SETUP_FAILED,
        UNSUPPORTED, PERMISSION_REQUIRED, WIFI_DISABLED }

    @Volatile var service = Service.STOPPED
        internal set
    @Volatile var miracast = Miracast.CHECKING
        internal set
}
