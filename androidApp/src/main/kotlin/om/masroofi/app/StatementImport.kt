package om.masroofi.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
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
internal class StatementReadError(message: String) : Exception(message)

/** Extraction happens on the device. Neither the PDF nor its password is stored. */
internal object StatementPdfReader {
    private fun hasBodyImage(resources:PDResources?,depth:Int=0):Boolean {
        if(resources==null || depth>3) return false
        return resources.xObjectNames.take(100).any { name ->
            when(val item=resources.getXObject(name)) {
                is PDImageXObject -> item.width>=600 && item.height>=200
                is PDFormXObject -> hasBodyImage(item.resources,depth+1)
                else -> false
            }
        }
    }
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
        val document = layout(context, bytes, password, checkActive)
        if (document.pages.sumOf { it.words.size } < 5) throw StatementReadError("لم نجد نصًا قابلًا للقراءة في الكشف. اختر PDF نصّيًا صادرًا من البنك.")
        val documentId = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ledgerJson.encodeToString(document) to documentId
    }
    fun layout(context: Context, bytes: ByteArray, password: String, checkActive: () -> Unit): StatementDocument {
        PDFBoxResourceLoader.init(context.applicationContext)
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
                        if (sideways) box.width.toDouble() else box.height.toDouble(), words.toList(),hasRasterContent=hasBodyImage(page.resources))
                }
            }
            // Discard the text output: positioned words are the only transient result.
            stripper.writeText(document, object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) {}
                override fun flush() {}
                override fun close() {}
            })
        }
        return StatementDocument(pages)
    }
}

