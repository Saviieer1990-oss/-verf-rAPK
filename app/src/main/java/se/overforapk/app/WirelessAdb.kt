package se.overforapk.app

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** ADB-anslutning med TLS för Trådlös felsökning (Android 11+). Nyckel och certifikat sparas i appens lagring. */
class WirelessAdb private constructor(context: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(context.filesDir, "adb-tls/private.key").also { it.parentFile?.mkdirs() }
    private val certFile = File(context.filesDir, "adb-tls/cert.der")
    private val tlsKey: PrivateKey
    private val tlsCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val loaded = runCatching {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            key to cert
        }.getOrNull()
        val pair = loaded ?: generate().also {
            keyFile.writeBytes(it.first.encoded)
            certFile.writeBytes(it.second.encoded)
        }
        tlsKey = pair.first
        tlsCert = pair.second
    }

    override fun getPrivateKey(): PrivateKey = tlsKey
    override fun getCertificate(): Certificate = tlsCert
    override fun getDeviceName(): String = "OverforAPK"

    private fun generate(): Pair<PrivateKey, Certificate> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
        val keyPair = generator.generateKeyPair()
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000)
        val name = X500Name("CN=OverforAPK")
        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(keyPair.public).identifier)
        )
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", CertificateSubjectName(name))
        info.set("key", CertificateX509Key(keyPair.public))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(name))
        info.set("extensions", extensions)
        val cert = X509CertImpl(info)
        cert.sign(keyPair.private, algorithm)
        return keyPair.private to cert
    }

    companion object {
        @Volatile private var instance: WirelessAdb? = null

        fun get(context: Context): WirelessAdb =
            instance ?: synchronized(this) {
                instance ?: WirelessAdb(context.applicationContext).also { instance = it }
            }
    }
}
