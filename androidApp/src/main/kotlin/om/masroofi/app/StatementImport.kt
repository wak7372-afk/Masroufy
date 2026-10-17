package om.masroofi.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import om.masroofi.core.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Writer
import java.security.MessageDigest

private const val MAX_STATEMENT_BYTES = 20 * 1024 * 1024
private class StatementReadError(message: String) : Exception(message)

/** Extraction happens on the device. Neither the PDF nor its password is stored. */
internal object StatementPdfReader {
    fun read(context: Context, uri: Uri, checkActive: () -> Unit): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw StatementReadError("تعذر فتح ملف كشف الحساب")
        val bytes = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkActive()
                val count = source.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_STATEMENT_BYTES) throw StatementReadError("اختر كشفًا بحجم 20 ميغابايت أو أقل")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (!bytes.copyOfRange(0, minOf(bytes.size, 1024)).toString(Charsets.ISO_8859_1).contains("%PDF-")) {
            bytes.fill(0)
            throw StatementReadError("الملف المختار ليس ملف PDF صالحًا")
        }
        return bytes
    }

    fun extract(context: Context, bytes: ByteArray, password: String, checkActive: () -> Unit): Pair<String, String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val documentId = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val pages = mutableListOf<StatementPage>()
        var words = mutableListOf<StatementWord>()
        var count = 0
        var characters = 0
        PDDocument.load(ByteArrayInputStream(bytes), password, MemoryUsageSetting.setupMainMemoryOnly(32L * 1024 * 1024)).use { document ->
            if (document.numberOfPages !in 1..50) throw StatementReadError("اختر كشفًا من صفحة واحدة إلى 50 صفحة")
            if (!document.currentAccessPermission.canExtractContent()) throw StatementReadError("هذا الملف لا يسمح باستخراج النص. صدّر كشفًا يسمح بقراءة محتواه.")
            val stripper = object : PDFTextStripper() {
                init { setSortByPosition(true); setShouldSeparateByBeads(false) }
                override fun startPage(page: PDPage) { checkActive(); words = mutableListOf() }
                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    checkActive()
                    if (text.isBlank() || positions.isEmpty()) return
                    count++; characters += text.length
                    if (count > 100_000 || characters > 1_000_000) throw StatementReadError("النص داخل الكشف كبير جدًا. اختر فترة أقصر.")
                    val left = positions.minOf { it.xDirAdj }.toDouble()
                    val right = positions.maxOf { it.xDirAdj + it.widthDirAdj }.toDouble()
                    val top = positions.minOf { it.yDirAdj - it.heightDir }.toDouble()
                    val bottom = positions.maxOf { it.yDirAdj }.toDouble()
                    if (listOf(left, right, top, bottom).all { it.isFinite() }) {
                        words += StatementWord(text.trim(), left, top.coerceAtLeast(0.0), (right - left).coerceAtLeast(0.1), (bottom - top).coerceAtLeast(0.1))
                    }
                }
                override fun endPage(page: PDPage) {
                    val box = page.cropBox
                    val sideways = page.rotation % 180 != 0
                    pages += StatementPage(if (sideways) box.height.toDouble() else box.width.toDouble(),
                        if (sideways) box.width.toDouble() else box.height.toDouble(), words.toList())
                }
            }
            // Discard the text output: positioned words are the only transient result.
            stripper.writeText(document, object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) {}
                override fun flush() {}
                override fun close() {}
            })
        }
        if (pages.sumOf { it.words.size } < 5) throw StatementReadError("لم نجد نصًا قابلًا للقراءة في الكشف. اختر PDF نصّيًا صادرًا من البنك.")
        return ledgerJson.encodeToString(StatementDocument(pages)) to documentId
    }
}

