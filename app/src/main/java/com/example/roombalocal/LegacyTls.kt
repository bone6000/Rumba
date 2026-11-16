package com.example.roombalocal

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS para hablar con el Roomba. El robot usa un certificado autofirmado y suites antiguas
 * que Android no ofrece, así que se usa BouncyCastle JSSE.
 *
 * Igual que roombapy ("DEFAULT:!DH"), se excluyen las suites Diffie-Hellman clásicas: el robot
 * negocia parámetros DH pequeños y BouncyCastle los rechaza con handshake_failure.
 * No se valida el certificado: úsese solo en la red local.
 */
object LegacyTls {

    /** Suites y protocolos ofrecidos en el último socket creado, para los mensajes de error. */
    @Volatile
    var lastOffered: String = ""

    val factory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val provider = BouncyCastleJsseProvider(BouncyCastleProvider())
        val ctx = SSLContext.getInstance("TLS", provider)
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        Wrapped(ctx.socketFactory)
    }

    private val preferred = listOf(
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA"
    )

    // Solo intercambio RSA o ECDHE/ECDH; fuera DHE, anónimas, PSK, NULL, EXPORT, etc.
    private fun usable(suite: String): Boolean =
        suite == "TLS_EMPTY_RENEGOTIATION_INFO_SCSV" ||
            ((suite.startsWith("TLS_RSA_") || suite.startsWith("TLS_ECDHE_RSA_") ||
                suite.startsWith("TLS_ECDH_RSA_") || suite.startsWith("TLS_ECDHE_ECDSA_")) &&
                !suite.contains("NULL") && !suite.contains("EXPORT") && !suite.contains("anon") &&
                !suite.contains("_PSK_"))

    private class Wrapped(private val d: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites

        override fun createSocket(): Socket = cfg(d.createSocket())

        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            cfg(d.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String?, port: Int): Socket =
            cfg(d.createSocket(host, port))

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            cfg(d.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            cfg(d.createSocket(host, port))

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            cfg(d.createSocket(address, port, localAddress, localPort))

        private fun cfg(s: Socket): Socket {
            if (s is SSLSocket) {
                val ok = s.supportedCipherSuites.filter { usable(it) }
                if (ok.isNotEmpty()) {
                    val ordered = (preferred.filter { it in ok } + ok).distinct()
                    s.enabledCipherSuites = ordered.toTypedArray()
                }
                val protos = listOf("TLSv1.2", "TLSv1.1", "TLSv1").filter { it in s.supportedProtocols }
                if (protos.isNotEmpty()) s.enabledProtocols = protos.toTypedArray()
                lastOffered = "protocolos=${s.enabledProtocols.joinToString(",")} suites=${s.enabledCipherSuites.size}"
            }
            return s
        }
    }
}
