package om.masroofi.core

/** Resource and structural limits also apply to authenticated, user-supplied backups. */
internal object LedgerSafety {
    const val MAX_JSON = 16_000_000
    const val MAX_CATEGORIES = 200
    fun json(text: String, limit: Int = MAX_JSON) {
        require(text.length <= limit) { "البيانات أكبر من الحد المسموح" }
        var depth = 0; var quoted = false; var escape = false
        for (char in text) {
            if (quoted) {
                if (escape) escape = false else if (char == '\\') escape = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 48) { "بنية البيانات معقدة أكثر من المسموح" } }
                '}', ']' -> { depth--; require(depth >= 0) { "بنية البيانات غير صالحة" } }
            }
        }
        require(!quoted && depth == 0) { "البيانات غير مكتملة" }
    }
    fun visibleLabel(text: String, max: Int): Boolean = text.length in 1..max && text.isNotBlank() &&
        text.none { it.code < 32 || it.code == 127 || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    fun categoryName(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        require(visibleLabel(clean, 40)) { "اسم الفئة مطلوب وبحد أقصى 40 حرفًا دون محارف تحكم" }
        return clean
    }
}
