package om.masroofi.core

import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Extraction geometry uses page points with its origin at the top left. PDF text is transient. */
@Serializable data class StatementWord(val text: String, val x: Double, val y: Double, val width: Double, val height: Double, val confidence: Double = 1.0)
@Serializable data class StatementPage(val width: Double, val height: Double, val words: List<StatementWord>, val isOcr: Boolean = false, val ocrDisagreement: Boolean = false)
@Serializable data class StatementDocument(val pages: List<StatementPage>, val version: Int = 1)
@Serializable data class StatementRow(
    val id: String, val page: Int, val dateText: String, val occurredAtMillis: Long?, val description: String,
    val amountBaisa: Long?, val direction: String?, val effect: String, val categoryId: String?,
    val duplicateOf: String?, val warnings: List<String>, val canImport: Boolean, val reviewRequired: Boolean = false,
    val userConfirmed: Boolean = false, val balanceBaisa: Long? = null, val directionSource: String? = null,
    val datePrecision: String = "day"
)
@Serializable data class StatementPreview(
    val documentId: String, val rows: List<StatementRow>, val currency: String,
    val skippedRows: Int, val warnings: List<String>, val bankName: String = BankKnowledge.UNKNOWN, val ownedAccount: Boolean = false
)

/** A conservative table reader: balances never stand in for transaction amounts. */
internal object StatementImport {
    const val MAX_AMOUNT = 9_000_000_000_000L
    val muscat = TimeZone.of("Asia/Muscat")
    private val ignoreCase = setOf(RegexOption.IGNORE_CASE)
    private val isoDate = Regex("(?<![0-9])([0-9]{4})[-/.]([0-9]{1,2})[-/.]([0-9]{1,2})(?![0-9])")
    private val numericDate = Regex("(?<![0-9])([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{4})(?![0-9])")
    private val namedDate = Regex("(?<![0-9])([0-9]{1,2})[-/ ]([A-Za-z]{3,9})[-/ ]([0-9]{4})(?![0-9])")
    private val amountToken = Regex("^[+−-]?[0-9]+(?:[.,][0-9]+)*(?:[+−-])?$")
    private val currencyToken = Regex("(?<![A-Za-z])(?:OMR|R\\.?\\s*O\\.?|USD|AED|SAR|EUR|GBP|INR|QAR|KWD|BHD|JPY|CHF|CAD|AUD)(?![A-Za-z])|ر\\.\\s*ع\\.?|ريال\\s+عماني", ignoreCase)
    private enum class Role { DATE, TIME, DESCRIPTION, DEBIT, CREDIT, AMOUNT, DIRECTION, BALANCE, IGNORE }
    private data class Column(val role: Role, val center: Double, val start: Double, val end: Double)
    private data class Header(val columns: List<Column>, val width: Double, val assisted: Boolean = false) {
        fun role(word: StatementWord): Role {
            val center = word.x + word.width / 2
            val nearest = columns.minBy { abs(it.center - center) }
            val description = columns.firstOrNull { it.role == Role.DESCRIPTION }
            if (description != null && nearest.role in listOf(Role.DEBIT, Role.CREDIT, Role.AMOUNT, Role.DIRECTION, Role.BALANCE)) {
                val firstNumeric = columns.filter { it.role in listOf(Role.DEBIT, Role.CREDIT, Role.AMOUNT, Role.DIRECTION, Role.BALANCE) && (it.center - description.center) * (center - description.center) > 0 }.minByOrNull { abs(it.center - description.center) }
                if (firstNumeric != null) {
                    val boundary = if (firstNumeric.center > description.center) firstNumeric.start else firstNumeric.end
                    // Description cells are often much wider than their title. A midpoint
                    // between title centers must not turn the narration into a debit cell.
                    if (center in minOf(description.center, boundary)..maxOf(description.center, boundary)) return Role.DESCRIPTION
                }
            }
            return nearest.role
        }
    }
    private data class Line(val words: List<StatementWord>, val y: Double, val height: Double) {
        val text: String get() = words.sortedBy { it.x }.joinToString(" ") { it.text }
    }
    private data class Pending(val page: Int, val line: Int, val header: Header?, val words: MutableList<StatementWord>, var lastY: Double, val isOcr: Boolean = false, val ocrDisagreement: Boolean = false)
    private data class Amount(val value: Long?, val sign: Int?, val direction: String?, val valid: Boolean)
    private data class HeaderCell(val role: Role, val words: MutableList<StatementWord>) {
        val start: Double get() = words.minOf { it.x }
        val end: Double get() = words.maxOf { it.x + it.width }
        val center: Double get() = (start + end) / 2
    }
    private data class HeaderBand(val header: Header, val lineCount: Int)

    fun normalized(value: String): String = SmsParser.normalize(value).replace(Regex("[\u200e\u200f\u202a-\u202e\u2066-\u2069]"), "")
    private fun label(value: String): String = normalized(value).lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه').replace(Regex("[\u064b-\u065f\u0670ـ]"), "").trim()

