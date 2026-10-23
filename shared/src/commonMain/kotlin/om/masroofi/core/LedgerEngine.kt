package om.masroofi.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.abs

class LedgerEngine @Throws(Exception::class) constructor(snapshot: String = "") {
    var state: LedgerState = if (snapshot.isBlank()) LedgerState() else TransferMatcher.reconcile(ledgerJson.decodeFromString<LedgerState>(snapshot).also(::validate))
        private set
    fun stateJson(): String = ledgerJson.encodeToString(state)
    fun detectBank(text:String):String=BankKnowledge.detect(text,state.keywordRules)
    fun reportJson(nowMillis: Long): String = ledgerJson.encodeToString(BudgetEngine.report(state, nowMillis))
    @Throws(Exception::class) fun restore(snapshot: String) {
        val parsed = ledgerJson.decodeFromString<LedgerState>(snapshot)
        validate(parsed)
        state = TransferMatcher.reconcile(parsed)
    }
    private fun validate(value: LedgerState) {
        require(value.schemaVersion == 1) { "إصدار نسخة احتياطية غير مدعوم" }
        require(value.sequence > 0 && value.transactions.size <= 100_000)
        require(value.transactions.map { it.id }.distinct().size == value.transactions.size)
        require(value.preferences.weekStart in 1..7 && value.preferences.monthStartDay in 1..31 && value.preferences.reminderHour in 0..23)
        kotlinx.datetime.TimeZone.of(value.preferences.timeZone)
        require(value.categories.map { it.id }.distinct().size == value.categories.size)
        require(value.ownedBanks.size<=100 && value.ownedBanks.distinct().size==value.ownedBanks.size && value.ownedBanks.all{it.length in 1..80})
        require(value.keywordRules.size<=1000)
        value.keywordRules.forEach { rule -> BankKnowledge.validateKeyword(rule.keyword);require(rule.categoryId==null || value.categories.any{it.id==rule.categoryId});require(rule.bankName==null || rule.bankName.length in 1..80) }
        value.transactions.forEach { require(it.amountMinor == null || it.amountMinor in 0..9_000_000_000_000L); require(it.accountingBaisa == null || it.accountingBaisa in 0..9_000_000_000_000L);require(it.bankName.length in 1..80);require(it.occurredAtMillis in -2_240_611_200_000L..7_289_654_400_000L) }
        require(value.budgets.all { (it.weeklyBaisa ?: 1) in 1..9_000_000_000_000L && (it.monthlyBaisa ?: 1) in 1..9_000_000_000_000L })
    }
    /** Creates a transient preview. It does not retain the PDF or change the ledger. */
    @Throws(Exception::class) fun previewStatement(documentJson: String, documentId: String, receivedAtMillis: Long): String {
        require(documentJson.length <= 24_000_000) { "الكشف أكبر من الحد المسموح" }
        require(receivedAtMillis in 0..7_289_654_400_000L) { "وقت الاستيراد غير صالح" }
        val parsed = StatementImport.parse(ledgerJson.decodeFromString<StatementDocument>(documentJson), documentId)
        val preview = parsed.copy(rows = parsed.rows.map { row ->
            val replay = state.transactions.firstOrNull { it.sourceId == "statement:${parsed.documentId}:${row.id}" }
            val duplicate = replay ?: StatementImport.duplicate(row, state.transactions,parsed.bankName)
            if (duplicate == null) row else row.copy(duplicateOf = duplicate.id,
                warnings = row.warnings + if (replay != null) "سبق استيراد هذا السطر من الملف نفسه" else "قد تطابق هذه العملية عملية مسجلة؛ راجعها قبل احتسابها")
        })
        return ledgerJson.encodeToString(preview)
    }
    /** Correct one transient row without changing saved transactions. */
    @Throws(Exception::class) fun confirmStatementRow(previewJson: String, rowId: String, dateText: String,
            description: String, amountText: String, direction: String): String {
        require(previewJson.length<=16_000_000) { "معاينة الكشف أكبر من الحد المسموح" }
        val preview=ledgerJson.decodeFromString<StatementPreview>(previewJson)
        require(preview.rows.size<=5000 && preview.rows.map { it.id }.distinct().size==preview.rows.size) { "معاينة الكشف غير صالحة" }
        val row=preview.rows.singleOrNull { it.id==rowId } ?: error("السطر غير موجود")
        require(dateText.length<=40 && amountText.length<=40 && description.length<=2000) { "البيانات أطول من الحد المسموح" }
        val date=StatementImport.dateMillis(dateText) ?: error("أدخل تاريخًا صحيحًا مثل 13/10/2026")
        val amount=Money.input(amountText)
        require(amount!=null && amount in 1..StatementImport.MAX_AMOUNT) { "أدخل مبلغًا موجبًا صحيحًا حتى ثلاث خانات عشرية" }
        require(direction in listOf("in","out")) { "اختر مبلغًا داخلًا أو خارجًا" }
        require(description.isNotBlank()) { "أدخل وصف العملية" }
        var corrected=row.copy(dateText=StatementImport.normalized(dateText).trim(),occurredAtMillis=date,
            description=description.trim(),amountBaisa=amount,direction=direction,effect=StatementImport.effect(description,direction),
            categoryId=BankKnowledge.category(description,state.keywordRules) ?: StatementImport.category(description),canImport=true,userConfirmed=true,directionSource="user",duplicateOf=null,warnings=emptyList(),datePrecision=StatementImport.datePrecision(dateText))
        val replay=state.transactions.firstOrNull { it.sourceId=="statement:${preview.documentId}:${row.id}" }
        corrected=corrected.copy(duplicateOf=(replay?:StatementImport.duplicate(corrected,state.transactions,preview.bankName))?.id)
        val warnings=buildList {
            add("أكد المستخدم التاريخ والمبلغ والاتجاه")
            if(corrected.duplicateOf!=null) add("توجد عملية مشابهة؛ راجع استقلال العملية")
            if(corrected.effect=="unresolved") add("حدد بعد الإضافة هل التحويل مصروف أم بين حساباتك")
        }
        return ledgerJson.encodeToString(preview.copy(rows=preview.rows.map { if(it.id==rowId) corrected.copy(warnings=warnings) else it }))
    }

