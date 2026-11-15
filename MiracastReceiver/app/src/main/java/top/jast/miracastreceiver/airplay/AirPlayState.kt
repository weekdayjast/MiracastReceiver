package top.jast.miracastreceiver.airplay

/**
 * Lightweight AirPlay protocol state used by the integrated PhairPlay receiver.
 */
enum class AirPlayState {
    DISABLED,
    ADVERTISING,
    CONNECTED,
    ERROR
}
