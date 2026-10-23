package om.masroofi.core

import kotlinx.datetime.*

data class Draft(
    val amount: Long?, val currency: String, val direction: String?, val type: String,
    val occurredAt: Long, val datePrecision: String, val counterparty: String?, val merchant: String?,
    val category: String?, val balance: Long?, val accountHint: String?, val reference: String?,
    val warnings: List<String>
)
data class ParsedMessage(val drafts: List<Draft> = emptyList(), val ignoredReason: String? = null)

object SmsParser {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val otp = Regex("\\botp\\b|one[- ]?time|verification code|رمز التحقق|رمز التفعيل|كلمة المرور|\\bcvv\\b", options)
    private val failed = Regex("declined|failed|unsuccessful|مرفوض|فشل|تم رفض|لم (?:يتم|تتم)|غير ناجح", options)
    private val promo = Regex("\\b(?:offer|discount|visit our|sale)\\b|احصل|عرض خاص|تخفيض|خصم\\s+\\d+\\s*%", options)
    private const val cur = "(?:OMR|R\\.?O\\.?|USD|AED|SAR|EUR|GBP|INR|QAR|KWD|BHD|ر\\.\\s*ع\\.?|ريال\\s*عماني|ريال)"
    private const val number = "[0-9]+(?:[.,][0-9]+)*"
    private val before = Regex("(?<![A-Za-z])($cur)\\s*($number)(?![0-9])", options)
    private val after = Regex("(?<![0-9.,A-Za-z])($number)\\s*($cur)(?![A-Za-z])", options)
    private val balanceLabel = Regex("\\bbal(?:ance)?\\b|رصيد|الرصيد", options)
    private val feeLabel = Regex("\\b(?:fees?|charges?)\\b|رسوم|عمولة", options)
    private val event = Regex("purchase|debited|credited|withdraw|deposit|transfer|paid|payment|refund|revers|salary|fee|\\bcharges?\\b|\\bcharged\\b|خصم|شراء|إيداع|ايداع|تحويل|إرسال|ارسال|سحب|استرجاع|عكس|راتب|دفع|رسوم|عمولة", options)
    private val stop = "(?=\\s+(?:on|at|using|ref|avail|balance|bal|credited|debited|card|date|with)\\b|\\s+(?:بتاريخ|رصيدك|الرصيد|رصيد|باستخدام|بإستخدام|باستعمال|من حساب|في حساب)|[.;،]|$)"
    private val merchantPatterns = listOf(
        Regex("(?:للحساب باسم|لصالح)\\s+(.+?)$stop", options),
        Regex("(?:\\bat\\b|@|لدى|عند)\\s+(.+?)$stop", options),
        Regex("(?:بطاقة.{0,35}?\\sفي|card.{0,35}?\\bat)\\s+(.+?)$stop", options)
    )
    private val incomingParty = Regex("(?:\\bfrom\\b|من)\\s+(.+?)$stop", options)
    private val outgoingParty = Regex("(?:\\bto\\b|إلى|الى)\\s+(.+?)$stop", options)
    private val noiseTokens = setOf("MCT", "MUSCAT", "LLC", "OM", "OMN", "SAOG", "POS", "TERMINAL", "CO")

    fun normalize(text: String): String = text.map { char ->
        when (char) {
            in '٠'..'٩' -> ('0'.code + char.code - '٠'.code).toChar()
            in '۰'..'۹' -> ('0'.code + char.code - '۰'.code).toChar()
            '٫' -> '.'; '٬' -> ','; else -> char
        }
    }.joinToString("").replace(Regex("\\s+"), " ").trim()

    fun splitMessages(text: String): List<String> {
        val wrapper = Regex("(?m)^\\[\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?,[^\\]]+\\]\\s*[^:]*:\\s*")
        return if (wrapper.containsMatchIn(text)) text.split(wrapper).map { it.trim() }.filter { it.isNotEmpty() }
        else listOf(text.trim()).filter { it.isNotEmpty() }
    }

