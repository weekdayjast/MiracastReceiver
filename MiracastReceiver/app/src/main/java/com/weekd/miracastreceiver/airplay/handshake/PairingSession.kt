package com.weekd.miracastreceiver.airplay.handshake

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PairingSession — per-connection AirPlay pair-setup / pair-verify state machine.
 *
 * Implements the raw-binary (non-HomeKit) pairing used by RPiPlay/UxPlay for macOS/iOS
 * screen mirroring. The RTSP control channel stays plaintext; the X25519 shared secret
 * established here is exposed via [sharedSecret] for later stream-key derivation.
 *
 * Reference: RPiPlay lib/pairing.c + lib/raop_handlers.h (pair-setup / pair-verify).
 */
class PairingSession(private val keys: PairingKeys) {

    private var ecdhOursPublic: ByteArray? = null
    private var ecdhTheirsPublic: ByteArray? = null
    private var edTheirsPublic: ByteArray? = null

    /** AES-128-CTR cipher spanning M1 (encrypt our sig, keystream 0..63) and M2 (decrypt theirs, 64..127). */
    private var verifyCipher: Cipher? = null

    /** 32-byte X25519 shared secret, available after pair-verify M1. */
    var sharedSecret: ByteArray? = null
        private set

    /** POST /pair-setup → our 32-byte Ed25519 public key. */
    fun pairSetup(requestBody: ByteArray): ByteArray {
        require(requestBody.size == 32) { "pair-setup expects 32 bytes, got ${requestBody.size}" }
        return keys.edPublic
    }

    /** POST /pair-verify → dispatch on the leading state byte (1 = M1, 0 = M2). */
    fun pairVerify(body: ByteArray): ByteArray {
        require(body.size >= 4) { "pair-verify body too short: ${body.size}" }
        return when (body[0].toInt()) {
            1 -> verifyM1(body)
            0 -> verifyM2(body)
            else -> throw IllegalArgumentException("unknown pair-verify state ${body[0].toInt()}")
        }
    }

    private fun verifyM1(body: ByteArray): ByteArray {
        require(body.size == 4 + 32 + 32) { "pair-verify M1 wrong size: ${body.size}" }
        val theirEcdh = body.copyOfRange(4, 36)
        ecdhTheirsPublic = theirEcdh
        edTheirsPublic = body.copyOfRange(36, 68)

        // Our ephemeral X25519 keypair + shared secret.
        val priv = X25519PrivateKeyParameters(SecureRandom())
        val ourPub = priv.generatePublicKey().encoded
        ecdhOursPublic = ourPub
        val secret = ByteArray(32)
        X25519Agreement().apply { init(priv) }
            .calculateAgreement(X25519PublicKeyParameters(theirEcdh, 0), secret, 0)
        sharedSecret = secret

        // Sign (ourPub ‖ theirPub) with our Ed25519 identity, then AES-CTR-encrypt the signature.
        val signature = keys.sign(ourPub + theirEcdh)          // 64 bytes
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derive(SALT_KEY, secret), "AES"),
                IvParameterSpec(derive(SALT_IV, secret))
            )
        }
        verifyCipher = cipher
        val encSig = cipher.update(signature)                  // keystream bytes 0..63
        return ourPub + encSig                                 // 96 bytes
    }

    private fun verifyM2(body: ByteArray): ByteArray {
        require(body.size == 4 + 64) { "pair-verify M2 wrong size: ${body.size}" }
        val cipher = verifyCipher ?: error("pair-verify M2 received before M1")
        val ourPub = ecdhOursPublic ?: error("missing our ECDH public key")
        val theirPub = ecdhTheirsPublic ?: error("missing their ECDH public key")
        val theirEd = edTheirsPublic ?: error("missing their Ed25519 public key")

        // CTR continues at keystream offset 64 — decrypts the client's signature.
        val clientSig = cipher.update(body.copyOfRange(4, 68))
        val verifyMsg = theirPub + ourPub                      // note: order swapped vs M1
        val ok = Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(theirEd, 0))
            update(verifyMsg, 0, verifyMsg.size)
            verifySignature(clientSig)
        }
        if (!ok) throw SecurityException("pair-verify signature mismatch")
        return ByteArray(0)                                    // success → empty 200 OK
    }

    /** key/iv = SHA-512(salt ‖ ecdhSecret) truncated to 16 bytes. */
    private fun derive(salt: String, secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512")
            .digest(salt.toByteArray(Charsets.US_ASCII) + secret)
            .copyOf(16)

    companion object {
        private const val SALT_KEY = "Pair-Verify-AES-Key"
        private const val SALT_IV = "Pair-Verify-AES-IV"
    }
}

