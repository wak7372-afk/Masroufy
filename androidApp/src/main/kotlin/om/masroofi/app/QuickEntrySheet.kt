package om.masroofi.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import om.masroofi.core.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The short creation flow is separate from the full transaction review form. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun QuickEntrySheet(state: LedgerState, dismiss: () -> Unit, createCategory: (String) -> String, save: (EditCommand) -> Unit) {
    val zone = remember(state.preferences.timeZone) { ZoneId.of(state.preferences.timeZone) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val amountFocus = remember { FocusRequester() }
    val reasonFocus = remember { FocusRequester() }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    // Reuse the last visible bank choice, without changing account ownership.
    val initialBank = remember {
        state.transactions.firstOrNull { it.source == "manual" && !it.isDemo && BankKnowledge.key(it.bankName) != null }?.bankName
            ?: state.transactions.firstOrNull { !it.isDemo && BankKnowledge.key(it.bankName) != null }?.bankName
            ?: BankKnowledge.names.singleOrNull { BankKnowledge.key(it) in state.ownedBanks }.orEmpty()
    }
    var bank by remember { mutableStateOf(initialBank) }
    var category by remember { mutableStateOf<String?>(null) }
    var effect by remember { mutableStateOf("expense") }
    var categoryName by remember { mutableStateOf("") }
    var nativePicker by remember { mutableStateOf<android.app.Dialog?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }
    var customBank by remember { mutableStateOf("") }
    var customTime by remember { mutableStateOf<Long?>(null) }
    var timeConfirmed by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val value = Money.input(amount)
    val validAmount = value != null && value > 0
    val selectedCategory = state.categories.find { it.id == category }
    val title = "إضافة عملية"
    fun submit() {
        if (!validAmount) { error = "أدخل مبلغًا أكبر من صفر، حتى ثلاث خانات عشرية"; amountFocus.requestFocus(); return }
        if (BankKnowledge.key(bank) == null) { error = "اختر البنك المستخدم"; keyboard?.hide(); picker = "bank"; return }
        // An automatic time is captured at Save, rather than when the sheet opened.
        save(EditCommand(amountBaisa = value!!, direction = if (effect in listOf("income", "refund")) "in" else "out",
            effect = effect, categoryId = category, note = reason.trim(),
            occurredAtMillis = customTime ?: System.currentTimeMillis(), bankName = BankKnowledge.canonical(bank),
            timeConfirmed = timeConfirmed))
    }
    DisposableEffect(Unit) { onDispose { nativePicker?.dismiss() } }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "إغلاق") }
                }
                Text("اسم البنك", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { keyboard?.hide(); picker = "bank" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.AccountBalance, null); Spacer(Modifier.width(8.dp))
                    Text(bank.ifBlank { "اختر البنك" }, Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                }
                OutlinedTextField(amount, { amount = it.take(32); error = null }, label = { Text("المبلغ") },
                    placeholder = { Text("0.000") }, suffix = { Text("ر.ع") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                    isError = amount.isNotBlank() && !validAmount,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { reasonFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(amountFocus))
                Text("نوع العملية", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("expense" to "مصروف", "income" to "وارد", "refund" to "استرجاع", "internal" to "تحويل").forEach { (key, label) ->
                        FilterChip(selected = effect == key, onClick = { effect = key }, label = { Text(label) })
                    }
                }
                Text("الفئة", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { keyboard?.hide(); picker = "category" }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedCategory?.name ?: if (state.categories.isEmpty()) "أنشئ فئتك الأولى" else "بدون فئة", Modifier.weight(1f)); Icon(Icons.Default.ExpandMore, null)
                }
                OutlinedTextField(reason, { reason = it.take(500) }, label = { Text("ملاحظة — اختياري") }, maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth().focusRequester(reasonFocus))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = validAmount) {
                    Text("اعتماد العملية")
                }
                TextButton(onClick = { keyboard?.hide(); picker = "timestamp" }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Schedule, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(Instant.ofEpochMilli(customTime ?: System.currentTimeMillis()).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm")) + if (customTime != null && !timeConfirmed) " · الساعة غير مؤكدة" else "", fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Edit, "تعديل التاريخ والوقت", Modifier.size(14.dp))
                }
            }
        }
    }
    if (picker == "timestamp") AlertDialog(onDismissRequest = { picker = null }, title = { Text("تعديل التاريخ والوقت") }, text = {
        Column {
            TextButton(onClick = {
                val chosen = Instant.ofEpochMilli(customTime ?: System.currentTimeMillis()).atZone(zone)
                nativePicker = DatePickerDialog(context, { _, year, month, day ->
                    customTime = chosen.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day).toInstant().toEpochMilli(); timeConfirmed = false
                }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).apply { setOnDismissListener { nativePicker = null }; show() }
            }) { Text("التاريخ") }
            TextButton(onClick = {
                val chosen = Instant.ofEpochMilli(customTime ?: System.currentTimeMillis()).atZone(zone)
                nativePicker = TimePickerDialog(context, { _, hour, minute -> customTime = chosen.withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant().toEpochMilli(); timeConfirmed = true }, chosen.hour, chosen.minute, true).apply { setOnDismissListener { nativePicker = null }; show() }
            }) { Text("الساعة") }
            if (customTime != null) TextButton(onClick = { customTime = null; timeConfirmed = true; picker = null }) { Text("استخدام الوقت الحالي") }
        }
    }, confirmButton = { TextButton(onClick = { picker = null }) { Text("تم") } })
    picker?.takeUnless { it == "timestamp" }?.let { mode ->
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(if (mode == "bank") "اختر البنك" else "اختر الفئة") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == "category") {
                        FilterChip(selected = category == null, onClick = { category = null; picker = null }, label = { Text("بدون فئة") })
                        state.categories.forEach { item -> FilterChip(selected = category == item.id,
                            onClick = { category = item.id; picker = null }, label = { Text("${item.symbol} ${item.name}") }) }
                    } else {
                        val bankNames = (listOf(bank) + state.transactions.filterNot { it.isDemo }.map { it.bankName } + BankKnowledge.names)
                            .filter { BankKnowledge.key(it) != null }.map(BankKnowledge::canonical).distinct()
                        bankNames.forEach { name -> FilterChip(selected = bank == name,
                            onClick = { bank = name; picker = null; error = null }, label = { Text(name) }) }
                    }
                }
                if (mode == "bank") OutlinedTextField(customBank, { customBank = it.take(80) }, label = { Text("بنك آخر — اكتب الاسم") }, singleLine = true)
                else {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedTextField(categoryName, { categoryName = it.take(40) }, label = { Text("اسم فئة جديدة") }, singleLine = true)
                    Button(onClick = {
                        try { category = createCategory(categoryName); categoryName = ""; picker = null; error = null }
                        catch (failure: Exception) { error = failure.message?.take(150) ?: "تعذر إنشاء الفئة" }
                    }, enabled = categoryName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إنشاء واختيار") }
                }
            }
        }, confirmButton = {
            if (mode == "bank" && customBank.isNotBlank()) TextButton(onClick = {
                if (BankKnowledge.key(customBank) != null) { bank = BankKnowledge.canonical(customBank); picker = null; error = null }
            }) { Text("اختيار البنك") }
        }, dismissButton = { TextButton(onClick = { picker = null }) { Text("رجوع") } })
    }
}
