package om.masroofi.core

/** Local, editable keyword knowledge. A bank word never determines an amount or direction. */
object BankKnowledge {
    const val UNKNOWN="غير محدد"
    val names=listOf("بنك ميثاق","بنك نزوى","بنك صحار الإسلامي","بنك صحار الدولي","بنك مسقط","بنك ظفار","البنك الوطني العماني","البنك الأهلي","بنك العز الإسلامي","بنك عمان العربي")
    private val aliases=listOf(
        names[0] to "(?<![a-z])mthq[a-z0-9]*\\b|\\b(?:meethaq|mithaq)\\b|ميثاق",
        names[1] to "\\b(?:nizwa|nizwabank|banknizwa)\\b|نزوي|نزوى",
        names[2] to "\\bsohar\\s*(?:al\\s*)?islamic\\b|صحار\\s*(?:الاسلامي|اسلامي)",
        names[3] to "\\bsohar\\s*international\\b|صحار\\s*الدولي",
        names[4] to "\\bbank\\s*muscat\\b|بنك\\s*مسقط",
        names[5] to "\\b(?:bank\\s*)?dhofar\\b|ظفار",
        names[6] to "\\bnbo\\b|national\\s*bank\\s*of\\s*oman|الوطني\\s*العماني",
        names[7] to "\\bahli\\s*bank\\b|البنك\\s*الاهلي",
        names[8] to "\\bal\\s*izz\\b|العز\\s*الاسلامي",
        names[9] to "\\boman\\s*arab\\s*bank\\b|عمان\\s*العربي"
    )
    private val patterns by lazy { aliases.map { it.first to Regex(it.second) } }
    private val directNames by lazy { names.associateBy(::normalize) }
    fun normalize(text:String)=StatementImport.normalized(text).lowercase().replace('أ','ا').replace('إ','ا').replace('آ','ا')
        .replace('ى','ي').replace('ة','ه').replace(Regex("[\u064b-\u065f\u0670ـ]"),"").replace(Regex("\\s+")," ").trim()
    fun contains(text:String,keyword:String):Boolean {
        val value=normalize(keyword)
        if(value.isBlank()) return false
        return Regex("(?<![a-z0-9ء-ي])"+Regex.escape(value)+"(?![a-z0-9ء-ي])").containsMatchIn(normalize(text))
    }
    fun detect(text:String,rules:List<KeywordRule> = emptyList()):String {
        val learned=matching(text,rules.filter { it.bankName!=null }).mapNotNull { it.bankName }.distinct()
        if(learned.isNotEmpty()) return learned.singleOrNull()?.let(::canonical) ?: UNKNOWN
        val clean=normalize(text)
        return patterns.filter { it.second.containsMatchIn(clean) }.map { it.first }.distinct().singleOrNull() ?: UNKNOWN
    }
    fun canonical(value:String):String {
        val text=value.trim().replace(Regex("\\s+")," ")
        if(text.isBlank() || normalize(text)==normalize(UNKNOWN)) return UNKNOWN
        val clean=normalize(text)
        directNames[clean]?.let{return it}
        val known=patterns.filter { it.second.containsMatchIn(clean) }.map { it.first }.distinct().singleOrNull()
        return known ?: text.take(80)
    }
    fun key(value:String):String?=canonical(value).takeUnless { it==UNKNOWN }?.let(::normalize)
    fun matching(text:String,rules:List<KeywordRule>):List<KeywordRule> {
        val found=rules.filter { contains(text,it.keyword) }
        val longest=found.maxOfOrNull { normalize(it.keyword).length } ?: return emptyList()
        return found.filter { normalize(it.keyword).length==longest }
    }
    fun category(text:String,rules:List<KeywordRule>):String?=matching(text,rules.filter { it.categoryId!=null }).mapNotNull { it.categoryId }.distinct().singleOrNull()
    fun validateKeyword(value:String) {
        require(value.length in 3..80 && value.count { it.isLetter() }>=3) { "الكلمة المفتاحية تحتاج ثلاثة أحرف على الأقل وحتى 80 حرفًا" }
    }
}