@Composable internal fun StatementImportAction(repo: LedgerRepository, onImported: (ImportResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<StatementPreview?>(null) }
    var pendingPdf by remember { mutableStateOf<TransientStatementSource?>(null) }
    var passwordPrompt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var reviewSource by remember {mutableStateOf<StatementReviewSource?>(null)}
    var work by remember {mutableStateOf<Job?>(null)}
    var progress by remember {mutableStateOf("قراءة الملف…")}
    fun clearReview(){reviewSource?.close();reviewSource=null;preview=null}

    fun clearPending() { pendingPdf?.close(); pendingPdf = null; password = ""; passwordPrompt = false }
    fun analyze(source: TransientStatementSource, secret: String) {
        work=scope.launch {
            busy = true; passwordPrompt = false; failure = null
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    source.useBytes { bytes ->
                        val (document, id) = OfflineStatementReader.extract(context, bytes, secret,{job.ensureActive()}) { page,total ->
                            scope.launch {progress="قراءة الصفحة $page من $total محليًا…"}
                        }
                        val engine = LedgerEngine(repo.snapshot())
                        ledgerJson.decodeFromString<StatementPreview>(engine.previewStatement(document, id, System.currentTimeMillis()))
                    }
                }
                reviewSource?.close();reviewSource=StatementReviewSource(source,secret)
                pendingPdf=null;password="";passwordPrompt=false;preview=parsed
            } catch (cancelled: CancellationException) { clearPending(); throw cancelled }
            catch (_: OutOfMemoryError) { clearPending(); failure = "ذاكرة الجهاز لا تكفي لقراءة هذه الصورة. اختر صورة أصغر أو PDF نصّيًا." }
            catch (_: InvalidPasswordException) { pendingPdf = source; password = ""; passwordPrompt = true; failure = "الكشف محمي أو كلمة المرور غير صحيحة. أدخل كلمة مرور الملف." }
            catch (error: Exception) { clearPending(); failure = if (error is StatementReadError) error.message else "تعذر تحليل هذا الكشف. تحقق من سلامة الملف وتنسيق جدول العمليات." }
            finally { busy = false }
        }
    }

    val pickerContract = remember { object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>) = super.createIntent(context, input).putExtra(android.content.Intent.EXTRA_LOCAL_ONLY, true)
    } }
    val picker = rememberLauncherForActivityResult(pickerContract) { uri ->
        if (uri != null) work=scope.launch {
            busy = true;progress="قراءة الملف…"
            try {
                val source = withContext(Dispatchers.IO) { val job = currentCoroutineContext(); TransientStatementSource(OfflineStatementReader.read(context, uri) { job.ensureActive() }) }
                pendingPdf = source; analyze(source, "")
            } catch (cancelled: CancellationException) { clearPending(); throw cancelled }
            catch (error: Exception) { clearPending(); failure = if (error is StatementReadError) error.message else "تعذر قراءة الملف المختار"; busy = false }
            catch (_: OutOfMemoryError) { clearPending(); failure = "ذاكرة الجهاز لا تكفي لهذا الملف. اختر جزءًا أصغر من الكشف."; busy = false }
        }
    }
    DisposableEffect(Unit) { onDispose { work?.cancel();pendingPdf?.close();reviewSource?.close() } }

    OutlinedButton(onClick = { failure = null; picker.launch(arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp")) },
        enabled = !busy && preview == null && repo.loadError == null, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "جارٍ قراءة الكشف محليًا…" else "استيراد كشف PDF أو صورة")
    }
    if (busy && preview == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(verticalAlignment=Alignment.CenterVertically){Text(progress,modifier=Modifier.weight(1f));TextButton(onClick={work?.cancel();clearPending()}){Text("إلغاء القراءة")}}
    }
    if (passwordPrompt) AlertDialog(onDismissRequest = { if (!busy) clearPending() }, title = { Text("كلمة مرور كشف الحساب") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(failure ?: "تُستخدم كلمة المرور لفتح الملف على جهازك، ولا تُحفظ.")
            OutlinedTextField(password, { password = it.take(256) }, label = { Text("كلمة مرور PDF") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }
    }, confirmButton = { Button(onClick = { pendingPdf?.let { analyze(it, password) } }, enabled = password.isNotEmpty() && !busy) { Text("فتح الكشف") } },
        dismissButton = { TextButton(onClick = { clearPending(); failure = null }) { Text("إلغاء") } })
    else if (preview == null) failure?.let { text -> AlertDialog(onDismissRequest = { failure = null }, title = { Text("استيراد كشف الحساب") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { failure = null }) { Text("حسنًا") } }) }

    preview?.let { value -> StatementPreviewDialog(value,reviewSource, {repo.snapshot()}, busy, failure, { clearReview(); failure = null }, { corrected, selected ->
        scope.launch {
            busy = true; failure = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val imported = repo.update { engine -> ledgerJson.decodeFromString<ImportResult>(engine.importStatement(
                        engine.configureStatementBank(ledgerJson.encodeToString(corrected),corrected.bankName,true), ledgerJson.encodeToString(selected.toList()), System.currentTimeMillis(), true)) }
                    runCatching { NoticeCenter.flush(context) }
                    imported
                }
                clearReview(); onImported(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message?.takeIf { it.length < 300 && Regex("[ء-ي]").containsMatchIn(it) } ?: "تعذر حفظ العمليات. لم تُضف بيانات جزئية." }
            finally { busy = false }
        }
    }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StatementPreviewDialog(initialPreview: StatementPreview,source:StatementReviewSource?, snapshot: ()->String, busy: Boolean, error: String?, dismiss: () -> Unit, import: (StatementPreview, Set<String>) -> Unit) {
    var preview by remember(initialPreview) { mutableStateOf(initialPreview) }
    var selected by remember(initialPreview) { mutableStateOf(initialPreview.rows.filter { it.canImport && !it.reviewRequired && it.duplicateOf == null }.map { it.id }.toSet()) }
    var currencyConfirmed by remember(initialPreview) { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StatementRow?>(null) }
    var onlyReview by remember {mutableStateOf(initialPreview.rows.any{it.reviewRequired && !it.userConfirmed || !it.canImport})}
    val needsReview=preview.rows.filter{it.reviewRequired && !it.userConfirmed || !it.canImport}
    val supportedCurrency = preview.currency in listOf("OMR", "UNKNOWN")
    val chosen = preview.rows.filter { it.id in selected && it.canImport && (!it.reviewRequired || it.userConfirmed) }
    val debit = chosen.filter { it.direction == "out" }.sumOf { it.amountBaisa ?: 0 }
    val credit = chosen.filter { it.direction == "in" }.sumOf { it.amountBaisa ?: 0 }
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = { TopAppBar(title = { Text("معاينة كشف الحساب") }, navigationIcon = { IconButton(onClick = dismiss, enabled = !busy) { Icon(Icons.Default.Close, "إلغاء") } }) }, bottomBar = {
                Surface(shadowElevation = 8.dp) { Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if(preview.audit?.requiresConfirmation==true) Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(preview.auditAcknowledged,{preview=preview.copy(auditAcknowledged=it)},enabled=!busy)
                        Text("راجعت تنبيهات الأرصدة والإجماليات وأؤكد إضافة العمليات المحددة",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(currencyConfirmed, { currencyConfirmed = it }, enabled = supportedCurrency && BankKnowledge.key(preview.bankName)!=null && !busy)
                        Text("أؤكد أن الكشف لحسابي وأن مبالغه بالريال العُماني وقد راجعت العمليات", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { import(preview,selected) }, enabled = chosen.isNotEmpty() && chosen.size==selected.size && currencyConfirmed && supportedCurrency && !busy && (preview.audit?.requiresConfirmation!=true || preview.auditAcknowledged), modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "جارٍ إضافة العمليات…" else "إضافة ${selected.size} عملية")
                    }
                } }
            }) { padding ->
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("الخطوة 2 من 3 — مراجعة الكشف",style=MaterialTheme.typography.titleMedium)
                        Text("${preview.rows.size} عملية • ${needsReview.size} تحتاج مراجعة • ${preview.rows.count{it.duplicateOf!=null}} تكرار محتمل",style=MaterialTheme.typography.bodyMedium)
                        Text("المعاينة الأصلية مؤقتة في الذاكرة، وتُزال عند الإضافة أو الإلغاء. لا يُحفظ ملف الكشف داخل التطبيق.",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(preview.bankName.takeUnless{it==BankKnowledge.UNKNOWN}.orEmpty(),{
                            preview=preview.copy(bankName=it.take(80),auditAcknowledged=false);currencyConfirmed=false
                        },label={Text("البنك المستخدم في هذا الكشف")},supportingText={Text("MTHQ = بنك ميثاق • NIZWA = بنك نزوى. يُحفظ البنك لكل عملية.")},enabled=!busy,modifier=Modifier.fillMaxWidth())
                        if(BankKnowledge.key(preview.bankName)==null) Text("حدد البنك لإكمال الاستيراد",color=MaterialTheme.colorScheme.error)
                        Text("المطابقة بين بنوك حساباتك تحتاج مبلغًا وعملة متطابقين ووقتًا موثوقًا خلال خمس دقائق؛ التاريخ وحده لا يكفي.",style=MaterialTheme.typography.bodySmall)
                        Text("خارج (دفع/سحب): ${Money.format(debit)} • داخل (إيداع): ${Money.format(credit)} ر.ع", style = MaterialTheme.typography.titleMedium)
                        Text("${preview.rows.size} سطر عملية • ${preview.skippedRows} سطر غير مستورد", style = MaterialTheme.typography.bodySmall)
                        if (!supportedCurrency) Text("عملة الكشف ${preview.currency}؛ الاستيراد الحالي يدعم الريال العُماني.", color = MaterialTheme.colorScheme.error)
                        StatementAuditCard(preview)
                        preview.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { selected = preview.rows.filter { it.canImport && (!it.reviewRequired || it.userConfirmed) && it.duplicateOf == null }.map { it.id }.toSet() }, enabled = !busy) { Text("اختيار المؤكدة") }
                            TextButton(onClick = { selected = emptySet() }, enabled = !busy) { Text("إلغاء الاختيار") }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FilterChip(onlyReview,{onlyReview=true},label={Text("تحتاج مراجعة (${needsReview.size})")})
                            FilterChip(!onlyReview,{onlyReview=false},label={Text("كل العمليات (${preview.rows.size})")})
                        }
                        if(needsReview.isNotEmpty()) TextButton(onClick={editing=needsReview.first()},enabled=!busy){Text("مراجعة العمليات بالتتابع")}
                    }
                    if (preview.rows.isEmpty()) item { Text("لم يُتعرف على جدول العمليات. لم تُضف أي بيانات. جرّب كشفًا نصّيًا بأعمدة تاريخ ووصف ومدين ودائن.") }
                    items(if(onlyReview)needsReview else preview.rows, key = { it.id }) { row ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                                Checkbox(row.id in selected, { checked ->
                                    if(checked && (!row.canImport || row.reviewRequired && !row.userConfirmed)) editing=row
                                    else selected=if(checked) selected+row.id else selected-row.id
                                }, enabled = supportedCurrency && !busy)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(row.description.ifBlank { "عملية من الكشف" }, style = MaterialTheme.typography.titleSmall)
                                    Text("${row.dateText} • صفحة ${row.page}", style = MaterialTheme.typography.bodySmall)
                                    if(row.datePrecision=="day") Text("الساعة غير موجودة؛ لا تُجرى مطابقة خمس دقائق لهذا الصف",style=MaterialTheme.typography.bodySmall)
                                    Text("${row.amountBaisa?.let(Money::format) ?: "مبلغ غير محسوم"} ${if (supportedCurrency) "ر.ع" else preview.currency} • ${when (row.direction) { "out" -> "خارج: دفع أو سحب"; "in" -> "داخل: إيداع أو وارد"; else -> "اختر داخلًا أو خارجًا" }}")
                                    if(row.reviewRequired && !row.userConfirmed) Text("يحتاج تأكيدك قبل الحفظ",color=MaterialTheme.colorScheme.error)
                                    if (row.duplicateOf != null) Text("توجد عملية مشابهة؛ لم تُحدد تلقائيًا. اختيارها يضيفها للمراجعة.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    if (row.canImport && row.effect == "unresolved") Text("تُضاف للمراجعة لتحديد أثرها المالي قبل الاحتساب.", style = MaterialTheme.typography.bodySmall)
                                    row.warnings.take(2).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick={editing=row},enabled=supportedCurrency && !busy) { Text(if(row.userConfirmed) "تعديل البيانات المؤكدة" else "مراجعة وتصحيح وتأكيد") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { row -> StatementRowConfirmation(row,source,needsReview.any{it.id!=row.id},{editing=null}) { date,description,amount,direction ->
        val json=LedgerEngine(snapshot()).confirmStatementRow(ledgerJson.encodeToString(preview),row.id,date,description,amount,direction)
        preview=ledgerJson.decodeFromString(json)
        selected=selected+row.id;currencyConfirmed=false
        editing=preview.rows.firstOrNull{it.id!=row.id && (it.reviewRequired && !it.userConfirmed || !it.canImport)}
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun StatementRowConfirmation(row: StatementRow,source:StatementReviewSource?,hasNext:Boolean,dismiss:()->Unit,confirm:(String,String,String,String)->Unit) {
    var date by remember(row.id) { mutableStateOf(row.dateText) }
    var description by remember(row.id) { mutableStateOf(row.description) }
    var amount by remember(row.id) { mutableStateOf(row.amountBaisa?.let(Money::format).orEmpty()) }
    var direction by remember(row.id) { mutableStateOf(row.direction.orEmpty()) }
    var checked by remember(row.id) { mutableStateOf(false) }
    var error by remember(row.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
      Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar={TopAppBar(title={Text("مراجعة العملية • صفحة ${row.page}")},navigationIcon={IconButton(onClick=dismiss){Icon(Icons.Default.Close,"إغلاق")}})},bottomBar={
            Surface(shadowElevation=8.dp){Column(Modifier.navigationBarsPadding().padding(16.dp)){
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                Button(onClick={runCatching{confirm(date,description,amount,direction)}.onFailure{error=it.message?:"تحقق من البيانات"}},enabled=checked && direction in listOf("in","out"),modifier=Modifier.fillMaxWidth()){
                    Text(if(hasNext)"تأكيد والتالي" else "تأكيد والعودة للملخص")
                }
            }}
        }) { padding ->
          BoxWithConstraints(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            val form:@Composable (Modifier)->Unit={modifier -> Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("صحّح القيم بالرجوع إلى كشفك الأصلي. لن نحفظ صورة الكشف داخل التطبيق.")
            OutlinedTextField(date,{date=it.take(40);checked=false},label={Text("التاريخ والوقت إن وُجد")},supportingText={Text("13/10/2026 10:30:00؛ اترك التاريخ وحده إذا كانت الساعة غير معروفة")},singleLine=true)
            OutlinedTextField(description,{description=it.take(2000);checked=false},label={Text("وصف العملية")})
            OutlinedTextField(amount,{amount=it.take(40);checked=false},label={Text("المبلغ بالريال العُماني")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true)
            listOf("in" to "داخل: إيداع أو مبلغ مستلم","out" to "خارج: دفع أو سحب").forEach { (value,label) ->
                Row(verticalAlignment=Alignment.CenterVertically) { RadioButton(direction==value,{direction=value;checked=false});Text(label) }
            }
            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked,{checked=it});Text("راجعت التاريخ والمبلغ والاتجاه وأؤكد صحتها") }
            row.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            }}
            if(maxWidth>=700.dp) Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)){StatementOriginalCrop(source,row)}
                Column(Modifier.weight(1f)){form(Modifier.verticalScroll(rememberScrollState()))}
            } else Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){StatementOriginalCrop(source,row);form(Modifier)}
          }
        }
      }
    }
}