@Composable internal fun StatementImportAction(repo: LedgerRepository, onImported: (ImportResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<StatementPreview?>(null) }
    var pendingPdf by remember { mutableStateOf<ByteArray?>(null) }
    var passwordPrompt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }

    fun clearPending() { pendingPdf?.fill(0); pendingPdf = null; password = ""; passwordPrompt = false }
    fun analyze(bytes: ByteArray, secret: String) {
        scope.launch {
            busy = true; passwordPrompt = false; failure = null
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    val (document, id) = StatementPdfReader.extract(context, bytes, secret) { job.ensureActive() }
                    val engine = LedgerEngine(repo.snapshot())
                    ledgerJson.decodeFromString<StatementPreview>(engine.previewStatement(document, id, System.currentTimeMillis()))
                }
                clearPending(); preview = parsed
            } catch (cancelled: CancellationException) { clearPending(); throw cancelled }
            catch (_: InvalidPasswordException) { pendingPdf = bytes; password = ""; passwordPrompt = true; failure = "الكشف محمي أو كلمة المرور غير صحيحة. أدخل كلمة مرور الملف." }
            catch (error: Exception) { clearPending(); failure = if (error is StatementReadError) error.message else "تعذر تحليل هذا الكشف. تحقق من سلامة الملف وتنسيق جدول العمليات." }
            finally { busy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) { val job = currentCoroutineContext(); StatementPdfReader.read(context, uri) { job.ensureActive() } }
                pendingPdf = bytes; analyze(bytes, "")
            } catch (cancelled: CancellationException) { clearPending(); throw cancelled }
            catch (error: Exception) { clearPending(); failure = if (error is StatementReadError) error.message else "تعذر قراءة الملف المختار"; busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { pendingPdf?.fill(0) } }

    OutlinedButton(onClick = { failure = null; picker.launch(arrayOf("application/pdf")) },
        enabled = !busy && preview == null && repo.loadError == null, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ قراءة الكشف…" else "استيراد كشف حساب PDF")
    }
    if (busy && preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (passwordPrompt) AlertDialog(onDismissRequest = { if (!busy) clearPending() }, title = { Text("كلمة مرور كشف الحساب") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failure ?: "تُستخدم كلمة المرور لفتح الملف على جهازك، ولا تُحفظ.")
            OutlinedTextField(password, { password = it.take(256) }, label = { Text("كلمة مرور PDF") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { Button(onClick = { pendingPdf?.let { analyze(it, password) } }, enabled = password.isNotEmpty() && !busy) { Text("فتح الكشف") } },
        dismissButton = { TextButton(onClick = { clearPending(); failure = null }) { Text("إلغاء") } })
    else if (preview == null) failure?.let { text -> AlertDialog(onDismissRequest = { failure = null }, title = { Text("استيراد كشف الحساب") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { failure = null }) { Text("حسنًا") } }) }

    preview?.let { value -> StatementPreviewDialog(value, busy, failure, { preview = null; failure = null }, { selected ->
        scope.launch {
            busy = true; failure = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val imported = repo.update { engine -> ledgerJson.decodeFromString<ImportResult>(engine.importStatement(
                        ledgerJson.encodeToString(value), ledgerJson.encodeToString(selected.toList()), System.currentTimeMillis(), true)) }
                    runCatching { NoticeCenter.flush(context) }
                    imported
                }
                preview = null; onImported(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message?.takeIf { it.length < 300 && Regex("[ء-ي]").containsMatchIn(it) } ?: "تعذر حفظ العمليات. لم تُضف بيانات جزئية." }
            finally { busy = false }
        }
    }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StatementPreviewDialog(preview: StatementPreview, busy: Boolean, error: String?, dismiss: () -> Unit, import: (Set<String>) -> Unit) {
    var selected by remember(preview) { mutableStateOf(preview.rows.filter { it.canImport && it.duplicateOf == null }.map { it.id }.toSet()) }
    var currencyConfirmed by remember(preview) { mutableStateOf(false) }
    val supportedCurrency = preview.currency in listOf("OMR", "UNKNOWN")
    val chosen = preview.rows.filter { it.id in selected && it.canImport }
    val debit = chosen.filter { it.direction == "out" }.sumOf { it.amountBaisa ?: 0 }
    val credit = chosen.filter { it.direction == "in" }.sumOf { it.amountBaisa ?: 0 }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = { TopAppBar(title = { Text("معاينة كشف الحساب") }, navigationIcon = { IconButton(onClick = dismiss, enabled = !busy) { Icon(Icons.Default.Close, "إلغاء") } }) }, bottomBar = {
                Surface(shadowElevation = 8.dp) { Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(currencyConfirmed, { currencyConfirmed = it }, enabled = supportedCurrency && !busy)
                        Text("أؤكد أن مبالغ الكشف بالريال العُماني وأنني راجعت العمليات", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { import(selected) }, enabled = selected.isNotEmpty() && currencyConfirmed && supportedCurrency && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "جارٍ إضافة العمليات…" else "إضافة ${selected.size} عملية")
                    }
                } }
            }) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("اختر العمليات التي تريد إضافتها. الملف وكلمة مروره يبقيان على جهازك.", style = MaterialTheme.typography.bodyMedium)
                        Text("مدين: ${Money.format(debit)} • دائن: ${Money.format(credit)} ر.ع", style = MaterialTheme.typography.titleMedium)
                        Text("${preview.rows.size} سطر عملية • ${preview.skippedRows} سطر غير مستورد", style = MaterialTheme.typography.bodySmall)
                        if (!supportedCurrency) Text("عملة الكشف ${preview.currency}؛ الاستيراد الحالي يدعم الريال العُماني.", color = MaterialTheme.colorScheme.error)
                        preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { selected = preview.rows.filter { it.canImport && it.duplicateOf == null }.map { it.id }.toSet() }, enabled = !busy) { Text("اختيار الصالحة") }
                            TextButton(onClick = { selected = emptySet() }, enabled = !busy) { Text("إلغاء الاختيار") }
                        }
                    }
                    if (preview.rows.isEmpty()) item { Text("لم يُتعرف على جدول العمليات. لم تُضف أي بيانات. جرّب كشفًا نصّيًا بأعمدة تاريخ ووصف ومدين ودائن.") }
                    items(preview.rows, key = { it.id }) { row ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Checkbox(row.id in selected, { checked -> selected = if (checked) selected + row.id else selected - row.id }, enabled = row.canImport && supportedCurrency && !busy)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.description.ifBlank { "عملية من الكشف" }, style = MaterialTheme.typography.titleSmall)
                                    Text("${row.dateText} • صفحة ${row.page}", style = MaterialTheme.typography.bodySmall)
                                    Text("${row.amountBaisa?.let(Money::format) ?: "مبلغ غير محسوم"} ${if (supportedCurrency) "ر.ع" else preview.currency} • ${when (row.direction) { "out" -> "مدين"; "in" -> "دائن"; else -> "اتجاه غير محسوم" }}")
                                    if (row.duplicateOf != null) Text("توجد عملية مشابهة؛ لم تُحدد تلقائيًا. اختيارها يضيفها للمراجعة.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    if (row.effect == "unresolved") Text("تُضاف للمراجعة لتحديد أثرها المالي قبل الاحتساب.", style = MaterialTheme.typography.bodySmall)
                                    row.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