    fun merchantName(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw.uppercase().replace(Regex("^\\d+[\\s-]+"), "")
            .replace(Regex("[#*_\\\\]+"), " ")
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.all(Char::isDigit) && it !in noiseTokens }
        val result = tokens.joinToString(" ").trim(' ', '.', '-', ',')
        return result.takeIf { it.length >= 2 && !Regex("^[X#*\\s]+$").matches(it) }
    }

    private fun validParty(value: String?): String? {
        val candidate = value?.trim(' ', '.', ',', '-', ';') ?: return null
        if (candidate.length < 2 || Regex("^(?:حساب|بطاق|your|account|the |[0-9]{1,2}:)", options).containsMatchIn(candidate)) return null
        if (Regex("^[X#*\\s]+$", options).matches(candidate)) return null
        return candidate
    }
    fun category(merchant: String?, type: String): String? {
        val t = merchant?.lowercase().orEmpty()
        val dictionary = linkedMapOf(
            "fuel" to listOf("oman oil", "oq station", "shell", "al maha", "محطة", "وقود", "بترول", "المها"),
            "groceries" to listOf("lulu", "carrefour", "nesto", "supermarket", "hypermarket", "بقالة", "سوبرماركت"),
            "telecom" to listOf("omantel", "ooredoo", "vodafone", "عمانتل", "أوريدو"),
            "restaurants" to listOf("cafe", "coffee", "restaurant", "kfc", "mcdonald", "pizza", "مطعم", "كافيه"),
            "utilities" to listOf("nama", "electricity", "كهرباء", "مياه"),
            "health" to listOf("pharmacy", "hospital", "clinic", "صيدلية", "مستشفى", "عيادة"),
            "transport" to listOf("taxi", "careem", "uber", "تاكسي"),
            "shopping" to listOf("amazon", "shopping")
        )
        dictionary.forEach { (category, words) -> if (words.any { t.contains(it) }) return category }
        return when (type) { "atm" -> "cash"; "fee" -> "fees"; "bill" -> "utilities"; else -> null }
    }

    private data class Amount(val value: Long?, val currency: String, val start: Int, val end: Int, val kind: String, val warning: String?)
    private fun amounts(text: String): List<Amount> {
        val matches = mutableListOf<Triple<MatchResult, String, String>>()
        before.findAll(text).forEach { matches += Triple(it, it.groupValues[1], it.groupValues[2]) }
        after.findAll(text).forEach { m ->
            if (matches.none { it.first.range.first <= m.range.last && m.range.first <= it.first.range.last }) matches += Triple(m, m.groupValues[2], m.groupValues[1])
        }
        var previousEnd = 0
        return matches.sortedBy { it.first.range.first }.map { (match, token, raw) ->
            val start = match.range.first
            val context = text.substring(maxOf(previousEnd, start - 38), start)
            previousEnd = match.range.last + 1
            val currency = if (Regex("[ء-ي]").containsMatchIn(token) || token.uppercase().replace(".", "") == "RO") "OMR" else token.uppercase()
            val kind = if (balanceLabel.containsMatchIn(context)) "balance" else if (feeLabel.containsMatchIn(context)) "fee" else "transaction"
            val digits = if (currency in listOf("OMR", "KWD", "BHD")) 3 else 2
            var warning: String? = null
            val clean = when {
                ',' in raw && '.' in raw -> if (Regex("[0-9]{1,3}(?:,[0-9]{3})+\\.[0-9]+").matches(raw)) raw.replace(",", "") else { warning = "تنسيق مبلغ غير واضح"; "" }
                ',' in raw -> { warning = "فاصل المبلغ يحتمل فاصلة عشرية أو آلاف؛ راجع المبلغ"; "" }
                else -> raw
            }
            val parts = clean.split('.')
            val whole = parts.firstOrNull()?.toLongOrNull()
            val fraction = parts.getOrNull(1).orEmpty()
            val factor = if (digits == 3) 1000L else 100L
            val value = if (warning != null || parts.size > 2 || fraction.length > digits || whole == null || whole > 9_000_000_000L) {
                if (warning == null) warning = "دقة مبلغ غير صالحة؛ راجع المبلغ"
                null
            } else whole * factor + fraction.padEnd(digits, '0').toLong()
            Amount(value, currency, start, match.range.last + 1, kind, warning)
        }
    }

    private data class DateResult(val millis: Long, val precision: String, val warning: String? = null)
    private fun date(text: String, receivedAt: Long, zone: TimeZone): DateResult {
        val iso = Regex("(?<![0-9])([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})(?![0-9])").find(text)
        val dmy = Regex("(?<![0-9])([0-9]{1,2})[-/]([0-9]{1,2})[-/]([0-9]{2,4})(?![0-9])").find(text)
        val named = Regex("(?<![0-9])([0-9]{1,2})[-/ ]([A-Za-z]{3})[A-Za-z]*[-/ ]([0-9]{2,4})(?![0-9])").find(text)
        val match = iso ?: named ?: dmy ?: return DateResult(receivedAt, "received")
        return try {
            val values = match.groupValues
            val year = (if (iso != null) values[1] else values[3]).toInt().let { if (it < 100) it + 2000 else it }
            require(year in 1900..2200)
            val month = if (named != null && iso == null) listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(values[2].lowercase()) + 1 else values[2].toInt()
            val day = (if (iso != null) values[3] else values[1]).toInt()
            val time = Regex("^\\s*(?:at\\s*|,\\s*|الساعة\\s*)?([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?:\\s*(AM|PM)\\b)?", options).find(text.substring(match.range.last + 1))
            var hour = time?.groupValues?.get(1)?.toInt() ?: 0
            val period = time?.groupValues?.get(4).orEmpty().uppercase()
            if (period.isNotEmpty()) { require(hour in 1..12); hour = hour % 12 + if (period == "PM") 12 else 0 }
            val local = LocalDateTime(year, month, day, hour, time?.groupValues?.get(2)?.toInt() ?: 0, time?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0)
            DateResult(local.toInstant(zone).toEpochMilliseconds(), if (time == null) "day" else if (time.groupValues[3].isNotEmpty()) "second" else "minute")
        } catch (_: IllegalArgumentException) { DateResult(receivedAt, "received", "تاريخ الرسالة غير صالح؛ راجع التاريخ") }
    }

    fun parse(message: String, receivedAt: Long, timeZone: String = "Asia/Muscat"): ParsedMessage {
        if (message.length > 16_000) return ParsedMessage(ignoredReason = "الرسالة طويلة؛ أدخل العملية يدويًا")
        val text = normalize(message)
        if (otp.containsMatchIn(text)) return ParsedMessage(ignoredReason = "رمز تحقق؛ لم يُحفظ")
        if (failed.containsMatchIn(text)) return ParsedMessage(ignoredReason = "عملية فاشلة أو مرفوضة؛ لم تُحتسب")
        if (promo.containsMatchIn(text)) return ParsedMessage(ignoredReason = "رسالة ترويجية؛ لم تُحفظ")
        if (!event.containsMatchIn(text)) return ParsedMessage(ignoredReason = "ليست رسالة عملية مالية")
        val values = amounts(text)
        val main = values.filter { it.kind == "transaction" }
        val fees = values.filter { it.kind == "fee" }
        if (main.isEmpty() && fees.isEmpty()) return ParsedMessage(ignoredReason = "لم يوجد مبلغ عملية؛ الرصيد وحده لا يُحتسب")
        val refund = Regex("refund|reversal|reversed|استرجاع|عكس|مسترد", options).containsMatchIn(text)
        val pending = Regex("\\b(?:pending|requested|request|processing|pre[- ]?authorization)\\b|will be (?:debited|charged|paid)|scheduled payment|قيد\\s+(?:المعالجة|التنفيذ)|طلب\\s*(?:استرجاع|عكس)|مبلغ\\s*محجوز|تفويض\\s*(?:معلق|مؤقت)|(?:سيتم|سوف يتم)\\s+(?:خصم|دفع|تحويل)", options).containsMatchIn(text)
        val transfer = Regex("transfer|تحويل|إرسال|ارسال|محفظة", options).containsMatchIn(text)
        val salary = Regex("salary|راتب", options).containsMatchIn(text)
        val deposit = Regex("deposit|إيداع|ايداع", options).containsMatchIn(text)
        val reversedInflow = refund && (deposit || salary)
        val atm = Regex("\\batm\\b|withdraw|صراف|سحب نقدي", options).containsMatchIn(text)
        val incoming = Regex("credited|received|to your account|إيداع|ايداع|استلام|استلمت|راتب|(?:إلى|الى) حسابك", options).containsMatchIn(text)
        val outgoing = Regex("purchase|debited|\\bcharged\\b|withdraw|\\bpaid\\b|خصم|سحب|شراء|مشتريات|إرسال|ارسال|من حسابك|تم دفع|تم سداد", options).containsMatchIn(text)
        val ambiguousAccountTransfer = transfer && Regex("(?:إلى|الى)\\s+حساب(?: التوفير)?", options).containsMatchIn(text) && !incoming && !outgoing
        val direction = when {
            reversedInflow -> null
            refund -> "in"
            incoming && !outgoing -> "in"
            outgoing && !incoming -> "out"
            ambiguousAccountTransfer -> null
            transfer && Regex("(?:\\bto\\b|إلى|الى)\\s+", options).containsMatchIn(text) -> "out"
            transfer && Regex("\\bfrom\\b|\\bمن\\b", options).containsMatchIn(text) -> "in"
            feeLabel.containsMatchIn(text) && main.isEmpty() -> "out"
            else -> null
        }
        var party = merchantPatterns.firstNotNullOfOrNull { validParty(it.find(text)?.groupValues?.get(1)) }
        if (party == null && direction == "in") party = validParty(incomingParty.findAll(text).map { it.groupValues[1] }.firstOrNull { validParty(it) != null })
        if (party == null && direction == "out") party = validParty(outgoingParty.find(text)?.groupValues?.get(1))
        val type = when {
            reversedInflow -> "reversal_unknown"; refund -> "refund"; salary -> "salary"; atm -> "atm"; transfer -> "transfer"
            deposit -> "deposit"
            Regex("direct\\s*debit|خصم مباشر", options).containsMatchIn(text) -> "direct_debit"
            Regex("\\bbill\\b|فاتورة|فواتير", options).containsMatchIn(text) -> "bill"
            party != null && direction == "out" -> "pos"
            else -> "other"
        }
        val zone = runCatching { TimeZone.of(timeZone) }.getOrDefault(TimeZone.of("Asia/Muscat"))
        val whenAt = date(text, receivedAt, zone)
        val account = Regex("(?:[Xx*]{2,}|ending(?: in| with)?)\\s*([0-9]{4})(?![0-9])", options).find(text)?.groupValues?.get(1)
        val ref = Regex("(?:رقم المعاملة|مرجع|\\bref(?:erence)?\\b)\\s*[:#-]?\\s*([A-Za-z0-9]{5,})", options).find(text)?.groupValues?.get(1)?.takeUnless { it.contains(Regex("[Xx#*]{3,}")) }
        val balance = values.firstOrNull { it.kind == "balance" && it.currency == (main.firstOrNull()?.currency ?: fees.first().currency) }?.value
        val normalizedParty = merchantName(party)
        val multiple = main.size > 1
        val drafts = (main + fees).map { amount ->
            val isFee = amount.kind == "fee"
            val reversedFee = isFee && refund && main.isEmpty() && !reversedInflow
            val warnings = buildList {
                amount.warning?.let(::add)
                whenAt.warning?.let(::add)
                if (!isFee && direction == null) add("اتجاه العملية غير محسوم")
                if (multiple) add("الرسالة تحمل أكثر من مبلغ عملية؛ راجع توزيعها")
                if (amount.currency != "OMR") add("عملة أجنبية؛ أدخل قيمة الخصم بالريال قبل الاحتساب")
                if (transfer && !isFee) add("حدد هل التحويل مصروف أم بين حساباتك")
                if (pending) add("طلب أو عملية معلقة؛ لا تُحتسب حتى تتأكد من اكتمالها")
                if (reversedInflow) add("عكس عملية واردة؛ حدد أثرها المالي بالمراجعة")
                if (isFee && refund && main.isNotEmpty()) add("رسوم مذكورة مع استرجاع؛ راجع هل خُصمت أم أُعيدت")
            }
            Draft(amount.value, amount.currency, if (reversedFee) "in" else if (isFee) "out" else direction, if (reversedFee) "refund" else if (isFee) "fee" else type,
                whenAt.millis, whenAt.precision, if (isFee) "رسوم العملية" else party,
                if (isFee) null else normalizedParty, if (isFee) "fees" else category(normalizedParty, type),
                balance, account, ref, warnings)
        }
        return ParsedMessage(drafts)
    }
}
