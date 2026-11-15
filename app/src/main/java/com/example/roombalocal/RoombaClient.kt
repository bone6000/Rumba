package com.example.roombalocal

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Cliente MQTT local para Roomba (series 600/800/900).
 * Mismo protocolo que usa dorita980: MQTT sobre TLS, puerto 8883,
 * usuario y clientId = BLID, contraseña = la del robot.
 * Órdenes: topic "cmd" con {"command": "...", "time": epoch, "initiator": "localApp"}.
 */
class RoombaClient(private val listener: Listener) {

    interface Listener {
        fun onStatus(message: String)
        fun onState(state: JSONObject)
    }

    private val exec = Executors.newSingleThreadExecutor()

    @Volatile
    private var client: MqttClient? = null
    private val state = JSONObject()

    fun connect(ip: String, blid: String, password: String) {
        exec.execute {
            try {
                disconnectNow()
                listener.onStatus("Conectando…")
                val c = MqttClient("ssl://$ip:8883", blid, MemoryPersistence())
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        listener.onStatus("Conexión perdida: ${cause?.message ?: ""}")
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        try {
                            val json = JSONObject(String(message?.payload ?: return))
                            val reported = json.optJSONObject("state")?.optJSONObject("reported")
                            if (reported != null) {
                                synchronized(state) {
                                    merge(state, reported)
                                    listener.onState(JSONObject(state.toString()))
                                }
                            }
                        } catch (_: Exception) {
                            // mensaje que no es JSON de estado: se ignora
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val opts = MqttConnectOptions().apply {
                    userName = blid
                    this.password = password.toCharArray()
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    isAutomaticReconnect = false
                    socketFactory = LegacyTls.factory
                    isHttpsHostnameVerificationEnabled = false
                }
                c.connect(opts)
                c.subscribe("#")
                client = c
                listener.onStatus("Conectado")
            } catch (e: Exception) {
                client = null
                listener.onStatus("Error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun send(command: String) {
        exec.execute {
            val c = client
            if (c == null || !c.isConnected) {
                listener.onStatus("No conectado")
                return@execute
            }
            try {
                val payload = JSONObject()
                    .put("command", command)
                    .put("time", System.currentTimeMillis() / 1000)
                    .put("initiator", "localApp")
                c.publish("cmd", MqttMessage(payload.toString().toByteArray()).apply { qos = 0 })
                listener.onStatus("Enviado: $command")
            } catch (e: Exception) {
                listener.onStatus("Error enviando: ${e.message}")
            }
        }
    }

    fun disconnect() {
        exec.execute { disconnectNow() }
    }

    private fun disconnectNow() {
        try {
            client?.let {
                if (it.isConnected) it.disconnect(2000)
                it.close()
            }
        } catch (_: Exception) {
        }
        client = null
    }

    private fun merge(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = source.get(k)
            val existing = target.opt(k)
            if (v is JSONObject && existing is JSONObject) {
                merge(existing, v)
            } else if (v is JSONObject) {
                target.put(k, JSONObject(v.toString()))
            } else {
                target.put(k, v)
            }
        }
    }
}
