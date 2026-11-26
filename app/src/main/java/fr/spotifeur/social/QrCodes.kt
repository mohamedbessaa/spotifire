package fr.spotifeur.social

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The friend code. It is a link, `spotifire://friend/<public key>?name=<name>`, so a phone's camera or QR app offers
 * to open it in Spotifire; the app's own scanner, and pictures picked from the gallery, read it too.
 */
object QrCodes {
    private const val LINK = "spotifire://friend/"
    private const val LEGACY = "spotifire:friend:"
    private val KEY = Regex("[0-9a-f]{64}")

    fun payload(publicKeyHex: String, name: String): String =
        LINK + publicKeyHex + "?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** (public key, name), or null when the text is not a Spotifire friend code. */
    fun parse(text: String): Pair<String, String>? {
        val body = when {
            text.startsWith(LINK) -> text.removePrefix(LINK)
            text.startsWith(LEGACY) -> text.removePrefix(LEGACY)
            else -> return null
        }
        val key = body.substringBefore('?').trimEnd('/').lowercase().takeIf { it.matches(KEY) } ?: return null
        val name = body.substringAfter("?name=", "").substringBefore('&')
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault("") }
        return key to name.take(Directory.MAX_NAME)
    }

    /** The code's modules, with the standard quiet zone (4 modules) so it still scans once cropped or photographed. */
    fun matrix(text: String): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))

    /** The text of a QR code found in a picture (ARGB pixels), or null when there is none. */
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val source = RGBLuminanceSource(width, height, argb)
        // Light-on-dark codes (a dark-mode screenshot) read once inverted.
        return listOf(source, source.invert()).firstNotNullOfOrNull { candidate ->
            runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text }.getOrNull()
        }
    }
}
