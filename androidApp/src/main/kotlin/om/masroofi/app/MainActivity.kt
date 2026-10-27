package om.masroofi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import om.masroofi.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Green = Color(0xFF145C45)
private val Mint = Color(0xFFE5F1E8)
private val Ink = Color(0xFF173D31)
private val Muted = Color(0xFF66776D)
private val Canvas = Color(0xFFF5F8F5)

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    private var reviewId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        NoticeCenter.channels(this)
        val repo = LedgerRepository.get(this)
        scheduleReminder(this, repo.states.value.preferences)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Green, secondary = Green, background = Canvas, surface = Color.White,
                    onPrimary = Color.White, onBackground = Ink, onSurface = Ink, outline = Color(0xFFD6E0D8))) {
                    ExpenseApp(repo, sharedText, reviewId, { sharedText = null }, { reviewId = null })
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); acceptIntent(intent) }
    override fun onResume() { super.onResume(); runCatching { NoticeCenter.flush(this) } }
    private fun acceptIntent(value: Intent) {
        if (value.action == Intent.ACTION_SEND) sharedText = value.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.length <= 200_000 }
        reviewId = value.getStringExtra("review")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExpenseApp(repo: LedgerRepository, sharedText: String?, reviewId: String?, consumedText: () -> Unit, consumedReview: () -> Unit) {
    val state by repo.states.collectAsState()
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val report = BudgetEngine.report(state, now)
    var page by remember { mutableIntStateOf(0) }
    var paste by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var add by remember { mutableStateOf(false) }
    var budget by remember { mutableStateOf<Category?>(null) }
    var manageCategories by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val quickEntryNotice = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun perform(action: () -> Unit) { try { action(); NoticeCenter.flush(context) } catch (error: Exception) { message = repo.loadError ?: error.message?.takeIf { it.length < 300 && Regex("[ء-ي]").containsMatchIn(it) } ?: "تعذر تنفيذ العملية. تحقق من المدخلات أو النسخة الاحتياطية." } }
    LaunchedEffect(sharedText) { sharedText?.let { paste = it; consumedText() } }
    LaunchedEffect(reviewId) { reviewId?.let { editing = state.transactions.find { txn -> txn.id == it }; page = 1; consumedReview() } }
    Scaffold(containerColor = Canvas, snackbarHost = { SnackbarHost(quickEntryNotice) }, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Green, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AccountBalanceWallet, null, tint = Color.White) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text("مصروفي", fontSize = 23.sp, fontWeight = FontWeight.Bold); Text("وضوح أكبر لكل ريال", color = Muted, fontSize = 12.sp) }
            IconButton(onClick = { paste = "" }) { Icon(Icons.Default.AddComment, "إضافة رسالة", tint = Green) }
        }
    }, bottomBar = {
        NavigationBar(containerColor = Color.White) {
            listOf("الرئيسية", "العمليات", "الحدود", "الإعدادات").forEachIndexed { index, label ->
                val icon = when (index) { 0 -> Icons.Default.Home; 1 -> Icons.AutoMirrored.Filled.List; 2 -> Icons.Default.PieChart; else -> Icons.Default.Settings }
                NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(icon, label) }, label = { Text(label) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Mint))
            }
        }
    }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { add = true }, containerColor = Green, contentColor = Color.White,
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("إضافة عملية") })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            repo.loadError?.let { NoteCard(it, true) }
            if (page == 0) StatementImportAction(repo) { result ->
                page = 1
                message = "أُضيفت ${result.ids.size} عملية من كشف الحساب. جرى تجاهل ${result.duplicates} عملية مسجلة مسبقًا."
            }
            when (page) {
                0 -> Dashboard(state, report, { paste = "" }, { page = 1 }, { txn -> editing = txn }, { add = true })
                1 -> TransactionList(state, { editing = it }, { paste = "" })
                2 -> {
                    Text("حدود تناسب حياتك", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { manageCategories = true }) { Text("إدارة الفئات") }
                    Text("حد أسبوعي وشهري مستقل لكل فئة. اضغط على الفئة لتعديل حدّها.", color = Muted)
                    report.categories.forEach { line -> BudgetCard(line, { budget = state.categories.first { it.id == line.categoryId } }) }
                }
                3 -> SettingsPage(repo, state, { perform(it) }, { message = it })
            }
        }
    }
    if (paste != null) PasteDialog(paste.orEmpty(),state, { paste = null }) { text,bank,owned ->
        perform {
            val result = repo.update { engine ->
                if(BankKnowledge.key(bank)!=null) engine.setOwnedBank(bank,owned)
                ledgerJson.decodeFromString<ImportResult>(engine.importBankMessage(text, System.currentTimeMillis(), "paste", UUID.randomUUID().toString(),bank))
            }
            paste = null; page = 1
            message = if (result.ids.isEmpty()) result.reasons.joinToString("\n").ifBlank { "لم تُضف عمليات جديدة" } else "أُضيفت ${result.ids.size} عملية. يمكنك مراجعة السبب والفئة."
        }
    }
    if (add) QuickEntrySheet(state, { add = false }, { name -> repo.update { it.addCategory(name, "◈") } }) { command ->
        perform {
            repo.update { it.editTransaction(ledgerJson.encodeToString(command)) }
            add = false
            scope.launch { quickEntryNotice.showSnackbar("تم تسجيل العملية") }
        }
    }
    if (manageCategories) CategoryManager(state, { manageCategories = false },
        { name -> repo.update { it.addCategory(name,"◈") } }, { id,name -> repo.update { it.renameCategory(id,name) } }, { id -> repo.update { it.deleteCategory(id) } })
    if (editing != null) EditDialog(editing, state, { editing = null }, { command ->
            perform { repo.update { it.editTransaction(ledgerJson.encodeToString(command)) }; editing = null; add = false }
    }, { id -> perform { repo.update { it.deleteTransaction(id) }; editing = null } }, { id -> perform {repo.update{it.unlinkTransfer(id)};editing=null} }, { id, value, category, note ->
        perform { repo.update { it.splitCash(id, value, category, note) }; editing = null }
    })
    budget?.let { category -> BudgetDialog(category, state.budgets.find { it.categoryId == category.id }, { budget = null }) { week, month ->
        perform { repo.update { it.setBudget(category.id, week, month) }; budget = null }
    } }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("مصروفي") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("حسنًا") } }) }
}

