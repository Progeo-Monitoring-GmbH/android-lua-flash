package com.progeo.teltonika.tls

import com.progeo.teltonika.api.RouterException
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The router's certificate is different from what we pinned: another device answers at that
 * address, or the router was reset (new certificate). Only a login with the factory password
 * from the device label may pin it again.
 */
class CertificateChangedException(val expected: String, val actual: String) : RouterException(
    "The router's certificate changed. If it was reset to factory settings, scan its label and trust it again.",
    "pinned $expected, got $actual",
)

/**
 * Trust on first use for the router's self-signed certificate. With [pinned] == null, any
 * certificate is accepted and its fingerprint is kept in [seen] (to be stored in the router's
 * record); otherwise only the certificate with that SHA-256 fingerprint is accepted.
 *
 * The router is addressed by IP, so the host name check is replaced by the fingerprint check
 * ([applyTo] installs both).
 */
class PinningTrustManager(private val pinned: String?) : X509TrustManager {
    @Volatile
    var seen: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
        val fingerprint = fingerprint(leaf)
        seen = fingerprint
        if (pinned != null && !pinned.equals(fingerprint, ignoreCase = true)) {
            throw CertificateException("certificate pin mismatch", CertificateChangedException(pinned, fingerprint))
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) =
        throw CertificateException("client certificates are not used")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    /** Makes [builder] trust only this pin (or the first certificate seen). */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinningTrustManager), SecureRandom()) }
        return builder
            .sslSocketFactory(context.socketFactory, this)
            .hostnameVerifier { _, _ -> true }
    }

    companion object {
        /** SHA-256 of the DER certificate as `AB:CD:…`. */
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }
}
