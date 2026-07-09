package com.weekd.miracastreceiver.airplay.handshake

import android.content.Context
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * PairingKeys — the receiver's long-lived Ed25519 identity used by AirPlay pair-setup /
 * pair-verify. The 32-byte seed is persisted in app-private prefs so the device keeps a
 * stable identity across restarts (matching RPiPlay/UxPlay behaviour).
 */
class PairingKeys private constructor(private val edPrivate: Ed25519PrivateKeyParameters) {

    /** 32-byte raw Ed25519 public key. */
    val edPublic: ByteArray by lazy { edPrivate.generatePublicKey().encoded }

    /** Ed25519 signature (64 bytes) over [message]. */
    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, edPrivate)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        private const val PREFS = "phairplay_prefs"
        private const val KEY_SEED = "pairing_ed25519_seed"

        @Volatile private var instance: PairingKeys? = null

        fun get(context: Context): PairingKeys =
            instance ?: synchronized(this) { instance ?: load(context).also { instance = it } }

        /** Builds keys from a raw 32-byte Ed25519 seed (used by tests). */
        fun create(seed: ByteArray): PairingKeys =
            PairingKeys(Ed25519PrivateKeyParameters(seed, 0))

        private fun load(context: Context): PairingKeys {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seed = prefs.getString(KEY_SEED, null)?.let(::hexToBytes)
                ?: ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                    prefs.edit().putString(KEY_SEED, bytesToHex(it)).apply()
                }
            return create(seed)
        }

        private fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        private fun hexToBytes(s: String): ByteArray =
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