    fun dateMillis(text: String): Long? {
        val input = normalized(text).replace(Regex("\\s*([/.-])\\s*"), "$1").trim()
        val time=Regex("(?:[ T]+)([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?:\\s*(AM|PM))?$",ignoreCase).find(input)
        val clean=if(time!=null) input.substring(0,time.range.first).trim() else input
        val candidates = (isoDate.findAll(clean).map { it.value } + numericDate.findAll(clean).map { it.value } + namedDate.findAll(clean).map { it.value }).distinct().toList()
        if (candidates.size != 1 || candidates.single() != clean) return null
        val iso = isoDate.matchEntire(clean)
        val numeric = numericDate.matchEntire(clean)
        val named = namedDate.matchEntire(clean)
        val match = iso ?: numeric ?: named ?: return null
        return try {
            val year = match.groupValues[if (iso != null) 1 else 3].toInt()
            val month = if (named != null) listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(match.groupValues[2].take(3).lowercase()) + 1 else match.groupValues[2].toInt()
            val day = match.groupValues[if (iso != null) 3 else 1].toInt()
            require(year in 1900..2200)
            if(time==null) LocalDate(year, month, day).atStartOfDayIn(muscat).toEpochMilliseconds()
            else {
                var hour=time.groupValues[1].toInt()
                val period=time.groupValues[4].uppercase()
                if(period.isNotEmpty()) {require(hour in 1..12);hour=hour%12+if(period=="PM")12 else 0}
                LocalDateTime(year,month,day,hour,time.groupValues[2].toInt(),time.groupValues[3].ifEmpty{"0"}.toInt()).toInstant(muscat).toEpochMilliseconds()
            }
        } catch (_: IllegalArgumentException) { null }
    }
    fun datePrecision(text:String)=if(Regex("[0-9]{1,2}:[0-9]{2}:[0-9]{2}(?:\\s*(?:AM|PM))?$",ignoreCase).containsMatchIn(normalized(text).trim())) "second"
        else if(Regex("[0-9]{1,2}:[0-9]{2}(?:\\s*(?:AM|PM))?$",ignoreCase).containsMatchIn(normalized(text).trim())) "minute" else "day"
    private fun dateToken(text: String): String? {
        val clean = normalized(text).replace(Regex("\\s*([/.-])\\s*"), "$1")
        return isoDate.find(clean)?.value ?: namedDate.find(clean)?.value ?: numericDate.find(clean)?.value
    }

