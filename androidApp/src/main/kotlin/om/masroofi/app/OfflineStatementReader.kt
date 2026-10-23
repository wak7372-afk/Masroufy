package om.masroofi.app

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import om.masroofi.core.*
import java.io.*
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.math.*

/** All document bytes, decoded images and OCR results stay in RAM. Only public
 * language weights are copied from the APK to a private, non-backup directory. */
internal object OfflineStatementReader {
    private const val BYTE_LIMIT = 20 * 1024 * 1024
    private const val PIXELS = 2_500_000
    @Serializable private data class Asset(val name: String, val bytes: Long, val sha256: String)
    @Serializable private data class Assets(val files: List<Asset>)

    fun read(context: Context, uri: android.net.Uri, checkActive: () -> Unit): ByteArray =
        (context.contentResolver.openInputStream(uri) ?: throw StatementReadError("تعذر فتح الملف")).use { source ->
            val output = BoundedBytes(BYTE_LIMIT)
            val buffer = ByteArray(8192)
            while (true) { checkActive(); val count = source.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            output.toByteArray()
        }

    @Synchronized fun extract(context: Context, bytes: ByteArray, password: String, checkActive: () -> Unit): Pair<String, String> {
        require(bytes.size <= BYTE_LIMIT)
        val pdf = bytes.copyOfRange(0, minOf(bytes.size, 1024)).toString(Charsets.ISO_8859_1).contains("%PDF-")
        val document = if (pdf) {
            val text = StatementPdfReader.layout(context, bytes, password, checkActive)
            val scanned = text.pages.indices.filter { text.pages[it].words.size < 5 }
            if (scanned.isEmpty()) text else {
                if (scanned.size > 10) throw StatementReadError("قراءة الصور تدعم 10 صفحات في المرة. اختر جزءًا أقصر من الكشف.")
                withEngine(context) { engine -> readScannedPdf(context, bytes, password, text, scanned.toSet(), engine, checkActive) }
            }
        } else {
            val image = decodeImage(bytes)
            try { withEngine(context) { StatementDocument(listOf(recognize(image, it, checkActive))) } }
            finally { image.eraseColor(Color.WHITE); image.recycle() }
        }
        if (document.pages.sumOf { it.words.size } < 5) throw StatementReadError("لم نجد نصًا واضحًا. اختر ملفًا أصليًا أو صورة أوضح للكشف.")
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ledgerJson.encodeToString(document) to hash
    }

    private inline fun <T> withEngine(context: Context, action: (TessBaseAPI) -> T): T {
        val directory = prepareAssets(context)
        val engine = try { TessBaseAPI() } catch (_: LinkageError) { throw StatementReadError("قارئ الصور غير متوافق مع هذا الجهاز. يمكنك استيراد PDF نصّي.") }
        try {
            if (!engine.init(directory.absolutePath, "ara+eng", TessBaseAPI.OEM_LSTM_ONLY, mapOf("debug_file" to "/dev/null")))
                throw StatementReadError("تعذر فتح ملفات القراءة المحلية")
            engine.setDebug(false)
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            return action(engine)
        } finally { engine.recycle() }
    }

    private fun prepareAssets(context: Context): File {
        val manifest = ledgerJson.decodeFromString<Assets>(context.assets.open("ocr/manifest.json").bufferedReader().use { it.readText().removePrefix("\uFEFF") })
        require(manifest.files.map { it.name }.toSet() == setOf("ara.traineddata", "eng.traineddata"))
        val root = File(context.noBackupFilesDir, "ocr-v1")
        val data = File(root, "tessdata"); check(data.mkdirs() || data.isDirectory)
        manifest.files.forEach { asset ->
            require(asset.bytes in 1..10_000_000 && Regex("[a-f0-9]{64}").matches(asset.sha256))
            val target = File(data, asset.name)
            fun valid(file: File): Boolean = file.isFile && file.length() == asset.bytes && file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
            }
            if (!valid(target)) {
                val temporary = File(data, asset.name + ".new")
                try {
                    context.assets.open("ocr/" + asset.name).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(valid(temporary)) { "ملف القراءة المحلية غير سليم" }
                    android.system.Os.rename(temporary.path, target.path)
                } finally { temporary.delete() }
            }
        }
        return root
    }

