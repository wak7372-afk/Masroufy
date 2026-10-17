package om.masroofi.core

import kotlinx.datetime.*

data class Period(val start: Long, val end: Long)
object BudgetEngine {
    private fun zone(preferences: Preferences) = TimeZone.of(preferences.timeZone)
    fun week(now: Long, preferences: Preferences): Period {
        val tz = zone(preferences)
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        val offset = (date.dayOfWeek.ordinal + 1 - preferences.weekStart + 7) % 7
        val start = date.minus(offset, DateTimeUnit.DAY)
        return Period(start.atStartOfDayIn(tz).toEpochMilliseconds(), start.plus(7, DateTimeUnit.DAY).atStartOfDayIn(tz).toEpochMilliseconds())
    }
    fun month(now: Long, preferences: Preferences): Period {
        val tz = zone(preferences)
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        fun boundary(year: Int, month: Int): LocalDate {
            val first = LocalDate(year, month, 1)
            val last = first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
            return LocalDate(year, month, minOf(preferences.monthStartDay, last.dayOfMonth))
        }
        val thisStart = boundary(date.year, date.monthNumber)
        val start = if (date >= thisStart) thisStart else {
            val previous = LocalDate(date.year, date.monthNumber, 1).minus(1, DateTimeUnit.MONTH)
            boundary(previous.year, previous.monthNumber)
        }
        val next = LocalDate(start.year, start.monthNumber, 1).plus(1, DateTimeUnit.MONTH)
        val end = boundary(next.year, next.monthNumber)
        return Period(start.atStartOfDayIn(tz).toEpochMilliseconds(), end.atStartOfDayIn(tz).toEpochMilliseconds())
    }
    fun eligible(txn: Transaction) = txn.financialVerified && txn.duplicateOf == null && txn.accountingBaisa != null
    fun signedExpense(txn: Transaction): Long = when (txn.effect) { "expense" -> txn.accountingBaisa ?: 0; "refund" -> -(txn.accountingBaisa ?: 0); else -> 0 }
    fun report(state: LedgerState, now: Long): Report {
        val month = month(now, state.preferences)
        val week = week(now, state.preferences)
        val eligible = state.transactions.filter(::eligible)
        val monthly = eligible.filter { it.occurredAtMillis >= month.start && it.occurredAtMillis < month.end }
        val weekly = eligible.filter { it.occurredAtMillis >= week.start && it.occurredAtMillis < week.end }
        val gross = monthly.filter { it.effect == "expense" }.sumOf { it.accountingBaisa ?: 0 }
        val refunds = monthly.filter { it.effect == "refund" }.sumOf { it.accountingBaisa ?: 0 }
        return Report(gross, refunds, gross - refunds,
            monthly.filter { it.effect == "income" }.sumOf { it.accountingBaisa ?: 0 },
            monthly.filter { it.categoryId == null }.sumOf(::signedExpense),
            state.transactions.count { !it.reviewed }, state.transactions.count { !eligible(it) && it.effect != "internal" },
            month.start, month.end, week.start, week.end,
            state.categories.map { category ->
                val budget = state.budgets.find { it.categoryId == category.id }
                BudgetLine(category.id, category.name, category.symbol,
                    weekly.filter { it.categoryId == category.id }.sumOf(::signedExpense),
                    monthly.filter { it.categoryId == category.id }.sumOf(::signedExpense), budget?.weeklyBaisa, budget?.monthlyBaisa)
            })
    }
}