    private fun currency(document: StatementDocument): String {
        val currencies = document.pages.flatMap { it.words }.flatMap { word -> currencyToken.findAll(normalized(word.text)).map { match ->
            val value = match.value.uppercase().replace(Regex("[.\\s]"), "")
            if (value == "RO" || Regex("[ء-ي]").containsMatchIn(value)) "OMR" else value
        }.toList() }.distinct()
        return when (currencies.size) { 0 -> "UNKNOWN"; 1 -> currencies.single(); else -> "MIXED" }
    }
    private fun lines(page: StatementPage): List<Line> {
        val rows = mutableListOf<MutableList<StatementWord>>()
        page.words.filter { it.text.isNotBlank() }.sortedWith(compareBy<StatementWord> { it.y }.thenBy { it.x }).forEach { word ->
            val previous = rows.lastOrNull()
            if (previous != null && abs(previous.map { it.y }.average() - word.y) <= maxOf(2.5, minOf(word.height, previous.map { it.height }.average()) * 0.45)) previous += word
            else rows += mutableListOf(word)
        }
        return rows.map { words -> Line(words.sortedBy { it.x }, words.map { it.y }.average(), words.maxOf { it.height }) }
    }
    private fun ruleRole(text: String): Role? {
        val t = label(text).replace(Regex("[:()./_-]"), " ").replace(Regex("\\s+"), " ").trim()
        return when {
            Regex("\\btime\\b|وقت|ساعه").containsMatchIn(t) && !Regex("\\b(?:date|dated)\\b|تاريخ").containsMatchIn(t) -> Role.TIME
            Regex("\\b(?:balance|bal)\\b|رصيد").containsMatchIn(t) -> Role.BALANCE
            Regex("\\b(?:debit|dr)\\b|مدين").containsMatchIn(t) && Regex("\\b(?:credit|cr)\\b|دائن").containsMatchIn(t) -> Role.DIRECTION
            Regex("\\b(?:debit|debits|withdrawal|withdrawals|withdrawn|dr)\\b|مدين|مسحوب|سحب|خصم").containsMatchIn(t) -> Role.DEBIT
            Regex("\\b(?:credit|credits|deposit|deposits|cr)\\b|دائن|ايداع|مضاف").containsMatchIn(t) -> Role.CREDIT
            Regex("\\b(?:date|dated)\\b|تاريخ").containsMatchIn(t) -> Role.DATE
            Regex("\\b(?:description|details|particulars|narration|remarks|merchant)\\b|وصف|تفاصيل|بيان|حركه").containsMatchIn(t) -> Role.DESCRIPTION
            Regex("\\b(?:amount|value)\\b|مبلغ|القيمه").containsMatchIn(t) -> Role.AMOUNT
            Regex("\\b(?:direction|type|sign|drcr)\\b|اتجاه|النوع").containsMatchIn(t) -> Role.DIRECTION
            Regex("\\b(?:reference|ref|cheque|check|number|serial)\\b|مرجع|تسلسلي|شيك").containsMatchIn(t) -> Role.IGNORE
            else -> null
        }
    }
    private fun role(text: String): Role? = ruleRole(text) ?: if (dateModifier(text) != null) null else LocalColumnModel.role(label(text))?.let { Role.valueOf(it) }
    private fun dateModifier(text: String): String? {
        val t = label(text)
        val posting = Regex("\\b(?:transaction|posting|posted|booking|booked)\\b|العمليه|المعامله|الحركه|القيد").containsMatchIn(t)
        val value = Regex("\\bvalue\\b|القيمه|^قيمه$").containsMatchIn(t)
        return when { posting && value -> "conflict"; posting -> "posting"; value -> "value"; else -> null }
    }
    private fun sameHeaderCell(a: StatementWord, b: StatementWord, role: Role): Boolean {
        val yDifference = abs(a.y - b.y)
        val sameBaseline = yDifference <= maxOf(2.5, minOf(a.height, b.height) * 0.45)
        val overlap = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
        if (sameBaseline) {
            // Two adjacent Date titles may identify separate posting/value columns.
            if (role == Role.DATE) return false
            val gap = maxOf(a.x, b.x) - minOf(a.x + a.width, b.x + b.width)
            return gap in 0.0..10.0
        }
        // Only vertically aligned translations/subtitles share a column. Proximity
        // alone must never merge two adjacent dates or two debit/credit columns.
        return overlap >= minOf(a.width, b.width) * 0.35 &&
            abs(a.x + a.width / 2 - b.x - b.width / 2) <= maxOf(8.0, minOf(a.width, b.width) * 0.65)
    }
    private fun modifierDistance(word: StatementWord, cell: HeaderCell): Double? {
        return cell.words.mapNotNull { title ->
            val horizontalGap = maxOf(0.0, maxOf(word.x, title.x) - minOf(word.x + word.width, title.x + title.width))
            val verticalGap = maxOf(0.0, maxOf(word.y, title.y) - minOf(word.y + word.height, title.y + title.height))
            if (horizontalGap > 48 || verticalGap > 24) null
            else horizontalGap + abs(word.x + word.width / 2 - title.x - title.width / 2) * 0.15 + verticalGap * 2
        }.minOrNull()
    }
    private fun header(band: List<Line>, width: Double): Header? {
        // OCR emits individual words. Join short, adjacent unknown title fragments
        // such as “Money / Out” before using the learned header classifier.
        val words = band.flatMap { line ->
            val sorted=line.words.sortedBy { it.x }
            val joined=mutableListOf<StatementWord>()
            var index=0
            while(index<sorted.size) {
                val first=sorted[index]
                var match: StatementWord?=null
                var consumed=1
                if(role(first.text)==null) {
                    for(length in 2..minOf(4,sorted.size-index)) {
                        val group=sorted.subList(index,index+length)
                        if(group.zipWithNext().any { (a,b) -> b.x-a.x-a.width !in -1.0..12.0 } || group.drop(1).any { ruleRole(it.text)!=null }) break
                        val text=group.joinToString(" ") { it.text }
                        if(role(text)!=null) {
                            match=first.copy(text=text,width=group.last().x+group.last().width-first.x,confidence=group.minOf { it.confidence })
                            consumed=length
                        }
                    }
                }
                joined+=match?:first; index+=consumed
            }
            joined
        }
        if (band.any { dateToken(it.text) != null || metadata(it.text) }) return null
        val cells = mutableListOf<HeaderCell>()
        words.sortedWith(compareBy<StatementWord> { it.y }.thenBy { it.x }).forEach { word ->
            val role = role(word.text) ?: return@forEach
            val cell = cells.filter { it.role == role && it.words.all { existing -> sameHeaderCell(word, existing, role) } }
                .minByOrNull { abs(it.center - word.x - word.width / 2) }
            if (cell == null) cells += HeaderCell(role, mutableListOf(word)) else cell.words += word
        }
        val dates = cells.filter { it.role == Role.DATE }
        if (dates.isEmpty()) return null
        val modifiers = dates.associateWith { cell -> cell.words.mapNotNull { dateModifier(it.text) }.toMutableSet() }
        val assignedModifiers = mutableSetOf<StatementWord>()
        words.filter { word ->
            val role = role(word.text)
            role != Role.DATE && (role == null || role == Role.AMOUNT && label(word.text) in listOf("value", "القيمه", "قيمه")) && dateModifier(word.text) != null
        }.forEach { word ->
            val nearest = dates.mapNotNull { date -> modifierDistance(word, date)?.let { date to it } }.sortedBy { it.second }
            val descriptionDistance = cells.filter { it.role == Role.DESCRIPTION }.mapNotNull { modifierDistance(word, it) }.minOrNull()
            // A label equally close to two dates proves neither column's meaning.
            // “Transaction / Details” stacked in the description column is not a date modifier.
            if (nearest.isNotEmpty() && (descriptionDistance == null || descriptionDistance > nearest[0].second) &&
                (nearest.size == 1 || nearest[1].second - nearest[0].second > 1.0)) {
                modifiers.getValue(nearest[0].first) += dateModifier(word.text)!!
                assignedModifiers += word
            }
        }
        if (modifiers.values.any { "conflict" in it || "posting" in it && "value" in it }) return null
        val priorities = dates.associateWith { date -> when { "posting" in modifiers.getValue(date) -> 2; "value" in modifiers.getValue(date) -> 0; else -> 1 } }
        val preferred = priorities.maxOf { it.value }
        val selectedDates = dates.filter { priorities.getValue(it) == preferred }
        if (selectedDates.size != 1) return null
        val selectedDate = selectedDates.single()
        val raw = cells.mapNotNull { cell ->
            if (cell.words.all { it in assignedModifiers }) null
            else Column(if (cell.role == Role.DATE && cell !== selectedDate) Role.IGNORE else cell.role, cell.center, cell.start, cell.end)
        }
        // An Amount subtitle belongs to its vertically aligned Debit/Credit cell;
        // keep separate Amount columns that are not geometrically part of a title.
        val columns = raw.filterNot { column -> column.role == Role.AMOUNT && cells.any { specific ->
            specific.role in listOf(Role.DEBIT, Role.CREDIT) && cells.filter { it.role == Role.AMOUNT && abs(it.center - column.center) < 0.1 }.any { amount ->
                amount.words.all { a -> specific.words.any { b -> sameHeaderCell(a, b, Role.AMOUNT) } }
            }
        } }.sortedBy { it.center }
        val roles = columns.map { it.role }
        if (Role.DATE !in roles || Role.DESCRIPTION !in roles || roles.none { it in listOf(Role.DEBIT, Role.CREDIT, Role.AMOUNT) }) return null
        if (listOf(Role.DESCRIPTION, Role.DEBIT, Role.CREDIT, Role.AMOUNT, Role.DIRECTION, Role.BALANCE).any { role -> roles.count { it == role } > 1 }) return null
        return Header(columns, width, words.any { ruleRole(it.text) == null && role(it.text) != null })
    }
    private fun headerAt(lines: List<Line>, index: Int, width: Double): HeaderBand? {
        val candidates = mutableListOf<Line>()
        for (offset in 0..2) {
            val line = lines.getOrNull(index + offset) ?: break
            // “Closing Balance” is also a legitimate table column title. Numeric
            // summary rows stop here via the amount/date checks; label-only rows
            // must participate in header recognition before summary filtering.
            if (dateToken(line.text) != null || metadata(line.text) || footer(line.text) ||
                line.words.any { readAmount(it.text).value != null } || line.words.none { role(it.text) != null || dateModifier(it.text) != null }) break
            val previous = candidates.lastOrNull()
            if (previous != null && line.y - previous.y > maxOf(18.0, maxOf(line.height, previous.height) * 1.8)) break
            candidates += line
        }
        // Do not accept a shorter prefix of a contradictory bilingual/date title.
        // Every adjacent header line participates in deciding what its columns mean.
        return if (candidates.isEmpty()) null else header(candidates, width)?.let { HeaderBand(it, candidates.size) }
    }
    private fun summary(text: String): Boolean {
        val t = label(text)
        return Regex("\\b(?:opening|closing|beginning|ending|available|current)\\s+balance\\b|\\bbalance\\s*(?:brought forward|carried forward|b/f|c/f)\\b|^(?:brought forward|carried forward|b/f|c/f)(?:\\s*[:0-9]|$)|(?:الرصيد|رصيد)\\s*(?:الافتتاحي|افتتاح|الختامي|ختامي|السابق|المرحل|المنقول|المتاح|الحالي)").containsMatchIn(t) ||
            Regex("^(?:total(?:s)?(?:\\s+(?:debits?|credits?|withdrawals?|deposits?|transactions?|amounts?))*|sub\\s*total)(?:\\s*[:+0-9-]|\\s+omr(?:\\s|$)|$)|^(?:الاجمالي|اجمالي|مجموع)(?:\\s+(?:المدين|الدائن|المبالغ|السحب|الايداع|الحركات|العمليات))*(?:\\s*[:+0-9-]|\\s+ر\\.ع|$)").containsMatchIn(t)
    }
    private fun metadata(text: String): Boolean {
        val t = label(text).replace(Regex("\\s*([/.-])\\s*"), "$1")
        val boundaryLabel = Regex("^(?:from|to)\\s+date\\s*[:：-]?\\s*").find(t)
        if (boundaryLabel != null) {
            val value = t.substring(boundaryLabel.range.last + 1).trim(' ', ':', '：', '-', '–', '—')
            if (value.isBlank() || dateToken(value) == value) return true
        }
        val dates = (isoDate.findAll(t).map { it.value } + numericDate.findAll(t).map { it.value } + namedDate.findAll(t).map { it.value }).distinct().toList()
        val marker = Regex("\\bstatement\\s+(?:period|date|range)\\b|\\bperiod\\s+of\\s+(?:the\\s+)?statement\\b|(?:فتره|مده)\\s+(?:البيان|الكشف|كشف الحساب)|(?:البيان|الكشف)\\s+(?:فتره|مده)")
        if (marker.containsMatchIn(t)) {
            val withoutMarker = marker.replace(t, "").trim(' ', ':', '：', '-', '–', '—', '.', '،')
            if (dates.isNotEmpty() || withoutMarker.isBlank()) return true
        }
        if (dates.size < 2) return false
        // A bare From/To range under a separate “Statement Period” label is
        // metadata too. Dates appearing inside a transaction narration stay rows.
        val remainder = namedDate.replace(numericDate.replace(isoDate.replace(t, " "), " "), " ")
            .replace(Regex("\\b(?:from|to|until|through|statement|period|date|range)\\b|فتره|مده|البيان|الكشف|(?:^|\\s)(?:من|الي|حتي)(?=\\s|$)"), " ")
            .trim(' ', ':', '：', '-', '–', '—', '/', '.', '،', ',', '(', ')')
        return remainder.isBlank()
    }
    private fun footer(text: String): Boolean = Regex("^(?:page\\s+[0-9]|statement\\s+(?:period|date|of)|generated\\s|computer\\s+generated|end\\s+of\\s+statement|صفحه\\s+[0-9]|نهايه\\s+الكشف)").containsMatchIn(label(text))

