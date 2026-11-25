package fr.spotifeur.social

import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics
import com.vitorpamplona.quartz.nip06KeyDerivation.Nip06
import com.vitorpamplona.quartz.nip49PrivKeyEnc.Nip49
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The Friends identity: a Nostr key made on the phone. A new identity is made from 12 recovery words (NIP-06), so the
 * words alone bring it back on another phone; an identity restored from a password export (NIP-49) has a key but no
 * words.
 */
class SocialKeys private constructor(privateKey: ByteArray, val words: String?) {
    val keyPair = KeyPair(privKey = privateKey)
    val signer by lazy { NostrSignerInternal(keyPair) }
    val privateKeyHex: String = privateKey.toHex()
    val publicKeyHex: String = keyPair.pubKey.toHex()

    /** The key protected by [password] (NIP-49 `ncryptsec`), for a phone without Google backup. */
    fun exportEncrypted(password: String): String = Nip49().encrypt(privateKeyHex, password)

    companion object {
        fun create(random: SecureRandom = SecureRandom()): SocialKeys {
            val entropy = ByteArray(16).also(random::nextBytes)
            return requireNotNull(fromWords(Bip39Mnemonics.toMnemonics(entropy).joinToString(" ")))
        }

        /** Null when the words are not a valid 12-word recovery phrase. */
        fun fromWords(text: String): SocialKeys? {
            val words = text.trim().lowercase().split(Regex("\\s+")).joinToString(" ")
            if (words.split(" ").size != 12 || !Nip06().isValidMnemonic(words)) return null
            return SocialKeys(Nip06().privateKeyFromMnemonic(words), words)
        }

        /** Null when [password] does not open [export]. */
        fun fromExport(export: String, password: String): SocialKeys? =
            runCatching { SocialKeys(Nip49().decrypt(export.trim(), password).hexToBytes(), null) }.getOrNull()

        /** For restoring a stored identity. */
        internal fun fromStored(privateKeyHex: String, words: String?): SocialKeys = SocialKeys(privateKeyHex.hexToBytes(), words)
    }
}

/**
 * A display handle: the chosen name, then 4 characters from the public key (`sara#7Q2K`). It tells two Saras apart;
 * it is not proof of identity (a tag can be forged by trying keys), the full public key is.
 */
object Handles {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun tag(publicKeyHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyHex.hexToBytes())
        // The first 20 bits, 5 bits per character.
        val bits = ((digest[0].toInt() and 0xFF) shl 12) or ((digest[1].toInt() and 0xFF) shl 4) or ((digest[2].toInt() and 0xFF) ushr 4)
        return (3 downTo 0).joinToString("") { CROCKFORD[(bits ushr (it * 5)) and 31].toString() }
    }

    fun handle(name: String, publicKeyHex: String): String = "${name.trim()}#${tag(publicKeyHex)}"
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