@Composable private fun Dashboard(state: LedgerState, report: Report, paste: () -> Unit, review: () -> Unit, edit: (Transaction) -> Unit, add: () -> Unit) {
    Text("مصروفاتك، بصورة أوضح", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Green, Color(0xFF277D57))), RoundedCornerShape(26.dp)).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("صافي الإنفاق — المبالغ الخارجة", color = Color(0xFFD5EBDC))
            Row(verticalAlignment = Alignment.Bottom) { Text(Money.format(report.netExpense), color = Color.White, fontSize = 37.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Text("ر.ع", color = Color.White, modifier = Modifier.padding(bottom = 6.dp)) }
            HorizontalDivider(color = Color.White.copy(alpha = .2f), modifier = Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("وارد", color = Color(0xFFD5EBDC), fontSize = 12.sp); Text("${Money.format(report.incoming)} ر.ع", color = Color.White) }
                Column { Text("استرجاع", color = Color(0xFFD5EBDC), fontSize = 12.sp); Text("${Money.format(report.refunds)} ر.ع", color = Color.White) }
            }
        }
    }
    if (report.reviewCount > 0) Surface(onClick = review, shape = RoundedCornerShape(18.dp), color = Color(0xFFFFF3D8)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FactCheck, null, tint = Color(0xFF8B6517)); Spacer(Modifier.width(12.dp))
            Column { Text("${report.reviewCount} عمليات تحتاج مراجعة", fontWeight = FontWeight.Bold); Text(if (report.excludedCount > 0) "${report.excludedCount} منها لا تدخل الإجمالي حتى تُحسم" else "أكد الفئات المقترحة أو أضف السبب", fontSize = 12.sp) }
        }
    }
    if (state.transactions.isEmpty()) {
        Surface(shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("سجّل أول عملية", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("اختر البنك والمبلغ والنوع. يمكنك إنشاء فئاتك أثناء الإضافة.", color = Muted)
                Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text("إضافة عملية") }
                TextButton(onClick = paste) { Text("أو لصق رسالة مصرفية") }
            }
        }
    }
    Text("أين يذهب إنفاقك؟", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val active = report.categories.filter { it.monthlySpent != 0L || it.monthlyLimit != null || it.weeklyLimit != null }
    if (active.isEmpty()) Text("تظهر الفئات هنا بعد إضافة عملياتك أو إعداد حدودك.", color = Muted)
    active.sortedByDescending { it.monthlySpent }.take(3).forEach { line -> BudgetCard(line, review) }
    if (report.unclassified != 0L) NoteCard("غير مصنف: ${Money.format(report.unclassified)} ر.ع — محسوب في الإجمالي")
    if (state.transactions.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("آخر العمليات", fontSize = 20.sp, fontWeight = FontWeight.Bold); TextButton(onClick = review) { Text("عرض الكل") } }
        state.transactions.take(4).forEach { TransactionCard(it, state, { edit(it) }) }
    }
}

