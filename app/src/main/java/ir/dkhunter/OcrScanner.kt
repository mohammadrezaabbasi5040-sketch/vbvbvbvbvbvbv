package ir.dkhunter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** وقتی سایت 403 یا 429 برگرداند؛ اسکن باید فوراً متوقف شود. */
class RateLimitedException(val code: Int) : Exception("HTTP $code")

class OcrScanner(private val recognizer: TextRecognizer) {

    /** تصویر را دانلود می‌کند، OCR می‌زند و کدهای احتمالی را برمی‌گرداند. null = تصویر قابل‌استفاده نبود. */
    suspend fun scan(imageUrl: String): Set<String>? = withContext(Dispatchers.IO) {
        val bytes = download(imageUrl) ?: return@withContext null
        val bitmap = decode(bytes) ?: return@withContext null
        try {
            val text = recognize(bitmap)
            CODE_REGEX.findAll(text).map { it.value.uppercase() }.toSet()
        } finally {
            bitmap.recycle()
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 7_000
            conn.readTimeout = 10_000
            val code = conn.responseCode
            if (code == 403 || code == 429) throw RateLimitedException(code)
            if (code !in 200..299) return null
            if (conn.contentType?.startsWith("image/") != true) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            return conn.inputStream.use { readLimited(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** با inSampleSize از OOM جلوگیری می‌کند (تصویر ۴MB فشرده می‌تواند صدها MB بیت‌مپ شود). */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w < MIN_SIDE || h < MIN_SIDE) return null // ML Kit حداقل ۳۲ پیکسل می‌خواهد
        var sample = 1
        while (maxOf(w, h) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }

    companion object {
        private const val MAX_BYTES = 4_000_000
        private const val MAX_SIDE = 2400
        private const val MIN_SIDE = 32

        /** الگوی حدسی کد؛ اگر قالب واقعی فرق داشت فقط همین خط را عوض کن. */
        val CODE_REGEX = Regex("(?<![A-Za-z0-9])DK[A-Za-z0-9]{3,8}(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    }
}
