package om.masroofi.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/** Direction suggestions never alter amounts and always require review without a signed column. */
internal object StatementDirection {
    data class Suggestion(val direction: String, val source: String)
    private fun clean(text: String) = StatementImport.normalized(text).lowercase()
        .replace('أ','ا').replace('إ','ا').replace('آ','ا').replace('ى','ي').replace('ة','ه')
        .replace(Regex("[\u064b-\u065f\u0670ـ]"), "").trim()
    private fun matches(pattern: String, text: String) = Regex(pattern).containsMatchIn(text)
    private fun blocked(text: String) = matches("\\b(?:failed|declined|rejected|unsuccessful|pending|cancelled|canceled|requested|request|reminder|limit)\\b|\\bnot\\s+(?:completed|processed|paid)\\b|فشل|مرفوض|لم تتم|لم يتم|معلق|قيد المعالجه|طلب است|تذكير|حد السحب", text)
    fun needsReview(text: String)=blocked(clean(text))

    fun explicit(text: String): String? {
        val t=clean(text).replace(Regex("[/():.-]")," ").replace(Regex("\\s+")," ").trim()
        return when(t) {
            "dr","debit","debited","out","outgoing","withdrawal","payment","paid","مدين","صادر","خصم","سحب","دفع" -> "out"
            "cr","credit","credited","in","incoming","deposit","received","دائن","وارد","ايداع","اضافه" -> "in"
            else -> rules(t)
        }
    }
    fun rules(text: String): String? {
        val t=clean(text)
        if(blocked(t)) return null
        val reversed=matches("\\b(?:reversal|reversed|reverse)\\b|عكس|الغاء مبلغ",t)
        val out=matches("\\b(?:withdrawal|withdrawn|debited|purchase|paid|outgoing|outward)\\b|\\b(?:pos purchase|pos payment|atm cash|atm withdrawal|card payment|bill payment|payment sent|money sent|funds deducted|bank fee|bank service charge|direct debit|debit transaction)\\b|سحب|خصم|شراء|دفع|سداد|صادر|ارسال|رسوم|عموله",t) || reversed && matches("\\bdebit\\b",t)
        val input=matches("\\b(?:deposit|deposited|credited|salary|payroll|received|incoming|inward)\\b|\\b(?:funds added|profit distribution|cashback|credit transaction|credit from)\\b|ايداع|مبلغ دائن|راتب|استلام|مستلمه|وارد|وصل مبلغ|اضافه|ارباح",t) || reversed && matches("\\bcredit\\b",t)
        if(reversed) return if(out == input) null else if(out) "in" else "out"
        if(matches("\\b(?:refund|refunded|cashback)\\b|استرداد|استرجاع|مسترد",t)) return "in"
        return if(out == input) null else if(out) "out" else "in"
    }
    fun suggest(text: String): Suggestion? {
        val t=clean(text)
        if(blocked(t)) return null
        rules(t)?.let { return Suggestion(it,"description") }
        // Generic transfers and contradictory/reversal text must not be guessed by the classifier.
        if(matches("\\b(?:transfer|reversal|reversed|refund|debit|credit)\\b|تحويل|حواله|عكس|استرجاع",t)) return null
        return model(t)?.let { Suggestion(it,"model") }
    }

    @Serializable private data class Weights(val v: Int,val features: List<String>,val weights: Map<String,List<Double>>)
    private val weights by lazy { ledgerJson.decodeFromString<Weights>(LOCAL_DIRECTION_WEIGHTS).also {
        require(it.v==1 && it.features.size<=5000 && it.weights.keys==setOf("in","out","OTHER"))
        require(it.weights.values.all { row -> row.size==it.features.size && row.all(Double::isFinite) })
    } }
    private val indices by lazy { weights.features.withIndex().associate { it.value to it.index } }
    internal fun model(text: String): String? {
        if(text.length !in 8..500) return null
        val value="^"+text.replace(Regex("[^a-zء-ي ]"),"")+"$"
        val features=(2..3).flatMap { n -> (0..(value.length-n).coerceAtLeast(-1)).map { value.substring(it,it+n) } }.toSet()
        val positions=features.mapNotNull { indices[it] }
        if(features.isEmpty() || positions.size.toDouble()/features.size<0.8) return null
        val scores=weights.weights.map { (role,row) -> role to positions.sumOf { row[it] } }.sortedByDescending { it.second }
        val top=scores.first()
        val probability=1.0/scores.sumOf { exp(it.second-top.second) }
        return top.first.takeIf { it!="OTHER" && probability>=0.999 && top.second-scores[1].second>=9.0 }
    }
}