@Composable private fun NoteCard(text: String, error: Boolean = false) {
    Surface(color = if (error) Color(0xFFFFE9E4) else Mint, shape = RoundedCornerShape(16.dp)) { Text(text, Modifier.fillMaxWidth().padding(16.dp), fontSize = 13.sp, color = if (error) Color(0xFF8A3024) else Green) }
}
@Composable private fun BudgetCard(line: BudgetLine, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(19.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${line.symbol}  ${line.name}", fontWeight = FontWeight.Bold); Text("${Money.format(line.monthlySpent)} ر.ع", color = Green) }
            val limit = line.monthlyLimit ?: line.weeklyLimit
            val spent = if (line.monthlyLimit != null) line.monthlySpent else line.weeklySpent
            if (limit != null) {
                LinearProgressIndicator(progress = { (spent.toFloat() / limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp), color = if (spent >= limit) Color(0xFFBD5948) else Green, trackColor = Mint)
            }
            Text(buildList {
                line.monthlyLimit?.let { add("شهري: ${Money.format(it)}") }
                line.weeklyLimit?.let { add("أسبوعي: ${Money.format(line.weeklySpent)} / ${Money.format(it)}") }
            }.joinToString(" • ").ifEmpty { "بدون حد — اضغط للإعداد" }, fontSize = 12.sp, color = Muted)
        }
    }
}
private fun dateText(txn: Transaction, zone: String): String {
    val format = if (txn.datePrecision == "day") "yyyy/MM/dd" else "yyyy/MM/dd HH:mm"
    return Instant.ofEpochMilli(txn.occurredAtMillis).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern(format))
}
@Composable private fun TransactionCard(txn: Transaction, state: LedgerState, click: () -> Unit) {
    val category = state.categories.find { it.id == txn.categoryId }
    Surface(onClick = click, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = Mint, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Text(category?.symbol ?: "↔", fontSize = 20.sp) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(txn.counterparty ?: txn.note.takeIf { it.isNotBlank() } ?: when (txn.effect) { "internal" -> "تحويل"; "income" -> "وارد"; "refund" -> "استرجاع"; else -> "مصروف" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${category?.name ?: "غير مصنف"} • ${dateText(txn, state.preferences.timeZone)}", color = Muted, fontSize = 11.sp)
                Text("${txn.bankName}${if(txn.transferPairId!=null) " • تحويل مرتبط بين حساباتي" else ""}",color=Muted,fontSize=11.sp)
                if (txn.note.isNotBlank()) Text(txn.note, fontSize = 12.sp, maxLines = 1)
                if (!txn.reviewed) Text(if (txn.duplicateOf != null) "تكرار محتمل — راجع" else if (!txn.financialVerified || txn.effect == "unresolved") "غير محتسب حتى المراجعة" else "فئة مقترحة", color = Color(0xFF9B701B), fontSize = 10.sp)
                if (txn.isDemo) Text("مثال توضيحي", color = Muted, fontSize = 10.sp)
            }
            val amount = txn.accountingBaisa?.let(Money::format) ?: txn.amountMinor?.let { "${it.toDouble() / if (txn.currency in listOf("OMR", "KWD", "BHD")) 1000 else 100} ${txn.currency}" } ?: "راجع المبلغ"
            Text(amount, color = if (txn.effect in listOf("income", "refund")) Green else Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
@Composable private fun TransactionList(state: LedgerState, edit: (Transaction) -> Unit, paste: () -> Unit) {
    var query by remember { mutableStateOf("") }; var onlyReview by remember { mutableStateOf(false) }
    Text("سجل العمليات", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن بنك أو جهة أو سبب") }, singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) { FilterChip(selected = onlyReview, onClick = { onlyReview = !onlyReview }, label = { Text("تحتاج مراجعة") }); Spacer(Modifier.weight(1f)); TextButton(onClick = paste) { Text("إضافة رسالة") } }
    val filtered = state.transactions.filter { (!onlyReview || !it.reviewed) && (query.isBlank() || "${it.bankName} ${it.counterparty} ${it.note} ${state.categories.find { cat -> cat.id == it.categoryId }?.name}".contains(query, true)) }
    if (filtered.isEmpty()) NoteCard("لا توجد عمليات بهذه الخيارات.")
    var resultPage by remember(query, onlyReview) { mutableIntStateOf(0) }
    val sorted = filtered.sortedByDescending { it.occurredAtMillis }
    val lastPage = ((sorted.size - 1).coerceAtLeast(0) / 50)
    val currentPage = resultPage.coerceAtMost(lastPage)
    sorted.drop(currentPage * 50).take(50).forEach { TransactionCard(it, state) { edit(it) } }
    if (lastPage > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { resultPage = currentPage - 1 }, enabled = currentPage > 0) { Text("السابق") }
        Text("${currentPage + 1} / ${lastPage + 1}")
        TextButton(onClick = { resultPage = currentPage + 1 }, enabled = currentPage < lastPage) { Text("التالي") }
    }
}

@Composable private fun PasteDialog(initial: String,state:LedgerState, dismiss: () -> Unit, import: (String,String,Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var bank by remember {mutableStateOf("")}
    var owned by remember {mutableStateOf(true)}
    val detected=BankKnowledge.detect(text,state.keywordRules)
    val effectiveBank=bank.ifBlank{detected}
    AlertDialog(onDismissRequest = dismiss, title = { Text("أضف رسالة مصرفية") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("نستخرج المبلغ والجهة محليًا. لن يُحفظ نص الرسالة.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().height(160.dp), label = { Text("نص الرسالة") })
            OutlinedTextField(bank,{bank=it.take(80)},label={Text("البنك — المكتشف: $detected")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(owned,{owned=it},enabled=BankKnowledge.key(effectiveBank)!=null);Text("هذا البنك ضمن حساباتي",fontSize=12.sp)}
        }
    }, confirmButton = { Button(onClick = { import(text,effectiveBank,owned) }, enabled = text.isNotBlank()) { Text("تحليل وإضافة") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Choice(label: String, value: String, options: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = options.find { it.first == value }?.second.orEmpty(), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) { options.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { change(key); expanded = false }) } }
    }
}
@Composable private fun EditDialog(txn: Transaction?, state: LedgerState, dismiss: () -> Unit, save: (EditCommand) -> Unit, delete: (String) -> Unit, unlink:(String)->Unit, splitCash: (String, Long, String, String) -> Unit) {
    var amount by remember { mutableStateOf(txn?.accountingBaisa?.let(Money::format).orEmpty()) }
    var party by remember { mutableStateOf(txn?.counterparty.orEmpty()) }
    var note by remember { mutableStateOf(txn?.note.orEmpty()) }
    var effect by remember { mutableStateOf(txn?.effect?.takeIf { it != "unresolved" } ?: if(txn==null || txn.direction=="out") "expense" else if(txn.direction=="in") "income" else "") }
    var category by remember { mutableStateOf(txn?.categoryId.orEmpty()) }
    var rememberRule by remember { mutableStateOf(false) }
    var bank by remember {mutableStateOf(txn?.bankName?.takeUnless{it==BankKnowledge.UNKNOWN}.orEmpty())}
    var ownsBank by remember {mutableStateOf(BankKnowledge.key(bank) in state.ownedBanks)}
    var categoryKeyword by remember {mutableStateOf("")}
    var bankKeyword by remember {mutableStateOf("")}
    var automatic by remember {mutableStateOf(txn?.matchTransfers ?: true)}
    var timeKnown by remember {mutableStateOf(txn==null || txn.datePrecision in listOf("second","minute"))}
    val originalTime = txn?.occurredAtMillis ?: System.currentTimeMillis()
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    var date by remember { mutableStateOf(Instant.ofEpochMilli(originalTime).atZone(ZoneId.of(state.preferences.timeZone)).format(formatter)) }
    var error by remember { mutableStateOf<String?>(null) }; var deleting by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf(txn?.originalId.orEmpty()) }
    var splitting by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (txn == null) "عملية جديدة" else "مراجعة العملية") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            txn?.warnings?.forEach { NoteCard(it) }
            if (txn == null) Text("إذا كان المصروف من سحب نقدي سجلته، وزّعه من عملية السحب لتجنب احتسابه مرتين.", fontSize = 12.sp, color = Muted)
            if (txn?.duplicateOf != null) NoteCard("توجد عملية مشابهة. الحفظ يؤكد أن هذه عملية مستقلة؛ احذفها إن كانت نسخة مكررة.")
            OutlinedTextField(bank,{bank=it.take(80);ownsBank=BankKnowledge.key(it) in state.ownedBanks},label={Text("البنك المستخدم")},supportingText={Text("MTHQ: ميثاق • NIZWA: نزوى")},modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(ownsBank,{ownsBank=it});Text("هذا البنك ضمن حساباتي",fontSize=13.sp)}
            OutlinedTextField(amount, { amount = it }, label = { Text(if (txn != null && txn.currency != "OMR") "قيمة الخصم الفعلية بالريال" else "المبلغ بالريال") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Choice("نوع العملية", effect, listOf("expense" to "مصروف", "income" to "مبلغ وارد", "refund" to "استرجاع", "internal" to "تحويل بين حساباتي")) { effect = it }
            if (effect == "refund") Choice("العملية الأصلية — اختياري", original,
                listOf("" to "بدون ربط") + state.transactions.filter { it.effect == "expense" && it.financialVerified && it.duplicateOf == null && it.id != txn?.id }.map { it.id to "${it.counterparty ?: "مصروف"} • ${Money.format(it.accountingBaisa ?: 0)}" }) { original = it }
            Choice("الفئة", category, listOf("" to "غير مصنف") + state.categories.map { it.id to "${it.symbol} ${it.name}" }) { category = it }
            OutlinedTextField(party, { party = it }, label = { Text("الجهة — اختياري") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("السبب أو الملاحظة — اختياري") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("التاريخ والوقت") }, supportingText = { Text(if (txn?.datePrecision == "received") "وقت الاستلام تقديري؛ يمكنك تصحيحه" else if (txn?.datePrecision == "day") "الرسالة ذكرت اليوم فقط؛ الساعة غير معروفة" else "yyyy-MM-dd HH:mm:ss") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(timeKnown,{timeKnown=it});Text("الساعة مؤكدة وليست تقديرية",fontSize=13.sp)}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(automatic,{automatic=it});Text("مطابقة التحويلات بين حساباتي تلقائيًا",fontSize=13.sp)}
            if(txn?.transferPairId!=null) TextButton(onClick={unlink(txn.id)}) {Text("فك الربط واعتبار العمليتين مستقلتين")}
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberRule, { rememberRule = it }); Text("تذكر الفئة لهذه الجهة", fontSize = 13.sp) }
            OutlinedTextField(categoryKeyword,{categoryKeyword=it.take(80)},label={Text("كلمة مفتاحية لتعلم الفئة — اختياري")},supportingText={Text("مثل OMAN OIL؛ تُحفظ للفئة التي تختارها هنا")})
            OutlinedTextField(bankKeyword,{bankKeyword=it.take(80)},label={Text("اختصار لتعلم البنك — اختياري")})
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (txn != null) TextButton(onClick = { deleting = true }) { Text("حذف العملية", color = MaterialTheme.colorScheme.error) }
            if (txn?.type == "atm" && txn.effect == "expense" && txn.financialVerified && (txn.accountingBaisa ?: 0) > 0) TextButton(onClick = { splitting = true }) { Text("تقسيم مبلغ من هذا السحب") }
        }
    }, confirmButton = { Button(onClick = {
        val value = Money.input(amount)
        val timestamp = runCatching { LocalDateTime.parse(date, formatter).atZone(ZoneId.of(state.preferences.timeZone)).toInstant().toEpochMilli() }.getOrNull()
        if (value == null || timestamp == null || BankKnowledge.key(bank)==null || effect !in listOf("expense","income","refund","internal")) error = "أدخل مبلغًا وتاريخًا وبنكًا واختر نوع العملية" else save(EditCommand(txn?.id, value,
            if (effect in listOf("income", "refund")) "in" else txn?.direction?.takeIf { effect == "internal" } ?: "out", effect,
            category.takeIf { it.isNotBlank() }, party, note, timestamp, rememberRule, original.takeIf { effect == "refund" && it.isNotBlank() },
            bankName=bank,ownsBank=ownsBank,learnKeyword=categoryKeyword.takeIf{it.isNotBlank()},learnBankAlias=bankKeyword.takeIf{it.isNotBlank()},matchTransfers=automatic,timeConfirmed=timeKnown))
    }) { Text("حفظ وتأكيد") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
    if (deleting && txn != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("حذف العملية؟") }, text = { Text("سيعاد حساب الإنفاق والحدود.") }, confirmButton = { TextButton(onClick = { delete(txn.id) }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("إلغاء") } })
    if (splitting && txn != null) CashSplitDialog(txn, state, { splitting = false }) { value, chosen, reason -> splitCash(txn.id, value, chosen, reason) }
}
@Composable private fun CashSplitDialog(txn: Transaction, state: LedgerState, dismiss: () -> Unit, save: (Long, String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }; var category by remember { mutableStateOf(state.categories.firstOrNull { it.id != "cash" }?.id.orEmpty()) }; var note by remember { mutableStateOf("") }; var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("تقسيم النقد") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("المتبقي للتقسيم: ${Money.format(txn.accountingBaisa ?: 0)} ر.ع. يبقى إجمالي المصروف ثابتًا.", fontSize = 13.sp)
            if (state.categories.none { it.id != "cash" }) Text("أنشئ فئة من إدارة الفئات أولًا.", fontSize = 12.sp)
            OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ بالريال") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Choice("الفئة", category, state.categories.filter { it.id != "cash" }.map { it.id to it.name }) { category = it }
            OutlinedTextField(note, { note = it }, label = { Text("السبب — اختياري") })
            if (error) Text("أدخل مبلغًا لا يتجاوز المتبقي", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { val value = Money.input(amount); if (value == null || value <= 0 || value > (txn.accountingBaisa ?: 0)) error = true else save(value, category, note) }) { Text("تقسيم وحفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}
@Composable private fun BudgetDialog(category: Category, budget: Budget?, dismiss: () -> Unit, save: (Long, Long) -> Unit) {
    var week by remember { mutableStateOf(budget?.weeklyBaisa?.let(Money::format).orEmpty()) }
    var month by remember { mutableStateOf(budget?.monthlyBaisa?.let(Money::format).orEmpty()) }; var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("حد ${category.name}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(week, { week = it }, label = { Text("أسبوعي — ر.ع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(month, { month = it }, label = { Text("شهري — ر.ع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("اترك الحقل فارغًا لإلغاء الحد. الفترتان مستقلتان.", fontSize = 12.sp, color = Muted)
            if (error) Text("المبلغ غير صالح", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = { val w = if (week.isBlank()) 0L else Money.input(week); val m = if (month.isBlank()) 0L else Money.input(month); if (w == null || m == null) error = true else save(w, m) }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = dismiss) { Text("إلغاء") } })
}

@Composable private fun SettingsPage(repo: LedgerRepository, state: LedgerState, perform: (() -> Unit) -> Unit, message: (String) -> Unit) {
    val context = LocalContext.current
    var sms by remember { mutableStateOf(inputPreferences(context).getBoolean("sms", false) && ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) }
    val smsRequest = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> sms = granted; inputPreferences(context).edit().putBoolean("sms", granted).apply() }
    val noticeRequest = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) message("يمكنك متابعة العمليات والتنبيهات داخل التطبيق.") }
    var prefs by remember(state.preferences) { mutableStateOf(state.preferences) }
    var monthDay by remember(state.preferences) { mutableStateOf(state.preferences.monthStartDay.toString()) }
    var reminderHour by remember(state.preferences) { mutableStateOf(state.preferences.reminderHour.toString()) }
    var password by remember { mutableStateOf("") }
    var managing by remember { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf<String?>(null) }; var encryptedBackup by remember { mutableStateOf("") }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { perform { val stream = context.contentResolver.openOutputStream(it) ?: error("Cannot open destination"); stream.use { it.write(encryptedBackup.toByteArray()) }; message("حُفظت نسخة احتياطية مشفّرة. احتفظ بكلمة مرورها.") } }
        encryptedBackup = ""; password = ""
    }
    val restore = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { perform { val bytes = context.contentResolver.openInputStream(it)?.use { stream ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = stream.read(buffer)
            while (count != -1) { require(output.size() + count <= 24_000_000); output.write(buffer, 0, count); count = stream.read(buffer) }
            output.toByteArray()
        } ?: error("Cannot read file"); repo.restore(BackupCrypto.open(bytes.toString(Charsets.UTF_8), password)); message("تمت استعادة النسخة الاحتياطية") } }
        password = ""
    }
    var clear by remember { mutableStateOf(false) }; var apps by remember { mutableStateOf(false) }
    Text("إعداداتك وخصوصيتك", fontSize = 25.sp, fontWeight = FontWeight.Bold)
    NoteCard("المعالجة والتخزين على جهازك. لا يحتاج التحليل إلى الإنترنت، ولا نحتفظ بنصوص الرسائل أو رموز التحقق.")
    SettingsCard("استقبال العمليات") {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("رسائل جديدة تلقائيًا"); Text("نستخرج العمليات المالية بعد إذنك", fontSize = 12.sp, color = Muted) }; Switch(sms, { enabled ->
            if (enabled) smsRequest.launch(Manifest.permission.RECEIVE_SMS) else { sms = false; inputPreferences(context).edit().putBoolean("sms", false).apply() }
        }) }
        TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) noticeRequest.launch(Manifest.permission.POST_NOTIFICATIONS) else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("إعداد الإشعارات") }
        TextButton(onClick = { apps = true }) { Text("اختيار تطبيقات مصدر الإشعارات") }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("منح الوصول للإشعارات — اختياري") }
        Text("الرسائل السابقة لا تستورد تلقائيًا. يمكن لصقها أو مشاركتها إلى مصروفي.", fontSize = 12.sp, color = Muted)
    }
    SettingsCard("الفترات والتذكير") {
        Choice("بداية الأسبوع", prefs.weekStart.toString(), listOf(6 to "السبت", 7 to "الأحد", 1 to "الاثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة").map { it.first.toString() to it.second }) { prefs = prefs.copy(weekStart = it.toInt()) }
        OutlinedTextField(monthDay, { monthDay = it }, label = { Text("بداية دورة الشهر: يوم 1–31") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(reminderHour, { reminderHour = it }, label = { Text("ساعة التذكير: 0–23") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(verticalAlignment = Alignment.CenterVertically) { Text("تذكير يومي مجمع", Modifier.weight(1f)); Switch(prefs.remindersEnabled, { prefs = prefs.copy(remindersEnabled = it) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("إظهار التفاصيل على شاشة القفل", Modifier.weight(1f), fontSize = 13.sp); Switch(prefs.showAmountsOnLockScreen, { prefs = prefs.copy(showAmountsOnLockScreen = it) }) }
        Button(onClick = { perform { val p = prefs.copy(monthStartDay = monthDay.toInt(), reminderHour = reminderHour.toInt()); repo.update { it.setPreferences(ledgerJson.encodeToString(p)) }; scheduleReminder(context, p); message("حُفظت الإعدادات") } }) { Text("حفظ الإعدادات") }
    }
    SettingsCard("فئاتك وقواعدك") {
        TextButton(onClick = { managing = true }) { Text("إدارة الفئات (${state.categories.size})") }
        Text("بنوك حساباتي",fontWeight=FontWeight.Bold)
        (BankKnowledge.names+state.ownedBanks.map(BankKnowledge::canonical)).distinct().forEach { bank ->
            val owned=BankKnowledge.key(bank) in state.ownedBanks
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(owned,{value->perform{repo.update{it.setOwnedBank(bank,value)}}});Text(bank,fontSize=13.sp)}
        }
        Text("ذاكرة الكلمات المفتاحية المحلية",fontWeight=FontWeight.Bold)
        state.keywordRules.forEach { rule ->
            Text("${rule.keyword} ← ${rule.bankName ?: state.categories.firstOrNull{it.id==rule.categoryId}?.name.orEmpty()}",fontSize=13.sp)
            TextButton(onClick={perform{repo.update{it.removeKeyword(rule.keyword)}}}) {Text("حذف الكلمة المحفوظة")}
        }
        state.rules.forEach { rule ->
            Text("${rule.merchant} ← ${state.categories.find { it.id == rule.categoryId }?.name}", fontSize = 13.sp)
            Row { TextButton(onClick = { perform { repo.update { it.applyRuleToHistory(rule.merchant) } } }) { Text("تطبيق على السابق") }; TextButton(onClick = { perform { repo.update { it.removeRule(rule.merchant) } } }) { Text("إلغاء القاعدة") } }
        }
    }
    SettingsCard("نسخ احتياطي محلي") {
        Text("نسخة مشفّرة بكلمة مرور تختارها، ويمكن نقلها بين هاتفين. الاستعادة تستبدل البيانات الحالية.", color = Muted, fontSize = 13.sp)
        Row { TextButton(onClick = { backupMode = "export"; password = "" }) { Text("حفظ نسخة") }; TextButton(onClick = { backupMode = "restore"; password = "" }) { Text("استعادة نسخة") } }
        if (state.transactions.any { it.isDemo }) TextButton(onClick = { perform { repo.update { it.clearDemo() } } }) { Text("حذف الأمثلة التوضيحية") }
        TextButton(onClick = { clear = true }) { Text("حذف جميع البيانات", color = MaterialTheme.colorScheme.error) }
    }
    Text("مصروفي ${BuildConfig.VERSION_NAME} • تجربة شخصية\nالقراءة والحفظ على جهازك. الفئة المقترحة قابلة للتصحيح.", color = Muted, fontSize = 12.sp)
    if (backupMode != null) AlertDialog(onDismissRequest = { backupMode = null; password = "" }, title = { Text(if (backupMode == "export") "كلمة مرور النسخة" else "استعادة واستبدال البيانات") }, text = {
        Column { Text(if (backupMode == "restore") "سيستبدل الملف بيانات التطبيق الحالية. أدخل كلمة مرور النسخة ثم اختر ملفها." else "8 أحرف على الأقل. لن تحفظ كلمة المرور في التطبيق.", fontSize = 13.sp); OutlinedTextField(password, { password = it }, visualTransformation = PasswordVisualTransformation(), label = { Text("كلمة المرور") }) }
    }, confirmButton = { Button(onClick = { val mode = backupMode; perform { if (mode == "export") { encryptedBackup = BackupCrypto.seal(repo.snapshot(), password); export.launch("Masroofi-backup.masroofi") } else restore.launch(arrayOf("*/*")); backupMode = null } }, enabled = if (backupMode == "export") password.length >= 8 else password.isNotEmpty()) { Text(if (backupMode == "export") "حفظ" else "اختيار واستعادة") } }, dismissButton = { TextButton(onClick = { backupMode = null; password = "" }) { Text("إلغاء") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("حذف جميع البيانات؟") }, text = { Text("ستحذف العمليات والحدود والقواعد المحلية. النسخ التي صدّرتها تبقى في مكانها.") }, confirmButton = { TextButton(onClick = { perform { repo.restore(LedgerEngine().stateJson()); clear = false } }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("إلغاء") } })
    if (managing) CategoryManager(state, { managing = false }, { name -> repo.update { it.addCategory(name,"◈") } },
        { id,name -> repo.update { it.renameCategory(id,name) } }, { id -> repo.update { it.deleteCategory(id) } })
    if (apps) {
        val available = remember { context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(context.packageManager).toString() } }
        var selected by remember { mutableStateOf(inputPreferences(context).getStringSet("apps", emptySet()).orEmpty().toSet()) }
        AlertDialog(onDismissRequest = { apps = false }, title = { Text("مصادر تختارها أنت") }, text = { Column(Modifier.height(350.dp).verticalScroll(rememberScrollState())) { Text("حدد تطبيق الرسائل أو البنك فقط. نحلل التنبيهات المالية محليًا.", fontSize = 12.sp); available.filter { it.activityInfo.packageName != context.packageName }.forEach { info -> val pkg = info.activityInfo.packageName; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pkg in selected, { checked -> selected = if (checked) selected + pkg else selected - pkg }); Text(info.loadLabel(context.packageManager).toString(), fontSize = 13.sp) } } } }, confirmButton = { TextButton(onClick = { inputPreferences(context).edit().putStringSet("apps", selected).apply(); apps = false }) { Text("حفظ") } }, dismissButton = { TextButton(onClick = { apps = false }) { Text("إلغاء") } })
    }
}
@Composable private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(title, Modifier.weight(1f), fontSize = 17.sp); Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) content()
        }
    }
}
