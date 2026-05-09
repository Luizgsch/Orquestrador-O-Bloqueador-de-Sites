package com.orquestrador.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object DnsResolver {

    private val dnsServers = listOf("8.8.8.8", "1.1.1.1")
    private const val UPSTREAM_PORT = 53
    private const val DNS_TIMEOUT_MS = 500
    private const val MAX_DNS_RESPONSE = 512

    private var cachedSocket: DatagramSocket? = null
    private val socketLock = ReentrantReadWriteLock()

    private fun getOrCreateSocket(): DatagramSocket? {
        return socketLock.read {
            cachedSocket?.takeIf { !it.isClosed }
        } ?: socketLock.write {
            cachedSocket?.takeIf { !it.isClosed } ?: try {
                DatagramSocket().apply {
                    soTimeout = DNS_TIMEOUT_MS
                    reuseAddress = true
                    cachedSocket = this
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun extractDnsName(payload: ByteArray): String? {
        return try {
            val buf = ByteBuffer.wrap(payload)
            if (buf.remaining() < 12) return null
            buf.position(12)

            val labels = mutableListOf<String>()
            while (buf.hasRemaining()) {
                val len = buf.get().toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 == 0xC0) return null

                if (buf.remaining() < len) return null
                val label = ByteArray(len)
                buf.get(label)
                labels.add(String(label, Charsets.US_ASCII))
            }
            labels.joinToString(".")
        } catch (_: Exception) {
            null
        }
    }

    fun buildServfailResponse(query: ByteArray): ByteArray {
        return try {
            val buf = ByteBuffer.wrap(query)
            if (query.size < 12) return query

            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)

            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x82.toByte())
            response.add(0x00)
            response.add(0x01)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)

            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get())
                        response.add(buf.get())
                        response.add(buf.get())
                        response.add(buf.get())
                    }
                    break
                }
            }

            response.toByteArray()
        } catch (_: Exception) {
            query
        }
    }

    fun buildBlockResponse(query: ByteArray): ByteArray {
        return try {
            val buf = ByteBuffer.wrap(query)
            if (query.size < 12) query

            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)

            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x80.toByte())
            response.add(0x00)
            response.add(0x01)
            response.add(0x00)
            response.add(0x01)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)

            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get())
                        response.add(buf.get())
                        response.add(buf.get())
                        response.add(buf.get())
                    }
                    break
                }
            }

            response.add(0xC0.toByte())
            response.add(0x0C.toByte())
            response.add(0x00)
            response.add(0x01)
            response.add(0x00)
            response.add(0x01)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x3C.toByte())
            response.add(0x00)
            response.add(0x04)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)
            response.add(0x00)

            response.toByteArray()
        } catch (_: Exception) {
            query
        }
    }

    fun forwardToUpstream(query: ByteArray): ByteArray? {
        for (upstreamHost in dnsServers) {
            try {
                val sock = getOrCreateSocket() ?: continue

                val dest = InetAddress.getByName(upstreamHost)
                sock.send(DatagramPacket(query, query.size, dest, UPSTREAM_PORT))

                val buf = ByteArray(MAX_DNS_RESPONSE)
                val resp = DatagramPacket(buf, buf.size)

                sock.receive(resp)
                if (resp.length > 0) {
                    return buf.copyOf(resp.length)
                }
            } catch (timeoutEx: java.net.SocketTimeoutException) {
                socketLock.write {
                    cachedSocket?.close()
                    cachedSocket = null
                }
                continue
            } catch (ex: Exception) {
                socketLock.write {
                    cachedSocket?.close()
                    cachedSocket = null
                }
                continue
            }
        }

        return null
    }

    fun closeSocket() {
        socketLock.write {
            cachedSocket?.close()
            cachedSocket = null
        }
    }
}