@Composable private fun StatementAuditCard(preview:StatementPreview) {
    val audit=preview.audit ?: return
    OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(when(audit.status){"matched"->"تطابقت الأرقام في الفحوص المتاحة";"mismatch"->"يوجد فرق يحتاج المراجعة";"incomplete"->"المطابقة غير مكتملة";else->"لا توجد أرصدة كافية للمطابقة"},style=MaterialTheme.typography.titleSmall)
        preview.summary.openingBaisa?.let{Text("الافتتاحي: ${Money.format(it)} • الداخل: ${Money.format(audit.incomingBaisa)} • الخارج: ${Money.format(audit.outgoingBaisa)}")}
        preview.summary.closingBaisa?.let{Text("الختامي المطبوع: ${Money.format(it)} • المحسوب: ${audit.calculatedClosingBaisa?.let(Money::format) ?: "غير متاح"}")}
        Text("${audit.checks} فحص • تشمل المطابقة كل حركات الكشف، ومنها التحويلات. اختيار بعض الصفوف لا يغيّر إجمالي الكشف.",style=MaterialTheme.typography.bodySmall)
        if(audit.checks>0)Text("تطابق الأرقام وحده لا يثبت صحة كل عملية؛ راجع الخانات المشكوك فيها.",style=MaterialTheme.typography.bodySmall)
        audit.warnings.forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }}
}

