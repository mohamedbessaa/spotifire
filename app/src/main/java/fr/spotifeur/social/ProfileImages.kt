package fr.spotifeur.social

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A picked picture made into a profile photo: the centre square, 512 px for friends and 128 px for the public thumbnail. */
object ProfileImages {
    data class Prepared(val large: ByteArray, val small: ByteArray)

    fun prepare(context: Context, uri: Uri): Prepared {
        val source = decode(context, uri) ?: throw IllegalArgumentException("This picture can't be opened.")
        val side = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        return Prepared(jpeg(square, 512, 88), jpeg(square, 128, 85))
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = if (android.os.Build.VERSION.SDK_INT >= 28) {
        runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // Big camera photos are read at a reduced size: 512 px is all a profile photo needs.
                val shortSide = minOf(info.size.width, info.size.height)
                if (shortSide > 1024) decoder.setTargetSampleSize(shortSide / 1024)
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()
    } else {
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull()
    }

    private fun jpeg(square: Bitmap, size: Int, quality: Int): ByteArray {
        val scaled = if (square.width == size) square else Bitmap.createScaledBitmap(square, size, size, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
