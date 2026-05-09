package com.orquestrador.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.orquestrador.MainActivity
import com.orquestrador.db.BlockedDomainDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class OrquestradorVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.orquestrador.vpn.START"
        const val ACTION_STOP = "com.orquestrador.vpn.STOP"
        const val EXTRA_ENABLED_CATEGORIES = "enabled_categories"
        const val CHANNEL_ID = "vpn_service"
        const val NOTIF_ID = 1
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_PREFIX = 0
    }

    private var tunFd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var enabledCategories: Set<BlockList.Category> = emptySet()
    private var allDomains: Map<String, String> = emptyMap()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
        }

        val cats = intent?.getStringArrayExtra(EXTRA_ENABLED_CATEGORIES)
            ?.mapNotNull { runCatching { BlockList.Category.valueOf(it) }.getOrNull() }
            ?.toSet() ?: emptySet()
        enabledCategories = cats

        allDomains = BlockedDomainDatabase(this).run {
            val domains = getAllDomains()
            close()
            domains
        }

        startForeground(NOTIF_ID, buildNotification())
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        tunFd?.close()
        tunFd = Builder()
            .setSession("Orquestrador VPN")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .setBlocking(false)
            .establish() ?: run {
            stopSelf()
            return
        }

        scope.launch { runPacketLoop() }
    }

    private suspend fun runPacketLoop() {
        val fd = tunFd ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32_768)

        while (scope.isActive) {
            val len = withContext(Dispatchers.IO) { input.read(buf) }
            if (len <= 0) {
                delay(1)
                continue
            }

            val packet = buf.copyOf(len)
            val dnsPacket = parseDnsFromIpPacket(packet) ?: continue

            val name = DnsResolver.extractDnsName(dnsPacket.dnsPayload) ?: continue

            val responsePayload = if (isBlockedDomain(name)) {
                DnsResolver.buildBlockResponse(dnsPacket.dnsPayload)
            } else {
                DnsResolver.forwardToUpstream(dnsPacket.dnsPayload) ?: continue
            }

            val responsePacket = buildIpUdpPacket(
                dnsPacket.ipHeader,
                dnsPacket.udpHeader,
                responsePayload
            )

            withContext(Dispatchers.IO) {
                try {
                    output.write(responsePacket)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun isBlockedDomain(domain: String): Boolean {
        val bare = domain.removePrefix("www.").lowercase()
        val category = allDomains[bare] ?: allDomains["www.$bare"] ?: return false
        return category in enabledCategories.map { it.name }
    }

    private fun stopVpn() {
        scope.cancel()
        tunFd?.close()
        tunFd = null
        DnsResolver.closeSocket()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("Orquestrador Ativo")
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun parseDnsFromIpPacket(pkt: ByteArray): DnsPacketParts? {
        return try {
            if (pkt.size < 20) return null

            val protocol = pkt[9].toInt() and 0xFF
            if (protocol != 0x11) return null

            val ihl = ((pkt[0].toInt() and 0x0F) * 4)
            if (pkt.size < ihl + 8) return null

            val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or
                    (pkt[ihl + 3].toInt() and 0xFF)

            if (dstPort != 53) return null

            val ipHeader = pkt.copyOfRange(0, ihl)
            val udpHeader = pkt.copyOfRange(ihl, ihl + 8)
            val dnsPayload = pkt.copyOfRange(ihl + 8, pkt.size)

            DnsPacketParts(ipHeader, udpHeader, dnsPayload)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildIpUdpPacket(
        origIpHeader: ByteArray,
        origUdpHeader: ByteArray,
        dnsPayload: ByteArray,
    ): ByteArray {
        val newIp = origIpHeader.copyOf()
        val newUdp = origUdpHeader.copyOf()

        swapIpAddresses(newIp)
        swapUdpPorts(newUdp)

        val newUdpLen = 8 + dnsPayload.size
        newUdp[4] = (newUdpLen shr 8).toByte()
        newUdp[5] = (newUdpLen and 0xFF).toByte()
        newUdp[6] = 0x00
        newUdp[7] = 0x00

        val newTotalLen = newIp.size + newUdpLen
        newIp[2] = (newTotalLen shr 8).toByte()
        newIp[3] = (newTotalLen and 0xFF).toByte()

        recalcIpChecksum(newIp)

        val response = ByteArray(newIp.size + newUdp.size + dnsPayload.size)
        var offset = 0
        System.arraycopy(newIp, 0, response, offset, newIp.size)
        offset += newIp.size
        System.arraycopy(newUdp, 0, response, offset, newUdp.size)
        offset += newUdp.size
        System.arraycopy(dnsPayload, 0, response, offset, dnsPayload.size)

        return response
    }

    private fun swapIpAddresses(header: ByteArray) {
        val tmp1 = header[12]
        val tmp2 = header[13]
        val tmp3 = header[14]
        val tmp4 = header[15]

        header[12] = header[16]
        header[13] = header[17]
        header[14] = header[18]
        header[15] = header[19]

        header[16] = tmp1
        header[17] = tmp2
        header[18] = tmp3
        header[19] = tmp4
    }

    private fun swapUdpPorts(header: ByteArray) {
        val tmp1 = header[0]
        val tmp2 = header[1]

        header[0] = header[2]
        header[1] = header[3]

        header[2] = tmp1
        header[3] = tmp2
    }

    private fun recalcIpChecksum(header: ByteArray) {
        header[10] = 0x00
        header[11] = 0x00

        var sum = 0
        for (i in 0 until header.size step 2) {
            val w = ((header[i].toInt() and 0xFF) shl 8) or
                    (header.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: 0)
            sum += w
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }

        val checksum = sum.inv() and 0xFFFF
        header[10] = (checksum shr 8).toByte()
        header[11] = (checksum and 0xFF).toByte()
    }
}

private data class DnsPacketParts(
    val ipHeader: ByteArray,
    val udpHeader: ByteArray,
    val dnsPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsPacketParts) return false
        return ipHeader.contentEquals(other.ipHeader) &&
                udpHeader.contentEquals(other.udpHeader) &&
                dnsPayload.contentEquals(other.dnsPayload)
    }

    override fun hashCode(): Int {
        var result = ipHeader.contentHashCode()
        result = 31 * result + udpHeader.contentHashCode()
        result = 31 * result + dnsPayload.contentHashCode()
        return result
    }
}
