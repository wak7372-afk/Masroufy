package om.masroofi.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class Category(val id: String, val name: String, val symbol: String)
val defaultCategories = listOf(
    Category("fuel", "وقود", "⛽"), Category("groceries", "بقالة", "🛒"),
    Category("restaurants", "مطاعم", "☕"), Category("telecom", "اتصالات", "📱"),
    Category("utilities", "خدمات", "💡"), Category("health", "صحة", "♡"),
    Category("transport", "نقل", "↗"), Category("shopping", "تسوق", "🛍"),
    Category("subscriptions", "اشتراكات", "↻"), Category("installments", "أقساط", "◷"),
    Category("fees", "رسوم", "%"), Category("cash", "نقد", "◈"), Category("other", "أخرى", "•••")
)

@Serializable data class Transaction(
    val id: String,
    val amountMinor: Long?,
    val currency: String = "OMR",
    val accountingBaisa: Long? = null,
    val direction: String? = null,
    val effect: String = "unresolved",
    val type: String = "other",
    val occurredAtMillis: Long,
    val receivedAtMillis: Long,
    val datePrecision: String = "received",
    val counterparty: String? = null,
    val merchant: String? = null,
    val categoryId: String? = null,
    val categoryStatus: String = "pending",
    val categoryConfidence: Double = 0.0,
    val financialVerified: Boolean = false,
    val reviewed: Boolean = false,
    val note: String = "",
    val reasonSource: String = "unknown",
    val balanceMinor: Long? = null,
    val accountHint: String? = null,
    val reference: String? = null,
    val source: String = "manual",
    val sourceId: String,
    val duplicateOf: String? = null,
    val originalId: String? = null,
    val warnings: List<String> = emptyList(),
    val isDemo: Boolean = false,
    val contentToken: String? = null,
    val bankName: String = "غير محدد",
    val matchTransfers: Boolean = true,
    val transferPairId: String? = null,
    val transferOrigin: TransferOrigin? = null
)
@Serializable data class TransferOrigin(val effect: String, val financialVerified: Boolean, val reviewed: Boolean, val warnings: List<String>)
@Serializable data class KeywordRule(val keyword: String, val categoryId: String? = null, val bankName: String? = null)
@Serializable data class Budget(val categoryId: String, val weeklyBaisa: Long? = null, val monthlyBaisa: Long? = null)
@Serializable data class MerchantRule(val merchant: String, val categoryId: String)
@Serializable data class Preferences(
    val weekStart: Int = 6,
    val monthStartDay: Int = 1,
    val timeZone: String = "Asia/Muscat",
    val reminderHour: Int = 20,
    val remindersEnabled: Boolean = true,
    val showAmountsOnLockScreen: Boolean = false
)
@Serializable data class LedgerState(
    val schemaVersion: Int = 1,
    val sequence: Long = 1,
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val rules: List<MerchantRule> = emptyList(),
    val preferences: Preferences = Preferences(),
    val deliveredAlerts: List<String> = emptyList(),
    val pendingAlerts: List<BudgetAlert> = emptyList(),
    val pendingTransactionNotices: List<String> = emptyList(),
    val ownedBanks: List<String> = emptyList(),
    val keywordRules: List<KeywordRule> = emptyList()
)
@Serializable data class ImportResult(
    val ids: List<String> = emptyList(),
    val ignored: Int = 0,
    val duplicates: Int = 0,
    val reasons: List<String> = emptyList()
)
@Serializable data class BudgetLine(
    val categoryId: String,
    val name: String,
    val symbol: String,
    val weeklySpent: Long,
    val monthlySpent: Long,
    val weeklyLimit: Long?,
    val monthlyLimit: Long?
)
@Serializable data class Report(
    val grossExpense: Long,
    val refunds: Long,
    val netExpense: Long,
    val incoming: Long,
    val unclassified: Long,
    val reviewCount: Int,
    val excludedCount: Int,
    val monthStartMillis: Long,
    val monthEndMillis: Long,
    val weekStartMillis: Long,
    val weekEndMillis: Long,
    val categories: List<BudgetLine>
)
@Serializable data class BudgetAlert(val key: String, val title: String, val body: String)
@Serializable data class EditCommand(
    val id: String? = null,
    val amountBaisa: Long,
    val direction: String,
    val effect: String,
    val categoryId: String? = null,
    val counterparty: String? = null,
    val note: String = "",
    val occurredAtMillis: Long,
    val rememberMerchant: Boolean = false,
    val originalId: String? = null,
    val bankName: String? = null,
    val ownsBank: Boolean? = null,
    val learnKeyword: String? = null,
    val learnBankAlias: String? = null,
    val matchTransfers: Boolean? = null,
    val timeConfirmed: Boolean? = null
)

object Money {
    fun format(baisa: Long): String {
        val sign = if (baisa < 0) "−" else ""
        val positive = if (baisa < 0) -baisa else baisa
        return "$sign${positive / 1000}.${(positive % 1000).toString().padStart(3, '0')}"
    }
    fun input(text: String): Long? {
        val value = SmsParser.normalize(text).trim().replace(',', '.')
        if (!Regex("[0-9]+(?:\\.[0-9]{1,3})?").matches(value)) return null
        val parts = value.split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        if (whole > 9_000_000_000L) return null
        return whole * 1000 + (parts.getOrNull(1) ?: "").padEnd(3, '0').toLong()
    }
}
