package com.weekd.miracastreceiver.airplay

/**
 * RtspRequest — Represents a parsed RTSP request received from the AirPlay sender.
 *
 * WHY: Parsing the raw TCP stream into a structured object lets each RTSP method handler
 * work with clean typed data instead of string manipulation.
 *
 * @param method  The RTSP method (e.g., "OPTIONS", "ANNOUNCE", "RECORD")
 * @param uri     The request URI (e.g., "rtsp://192.168.1.1/phairplay")
 * @param headers All headers as a key→value map (keys are case-sensitive per RFC 2326)
 * @param body    The request body text (empty string if Content-Length was 0 or absent)
 */
data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String>,
    val body: String,
    val bodyBytes: ByteArray = body.toByteArray(Charsets.UTF_8),
    val protocol: String = "RTSP/1.0"
)

/**
 * RtspResponse — Represents an RTSP response to send back to the AirPlay sender.
 *
 * WHY: Building responses as data objects (rather than raw strings) makes the handler
 * methods easier to test — they return typed data, not side-effectful writes.
 *
 * @param statusCode    HTTP-like status code (200 = OK, 400 = Bad Request, 503 = Unavailable, …)
 * @param statusMessage Human-readable status phrase (e.g., "OK", "Not Found")
 * @param headers       Optional extra headers included in the serialized response
 * @param body          Optional text response body (e.g., SDP for a re-ANNOUNCE)
 * @param bodyBytes     Optional binary response body. When non-null this is the exact
 *                      wire body (used for AirPlay 2 binary plists, FairPlay, encrypted
 *                      payloads). Takes precedence over [body].
 * @param contentType   Optional Content-Type header (e.g. "application/x-apple-binary-plist").
 */
data class RtspResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val bodyBytes: ByteArray? = null,
    val contentType: String? = null,
    val protocol: String = "RTSP/1.0"
)

/** Effective wire body: prefers binary [RtspResponse.bodyBytes], else UTF-8 of [RtspResponse.body]. */
fun RtspResponse.wireBody(): ByteArray = bodyBytes ?: body.toByteArray(Charsets.UTF_8)

