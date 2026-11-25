package fr.spotifeur.social

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoCryptoTest {
    private val photo = ByteArray(40_000) { (it * 31 % 251).toByte() }

    @Test
    fun aPhotoOpensOnlyWithItsKeyAndHash() {
        val sealed = PhotoCrypto.seal(photo)
        assertFalse("not readable as is", sealed.bytes.copyOfRange(12, 1012).contentEquals(photo.copyOfRange(0, 1000)))
        assertEquals(64, sealed.keyHex.length)
        assertArrayEquals(photo, PhotoCrypto.open(sealed.bytes, sealed.keyHex, sealed.sha256))
        assertNull("another key", PhotoCrypto.open(sealed.bytes, PhotoCrypto.seal(photo).keyHex, sealed.sha256))
    }

    @Test
    fun aServerThatChangesOneByteIsCaught() {
        val sealed = PhotoCrypto.seal(photo)
        val tampered = sealed.bytes.copyOf().also { it[500] = (it[500] + 1).toByte() }
        assertNull(PhotoCrypto.open(tampered, sealed.keyHex, sealed.sha256))
    }
}
