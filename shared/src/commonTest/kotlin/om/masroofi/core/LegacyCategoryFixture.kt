package om.masroofi.core

import kotlinx.serialization.encodeToString

/** Parser/accounting regression fixtures explicitly supply the former category catalog. */
internal fun legacyCategoryLedger() = LedgerEngine(ledgerJson.encodeToString(LedgerState(categories = defaultCategories)))
