package fr.spotifeur.social

import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A profile photo encrypted for friends: AES-256-GCM with a key of its own, which only the profile message carries. */
object PhotoCrypto {
    data class Sealed(val bytes: ByteArray, val keyHex: String, val sha256: String)

    fun seal(photo: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        val bytes = iv + cipher.doFinal(photo)
        return Sealed(bytes, key.toHex(), sha256(bytes))
    }

    /** Null when the bytes are not the ones described ([sha256]) or the key does not open them. */
    fun open(bytes: ByteArray, keyHex: String, sha256: String): ByteArray? {
        if (sha256(bytes) != sha256 || bytes.size < 29) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        }.getOrNull()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

/**
 * Blossom file servers (BUD-01/02) for profile photos. The servers were measured on 2026-11-25 to accept, serve back
 * unchanged and delete an upload from a new key. Every file is checked against its hash when downloaded.
 */
object Blossom {
    val servers = listOf("https://nostr.download", "https://files.sovbit.host", "https://24242.io")
    private val http by lazy { OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build() }

    /** Uploads to every server at once; returns the addresses that hold the file (empty when none took it). */
    suspend fun upload(bytes: ByteArray, contentType: String, keys: SocialKeys): List<String> = withContext(Dispatchers.IO) {
        val hash = PhotoCrypto.sha256(bytes)
        coroutineScope {
            servers.map { server ->
                async {
                    runCatching {
                        val auth = BlossomAuthorizationEvent.createUploadAuth(hash, bytes.size.toLong(), "Upload a profile photo", keys.signer)
                        http.newCall(Request.Builder().url("$server/upload").put(bytes.toRequestBody(contentType.toMediaType()))
                            .header("Authorization", "Nostr " + auth.rawToken()).header("X-SHA-256", hash).build()).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) null
                            else Regex("\"url\"\\s*:\\s*\"(https://[^\"]+)\"").find(body)?.groupValues?.get(1) ?: "$server/$hash"
                        }
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** The first copy whose bytes match [sha256]. */
    suspend fun download(urls: List<String>, sha256: String): ByteArray? = withContext(Dispatchers.IO) {
        for (url in urls.filter { it.startsWith("https://") }.take(6)) {
            val bytes = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            }.getOrNull() ?: continue
            if (bytes.size <= MAX_BYTES && PhotoCrypto.sha256(bytes) == sha256) return@withContext bytes
        }
        null
    }

    /** Asks each server to delete the file; best effort. */
    suspend fun delete(sha256: String, keys: SocialKeys) = withContext(Dispatchers.IO) {
        servers.forEach { server ->
            runCatching {
                val auth = BlossomAuthorizationEvent.createDeleteAuth(sha256, "Remove a profile photo", keys.signer)
                http.newCall(Request.Builder().url("$server/$sha256").delete().header("Authorization", "Nostr " + auth.rawToken()).build()).execute().close()
            }
        }
    }

    private const val MAX_BYTES = 2 * 1024 * 1024
}