    internal fun decodeImage(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toDouble() / bounds.outHeight !in 0.1..10.0)
            throw StatementReadError("اختر صورة JPEG أو PNG أو WebP واضحة للكشف")
        if (bounds.outWidth < 600 || bounds.outHeight < 300) throw StatementReadError("الصورة صغيرة جدًا لقراءة الأرقام بأمان. اختر الصورة الأصلية أو كشف PDF.")
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true })
            ?: throw StatementReadError("تعذر قراءة الصورة")
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) { if (decoded.isMutable) decoded.eraseColor(Color.WHITE); decoded.recycle() }
        val opaque = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(rotated, 0f, 0f, null) }
        if (rotated.isMutable) rotated.eraseColor(Color.WHITE)
        rotated.recycle()
        return opaque
    }

    private fun recognize(bitmap: Bitmap, engine: TessBaseAPI, checkActive: () -> Unit): StatementPage {
        checkActive(); engine.setImage(bitmap)
        engine.getUTF8Text() // Run recognition in memory; never write hOCR/debug files.
        checkActive()
        val iterator = engine.resultIterator ?: return StatementPage(600.0, 600.0 * bitmap.height / bitmap.width, emptyList(), true)
        val words = mutableListOf<StatementWord>()
        val scale = 600.0 / bitmap.width
        var characters = 0
        try {
            iterator.begin()
            do {
                checkActive()
                val text = Normalizer.normalize(iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD).orEmpty(), Normalizer.Form.NFKC).trim()
                if (text.isNotBlank()) {
                    val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    val confidence = (iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD).toDouble() / 100).coerceIn(0.0, 1.0)
                    characters += text.length
                    if (words.size >= 100_000 || characters > 1_000_000 || text.length > 4000) throw StatementReadError("النص داخل الصورة أكبر من الحد المسموح")
                    if (box.size == 4 && box[0] >= 0 && box[1] >= 0 && box[2] <= bitmap.width && box[3] <= bitmap.height && box[2] > box[0] && box[3] > box[1]) {
                        words += StatementWord(text, box[0]*scale, box[1]*scale, (box[2]-box[0])*scale, (box[3]-box[1])*scale, confidence)
                    }
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally { iterator.delete(); engine.clear() }
        return StatementPage(600.0, bitmap.height*scale, words, true)
    }

    private fun readScannedPdf(context: Context, source: ByteArray, password: String, text: StatementDocument,
                               scanned: Set<Int>, engine: TessBaseAPI, checkActive: () -> Unit): StatementDocument {
        var renderBytes = source
        PDDocument.load(ByteArrayInputStream(source), password, MemoryUsageSetting.setupMainMemoryOnly(32L*1024*1024)).use { document ->
            if (!document.currentAccessPermission.canExtractContent()) throw StatementReadError("هذا الكشف لا يسمح باستخراج النص")
            if (document.isEncrypted) {
                document.isAllSecurityToBeRemoved = true
                val output = BoundedBytes(40 * 1024 * 1024); document.save(output); renderBytes = output.toByteArray()
            }
        }
        val callbackThread = HandlerThread("local-pdf-memory").apply { start() }
        try {
            val memory = MemoryPdfCallback(renderBytes)
            val fd = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, memory, Handler(callbackThread.looper))
            fd.use { PdfRenderer(it).use { renderer ->
                if (renderer.pageCount != text.pages.size) throw StatementReadError("تعذر مطابقة صفحات الكشف")
                return StatementDocument(text.pages.mapIndexed { index, original ->
                    checkActive()
                    if (index !in scanned) original else renderer.openPage(index).use { page ->
                        val scale = minOf(1800.0 / maxOf(page.width, page.height), sqrt(PIXELS.toDouble() / page.width / page.height))
                        val width = (page.width*scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height*scale).roundToInt().coerceAtLeast(1)
                        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            image.eraseColor(Color.WHITE); page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            recognize(image, engine, checkActive)
                        } finally { image.eraseColor(Color.WHITE); image.recycle() }
                    }
                })
            } }
        } finally { callbackThread.quitSafely(); if (renderBytes !== source) renderBytes.fill(0) }
    }

    internal class MemoryPdfCallback(private val bytes: ByteArray) : ProxyFileDescriptorCallback() {
        override fun onGetSize() = bytes.size.toLong()
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset < 0 || size < 0 || size > data.size) throw ErrnoException("read", OsConstants.EINVAL)
            if (offset >= bytes.size) return 0
            val count = minOf(size.toLong(), bytes.size-offset).toInt()
            bytes.copyInto(data, 0, offset.toInt(), offset.toInt()+count)
            return count
        }
        override fun onRelease() {}
    }
    private class BoundedBytes(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(b: ByteArray, off: Int, len: Int) { if (size().toLong()+len > limit) throw StatementReadError("الملف أكبر من الحد المسموح"); super.write(b, off, len) }
        override fun write(b: Int) { if (size() >= limit) throw StatementReadError("الملف أكبر من الحد المسموح"); super.write(b) }
    }
}
