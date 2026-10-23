package om.masroofi.core

import kotlinx.serialization.Serializable
import kotlin.math.exp

/** Small, locally trained header classifier. It cannot generate or modify numbers. */
internal object LocalColumnModel {
    @Serializable private data class Weights(val v: Int, val features: List<String>, val weights: Map<String, List<Double>>)
    private val model by lazy { ledgerJson.decodeFromString<Weights>(LOCAL_COLUMN_WEIGHTS).also {
        require(it.v == 1 && it.features.size <= 5000 && it.weights.size == 9)
        require(it.weights.values.all { weights -> weights.size == it.features.size && weights.all(Double::isFinite) })
    } }
    private val indices by lazy { model.features.withIndex().associate { it.value to it.index } }
    fun role(text: String): String? {
        if (text.length !in 4..80 || text.any(Char::isDigit)) return null
        val normalized = StatementImport.normalized(text).lowercase().replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه').replace(Regex("[\u064b-\u065f\u0670ـ]"), "")
        val value = "^" + normalized.replace(Regex("[^a-zء-ي ]"), "") + "$"
        val features = (2..3).flatMap { n -> (0..(value.length - n).coerceAtLeast(-1)).map { value.substring(it, it + n) } }.toSet()
        if (features.isEmpty()) return null
        val positions = features.mapNotNull { indices[it] }
        // Unknown vocabulary is deliberately rejected rather than guessed.
        if (positions.size.toDouble() / features.size < 0.75) return null
        val scores = model.weights.map { (role, weights) -> role to positions.sumOf { weights[it] } }.sortedByDescending { it.second }
        val top = scores.first()
        val probability = 1.0 / scores.sumOf { exp(it.second - top.second) }
        return top.first.takeIf { it != "OTHER" && probability >= 0.995 && top.second - scores[1].second >= 6.0 }
    }
}
