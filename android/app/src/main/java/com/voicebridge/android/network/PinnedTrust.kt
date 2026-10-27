package com.voicebridge.android.network

import com.voicebridge.android.pairing.fingerprintOf
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

private val PINNED_FINGERPRINT = Regex("SHA256:[0-9a-f]{64}")

/**
 * OkHttp client that completes a TLS handshake only with the peer whose leaf certificate is
 * [fingerprint].
 *
 * A `okhttp3.CertificatePinner` is not enough on its own: OkHttp runs it after the handshake, and
 * its default trust manager rejects the self-signed certificate the Windows side generates for a
 * LAN address before the pin is ever checked. The trust manager below replaces the platform one so
 * the hash decides whether the chain is acceptable.
 */
fun clientFor(fingerprint: String): OkHttpClient {
    require(PINNED_FINGERPRINT.matches(fingerprint)) {
        "A pin is 'SHA256:' plus 64 lowercase hex digits"
    }
    val trustManager = FingerprintTrustManager(fingerprint)
    val context = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(trustManager), null)
    }
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trustManager)
        // Pinning replaces hostname verification here. The peer is a self-signed LAN device, so no
        // CA vouches for its name and its SAN can be absent or stale; requiring a matching name
        // would reject correctly pinned devices while proving less than a full certificate hash.
        // The hash is exactly what the user confirmed during pairing, so it authenticates the
        // device and is not weakened by skipping the name check.
        .hostnameVerifier { _, _ -> true }
        // A peer that dies without a TCP reset (Wi-Fi drop, PC sleep) would otherwise look
        // connected forever and the reconnect loop would never run.
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
}

private class FingerprintTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // This client never sits on the server side of a handshake, so no client chain is trusted.
        throw CertificateException("Client certificates are not accepted")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw SSLException("The peer presented no certificate")
        // An SSLException aborts the handshake exactly like a CertificateException and reaches the
        // caller as a TLS failure, so a wrong peer can never be reached.
        if (fingerprintOf(leaf.encoded) != fingerprint) {
            throw SSLException("The peer certificate does not match the pinned fingerprint")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
