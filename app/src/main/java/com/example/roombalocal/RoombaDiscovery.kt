package com.example.roombalocal

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

data class FoundRobot(val ip: String, val blid: String, val name: String, val sku: String)

/**
 * Descubrimiento del robot y obtención local de sus credenciales, como hacen
 * dorita980 y roombapy. Todo ocurre en tu red local, sin la nube.
 *
 * - IP y BLID: mensaje UDP "irobotmcs" al puerto 5678; el robot responde con un JSON.
 * - Contraseña: solo se entrega si el robot está en modo emparejamiento
 *   (el usuario mantiene pulsado HOME hasta el tono).
 */
object RoombaDiscovery {
    private const val UDP_PORT = 5678
    private const val TLS_PORT = 8883

    fun discover(context: Context, timeoutMs: Int = 4000): List<FoundRobot> {
        val found = LinkedHashMap<String, FoundRobot>()
        val msg = "irobotmcs".toByteArray()
        val targets = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        subnetBroadcast(context)?.let { targets.add(it) }

        DatagramSocket(null).use { s ->
            s.reuseAddress = true
            s.broadcast = true
            s.soTimeout = 500
            s.bind(InetSocketAddress(0))
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSend = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now - lastSend >= 1000) {
                    for (t in targets) {
                        try {
                            s.send(DatagramPacket(msg, msg.size, t, UDP_PORT))
                        } catch (_: Exception) {
                        }
                    }
                    lastSend = now
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val text = String(p.data, 0, p.length)
                    parse(text, p.address.hostAddress ?: "")?.let { found[it.ip] = it }
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return found.values.toList()
    }

    private fun parse(text: String, sourceIp: String): FoundRobot? {
        return try {
            val j = JSONObject(text)
            val hostname = j.optString("hostname")
            val blid = hostname.substringAfter('-', "").ifEmpty { j.optString("blid") }
            if (blid.isEmpty()) return null
            val ip = j.optString("ip").ifEmpty { sourceIp }
            FoundRobot(ip, blid, j.optString("robotname"), j.optString("sku"))
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(context: Context): InetAddress? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val d = wm.dhcpInfo ?: return null
            if (d.ipAddress == 0) return null
            val b = (d.ipAddress and d.netmask) or d.netmask.inv()
            val bytes = byteArrayOf(
                (b and 0xff).toByte(),
                ((b shr 8) and 0xff).toByte(),
                ((b shr 16) and 0xff).toByte(),
                ((b shr 24) and 0xff).toByte()
            )
            InetAddress.getByAddress(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private val PASSWORD_REQUEST = byteArrayOf(0xf0.toByte(), 0x05, 0xef.toByte(), 0xcc.toByte(), 0x3b, 0x29, 0x00)
    private val UNSUPPORTED_MAGIC = byteArrayOf(0xf0.toByte(), 0x05, 0xef.toByte(), 0xcc.toByte(), 0x3b, 0x29, 0x03)

    /** El robot indica que su contraseña solo se obtiene desde la nube de iRobot. */
    private class CloudOnlyException : IOException(
        "Este modelo solo entrega la contraseña desde la nube de iRobot."
    )

    /**
     * Pide la contraseña al robot. Hay que haberlo puesto antes en modo emparejamiento
     * (HOME pulsado hasta el tono). Igual que roombapy: TLS primero y la petición dentro del TLS;
     * como alternativa, el intercambio antiguo (paquete en claro y luego TLS).
     */
    fun fetchPassword(ip: String): String {
        val errors = mutableListOf<String>()
        val variants = listOf("TLS directo", "paquete previo + TLS")
        for ((i, name) in variants.withIndex()) {
            if (i > 0) Thread.sleep(2500) // el robot solo atiende una conexión a la vez
            try {
                val data = if (i == 0) tlsThenPacket(ip) else plainThenTls(ip)
                val pw = parsePassword(data)
                if (pw != null) return pw
                errors.add("$name: respuesta de ${data.size} bytes (¿está el robot en modo emparejamiento?)")
            } catch (e: CloudOnlyException) {
                throw e
            } catch (e: Exception) {
                errors.add("$name: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        throw IOException(errors.joinToString("\n") + "\n(" + LegacyTls.lastOffered + ")")
    }

    private fun tlsThenPacket(ip: String): ByteArray {
        val plain = Socket()
        plain.connect(InetSocketAddress(ip, TLS_PORT), 5000)
        plain.use {
            val ssl = LegacyTls.factory.createSocket(it, ip, TLS_PORT, true) as SSLSocket
            ssl.soTimeout = 4000
            ssl.startHandshake()
            ssl.outputStream.apply {
                write(PASSWORD_REQUEST)
                flush()
            }
            return readFramed(ssl)
        }
    }

    // Firmware antiguo: paquete en claro; si la respuesta es corta, se pasa a TLS y se repite.
    private fun plainThenTls(ip: String): ByteArray {
        val plain = Socket()
        plain.connect(InetSocketAddress(ip, TLS_PORT), 5000)
        plain.soTimeout = 3000
        plain.use {
            it.getOutputStream().apply {
                write(PASSWORD_REQUEST)
                flush()
            }
            val buf = ByteArray(1024)
            val n = try {
                it.getInputStream().read(buf)
            } catch (_: SocketTimeoutException) {
                -1
            }
            if (n > 7) return buf.copyOf(n)
            val ssl = LegacyTls.factory.createSocket(it, ip, TLS_PORT, true) as SSLSocket
            ssl.soTimeout = 4000
            ssl.startHandshake()
            ssl.outputStream.apply {
                write(PASSWORD_REQUEST)
                flush()
            }
            return readFramed(ssl)
        }
    }

    // La respuesta trae su longitud en el segundo byte: total = longitud + 2.
    private fun readFramed(s: Socket): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        var need = 35
        try {
            while (out.size() < need + 2) {
                val n = s.getInputStream().read(buf)
                if (n < 0) break
                val chunk = buf.copyOf(n)
                if (chunk.contentEquals(UNSUPPORTED_MAGIC)) throw CloudOnlyException()
                out.write(chunk)
                if (out.size() >= 2) need = out.toByteArray()[1].toInt() and 0xff
            }
        } catch (_: SocketTimeoutException) {
        }
        return out.toByteArray()
    }

    private fun parsePassword(data: ByteArray): String? {
        if (data.size <= 7) return null
        val pw = String(data, 7, data.size - 7).trimEnd('\u0000').trim()
        return pw.ifEmpty { null }
    }
}