    @Throws(Exception::class) fun configureStatementBank(previewJson:String,bankName:String,ownedAccount:Boolean):String {
        require(previewJson.length<=16_000_000 && bankName.length<=80)
        val preview=ledgerJson.decodeFromString<StatementPreview>(previewJson)
        val bank=BankKnowledge.canonical(bankName)
        require(!ownedAccount || BankKnowledge.key(bank)!=null) { "حدد البنك أولًا لربط التحويلات بين حساباتك" }
        val rows=preview.rows.map { row ->
            val replay=state.transactions.firstOrNull{it.sourceId=="statement:${preview.documentId}:${row.id}"}
            row.copy(duplicateOf=(replay?:StatementImport.duplicate(row,state.transactions,bank))?.id)
        }
        return ledgerJson.encodeToString(preview.copy(bankName=bank,ownedAccount=ownedAccount,rows=rows))
    }

    @Throws(Exception::class) fun importStatement(previewJson: String, selectedRowIdsJson: String, receivedAtMillis: Long, currencyConfirmed: Boolean): String {
        require(previewJson.length <= 16_000_000 && selectedRowIdsJson.length <= 200_000) { "بيانات الاستيراد أكبر من الحد المسموح" }
        require(receivedAtMillis in 0..7_289_654_400_000L) { "وقت الاستيراد غير صالح" }
        val preview = ledgerJson.decodeFromString<StatementPreview>(previewJson)
        require(preview.bankName.length in 1..80 && (!preview.ownedAccount || BankKnowledge.key(preview.bankName)!=null)) { "بنك الكشف غير صالح" }
        require(Regex("[a-f0-9]{64}").matches(preview.documentId) && preview.rows.size <= 5000 && preview.skippedRows >= 0) { "معاينة الكشف غير صالحة" }
        require(preview.currency in listOf("OMR", "UNKNOWN")) { "لا يمكن استيراد كشف بعملة أجنبية أو عملات مختلطة" }
        require(currencyConfirmed) { "أكّد أن الكشف بالريال العماني قبل الإضافة" }
        require(preview.rows.map { it.id }.distinct().size == preview.rows.size) { "معرّفات سطور الكشف مكررة" }
        val selectedIds = ledgerJson.decodeFromString<List<String>>(selectedRowIdsJson)
        require(selectedIds.size <= 5000 && selectedIds.distinct().size == selectedIds.size) { "اختيار السطور غير صالح" }
        val byId = preview.rows.associateBy { it.id }
        val selected = selectedIds.map { byId[it] ?: error("السطر المحدد غير موجود في المعاينة") }
        // These checks are independent of canImport and duplicateOf supplied by a caller.
        selected.forEach { row ->
            require(row.page in 1..50 && Regex("p${row.page}-r[1-9][0-9]{0,5}").matches(row.id)) { "معرّف السطر غير صالح" }
            require(row.description.isNotBlank() && row.description.length <= 2000 && row.dateText.length <= 40 && row.warnings.size <= 30 && row.warnings.all { it.length <= 500 }) { "بيانات السطر غير صالحة" }
            require(row.amountBaisa != null && row.amountBaisa in 1..StatementImport.MAX_AMOUNT && row.direction in listOf("in", "out")) { "مبلغ السطر أو اتجاهه غير صالح" }
            val date = StatementImport.dateMillis(row.dateText)
            require(date != null && date == row.occurredAtMillis) { "تاريخ السطر غير صالح" }
            require(row.effect == StatementImport.effect(row.description, row.direction)) { "الأثر المالي للسطر غير صالح" }
            require(row.canImport) { "السطر غير محسوم؛ أدخله يدويًا بعد مراجعته" }
            require(!row.reviewRequired || row.userConfirmed) { "راجع بيانات العملية المشكوك فيها وأكّدها فرديًا قبل الإضافة" }
        }
        val originalTransactions = state.transactions.filterNot { it.isDemo }
        val replayIds = originalTransactions.map { it.sourceId }.toSet()
        val freshRows = selected.filterNot { "statement:${preview.documentId}:${it.id}" in replayIds }
        require(originalTransactions.size + freshRows.size <= 100_000) { "وصل السجل إلى الحد الأقصى للعمليات" }
        if (freshRows.isEmpty()) return ledgerJson.encodeToString(ImportResult(duplicates = selected.size))
        var sequence = state.sequence
        val transactions = freshRows.map { row ->
            val merchant = SmsParser.merchantName(row.description)
            val rule = state.rules.firstOrNull { it.merchant == merchant }
            val history = originalTransactions.filter { it.merchant != null && it.merchant == merchant && it.reviewed && it.categoryStatus == "confirmed" }.mapNotNull { it.categoryId }.distinct().singleOrNull()
            val learned=BankKnowledge.category(row.description,state.keywordRules)
            val category = (rule?.categoryId ?: learned ?: history ?: StatementImport.category(row.description))?.takeIf { categoryId -> state.categories.any { it.id == categoryId } }
            // Deliberately compare against the pre-import ledger only. Two identical rows
            // in one statement can be two real purchases and must retain independent identities.
            val duplicate = StatementImport.duplicate(row, originalTransactions,preview.bankName)
            val effect = StatementImport.effect(row.description, row.direction)
            val verified = effect != "unresolved"
            Transaction("statement-$receivedAtMillis-${sequence++}", row.amountBaisa, accountingBaisa = row.amountBaisa,
                direction = row.direction, effect = effect, type = StatementImport.type(row.description),
                occurredAtMillis = row.occurredAtMillis!!, receivedAtMillis = receivedAtMillis, datePrecision = StatementImport.datePrecision(row.dateText),
                counterparty = row.description, merchant = merchant, categoryId = category,
                categoryStatus = if (category == null) "pending" else if (rule != null || learned!=null || history != null) "confirmed" else "guessed",
                categoryConfidence = if (category == null) 0.0 else if (rule != null || learned!=null || history != null) 1.0 else 0.85,
                financialVerified = verified, reviewed = verified && duplicate == null && (rule != null || learned!=null || history != null),bankName=BankKnowledge.canonical(preview.bankName),
                source = "statement", sourceId = "statement:${preview.documentId}:${row.id}", duplicateOf = duplicate?.id,
                reference = StatementImport.referenceFor(row.description),
                warnings = buildList {
                    if (effect == "unresolved") add("حدد هل التحويل مصروف أم بين حساباتك")
                    if (duplicate != null) add("عملية مشابهة مسجلة؛ أكد استقلال هذه العملية قبل احتسابها")
                })
        }
        val owner=if(preview.ownedAccount) listOfNotNull(BankKnowledge.key(preview.bankName)) else emptyList()
        val next = TransferMatcher.reconcile(state.copy(sequence = sequence, transactions = transactions.asReversed() + originalTransactions,ownedBanks=(state.ownedBanks+owner).distinct()))
        validate(next)
        state = next
        return ledgerJson.encodeToString(ImportResult(ids = transactions.map { it.id }, duplicates = selected.size - freshRows.size))
    }
    @Throws(Exception::class) fun importMessage(text: String, receivedAtMillis: Long, source: String, sourceId: String): String = importBankMessage(text,receivedAtMillis,source,sourceId,"")
    @Throws(Exception::class) fun importBankMessage(text: String, receivedAtMillis: Long, source: String, sourceId: String, bankHint:String): String {
        require(bankHint.length<=80 && receivedAtMillis in 0..7_289_654_400_000L)
        val added = mutableListOf<String>(); val reasons = mutableListOf<String>()
        var ignored = 0; var duplicates = 0
        SmsParser.splitMessages(text).forEachIndexed { messageIndex, message ->
            val parsed = SmsParser.parse(message, receivedAtMillis, state.preferences.timeZone)
            val bank=if(bankHint.isNotBlank() && BankKnowledge.canonical(bankHint)!=BankKnowledge.UNKNOWN) BankKnowledge.canonical(bankHint) else BankKnowledge.detect(message,state.keywordRules)
            if (parsed.drafts.isEmpty()) { ignored++; parsed.ignoredReason?.let(reasons::add) }
            if (source != "demo" && parsed.drafts.isNotEmpty() && state.transactions.any { it.isDemo }) clearDemo()
            // This token only finds review candidates. It never proves identity
            // or removes a payment; authoritative replay uses sourceId above.
            val normalizedContent = SmsParser.normalize(message)
            val contentToken = "${normalizedContent.hashCode()}:${normalizedContent.length}"
            parsed.drafts.forEachIndexed { index, draft ->
                val external = "$source:$sourceId:$messageIndex:$index"
                if (state.transactions.any { it.sourceId == external }) { duplicates++; return@forEachIndexed }
                val rule = state.rules.find { it.merchant == draft.merchant }
                val history = state.transactions.filter { it.merchant != null && it.merchant == draft.merchant && it.reviewed && it.categoryStatus == "confirmed" }.mapNotNull { it.categoryId }.distinct().singleOrNull()
                val learned=BankKnowledge.category(message,state.keywordRules)
                val category = rule?.categoryId ?: learned ?: history ?: draft.category
                val effect = when {
                    draft.type == "refund" -> "refund"
                    draft.direction == "out" -> "expense"
                    draft.direction == "in" -> "income"
                    else -> "unresolved"
                }
                val warnings=draft.warnings.filterNot{it=="حدد هل التحويل مصروف أم بين حساباتك" && draft.direction!=null}
                val verified = draft.amount != null && draft.currency == "OMR" && draft.direction != null && warnings.isEmpty() && effect != "unresolved"
                val notificationGroup = if (source == "notification" && Regex("[a-f0-9]{64}:[0-9]+").matches(sourceId)) "notification:${sourceId.substringBefore(':')}:" else null
                val smsGroup = if (source == "sms" && Regex("[a-f0-9]{64}:[0-9]+:[a-f0-9]{64}").matches(sourceId)) "sms:${sourceId.substringBefore(':')}:" else null
                val candidate = state.transactions.firstOrNull { existing ->
                    if (existing.isDemo || source == "demo" || BankKnowledge.key(bank)!=null && BankKnowledge.key(existing.bankName)!=null && BankKnowledge.key(bank)!=BankKnowledge.key(existing.bankName)) false
                    else if (existing.contentToken == contentToken && existing.amountMinor == draft.amount && existing.type == draft.type &&
                        (existing.source != source || source in listOf("paste", "shortcuts", "notification"))) true
                    else if (notificationGroup != null && existing.source == "notification" && existing.sourceId.startsWith(notificationGroup) && existing.sourceId.endsWith(":$messageIndex:$index")) true
                    else if (smsGroup != null && existing.sourceId.startsWith(smsGroup) && draft.reference != null && existing.reference == draft.reference &&
                        existing.amountMinor == draft.amount && existing.currency == draft.currency && existing.direction == draft.direction && existing.type == draft.type &&
                        (draft.accountHint == null || existing.accountHint == null || draft.accountHint == existing.accountHint) && abs(existing.occurredAtMillis - draft.occurredAt) < 86_400_000) true
                    else
                    if (draft.reference != null && existing.reference != null && draft.reference != existing.reference) false
                    else if (draft.accountHint != null && existing.accountHint != null && draft.accountHint != existing.accountHint) false
                    else existing.amountMinor == draft.amount && existing.currency == draft.currency && existing.direction == draft.direction &&
                        (existing.type == draft.type || (existing.source != source && (existing.type == "other" || draft.type == "other"))) &&
                        (existing.merchant != null && existing.merchant == draft.merchant ||
                            existing.source != source && existing.source in listOf("sms", "notification") && source in listOf("sms", "notification") && (existing.merchant == null || draft.merchant == null)) &&
                        abs(existing.occurredAtMillis - draft.occurredAt) < 120_000 && (existing.source != source || source in listOf("paste", "shortcuts", "notification"))
                }
                val id = "txn-${receivedAtMillis}-${state.sequence}"
                val transaction = Transaction(id, draft.amount, draft.currency, if (draft.currency == "OMR") draft.amount else null,
                    draft.direction, effect, draft.type, draft.occurredAt, receivedAtMillis, draft.datePrecision,
                    draft.counterparty, draft.merchant, category,
                    if (category == null) "pending" else if (rule != null || learned!=null || history != null) "confirmed" else "guessed",
                    if (category == null) 0.0 else if (rule != null || learned!=null || history != null) 1.0 else 0.85,
                    verified, reviewed = candidate == null && verified && (rule != null || learned!=null || history != null),
                    balanceMinor = draft.balance, accountHint = draft.accountHint, reference = draft.reference,
                    source = source, sourceId = external, duplicateOf = candidate?.id, warnings = warnings,bankName=bank,
                    isDemo = source == "demo", contentToken = contentToken)
                state = state.copy(sequence = state.sequence + 1, transactions = listOf(transaction) + state.transactions,
                    pendingTransactionNotices = if (source in listOf("sms", "notification", "shortcuts")) state.pendingTransactionNotices + id else state.pendingTransactionNotices)
                added += id
            }
        }
        state=TransferMatcher.reconcile(state)
        return ledgerJson.encodeToString(ImportResult(added, ignored, duplicates, reasons.distinct()))
    }
    @Throws(Exception::class) fun editTransaction(commandJson: String): String {
        val requested = ledgerJson.decodeFromString<EditCommand>(commandJson)
        val stored=requested.id?.let { id -> state.transactions.firstOrNull{it.id==id} }
        val command=if(stored?.transferOrigin!=null && requested.effect=="internal" && requested.matchTransfers!=false)
            requested.copy(effect=stored.transferOrigin.effect) else requested
        require(command.amountBaisa >= 0 && command.amountBaisa <= 9_000_000_000_000L) { "المبلغ غير صالح" }
        require(command.occurredAtMillis in -2_240_611_200_000L..7_289_654_400_000L) { "التاريخ غير صالح" }
        require(command.direction in listOf("in", "out"))
        require(command.effect in listOf("expense", "income", "refund", "internal"))
        require(command.categoryId == null || state.categories.any { it.id == command.categoryId })
        require(command.effect != "expense" || command.direction == "out")
        require(command.effect !in listOf("income", "refund") || command.direction == "in")
        val old = command.id?.let { id -> state.transactions.find { it.id == id }?.let(TransferMatcher::base) ?: error("العملية غير موجودة") }
        val original = command.originalId?.let { originalId ->
            require(command.effect == "refund" && originalId != command.id)
            state.transactions.find { it.id == originalId && it.effect == "expense" && it.financialVerified && it.duplicateOf == null }
                ?: error("عملية الاسترجاع الأصلية غير صالحة")
        }
        if (original != null) {
            val previousRefunds = state.transactions.filter { it.originalId == original.id && it.id != command.id && it.effect == "refund" && it.financialVerified && it.duplicateOf == null }.sumOf { it.accountingBaisa ?: 0 }
            require(command.amountBaisa + previousRefunds <= (original.accountingBaisa ?: 0)) { "مجموع الاسترجاعات أكبر من المصروف الأصلي" }
        }
        val effectiveCategory = original?.categoryId ?: command.categoryId
        val bank=BankKnowledge.canonical(command.bankName ?: old?.bankName ?: BankKnowledge.UNKNOWN)
        require(command.bankName==null || command.bankName.length<=80) { "اسم البنك أطول من الحد المسموح" }
        val owned=updatedOwnedBanks(bank,command.ownsBank)
        var keywords=state.keywordRules
        command.learnKeyword?.takeIf{it.isNotBlank()}?.let { keywords=updatedKeywords(keywords,it,effectiveCategory,null) }
        command.learnBankAlias?.takeIf{it.isNotBlank()}?.let { keywords=updatedKeywords(keywords,it,null,bank) }
        val linkedRefunds = if (old == null) 0 else state.transactions.filter { it.originalId == old.id && it.effect == "refund" && it.financialVerified && it.duplicateOf == null }.sumOf { it.accountingBaisa ?: 0 }
        if (linkedRefunds > 0) {
            require(command.effect == "expense") { "العملية لها استرجاعات مرتبطة؛ راجعها قبل تغيير النوع" }
            require(command.amountBaisa >= linkedRefunds) { "المبلغ أقل من مجموع الاسترجاعات المرتبطة" }
        }
        val cashAllocated = if (old?.type == "atm") state.transactions.filter { it.originalId == old.id && it.source == "cash_split" }.sumOf { it.accountingBaisa ?: 0 } else 0
        require(cashAllocated == 0L || command.effect == "expense") { "أعد تقسيمات النقد قبل تغيير نوع السحب" }
        require(command.amountBaisa + cashAllocated <= 9_000_000_000_000L)
        val cashParent = if (old?.source == "cash_split") state.transactions.find { it.id == old.originalId && it.type == "atm" }
            ?: error("السحب الأصلي غير موجود") else null
        if (cashParent != null) {
            require(command.effect == "expense") { "تقسيم السحب مصروف؛ غيّر الفئة أو السبب فقط" }
            require(command.amountBaisa <= (cashParent.accountingBaisa ?: 0) + (old?.accountingBaisa ?: 0)) { "المبلغ أكبر من النقد المتاح في السحب" }
        }
        val id = old?.id ?: "manual-${command.occurredAtMillis}-${state.sequence}"
        val base = old ?: Transaction(id, command.amountBaisa, occurredAtMillis = command.occurredAtMillis,
            receivedAtMillis = command.occurredAtMillis, sourceId = id)
        val merchant = SmsParser.merchantName(command.counterparty)
        val edited = base.copy(amountMinor = if (base.currency == "OMR") command.amountBaisa + cashAllocated else base.amountMinor,
            accountingBaisa = command.amountBaisa, direction = command.direction, effect = command.effect,
            occurredAtMillis = command.occurredAtMillis,
            datePrecision = when(command.timeConfirmed) { true -> "second";false -> if(old?.datePrecision=="received") "received" else "day";null -> if (old != null && old.occurredAtMillis == command.occurredAtMillis) old.datePrecision else "second" },
            counterparty = command.counterparty?.takeIf { it.isNotBlank() }, merchant = merchant,
            categoryId = effectiveCategory, categoryStatus = if (effectiveCategory == null) "pending" else "confirmed",
            categoryConfidence = if (effectiveCategory == null) 0.0 else 1.0, financialVerified = true, reviewed = true,
            note = command.note, reasonSource = if (command.note.isBlank()) "unknown" else "user", duplicateOf = null,
            originalId = if (base.source == "cash_split") base.originalId else command.originalId, warnings = emptyList(),
            bankName=bank,matchTransfers=command.matchTransfers ?: old?.matchTransfers ?: true,transferOrigin=null,transferPairId=null)
        var rules = state.rules
        if (command.rememberMerchant && merchant != null && command.categoryId != null) rules = rules.filterNot { it.merchant == merchant } + MerchantRule(merchant, command.categoryId)
        state = state.copy(sequence = if (old == null) state.sequence + 1 else state.sequence,
            transactions = if (old == null) listOf(edited) + state.transactions else state.transactions.map {
                if (it.id == id) edited
                else if (it.id == cashParent?.id) it.copy(accountingBaisa = (it.accountingBaisa ?: 0) + (old.accountingBaisa ?: 0) - command.amountBaisa)
                else it
            }, rules = rules,ownedBanks=owned,keywordRules=keywords)
        alignRefundCategories()
        state=TransferMatcher.reconcile(state)
        return id
    }
    @Throws(Exception::class) fun assignCategory(id: String, categoryId: String, remember: Boolean) {
        val txn = state.transactions.find { it.id == id } ?: return
        require(state.categories.any { it.id == categoryId })
        state = state.copy(transactions = state.transactions.map { if (it.id == id) it.copy(categoryId = categoryId, categoryStatus = "confirmed", categoryConfidence = 1.0, reviewed = it.financialVerified && it.duplicateOf == null) else it },
            rules = if (remember && txn.merchant != null) state.rules.filterNot { it.merchant == txn.merchant } + MerchantRule(txn.merchant, categoryId) else state.rules)
        alignRefundCategories()
    }
    @Throws(Exception::class) fun deleteTransaction(id: String) {
        val removed = state.transactions.find { it.id == id } ?: return
        require(removed.type != "atm" || state.transactions.none { it.originalId == id && it.source == "cash_split" }) { "احذف تقسيمات النقد أولًا؛ تعود قيمتها إلى السحب" }
        state = state.copy(transactions = state.transactions.filterNot { it.id == id }.map { txn ->
            if (removed.source == "cash_split" && txn.id == removed.originalId) txn.copy(accountingBaisa = (txn.accountingBaisa ?: 0) + (removed.accountingBaisa ?: 0))
            else
            if (txn.duplicateOf == id) txn.copy(duplicateOf = null, financialVerified = false, reviewed = false, warnings = txn.warnings + "حُذفت العملية المشابهة؛ أكد هذه العملية قبل احتسابها")
            else if (txn.originalId == id) txn.copy(originalId = null) else txn
        }, pendingTransactionNotices = state.pendingTransactionNotices.filterNot { it == id })
        state=TransferMatcher.reconcile(state)
    }
    private fun updatedOwnedBanks(bankName:String,owned:Boolean?):List<String> {
        if(owned==null) return state.ownedBanks
        val key=BankKnowledge.key(bankName)
        require(key!=null || !owned) { "حدد اسم البنك أولًا" }
        val result=if(owned) (state.ownedBanks+key!!).distinct() else state.ownedBanks.filterNot{it==key}
        require(result.size<=100)
        return result
    }
    @Throws(Exception::class) fun setOwnedBank(bankName:String,owned:Boolean) {
        require(bankName.length<=80)
        state=TransferMatcher.reconcile(state.copy(ownedBanks=updatedOwnedBanks(bankName,owned)))
    }
    private fun updatedKeywords(rules:List<KeywordRule>,keyword:String,categoryId:String?,bankName:String?):List<KeywordRule> {
        BankKnowledge.validateKeyword(keyword)
        require(categoryId!=null || bankName!=null) { "اختر فئة قبل حفظ كلمة التصنيف" }
        require(categoryId==null || state.categories.any{it.id==categoryId})
        require(bankName==null || BankKnowledge.key(bankName)!=null) { "حدد البنك قبل تعليم الاختصار" }
        val key=BankKnowledge.normalize(keyword)
        val result=rules.filterNot { BankKnowledge.normalize(it.keyword)==key && (if(bankName!=null) it.bankName!=null else it.categoryId!=null) }+
            KeywordRule(keyword.trim(),categoryId,bankName?.let(BankKnowledge::canonical))
        require(result.size<=1000)
        return result
    }
    @Throws(Exception::class) fun learnKeyword(keyword:String,categoryId:String,bankName:String) {
        state=state.copy(keywordRules=updatedKeywords(state.keywordRules,keyword,categoryId.takeIf{it.isNotBlank()},bankName.takeIf{it.isNotBlank()}))
    }
    fun removeKeyword(keyword:String) { state=state.copy(keywordRules=state.keywordRules.filterNot { BankKnowledge.normalize(it.keyword)==BankKnowledge.normalize(keyword) }) }
    @Throws(Exception::class) fun unlinkTransfer(id:String) {
        val txn=state.transactions.firstOrNull{it.id==id} ?: error("العملية غير موجودة")
        val ids=setOfNotNull(txn.id,txn.transferPairId)
        state=TransferMatcher.reconcile(state.copy(transactions=state.transactions.map { if(it.id in ids) TransferMatcher.base(it).copy(matchTransfers=false) else it }))
    }
    @Throws(Exception::class) fun splitCash(id: String, amountBaisa: Long, categoryId: String, note: String): String {
        val cash = state.transactions.find { it.id == id } ?: error("السحب غير موجود")
        require(cash.type == "atm" && cash.effect == "expense" && cash.financialVerified && cash.currency == "OMR" && cash.duplicateOf == null)
        require(amountBaisa > 0 && amountBaisa <= (cash.accountingBaisa ?: 0))
        val refunds = state.transactions.filter { it.originalId == id && it.effect == "refund" && it.financialVerified && it.duplicateOf == null }.sumOf { it.accountingBaisa ?: 0 }
        require((cash.accountingBaisa ?: 0) - amountBaisa >= refunds) { "هذا الجزء مرتبط باسترجاع؛ راجع الاسترجاع قبل تقسيمه" }
        require(state.categories.any { it.id == categoryId } && categoryId != "cash")
        val childId = "cash-${cash.receivedAtMillis}-${state.sequence}"
        val portion = cash.copy(id = childId, amountMinor = amountBaisa, accountingBaisa = amountBaisa,
            type = "cash_expense", categoryId = categoryId, categoryStatus = "confirmed", categoryConfidence = 1.0,
            reviewed = true, counterparty = "مصروف نقدي", merchant = null, balanceMinor = null,
            source = "cash_split", sourceId = childId, originalId = id, note = note,
            reasonSource = if (note.isBlank()) "unknown" else "user")
        state = state.copy(sequence = state.sequence + 1, transactions = listOf(portion) + state.transactions.map {
            if (it.id == id) it.copy(accountingBaisa = (it.accountingBaisa ?: 0) - amountBaisa, categoryId = "cash", categoryStatus = "confirmed", reviewed = true) else it
        })
        alignRefundCategories()
        return childId
    }
    fun acknowledgeTransactionNotice(id: String) { state = state.copy(pendingTransactionNotices = state.pendingTransactionNotices.filterNot { it == id }) }
    fun acknowledgeBudgetAlert(key: String) { state = state.copy(pendingAlerts = state.pendingAlerts.filterNot { it.key == key }, deliveredAlerts = (state.deliveredAlerts + key).distinct().takeLast(2000)) }
    fun clearDemo() { state = state.copy(transactions = state.transactions.filterNot { it.isDemo }) }
    fun clearAll() { state = LedgerState() }
    @Throws(Exception::class) fun setBudget(categoryId: String, weeklyBaisa: Long, monthlyBaisa: Long) {
        require(state.categories.any { it.id == categoryId }); require(weeklyBaisa in 0..9_000_000_000_000L && monthlyBaisa in 0..9_000_000_000_000L)
        val budget = Budget(categoryId, weeklyBaisa.takeIf { it > 0 }, monthlyBaisa.takeIf { it > 0 })
        state = state.copy(budgets = state.budgets.filterNot { it.categoryId == categoryId } + budget)
    }
    @Throws(Exception::class) fun addCategory(name: String, symbol: String): String {
        require(name.isNotBlank() && name.length <= 40)
        val id = "custom-${state.sequence}"
        state = state.copy(sequence = state.sequence + 1, categories = state.categories + Category(id, name.trim(), symbol.take(8)))
        return id
    }
    fun removeRule(merchant: String) { state = state.copy(rules = state.rules.filterNot { it.merchant == merchant }) }
    fun applyRuleToHistory(merchant: String) {
        val rule = state.rules.find { it.merchant == merchant } ?: return
        state = state.copy(transactions = state.transactions.map { if (it.merchant == merchant) it.copy(categoryId = rule.categoryId, categoryStatus = "confirmed", categoryConfidence = 1.0) else it })
        alignRefundCategories()
    }
    private fun alignRefundCategories() {
        val originals = state.transactions.associateBy { it.id }
        state = state.copy(transactions = state.transactions.map { txn ->
            val original = if (txn.effect == "refund") txn.originalId?.let { originals[it] } else null
            if (original == null) txn else txn.copy(categoryId = original.categoryId,
                categoryStatus = if (original.categoryId == null) "pending" else "confirmed", categoryConfidence = original.categoryConfidence)
        })
    }
    @Throws(Exception::class) fun setPreferences(json: String) {
        val value = ledgerJson.decodeFromString<Preferences>(json)
        require(value.weekStart in 1..7 && value.monthStartDay in 1..31 && value.reminderHour in 0..23)
        kotlinx.datetime.TimeZone.of(value.timeZone)
        state = state.copy(preferences = value)
    }
    fun takeBudgetAlerts(nowMillis: Long): String {
        val report = BudgetEngine.report(state, nowMillis)
        val alerts = mutableListOf<BudgetAlert>()
        report.categories.forEach { line ->
            listOf(Triple("week", report.weekStartMillis, line.weeklyLimit to line.weeklySpent), Triple("month", report.monthStartMillis, line.monthlyLimit to line.monthlySpent)).forEach { (period, start, pair) ->
                val (limit, spent) = pair
                if (limit != null && limit > 0) listOf(80, 100).forEach { threshold ->
                    val key = "${line.categoryId}:$period:$start:$threshold"
                    if (spent >= (limit * threshold + 99) / 100 && key !in state.deliveredAlerts && state.pendingAlerts.none { it.key == key }) {
                        alerts += BudgetAlert(key, "${line.name}: ${if (threshold == 100) "بلغت الحد" else "اقتربت من الحد"}", "${if (period == "week") "هذا الأسبوع" else "هذه الدورة"}: ${Money.format(spent)} من ${Money.format(limit)} ر.ع")
                    }
                }
            }
        }
        state = state.copy(pendingAlerts = state.pendingAlerts + alerts)
        return ledgerJson.encodeToString(state.pendingAlerts)
    }
    @Throws(Exception::class) fun seedDemo(nowMillis: Long) {
        if (state.transactions.any { it.isDemo }) return
        listOf(
            "تم خصم 3.000 ر.ع من حسابك للحساب باسم 5470 OMAN OIL ALKHOUD. رصيدك الحالي 7.030 ر.ع",
            "تم خصم 1.000 OMR من حسابك باستخدام بطاقة الخصم المباشر في 668633-Omantel. رصيدك الحالي 0.029 OMR",
            "تم إيداع ر.ع 2.000 في حسابك من PERSON DEMO. الرصيد 10.559 ر.ع",
            "لقد قمت بإرسال OMR 9.650 إلى شخص من حسابك باستخدام المحفظة الإلكترونية. رصيدك الحالي OMR 22.000"
        ).forEachIndexed { index, message -> importMessage(message, nowMillis - index * 3600_000L, "demo", "example-$index") }
    }
}
