package fr.spotifeur.social

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodesTest {
    private val key = "a1".repeat(32)

    @Test
    fun theCodeIsALinkPhonesCanOpenAndReadsBack() {
        val payload = QrCodes.payload(key, "Sara K")
        assertTrue(payload, payload.startsWith("spotifire://friend/"))
        assertEquals(key to "Sara K", QrCodes.parse(payload))
    }

    @Test
    fun theOldFormatStillWorksAndOtherCodesAreRefused() {
        assertEquals(key to "Sara", QrCodes.parse("spotifire:friend:$key?name=Sara"))
        assertNull(QrCodes.parse("https://example.com"))
        assertNull(QrCodes.parse("spotifire://friend/not-a-key"))
    }

    @Test
    fun theDrawnCodeHasItsQuietZone() {
        val matrix = QrCodes.matrix(QrCodes.payload(key, "Sara"))
        val modules = QRCodeWriter().encode(QrCodes.payload(key, "Sara"), BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)).width
        assertEquals("4 white modules each side", modules + 8, matrix.width)
        assertTrue((0 until matrix.width).none { matrix[it, 0] || matrix[0, it] })
    }

    /** A picture of the code: scaled up, framed by other content, on a grey photo background. */
    private fun picture(payload: String, scale: Int, background: Int): Triple<IntArray, Int, Int> {
        val m = QrCodes.matrix(payload)
        val size = m.width * scale + 200
        val pixels = IntArray(size * size) { background }
        for (y in 0 until m.height * scale) for (x in 0 until m.width * scale) {
            pixels[(y + 100) * size + (x + 100)] = if (m[x / scale, y / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(pixels, size, size)
    }

    @Test
    fun aCodeIsReadFromAPhotoOrAScreenshot() {
        val payload = QrCodes.payload(key, "Sara")
        val (pixels, w, h) = picture(payload, scale = 7, background = 0xFF808080.toInt())
        assertEquals(payload, QrCodes.decode(pixels, w, h))
        assertNull(QrCodes.decode(IntArray(400 * 400) { 0xFF808080.toInt() }, 400, 400))
    }
}
