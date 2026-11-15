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
 * (TLS 1.2 con RSA + AES-CBC) que Android no ofrece, así que se usa BouncyCastle JSSE.
 * No se valida el certificado: úsese solo en la red local.
 */
object LegacyTls {

    val factory: SSLSocketFactory by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val provider = BouncyCastleJsseProvider(BouncyCastleProvider())
        val ctx = SSLContext.getInstance("TLSv1.2", provider)
        ctx.init(null, arrayOf(trustAll), SecureRandom())
        Wrapped(ctx.socketFactory)
    }

    private val wantedSuites = listOf(
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA"
    )

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
                val supported = s.supportedCipherSuites.toSet()
                val extra = wantedSuites.filter { it in supported }
                if (extra.isNotEmpty()) {
                    s.enabledCipherSuites = (extra + s.enabledCipherSuites.toList()).distinct().toTypedArray()
                }
                if ("TLSv1.2" in s.supportedProtocols) {
                    s.enabledProtocols = arrayOf("TLSv1.2")
                }
            }
            return s
        }
    }
}
