package com.orquestrador.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object DnsResolver {

    private const val UPSTREAM_PORT = 53
    private const val DNS_TIMEOUT_MS = 2500
    private const val MAX_DNS_RESPONSE = 512
    private const val LRU_CAPACITY = 100
    private const val LOG_TAG = "DnsResolver"

    private var cachedSocket: DatagramSocket? = null
    private val socketLock = ReentrantReadWriteLock()

    @Volatile private var protectFn: ((DatagramSocket) -> Boolean)? = null

    private val lruCache = object : LinkedHashMap<String, ByteArray>(LRU_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ByteArray>) = size > LRU_CAPACITY
    }

    fun setProtect(fn: ((DatagramSocket) -> Boolean)?) {
        protectFn = fn
    }

    @Synchronized
    fun getCached(name: String): ByteArray? = lruCache[name]

    @Synchronized
    fun cache(name: String, response: ByteArray) {
        lruCache[name] = response
    }

    @Synchronized
    fun clearCache() = lruCache.clear()

    private fun getOrCreateSocket(): DatagramSocket? {
        return socketLock.read {
            cachedSocket?.takeIf { !it.isClosed }
        } ?: socketLock.write {
            cachedSocket?.takeIf { !it.isClosed } ?: try {
                val socket = DatagramSocket()
                val fn = protectFn
                if (fn != null && !fn(socket)) {
                    socket.close()
                    null
                } else {
                    socket.soTimeout = DNS_TIMEOUT_MS
                    socket.reuseAddress = true
                    cachedSocket = socket
                    socket
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun tryForward(query: ByteArray, host: String): ByteArray? {
        return try {
            val sock = getOrCreateSocket() ?: return null
            val dest = InetAddress.getByName(host)
            sock.send(DatagramPacket(query, query.size, dest, UPSTREAM_PORT))
            val buf = ByteArray(MAX_DNS_RESPONSE)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            if (resp.length > 0) buf.copyOf(resp.length) else null
        } catch (_: java.net.SocketTimeoutException) {
            socketLock.write { cachedSocket?.close(); cachedSocket = null }
            null
        } catch (_: Exception) {
            socketLock.write { cachedSocket?.close(); cachedSocket = null }
            null
        }
    }

    fun forwardToUpstream(
        query: ByteArray,
        upstreams: List<String>,
        lastResort: String? = null,
    ): ByteArray? {
        for (host in upstreams) {
            val result = tryForward(query, host)
            if (result != null) return result
        }
        if (lastResort != null) {
            System.err.println("[$LOG_TAG] Falha de Upstream DNS")
            return tryForward(query, lastResort)
        }
        return null
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
            if (query.size < 12) return query
            val buf = ByteBuffer.wrap(query)
            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)
            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x82.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get()); response.add(buf.get())
                        response.add(buf.get()); response.add(buf.get())
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
            if (query.size < 12) return query
            val buf = ByteBuffer.wrap(query)
            val txid = ByteArray(2)
            buf.position(0)
            buf.get(txid)
            val response = mutableListOf<Byte>()
            response.addAll(txid.toList())
            response.add(0x81.toByte())
            response.add(0x80.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            buf.position(12)
            while (buf.hasRemaining() && response.size < 512) {
                val b = buf.get()
                response.add(b)
                if (b == 0x00.toByte()) {
                    if (buf.remaining() >= 4) {
                        response.add(buf.get()); response.add(buf.get())
                        response.add(buf.get()); response.add(buf.get())
                    }
                    break
                }
            }
            response.add(0xC0.toByte()); response.add(0x0C.toByte())
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x01)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x3C.toByte())
            response.add(0x00); response.add(0x04)
            response.add(0x00); response.add(0x00)
            response.add(0x00); response.add(0x00)
            response.toByteArray()
        } catch (_: Exception) {
            query
        }
    }

    fun closeSocket() {
        socketLock.write {
            cachedSocket?.close()
            cachedSocket = null
        }
    }
}
