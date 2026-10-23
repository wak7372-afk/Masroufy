package om.masroofi.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable data class StatementSummary(
    val openingBaisa:Long?=null, val closingBaisa:Long?=null,
    val debitTotalBaisa:Long?=null, val creditTotalBaisa:Long?=null,
    val needsReview:Boolean=false, val warnings:List<String> = emptyList()
)
@Serializable data class StatementAudit(
    val status:String, val outgoingBaisa:Long, val incomingBaisa:Long, val unresolvedRows:Int,
    val calculatedClosingBaisa:Long?=null, val differenceBaisa:Long?=null,
    val checks:Int=0, val requiresConfirmation:Boolean=false, val warnings:List<String> = emptyList()
)

/** Independent arithmetic, in the statement currency and before internal-transfer exclusions. */
internal object StatementAuditEngine {
    private val labels=listOf(
        "opening" to Regex("opening\\s+balance|beginning\\s+balance|(?:الرصيد|رصيد)\\s*(?:الافتتاحي|افتتاحي|الافتتاح|افتتاح)") ,
        "closing" to Regex("closing\\s+balance|ending\\s+balance|(?:الرصيد|رصيد)\\s*(?:الختامي|ختامي|الختام|ختام)") ,
        "debit" to Regex("total\\s+(?:debits?|withdrawals?|payments?)(?:\\s+amount)?|(?:اجمالي|مجموع)\\s*(?:المدين|المسحوبات|السحب|المدفوعات)") ,
        "credit" to Regex("total\\s+(?:credits?|deposits?|receipts?)(?:\\s+amount)?|(?:اجمالي|مجموع)\\s*(?:الدائن|الايداعات|الايداع|المقبوضات)")
    )
    fun readSummary(document:StatementDocument):StatementSummary {
        val readings=mutableMapOf<String,MutableList<Long>>()
        val warnings=mutableListOf<String>();var weak=false
        document.pages.forEach { page ->
            val lines=mutableListOf<MutableList<StatementWord>>()
            page.words.sortedWith(compareBy<StatementWord>{it.y}.thenBy{it.x}).forEach { word ->
                val previous=lines.lastOrNull()
                if(previous!=null && abs(previous.map{it.y}.average()-word.y)<=maxOf(2.5,minOf(word.height,previous.map{it.height}.average())*0.45)) previous+=word
                else lines+=mutableListOf(word)
            }
            lines.forEach line@{ words ->
                val text=BankKnowledge.normalize(words.sortedBy{it.x}.joinToString(" "){it.text})
                // A transaction narration or account number must never become a boundary balance.
                if(Regex("[0-9]{1,4}[/.-][0-9]{1,2}[/.-][0-9]{1,4}").containsMatchIn(text)) return@line
                labels.forEach { (kind,pattern) ->
                    if(!pattern.containsMatchIn(text)) return@forEach
                    val remainder=pattern.replace(text,"").trim(' ',':','：','=','|','؛')
                    if(remainder.none{it.isDigit()}) return@forEach // a column title, not a balance
                    val value=StatementImport.auditAmount(remainder)
                    if(value==null) warnings+="تعذر قراءة أحد الأرصدة أو الإجماليات المطبوعة؛ راجع الكشف"
                    else {
                        readings.getOrPut(kind){mutableListOf()}+=if(kind in listOf("debit","credit")) abs(value) else value
                        if(page.isOcr || words.any{it.confidence<0.85}) weak=true
                    }
                }
            }
        }
        fun single(key:String):Long? {
            val values=readings[key].orEmpty().distinct()
            if(values.size>1) warnings+="توجد أرصدة أو إجماليات متعارضة أو خاصة بصفحات مختلفة؛ تعذرت مطابقة الكشف كاملًا"
            return values.singleOrNull()
        }
        val opening=single("opening");val closing=single("closing");val debit=single("debit");val credit=single("credit")
        if(weak) warnings+="الأرصدة أو الإجماليات مستخرجة من صورة؛ أكّدها بالرجوع إلى الأصل"
        return StatementSummary(opening,closing,debit,credit,weak || warnings.isNotEmpty(),warnings.distinct())
    }
    fun evaluate(summary:StatementSummary,rows:List<StatementRow>):StatementAudit {
        require(listOfNotNull(summary.openingBaisa,summary.closingBaisa,summary.debitTotalBaisa,summary.creditTotalBaisa).all{it in -StatementImport.MAX_AMOUNT..StatementImport.MAX_AMOUNT})
        require(summary.warnings.size<=20 && summary.warnings.all{it.length<=500})
        val outgoing=rows.filter{it.direction=="out"}.sumOf{it.amountBaisa?.takeIf{amount->amount in 0..StatementImport.MAX_AMOUNT} ?: 0}
        val incoming=rows.filter{it.direction=="in"}.sumOf{it.amountBaisa?.takeIf{amount->amount in 0..StatementImport.MAX_AMOUNT} ?: 0}
        val incomplete=rows.count{it.amountBaisa==null || it.direction !in listOf("in","out") || !it.canImport}
        val warnings=summary.warnings.toMutableList();var checks=0;var mismatch=false
        val calculated=summary.openingBaisa?.let{it+incoming-outgoing}
        val difference=if(calculated!=null && summary.closingBaisa!=null) calculated-summary.closingBaisa else null
        fun compare(actual:Long,expected:Long,message:String) {checks++;if(actual!=expected){mismatch=true;warnings+=message}}
        if(difference!=null) compare(difference,0,"الرصيد المحسوب لا يطابق الختامي؛ فرق ${Money.format(abs(difference))} ر.ع")
        summary.debitTotalBaisa?.let{compare(outgoing,it,"مجموع المبالغ الخارجة لا يطابق إجمالي المدين المطبوع")}
        summary.creditTotalBaisa?.let{compare(incoming,it,"مجموع المبالغ الداخلة لا يطابق إجمالي الدائن المطبوع")}
        val order=rows.zipWithNext().mapNotNull{(a,b)->
            if(a.occurredAtMillis==null || b.occurredAtMillis==null || a.occurredAtMillis==b.occurredAtMillis)null
            else if(a.occurredAtMillis<b.occurredAtMillis)1 else -1
        }.distinct().singleOrNull()
        if(order!=null) rows.zipWithNext().forEach { (a,b) ->
            val target=if(order==1)b else a
            if(a.balanceBaisa!=null && b.balanceBaisa!=null && target.amountBaisa!=null && target.direction in listOf("in","out")) {
                val delta=if(order==1)b.balanceBaisa-a.balanceBaisa else a.balanceBaisa-b.balanceBaisa
                compare(delta,if(target.direction=="in")target.amountBaisa else -target.amountBaisa,"فرق الرصيد عند العملية ${target.id} لا يطابق المبلغ والاتجاه")
            }
        }
        if(incomplete>0) warnings+="توجد $incomplete عملية غير مكتملة؛ المطابقة تستخدم القيم المقروءة فقط"
        val status=when{mismatch->"mismatch";incomplete>0->"incomplete";checks==0->"unavailable";else->"matched"}
        return StatementAudit(status,outgoing,incoming,incomplete,calculated,difference,checks,
            mismatch || summary.needsReview || incomplete>0 && checks>0,warnings.distinct().take(20))
    }
    fun refresh(preview:StatementPreview)=preview.copy(audit=evaluate(preview.summary,preview.rows),auditAcknowledged=false)
}
