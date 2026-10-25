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
@Composable internal fun QuickEntrySheet(state: LedgerState, dismiss: () -> Unit, save: (EditCommand) -> Unit) {
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
    var details by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var customBank by remember { mutableStateOf("") }
    var customTime by remember { mutableStateOf<Long?>(null) }
    var timeConfirmed by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val value = Money.input(amount)
    val validAmount = value != null && value > 0
    val selectedCategory = state.categories.find { it.id == category }
    val quickCategories = listOf("restaurants", "groceries", "fuel", "telecom", "transport", "shopping")
        .mapNotNull { id -> state.categories.find { it.id == id } }
    val title = when (effect) { "income" -> "تسجيل مبلغ وارد"; "refund" -> "تسجيل استرجاع"; "internal" -> "تسجيل تحويل"; else -> "تسجيل مصروف" }
    fun submit() {
        if (!validAmount) { error = "أدخل مبلغًا أكبر من صفر، حتى ثلاث خانات عشرية"; amountFocus.requestFocus(); return }
        if (BankKnowledge.key(bank) == null) { error = "اختر البنك المستخدم"; keyboard?.hide(); picker = "bank"; return }
        // An automatic time is captured at Save, rather than when the sheet opened.
        save(EditCommand(amountBaisa = value!!, direction = if (effect in listOf("income", "refund")) "in" else "out",
            effect = effect, categoryId = category, counterparty = reason.trim().takeIf { it.isNotEmpty() },
            occurredAtMillis = customTime ?: System.currentTimeMillis(), bankName = BankKnowledge.canonical(bank),
            timeConfirmed = timeConfirmed))
    }
    LaunchedEffect(Unit) { amountFocus.requestFocus(); keyboard?.show() }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "إغلاق") }
                }
                OutlinedTextField(amount, { amount = it; error = null }, label = { Text("المبلغ") },
                    placeholder = { Text("0.000") }, suffix = { Text("ر.ع") }, singleLine = true,
                    textStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
                    isError = amount.isNotBlank() && !validAmount,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { reasonFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(amountFocus))
                OutlinedTextField(reason, { reason = it.take(160) }, label = { Text(if (effect == "expense") "على ماذا صرفت؟ — اختياري" else "السبب أو الجهة — اختياري") },
                    placeholder = { Text("قهوة، بقالة، بنزين…") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(reasonFocus))
                Text("الفئة — اختياري", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickCategories.forEach { item ->
                        FilterChip(selected = category == item.id, onClick = { category = if (category == item.id) null else item.id },
                            label = { Text("${item.symbol} ${item.name}") })
                    }
                    FilterChip(selected = selectedCategory != null && selectedCategory !in quickCategories,
                        onClick = { keyboard?.hide(); picker = "category" },
                        label = { Text(if (selectedCategory != null && selectedCategory !in quickCategories) "${selectedCategory.symbol} ${selectedCategory.name}" else "المزيد") })
                }
                OutlinedButton(onClick = { keyboard?.hide(); picker = "bank" }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.AccountBalance, null)
                    Spacer(Modifier.width(8.dp))
                    Text(bank.ifBlank { "اختر البنك المستخدم" }, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                }
                Text(customTime?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + if (timeConfirmed) " · " + Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) else " · دون ساعة مؤكدة" }
                    ?: "اليوم · وقت التسجيل تلقائيًا", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { details = !details }) {
                    Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    Text(if (details) "إخفاء التفاصيل" else "تفاصيل إضافية")
                }
                if (details) {
                    Text("نوع العملية", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("expense" to "مصروف", "income" to "وارد", "refund" to "استرجاع", "internal" to "تحويل بين حساباتي").forEach { (key, label) ->
                            FilterChip(selected = effect == key, onClick = { effect = key }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val chosen = Instant.ofEpochMilli(customTime ?: System.currentTimeMillis()).atZone(zone)
                            DatePickerDialog(context, { _, year, month, day ->
                                customTime = chosen.withDayOfMonth(1).withYear(year).withMonth(month + 1).withDayOfMonth(day).toInstant().toEpochMilli()
                                timeConfirmed = false
                            }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).show()
                        }, modifier = Modifier.weight(1f)) { Text("تغيير التاريخ") }
                        OutlinedButton(onClick = {
                            val chosen = Instant.ofEpochMilli(customTime ?: System.currentTimeMillis()).atZone(zone)
                            TimePickerDialog(context, { _, hour, minute ->
                                customTime = chosen.withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant().toEpochMilli()
                                timeConfirmed = true
                            }, chosen.hour, chosen.minute, true).show()
                        }, modifier = Modifier.weight(1f)) { Text("تحديد الساعة") }
                    }
                    if (customTime != null) TextButton(onClick = { customTime = null; timeConfirmed = true }) { Text("العودة إلى الآن") }
                    Text("إذا كان المصروف من سحب نقدي مسجل، وزّعه من عملية السحب لتجنب احتسابه مرتين.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { submit() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = validAmount) {
                    Text(when (effect) { "income" -> "حفظ المبلغ الوارد"; "refund" -> "حفظ الاسترجاع"; "internal" -> "حفظ التحويل"; else -> "حفظ المصروف" })
                }
            }
        }
    }
    picker?.let { mode ->
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
            }
        }, confirmButton = {
            if (mode == "bank" && customBank.isNotBlank()) TextButton(onClick = {
                if (BankKnowledge.key(customBank) != null) { bank = BankKnowledge.canonical(customBank); picker = null; error = null }
            }) { Text("اختيار البنك") }
        }, dismissButton = { TextButton(onClick = { picker = null }) { Text("رجوع") } })
    }
}
