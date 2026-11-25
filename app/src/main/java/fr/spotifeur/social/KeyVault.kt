package fr.spotifeur.social

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Where the Friends key lives: encrypted on the phone with a key of the Android Keystore (never in plain prefs), and in
 * Google's Block Store, which keeps it end-to-end encrypted with the phone's screen lock and gives it back after a
 * reinstall or on a new phone set up from a Google backup.
 */
internal object KeyVault {
    private const val PREFS = "social_identity"
    private const val ENTRY = "sealed"
    private const val ALIAS = "spotifire_social_identity"
    private const val BLOCK_KEY = "app.spotifire.social.identity"

    /** The stored identity: on the phone first, then the Google backup; null when there is none yet. */
    suspend fun load(context: Context): SocialKeys? {
        local(context)?.let { return it }
        val restored = runCatching { fromBackup(context) }.getOrNull() ?: return null
        saveLocal(context, restored)
        android.util.Log.i("SpotifeurSocial", "identity restored from the Google backup")
        return restored
    }

    suspend fun save(context: Context, keys: SocialKeys) {
        saveLocal(context, keys)
        runCatching {
            val data = StoreBytesData.Builder().setKey(BLOCK_KEY).setBytes(encode(keys).toByteArray())
                .setShouldBackupToCloud(true).build()
            Blockstore.getClient(context).storeBytes(data).await()
        }.onFailure { android.util.Log.w("SpotifeurSocial", "Google backup of the identity failed: ${it.javaClass.simpleName}") }
    }

    private fun encode(keys: SocialKeys) = keys.privateKeyHex + "|" + keys.words.orEmpty()
    private fun decode(text: String): SocialKeys? {
        val (key, words) = text.split('|', limit = 2).let { it[0] to it.getOrNull(1)?.takeIf(String::isNotBlank) }
        return runCatching { SocialKeys.fromStored(key, words) }.getOrNull()
    }

    private fun local(context: Context): SocialKeys? {
        val sealed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes, 0, 12))
            decode(String(cipher.doFinal(bytes, 12, bytes.size - 12)))
        }.getOrNull()
    }

    private fun saveLocal(context: Context, keys: SocialKeys) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val sealed = cipher.iv + cipher.doFinal(encode(keys).toByteArray())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENTRY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private suspend fun fromBackup(context: Context): SocialKeys? {
        val request = RetrieveBytesRequest.Builder().setKeys(listOf(BLOCK_KEY)).build()
        val response = Blockstore.getClient(context).retrieveBytes(request).await()
        val bytes = response.blockstoreDataMap[BLOCK_KEY]?.bytes ?: return null
        return decode(String(bytes))
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.cancel(it) }
    }
}

/** The phone's Friends identity: loaded, restored from the backup, or created once. */
object Identity {
    @Volatile private var cached: SocialKeys? = null
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun current(context: Context): SocialKeys = cached ?: lock.withLockInline {
        cached ?: (KeyVault.load(context) ?: SocialKeys.create().also { KeyVault.save(context, it) }).also { cached = it }
    }

    /** Replaces the identity with a restored one (recovery words or export). */
    suspend fun replace(context: Context, keys: SocialKeys) { KeyVault.save(context, keys); cached = keys }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockInline(action: () -> T): T { lock(); try { return action() } finally { unlock() } }
}
