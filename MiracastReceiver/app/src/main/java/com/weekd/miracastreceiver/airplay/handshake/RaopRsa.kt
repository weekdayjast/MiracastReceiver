package com.weekd.miracastreceiver.airplay.handshake

import com.weekd.miracastreceiver.util.Logger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * RaopRsa — recovers the legacy AirTunes/RAOP audio key (`a=rsaaeskey:` in the ANNOUNCE SDP).
 *
 * Before FairPlay, AirPlay-1 senders (older iTunes, third-party apps) shipped the 16-byte AES-128
 * stream key RSA-encrypted with the AirPort Express *public* key and OAEP/SHA-1 padding (the blob
 * decodes to 256 bytes for RSA-2048). Receivers recover it with the matching *private* key — the
 * long-public "AirPort Express" key that every open-source AirPlay receiver ships (shairport-sync,
 * RPiPlay, UxPlay). Modern macOS/iOS use FairPlay (`fpaeskey` / SETUP `ekey`) instead, so this path
 * only serves legacy senders; it complements [FairPlay] (v2/v3) to cover all three key mechanisms.
 *
 * The key is embedded as PKCS#8 DER hex (not Base64) so it loads with plain JCA on every API level
 * (minSdk 25 predates java.util.Base64) and in plain-JVM unit tests. Modulus: E7D744F2…
 * Source: shairport-sync common.c `super_secret_key`.
 */
object RaopRsa {

    /** RSA-OAEP-SHA1 transformation; the default provider (Conscrypt on Android) implements it. */
    private const val TRANSFORM = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"

    private val privateKey: PrivateKey by lazy {
        val der = hexToBytes(PRIVATE_KEY_PKCS8_HEX)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * RSA-OAEP-decrypts an `rsaaeskey` blob (128/256 bytes) into the 16-byte AES-128 stream key.
     * Returns null on any failure (malformed blob, wrong size) so a bad sender fails cleanly.
     */
    fun decryptAesKey(encrypted: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(encrypted).also {
            if (it.size != 16) Logger.w("rsaaeskey decrypted to ${it.size} bytes — expected 16")
        }
    } catch (e: Exception) {
        Logger.w("rsaaeskey RSA decrypt failed: ${e.message}")
        null
    }

    /** The public half of the embedded key — used only by tests to round-trip the decrypt path. */
    internal fun publicKeyForTest(): PublicKey {
        val crt = privateKey as RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
    }

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }

    // AirPort Express RSA-2048 private key, PKCS#8 DER as hex (public-knowledge key; see KDoc).
    private const val PRIVATE_KEY_PKCS8_HEX =
        "308204bf020100300d06092a864886f70d0101010500048204a9308204a50201000282010100e7d744f2a2e2788b6c1f55a08eb70544a8fa7945aa8be6c62ce5f51cbdd4dc6842fe3d1083dd2edec1bfd4252dc02e6f398bdf0e6148ea84855e2e442da6d62664f674a1f304929ade4f6893ef2df6e711a8c77a0d91c9d980822e50d12922afea40ea9f0e14c0f76938c5f3882fc0323dd9fe55155f51bb5921c201629fd73352d5e2efaabf9ba048d7b813a2b6767f6c3ccf1eb4ce673d037b0d2ea30c5fffeb06f8d08adde409571a9c689fef10728855dd8cfb9a8bef5c8943ef3b5faa15dde698beddf3599603eb3e6f61372bb628f6559f599a78bf500687aa7f4976c0562d412956f8989e18a6355bd81597825e0fc875343ec782117625cdbf98447b02030100010282010100e5f00c72f577d604b9a4ce4122aa84b01743ec995acfcc7f4ab27c0b187f90665be359df1259818deeed79d3b1ef845e4ddddac9a155373b5e270d8e1315001a2e527d54cdf9000a5768bc98d4446b37bbbd00b29dd8b53062133b2a6e77f4ee32505622904da720fb1c12c03996da713a0506098edbedecf936d0fa9cbd5929abb0eda35799502f9894dcb8fc569a892d17780324a2b6c3166e346709134b854041b867706b58fef2a0db922b77628b68e69693c7af43bf2a73d0b732377a0ba17b44f051e9bf79849dcb3332571fd8a70933c2d60bdec479934a3daca40bb6f2f37c0a9d07106eadc8b369a03f2f41c880098e8add46240dac68cc5354f36102818100f7e0bf5a1e6718319a8b6209c31714440459f973856613b17ae1508bb3e6316e6b7f462d2f7d64412b84b76bc23f2b0c3562455279b243a9f7316f958007b34c61f768e2d44ed5ff2b272817ec32b3e493929228fae78e774ca0f75ebd69d59202798f116e360c6438b32e1bd8b9dc1e3232f0d30918883cc43ef8dda22c369102818100ef6ffff994f1e56441aa0035fd19a0c8d6f02378c70580d9c48420791df407c591fb6ebfca322c3086dd901fd2fae1aebb64adf6bb79ff8051bebd0cd820ab8987400601a7b2fe9390cacc9acab8ed2bf91d186d8f69643d7efe0f5d56df7577a2d035ea5413fc98d8f3f908da059a379da4b1cc38f15d560a83cc317153c84b02818100d0ebafbc4025ba818c75702334384e8f696f804d7aa0e7764e507bb7d3dfefc7d678c6682d3fad713441beeae724a09ec09bdc3bc0709c9133d489ece2a51add053127490f9286d173c8a4054dc20a575c7e4c0c9834f4a1de874917a3e400eaf885062db5cb7e343689e711f75fe783d7e19192fd769cd542bea4b90107ecd10281807f4018dc7dea292da53042386f3105a0778adc6f3de690da2b74c5055983edf574661a2fd7b7de8053ccc0e208f0c8ac626f597d3d99d2ce51a37b39ae4b7e9ef2c075f0bf3d83cacd32da969192c2899235825c07d1cd3259a1906cdcd499cb613e22c94cb1ea971906609df1b0f48b063f17372034369499b5fdf970ef440d02818100904ee920f944ef5aaf7c9420a00f5e9b48082c0b84e0fbb5dda2a22677dfb7b8488db2bee64c9bdd3cac66fa320e76f71ce2af2272bbbd76cab94e084a0c41d9b0771dc63340c1accf5a89da01b437986f269cf0c216e15ea14a038cda692af0eb6db00e78802b9325204d2d20028a3f8cb13468e80f64188e1046ba1be458a6"
}

