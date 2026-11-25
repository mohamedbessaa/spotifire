package fr.spotifeur.social

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SNTP (RFC 4330), for Listen together: each phone measures its own clock against nearby public time servers, so the
 * host can say "this song is at X ms at true time T" and relay delays stop mattering.
 */
object Ntp {
    data class Sample(val roundTrip: Long, val offset: Long)

    private const val UNIX_FROM_1900 = 2_208_988_800L

    /** t0 sent and t3 received on the local clock; t1 received and t2 sent on the server clock (all ms). */
    fun sample(t0: Long, t1: Long, t2: Long, t3: Long) = Sample((t3 - t0) - (t2 - t1), ((t1 - t0) + (t2 - t3)) / 2)

    fun best(samples: List<Sample>): Sample? = samples.filter { it.roundTrip >= 0 }.minByOrNull { it.roundTrip }

    fun readTimestamp(packet: ByteArray, at: Int): Long {
        fun u32(i: Int) = ((packet[i].toLong() and 0xFF) shl 24) or ((packet[i + 1].toLong() and 0xFF) shl 16) or
            ((packet[i + 2].toLong() and 0xFF) shl 8) or (packet[i + 3].toLong() and 0xFF)
        val seconds = u32(at); val fraction = u32(at + 4)
        if (seconds == 0L && fraction == 0L) return 0
        return (seconds - UNIX_FROM_1900) * 1000 + fraction * 1000 / 0x1_0000_0000L
    }

    fun writeTimestamp(packet: ByteArray, at: Int, unixMs: Long) {
        val seconds = unixMs / 1000 + UNIX_FROM_1900
        val fraction = (unixMs % 1000) * 0x1_0000_0000L / 1000
        for (i in 0..3) packet[at + i] = (seconds ushr (24 - 8 * i)).toByte()
        for (i in 0..3) packet[at + 4 + i] = (fraction ushr (24 - 8 * i)).toByte()
    }

    /** Null unless it is a server's answer (mode 4, a real stratum) carrying both times. */
    fun parse(reply: ByteArray, t0: Long, t3: Long): Sample? {
        if (reply.size < 48 || reply[0].toInt() and 0x7 != 4 || (reply[1].toInt() and 0xFF) !in 1..15) return null
        val t1 = readTimestamp(reply, 32); val t2 = readTimestamp(reply, 40)
        if (t1 == 0L || t2 == 0L) return null
        return sample(t0, t1, t2, t3)
    }

    /** Asks [host] [count] times; times are on the monotonic clock, so the offset maps it to true time. */
    suspend fun query(host: String, count: Int = 4): List<Sample> = withContext(Dispatchers.IO) {
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                val address = InetAddress.getByName(host)
                (1..count).mapNotNull {
                    val request = ByteArray(48).also { p -> p[0] = 0x23 } // version 4, client
                    val reply = ByteArray(48)
                    runCatching {
                        val t0 = SystemClock.elapsedRealtime()
                        socket.send(DatagramPacket(request, 48, address, 123))
                        socket.receive(DatagramPacket(reply, 48))
                        parse(reply, t0, SystemClock.elapsedRealtime())
                    }.getOrNull().also { Thread.sleep(60) }
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** True time for this phone: monotonic clock + the offset from the best recent time-server sample. */
object NtpClock {
    private val servers = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
    @Volatile var offset: Long? = null; private set
    @Volatile var roundTrip: Long? = null; private set

    suspend fun sync(): Boolean {
        val best = Ntp.best(servers.flatMap { Ntp.query(it) }) ?: return false
        // Keep the better estimate unless the old one is clearly stale (a new sample within 20 ms of round trip is fine).
        if (roundTrip == null || best.roundTrip <= (roundTrip ?: 0) + 20) { offset = best.offset; roundTrip = best.roundTrip }
        android.util.Log.i("SpotifeurJamSync", "ntp offset=${best.offset} rtt=${best.roundTrip}")
        return true
    }

    fun utcNow(): Long? = offset?.let { SystemClock.elapsedRealtime() + it }
}