    private fun readAmount(raw: String): Amount {
        var t = normalized(raw).trim()
        if (t.isBlank() || t in listOf("-", "—", "–", "N/A")) return Amount(null, null, null, true)
        val directionMatches = Regex("(?<![A-Za-z])(?:DR|CR|debit|credit)(?![A-Za-z])|مدين|دائن", ignoreCase).findAll(t).map {
            if (label(it.value) in listOf("dr", "debit", "مدين")) "out" else "in"
        }.distinct().toList()
        if (directionMatches.size > 1) return Amount(null, null, null, false)
        val embeddedDirection = directionMatches.singleOrNull()
        t = t.replace(Regex("(?<![A-Za-z])(?:DR|CR|debit|credit)(?![A-Za-z])|مدين|دائن", ignoreCase), "")
            .replace(currencyToken, "").trim()
        var parenthesized = false
        if (t.startsWith('(') && t.endsWith(')')) { parenthesized = true; t = t.drop(1).dropLast(1).trim() }
        if (!amountToken.matches(t)) return Amount(null, null, embeddedDirection, false)
        val negative = parenthesized || t.startsWith('-') || t.startsWith('−') || t.endsWith('-') || t.endsWith('−')
        val positive = t.startsWith('+') || t.endsWith('+')
        if (negative && positive || parenthesized && (t.firstOrNull() in listOf('-', '−', '+') || t.lastOrNull() in listOf('-', '−', '+'))) return Amount(null, null, embeddedDirection, false)
        t = t.trim('+', '-', '−')
        t = when {
            ',' in t && '.' in t -> if (Regex("[0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]{1,3}").matches(t)) t.replace(",", "") else return Amount(null, null, embeddedDirection, false)
            // A comma alone may be a decimal separator (3,000) or a thousands
            // separator. No document locale is proven, so do not choose either.
            ',' in t -> return Amount(null, null, embeddedDirection, false)
            else -> t
        }
        val parts = t.split('.')
        val whole = parts.firstOrNull()?.toLongOrNull() ?: return Amount(null, null, embeddedDirection, false)
        val fraction = parts.getOrNull(1).orEmpty()
        if (parts.size > 2 || fraction.length > 3 || whole > MAX_AMOUNT / 1000) return Amount(null, null, embeddedDirection, false)
        val baisa = whole * 1000 + fraction.padEnd(3, '0').toLong()
        if (baisa > MAX_AMOUNT) return Amount(null, null, embeddedDirection, false)
        return Amount(baisa, if (negative) -1 else if (positive) 1 else null, embeddedDirection, true)
    }
    private fun explicitDirection(value: String): String? {
        return StatementDirection.explicit(value)
    }
    fun effect(description: String, direction: String?): String = when {
        direction == "in" && Regex("\\b(?:refund|reversal|reversed)\\b|استرجاع|مسترد|عكس").containsMatchIn(label(description)) -> "refund"
        direction == "in" -> "income"
        direction == "out" -> "expense"
        else -> "unresolved"
    }
    fun type(description: String): String = when {
        Regex("\\b(?:transfer|remittance|wallet)\\b|تحويل|حواله|محفظه").containsMatchIn(label(description)) -> "transfer"
        Regex("\\b(?:refund|reversal|reversed)\\b|استرجاع|مسترد|عكس").containsMatchIn(label(description)) -> "refund"
        Regex("\\b(?:atm|withdrawal|cash withdrawal)\\b|صراف|سحب نقدي").containsMatchIn(label(description)) -> "atm"
        Regex("\\b(?:fee|fees|charges?|commission)\\b|رسوم|عموله").containsMatchIn(label(description)) -> "fee"
        else -> "other"
    }
    fun category(description: String): String? = SmsParser.category(SmsParser.merchantName(description), type(description))
    private fun makeRow(pending: Pending): StatementRow {
        val words = pending.words
        val h = pending.header
        val warnings = mutableListOf<String>()
        if (h == null) warnings += "تعذر تحديد أعمدة الكشف؛ أدخل هذه العملية يدويًا"
        fun cell(role: Role): String {
            val selected = words.filter { h?.role(it) == role }
            // Keep Arabic description words in reading order while leaving numeric columns LTR.
            val rtl = role == Role.DESCRIPTION && selected.count { Regex("[ء-ي]").containsMatchIn(it.text) } > selected.count { Regex("[A-Za-z]").containsMatchIn(it.text) }
            return selected.sortedWith(compareBy<StatementWord> { it.y }.thenBy { if (rtl) -it.x else it.x }).joinToString(" ") { it.text }.trim()
        }
        val dateText = if (h != null) normalized(listOf(cell(Role.DATE),cell(Role.TIME)).filter{it.isNotBlank()}.joinToString(" ")) else dateToken(words.joinToString(" ") { it.text }).orEmpty()
        val date = dateMillis(dateText)
        if (date == null) warnings += "تاريخ العملية غير صالح أو غير مكتمل"
        val description = if (h != null) normalized(cell(Role.DESCRIPTION)) else normalized(words.joinToString(" ") { it.text }).take(2000)
        if (description.isBlank()) warnings += "وصف العملية مفقود"
        val debit = readAmount(cell(Role.DEBIT)); val credit = readAmount(cell(Role.CREDIT)); val amount = readAmount(cell(Role.AMOUNT))
        var value: Long? = null; var direction: String? = null; var directionSource: String? = null
        var directionConflict=false
        if (h != null) {
            if (!debit.valid || !credit.valid || !amount.valid) warnings += "تنسيق المبلغ غير واضح؛ لم يُحتسب"
            else if (debit.value != null && debit.value != 0L && credit.value != null && credit.value != 0L) warnings += "يوجد مبلغ في المدين والدائن؛ راجع الكشف"
            else if ((debit.value ?: 0L) != 0L || (credit.value ?: 0L) != 0L) {
                val outgoing = (debit.value ?: 0L) != 0L
                val selected = if (outgoing) debit else credit
                value = selected.value; direction = if (outgoing) "out" else "in"; directionSource="column"
                if (selected.sign == -1 || selected.direction != null && selected.direction != direction) { value = null; direction = null; warnings += "إشارة المبلغ تخالف عمود المدين أو الدائن" }
                if (amount.value != null && amount.value != value) { value = null; warnings += "أعمدة المبلغ متعارضة" }
            } else if (amount.value != null) {
                value = amount.value
                val marker = explicitDirection(cell(Role.DIRECTION))
                val signDirection = when (amount.sign) { -1 -> "out"; 1 -> "in"; else -> null }
                val indications = listOfNotNull(marker, amount.direction, signDirection).distinct()
                direction = indications.singleOrNull()
                if(direction!=null) directionSource="marker"
                if(indications.isEmpty()) StatementDirection.suggest(description)?.let { direction=it.direction; directionSource=it.source }
                if (indications.size > 1) { value = null; warnings += "اتجاه المبلغ متعارض" }
            }
        }
        if (value == null || value == 0L) warnings += "لم يوجد مبلغ عملية صالح؛ الرصيد لا يُستورد"
        if (direction == null) warnings += "اتجاه العملية غير محسوم"
        val pendingOrFailed=StatementDirection.needsReview(description)
        if(pendingOrFailed) warnings+="الوصف يشير إلى فشل أو تعليق أو إلغاء؛ تأكد من أن الحركة قُيّدت فعلًا قبل تأكيدها"
        if(directionSource in listOf("description","model")) warnings += "اتجاه مقترح من الوصف؛ أكّد هل المبلغ داخل أم خارج"
        if(directionSource in listOf("column","marker")) {
            val narration=StatementDirection.rules(description)
            if(narration!=null && narration!=direction) { directionConflict=true; warnings+="الوصف يخالف اتجاه عمود المبلغ؛ اختر الاتجاه الصحيح بعد مراجعة الكشف" }
        }
        val effect = effect(description, direction)
        if (effect == "unresolved" && direction != null) warnings += "حدد بعد الإضافة هل التحويل مصروف أم بين حساباتك"
        val uncertainNumbers = pending.isOcr && words.any { h?.role(it) in listOf(Role.DATE, Role.TIME, Role.DEBIT, Role.CREDIT, Role.AMOUNT, Role.DIRECTION) && it.confidence < 0.85 }
        if (uncertainNumbers) warnings += "قراءة أحد حقول التاريخ أو المبلغ أو الاتجاه غير موثوقة؛ صحح البيانات وأكّدها"
        if(pending.ocrDisagreement) warnings+="اختلفت محاولتا قراءة الصورة؛ تحقّق من المبلغ والتاريخ والاتجاه"
        if (pending.isOcr) warnings += "مستخرجة من صورة على الجهاز؛ راجع الأرقام ثم حدد الحركة لإضافتها"
        if (h?.assisted == true) warnings += "اقتراح أعمدة بالنموذج المحلي؛ راجع التاريخ والاتجاه والمبلغ قبل الاختيار"
        return StatementRow("p${pending.page}-r${pending.line}", pending.page, dateText, date, description.take(2000), value, direction, effect, category(description), null,
            warnings.distinct(), !uncertainNumbers && h != null && date != null && description.isNotBlank() && value != null && value > 0 && direction != null,
            pending.isOcr || h?.assisted == true || directionSource in listOf("description","model") || directionConflict || direction==null || pendingOrFailed,
            balanceBaisa=readAmount(cell(Role.BALANCE)).takeIf { it.valid && it.sign!=-1 }?.value, directionSource=directionSource,datePrecision=datePrecision(dateText))
    }

