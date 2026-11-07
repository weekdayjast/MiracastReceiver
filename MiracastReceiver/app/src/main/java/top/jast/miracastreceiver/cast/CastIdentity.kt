package top.jast.miracastreceiver.cast

import top.jast.miracastreceiver.cast.CastWire.bytes
import top.jast.miracastreceiver.cast.CastWire.integer
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Experimental self-signed identity; it cannot pass Google's Cast CA validation. */
class CastIdentity {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val certificate: X509Certificate
    val sslContext: SSLContext

    init {
        val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
        val name = X500Name("CN=Miracast Receiver")
        val now = System.currentTimeMillis()
        val generator = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger(128, SecureRandom())))
            setSignature(algorithm)
            setIssuer(name)
            setSubject(name)
            setStartDate(Time(Date(now - 60_000)))
            setEndDate(Time(Date(now + 3 * 24 * 60 * 60_000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(key.public.encoded))
        }
        val tbs = generator.generateTBSCertificate()
        val encoded = DERSequence(arrayOf(tbs, algorithm, DERBitString(sign(tbs.encoded, "SHA256withRSA")))).encoded
        certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(encoded.inputStream()) as X509Certificate
        val password = CharArray(0)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("cast", key.private, password, arrayOf(certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }
        sslContext = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
    }

    private fun sign(data: ByteArray, algorithm: String) = Signature.getInstance(algorithm).run {
        initSign(key.private); update(data); sign()
    }

    fun authenticate(payload: ByteArray): ByteArray {
        val challenge = CastWire.fields(payload)[1]?.firstOrNull() ?: error("Missing auth challenge")
        val fields = CastWire.fields(challenge)
        val signatureAlgorithm = CastWire.number(fields, 1) ?: 1
        val hash = CastWire.number(fields, 3) ?: 0
        if (signatureAlgorithm != 1L || hash !in 0L..1L) {
            return ByteArrayOutputStream().apply {
                bytes(3, ByteArrayOutputStream().apply { integer(1, 2) }.toByteArray())
            }.toByteArray()
        }
        val nonce = fields[2]?.firstOrNull() ?: byteArrayOf()
        val response = ByteArrayOutputStream().apply {
            bytes(1, sign(nonce + certificate.encoded, if (hash == 1L) "SHA256withRSA" else "SHA1withRSA"))
            bytes(2, certificate.encoded)
            integer(4, 1); bytes(5, nonce); integer(6, hash)
        }.toByteArray()
        return ByteArrayOutputStream().apply { bytes(2, response) }.toByteArray()
    }
}