@Composable private fun StatementOriginalCrop(source:StatementReviewSource?,row:StatementRow) {
    val context=LocalContext.current
    var focus by remember(row.id){mutableStateOf("amount")}
    var bitmap by remember{mutableStateOf<android.graphics.Bitmap?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(source,row.id,focus) {
        bitmap?.eraseColor(android.graphics.Color.WHITE);bitmap=null;error=null
        if(source==null || row.region==null){error="المقتطف الأصلي غير متاح؛ راجع ملفك الأصلي";return@LaunchedEffect}
        try{bitmap=source.crop(context,row,row.fields.firstOrNull{it.name==focus})}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:OutOfMemoryError){error="الذاكرة لا تكفي للتكبير الآن؛ راجع الخانة في الملف الأصلي"}
        catch(_:Exception){error="تعذر تكبير هذه الخانة؛ راجعها في الملف الأصلي"}
    }
    DisposableEffect(Unit){onDispose{bitmap?.eraseColor(android.graphics.Color.WHITE);bitmap=null}}
    Text("الخانة الأصلية — مؤقتة في الذاكرة",style=MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        (listOf("row" to "الصف كاملًا")+row.fields.map{it.name to when(it.name){"date"->"التاريخ";"amount"->"المبلغ";"balance"->"الرصيد";else->"الاتجاه"}}).forEach{(key,label)->
            FilterChip(focus==key,{focus=key},label={Text(label)})
        }
    }
    bitmap?.let{Image(it.asImageBitmap(),"مقتطف الخانة من الكشف الأصلي",modifier=Modifier.fillMaxWidth().heightIn(min=90.dp,max=220.dp),contentScale=ContentScale.Fit)}
        ?: if(error==null)LinearProgressIndicator(Modifier.fillMaxWidth()) else Text(error.orEmpty(),style=MaterialTheme.typography.bodySmall)
}