    fun parse(document: StatementDocument, documentId: String): StatementPreview {
        require(document.version == 1 && document.pages.size in 1..50) { "الكشف يتجاوز الحد المسموح أو إصداره غير مدعوم" }
        require(Regex("[a-fA-F0-9]{64}").matches(documentId)) { "معرّف ملف الكشف غير صالح" }
        require(document.pages.sumOf { it.words.size } <= 100_000 && document.pages.sumOf { page -> page.words.sumOf { it.text.length.toLong() } } <= 1_000_000L) { "نص الكشف أكبر من الحد المسموح" }
        document.pages.forEach { page ->
            require(page.width.isFinite() && page.height.isFinite() && page.width in 1.0..20_000.0 && page.height in 1.0..20_000.0)
            page.words.forEach { word -> require(word.text.length <= 4000 && word.confidence.isFinite() && word.confidence in 0.0..1.0 && listOf(word.x, word.y, word.width, word.height).all { it.isFinite() } && word.x in -2.0..page.width + 2 && word.y in -2.0..page.height + 2 && word.width in 0.0..page.width && word.height in 0.0..page.height) { "إحداثيات نص الكشف غير صالحة" } }
        }
        val rows = mutableListOf<StatementRow>(); var skipped = 0; val warnings = mutableListOf<String>()
        var previousHeader: Header? = null
        document.pages.forEachIndexed { pageIndex, page ->
            var activeHeader = previousHeader?.takeIf { abs(it.width - page.width) < 2 }
            var pending: Pending? = null
            fun flush() {
                pending?.let { row ->
                    val description = row.words.filter { row.header?.role(it) == Role.DESCRIPTION }.joinToString(" ") { it.text }
                    if (summary(description) || summary(row.words.joinToString(" ") { it.text })) skipped++ else rows += makeRow(row)
                }; pending = null
                require(rows.size <= 5000) { "الكشف يحتوي أكثر من 5000 عملية" }
            }
            val lines = lines(page)
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val foundHeader = headerAt(lines, index, page.width)
                if (foundHeader != null) {
                    flush(); activeHeader = foundHeader.header; previousHeader = foundHeader.header
                    index += foundHeader.lineCount; continue
                }
                val hasDate = dateToken(line.text) != null
                val hasMoney = activeHeader != null && line.words.filter { activeHeader?.role(it) in listOf(Role.DEBIT, Role.CREDIT, Role.AMOUNT) }.any { readAmount(it.text).value != null }
                val knownFinancialRow = activeHeader != null && hasDate && hasMoney
                if (summary(line.text) || footer(line.text) && !knownFinancialRow || metadata(line.text) && !knownFinancialRow) {
                    flush(); if (summary(line.text) || metadata(line.text)) skipped++
                    index++; continue
                }
                if (hasDate) {
                    flush(); pending = Pending(pageIndex + 1, index + 1, activeHeader, line.words.toMutableList(), line.y, page.isOcr, page.ocrDisagreement)
                } else if (pending != null && line.y - pending!!.lastY <= maxOf(24.0, line.height * 2.3)) {
                    val onlyDescription = activeHeader != null && line.words.all { activeHeader?.role(it) in listOf(Role.DESCRIPTION, Role.IGNORE) }
                    val priorHasMoney = pending!!.words.filter { activeHeader?.role(it) in listOf(Role.DEBIT, Role.CREDIT, Role.AMOUNT) }.any { readAmount(it.text).value != null }
                    if (onlyDescription || hasMoney && !priorHasMoney) { pending!!.words += line.words; pending!!.lastY = line.y }
                    else { flush(); if (hasMoney) pending = Pending(pageIndex + 1, index + 1, activeHeader, line.words.toMutableList(), line.y, page.isOcr, page.ocrDisagreement) }
                } else {
                    flush()
                    if (hasMoney) pending = Pending(pageIndex + 1, index + 1, activeHeader, line.words.toMutableList(), line.y, page.isOcr, page.ocrDisagreement)
                }
                index++
            }
            flush()
        }
        val currency = currency(document)
        if (currency == "UNKNOWN") warnings += "لم تظهر عملة واضحة؛ أكّد أن الكشف بالريال العماني قبل الإضافة"
        if (currency !in listOf("OMR", "UNKNOWN")) warnings += "هذا الكشف ليس بعملة الريال العماني فقط؛ لا يمكن استيراده"
        if (rows.isEmpty()) warnings += "لم نجد جدول عمليات قابلًا للقراءة؛ قد يكون الملف صورة أو بتنسيق غير مدعوم"
        val checked=checkBalances(rows).map { if(!it.canImport) it.copy(reviewRequired=true) else it }
        if (checked.any { !it.canImport || it.reviewRequired }) warnings += "توجد عمليات تحتاج تصحيحًا أو تأكيدًا فرديًا قبل الإضافة"
        val first=document.pages.first()
        val headerText=first.words.filter { it.y<minOf(first.height*0.3,first.words.filter { word -> ruleRole(word.text)==Role.DATE }.minOfOrNull { word -> word.y } ?: first.height*0.3) }.joinToString(" "){it.text}
        return StatementPreview(documentId.lowercase(), checked, currency, skipped, warnings,bankName=BankKnowledge.detect(headerText))
    }

    private fun checkBalances(rows: List<StatementRow>): List<StatementRow> {
        val chronological=rows.zipWithNext().mapNotNull { (a,b) ->
            if(a.page!=b.page || a.occurredAtMillis==null || b.occurredAtMillis==null || a.occurredAtMillis==b.occurredAtMillis) null
            else if(a.occurredAtMillis<b.occurredAtMillis) 1 else -1
        }.distinct().singleOrNull() ?: return rows
        val result=rows.toMutableList()
        rows.zipWithNext().forEachIndexed { index,(a,b) ->
            if(a.page!=b.page || a.balanceBaisa==null || b.balanceBaisa==null || a.occurredAtMillis==null || b.occurredAtMillis==null) return@forEachIndexed
            val targetIndex=if(chronological==1) index+1 else index
            val target=result[targetIndex]
            val amount=target.amountBaisa ?: return@forEachIndexed
            if(amount<=0) return@forEachIndexed
            val delta=if(chronological==1) b.balanceBaisa-a.balanceBaisa else a.balanceBaisa-b.balanceBaisa
            val balanceDirection=if(delta>0) "in" else "out"
            if(abs(delta)==amount && target.direction!=balanceDirection) {
                result[targetIndex]=target.copy(direction=balanceDirection,effect=effect(target.description,balanceDirection),canImport=false,directionSource="balance",reviewRequired=true,
                    warnings=target.warnings+"اقتُرح الاتجاه من فرق الرصيد؛ أكّده بعد مراجعة الكشف")
            } else if(target.direction!=null && delta!=(if(target.direction=="in") amount else -amount)) {
                result[targetIndex]=target.copy(reviewRequired=true,warnings=target.warnings+"فرق الرصيد لا يطابق مبلغ العملية واتجاهها؛ راجع القيم والعمليات المفقودة")
            }
        }
        return result
    }

    private fun reference(description: String): String? = Regex("(?:\\bref(?:erence)?\\b|مرجع|رقم المعامله)\\s*[:#-]?\\s*([a-z0-9]{5,})", ignoreCase).find(label(description))?.groupValues?.get(1)?.uppercase()
    fun duplicate(row: StatementRow, transactions: List<Transaction>, bankName:String=BankKnowledge.UNKNOWN): Transaction? {
        val whenAt = row.occurredAtMillis ?: return null
        val day = Instant.fromEpochMilliseconds(whenAt).toLocalDateTime(muscat).date
        val merchant = SmsParser.merchantName(row.description)
        val ref = reference(row.description)
        return transactions.firstOrNull { txn ->
            !txn.isDemo && (BankKnowledge.key(bankName)==null || BankKnowledge.key(txn.bankName)==null || BankKnowledge.key(bankName)==BankKnowledge.key(txn.bankName)) && txn.amountMinor == row.amountBaisa && txn.currency == "OMR" && txn.direction == row.direction &&
                Instant.fromEpochMilliseconds(txn.occurredAtMillis).toLocalDateTime(muscat).date == day &&
                (ref != null && txn.reference?.uppercase() == ref || merchant != null && txn.merchant != null &&
                    (merchant == txn.merchant || minOf(merchant.length, txn.merchant.length) >= 5 && (merchant.contains(txn.merchant) || txn.merchant.contains(merchant))))
        }
    }
    fun referenceFor(description: String): String? = reference(description)
}
