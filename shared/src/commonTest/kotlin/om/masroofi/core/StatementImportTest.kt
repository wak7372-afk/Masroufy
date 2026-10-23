package om.masroofi.core

import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class StatementImportTest {
    private val now = Instant.parse("2026-10-17T08:00:00Z").toEpochMilliseconds()
    private val hash = "a".repeat(64)
    private fun word(text: String, x: Double, y: Double, width: Double = 44.0) = StatementWord(text, x, y, width, 10.0)
    private fun line(y: Double, date: String = "13/10/2026", description: String = "OMAN OIL", debit: String = "3.000", credit: String = "", balance: String = "97.000"): List<StatementWord> = buildList {
        add(word(date, 20.0, y, 75.0)); add(word(description, 140.0, y, 115.0))
        if (debit.isNotEmpty()) add(word(debit, 320.0, y))
        if (credit.isNotEmpty()) add(word(credit, 415.0, y))
        add(word(balance, 510.0, y))
    }
    private fun header(y: Double = 40.0): List<StatementWord> = listOf(word("Date", 20.0, y), word("Description", 140.0, y, 100.0), word("Debit", 320.0, y), word("Credit", 415.0, y), word("Balance", 510.0, y, 60.0))
    private fun page(rows: List<List<StatementWord>>, currency: String? = "OMR", includeHeader: Boolean = true): StatementPage = StatementPage(600.0, 800.0,
        (currency?.let { listOf(word(it, 20.0, 10.0)) } ?: emptyList()) + (if (includeHeader) header() else emptyList()) + rows.flatten())
    private fun preview(engine: LedgerEngine = LedgerEngine(), pages: List<StatementPage>, id: String = hash): StatementPreview =
        ledgerJson.decodeFromString(engine.previewStatement(ledgerJson.encodeToString(StatementDocument(pages)), id, now))
    private fun imported(engine: LedgerEngine, preview: StatementPreview, ids: List<String> = preview.rows.filter { it.canImport }.map { it.id }, confirmed: Boolean = true): ImportResult =
        ledgerJson.decodeFromString(engine.importStatement(ledgerJson.encodeToString(preview), ledgerJson.encodeToString(ids), now, confirmed))

    @Test fun soharStyleColumnsSeparateChequeValueDateAndClosingBalance() {
        // Synthetic amounts and identifiers; headings follow the supplied template.
        val words = listOf(
            word("Account Currency OMR", 20.0, 10.0, 180.0),
            word("Opening Balance", 20.0, 30.0, 110.0), word("100.000", 145.0, 30.0),
            word("Closing Balance", 20.0, 50.0, 110.0), word("102.000", 145.0, 50.0),
            word("From Date", 20.0, 70.0, 70.0), word("01/10/2026", 105.0, 70.0, 75.0),
            word("To Date", 20.0, 90.0, 70.0), word("31/10/2026", 105.0, 90.0, 75.0),
            word("Transaction Date", 20.0, 130.0, 85.0), word("Description", 125.0, 130.0, 110.0),
            word("Cheque No", 260.0, 130.0, 50.0), word("Value Date", 325.0, 130.0, 75.0),
            word("Debit", 420.0, 130.0, 35.0), word("Credit", 470.0, 130.0, 35.0), word("Closing Balance", 520.0, 130.0, 70.0),
            word("13/10/2026", 20.0, 170.0, 75.0), word("OMAN OIL", 125.0, 170.0, 110.0),
            word("123456", 260.0, 170.0, 50.0), word("15/10/2026", 325.0, 170.0, 75.0),
            word("3.000", 420.0, 170.0, 35.0), word("97.000", 520.0, 170.0, 70.0),
            word("14/10/2026", 20.0, 200.0, 75.0), word("Salary", 125.0, 200.0, 110.0),
            word("16/10/2026", 325.0, 200.0, 75.0), word("5.000", 470.0, 200.0, 35.0), word("102.000", 520.0, 200.0, 70.0)
        )
        val engine = LedgerEngine()
        val p = preview(engine, listOf(StatementPage(600.0, 800.0, words)))
        assertEquals(2, p.rows.size)
        assertEquals(listOf("13/10/2026", "14/10/2026"), p.rows.map { it.dateText })
        assertEquals(listOf(3000L, 5000L), p.rows.map { it.amountBaisa })
        assertEquals(listOf("out", "in"), p.rows.map { it.direction })
        assertTrue(p.rows.all { it.canImport })
        assertEquals(2, imported(engine, p).ids.size)
        val report = BudgetEngine.report(engine.state, now)
        assertEquals(3000L, report.grossExpense)
        assertEquals(5000L, report.incoming)
    }

    @Test fun individualFromAndToDateMetadataNeverBecomeTransactionRows() {
        val words = listOf(word("From Date: 01/10/2026", 20.0, 20.0, 220.0), word("To Date: 31/10/2026", 20.0, 40.0, 220.0))
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertTrue(p.rows.isEmpty())
        assertEquals(2, p.skippedRows)
    }

    @Test fun geometrySeparatesBlankDebitAndCreditFromRunningBalance() {
        val engine = LedgerEngine()
        val preview = preview(engine, listOf(page(listOf(line(80.0), line(110.0, description = "Salary", debit = "", credit = "250.000", balance = "347.000")))))
        assertEquals(listOf(3000L, 250000L), preview.rows.map { it.amountBaisa })
        assertEquals(listOf("out", "in"), preview.rows.map { it.direction })
        assertTrue(preview.rows.all { it.canImport })
        assertEquals("fuel", preview.rows.first().categoryId)
        assertEquals(2, imported(engine, preview).ids.size)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).grossExpense)
        assertEquals(250000L, BudgetEngine.report(engine.state, now).incoming)
        assertTrue(engine.state.transactions.all { it.source == "statement" && it.datePrecision == "day" && it.balanceMinor == null })
        assertTrue(engine.state.pendingTransactionNotices.isEmpty())
    }
    @Test fun arabicRtlColumnOrderAndDigitsAreIndependentOfVisualDirection() {
        val words = listOf(word("ر.ع", 20.0, 10.0), word("الرصيد", 20.0, 40.0), word("دائن", 140.0, 40.0), word("مدين", 240.0, 40.0), word("الوصف", 360.0, 40.0), word("التاريخ", 500.0, 40.0)) +
            listOf(word("٩٧٫٠٠٠", 20.0, 80.0), word("٣٫٠٠٠", 240.0, 80.0), word("عمانتل", 360.0, 80.0, 80.0), word("١٣/١٠/٢٠٢٦", 500.0, 80.0, 75.0)) +
            listOf(word("١٠٠٫٠٠٠", 20.0, 110.0), word("٣٫٠٠٠", 140.0, 110.0), word("ايداع", 360.0, 110.0, 80.0), word("٢٠٢٦/١٠/١٤", 500.0, 110.0, 75.0))
        val preview = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertEquals("OMR", preview.currency)
        assertEquals(listOf("out", "in"), preview.rows.map { it.direction })
        assertEquals(listOf(3000L, 3000L), preview.rows.map { it.amountBaisa })
        assertEquals("telecom", preview.rows[0].categoryId)
    }
    @Test fun openingClosingTotalsAndFooterNeverBecomeTransactions() {
        val preview = preview(pages = listOf(page(listOf(
            line(60.0, description = "Opening Balance", debit = "", balance = "100.000"), line(90.0),
            line(120.0, description = "Closing Balance", debit = "", balance = "97.000"),
            listOf(word("Total Debits", 140.0, 150.0, 110.0), word("3.000", 320.0, 150.0)),
            listOf(word("Page 1 of 1", 140.0, 180.0, 100.0))
        ))))
        assertEquals(1, preview.rows.size)
        assertEquals(3, preview.skippedRows)
        assertEquals(3000L, preview.rows.single().amountBaisa)
    }
    @Test fun openingAndCurrentWordsInRealFeesAreNotSummaryRows() {
        val p = preview(pages = listOf(page(listOf(line(80.0, description = "Current Account Charges"), line(110.0, description = "Account opening fee"), line(140.0, description = "TOTAL FUEL")))))
        assertEquals(3, p.rows.size)
        assertTrue(p.rows.all { it.canImport })
        assertTrue(p.rows.take(2).all { it.categoryId == "fees" })
        assertEquals(0, p.skippedRows)
    }
    @Test fun explicitTransactionDateWinsOverValueDateEvenWhenValueDateIsFirst() {
        val words = listOf(word("OMR", 20.0, 10.0), word("Value Date", 20.0, 40.0, 75.0), word("Transaction Date", 110.0, 40.0, 85.0), word("Description", 220.0, 40.0, 80.0), word("Debit", 350.0, 40.0), word("Credit", 430.0, 40.0), word("Balance", 510.0, 40.0)) +
            listOf(word("15/10/2026", 20.0, 80.0, 75.0), word("13/10/2026", 110.0, 80.0, 75.0), word("OMANTEL", 220.0, 80.0, 80.0), word("3.000", 350.0, 80.0), word("97.000", 510.0, 80.0))
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertEquals("13/10/2026", p.rows.single().dateText)
        assertEquals(Instant.parse("2026-10-12T20:00:00Z").toEpochMilliseconds(), p.rows.single().occurredAtMillis)
        assertTrue(p.rows.single().canImport)
    }
    @Test fun arabicValueDateIsIgnoredWhenSeparateTransactionDateExists() {
        val words = listOf(word("ر.ع", 20.0, 10.0), word("تاريخ القيمة", 20.0, 40.0, 75.0), word("تاريخ العملية", 110.0, 40.0, 85.0), word("الوصف", 220.0, 40.0, 80.0), word("مدين", 350.0, 40.0), word("دائن", 430.0, 40.0), word("الرصيد", 510.0, 40.0)) +
            listOf(word("15/10/2026", 20.0, 80.0, 75.0), word("13/10/2026", 110.0, 80.0, 75.0), word("عمانتل", 220.0, 80.0, 80.0), word("3.000", 350.0, 80.0), word("97.000", 510.0, 80.0))
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertEquals("13/10/2026", p.rows.single().dateText)
        assertTrue(p.rows.single().canImport)
    }
    @Test fun repeatedPageHeadersAndWrappedDescriptionRemainDistinctRows() {
        val p1 = page(listOf(line(80.0, description = "CARD PURCHASE"), listOf(word("OMANTEL", 140.0, 95.0, 100.0))))
        val p2 = page(listOf(line(80.0, date = "14-Oct-2026", description = "NESTO", debit = "4.500")))
        val preview = preview(pages = listOf(p1, p2))
        assertEquals(2, preview.rows.size)
        assertTrue(preview.rows.first().description.contains("OMANTEL"))
        assertEquals(listOf("telecom", "groceries"), preview.rows.map { it.categoryId })
        assertEquals(listOf(1, 2), preview.rows.map { it.page })
        assertNotEquals(preview.rows[0].id, preview.rows[1].id)
        assertTrue(preview.rows.all { it.canImport })
    }
    @Test fun missingInvalidAndUnsupportedDatesCannotUseReceivedTime() {
        val preview = preview(pages = listOf(page(listOf(
            line(80.0, date = "31/02/2026"), line(110.0, date = "10/14/2026"), line(140.0, date = "13/10/26"), line(170.0, date = "", description = "OMANTEL")
        ))))
        assertEquals(4, preview.rows.size)
        assertTrue(preview.rows.none { it.canImport })
        assertTrue(preview.rows.all { it.occurredAtMillis == null })
    }
    @Test fun thousandsBaisaLimitsAndNegativeDebitAreNotSilentlyCoerced() {
        val preview = preview(pages = listOf(page(listOf(
            line(80.0, debit = "1,250.500"), line(110.0, debit = "١٬٢٥٠٫٥٠٠"),
            line(140.0, debit = "-3.000"), line(170.0, debit = "3.000", credit = "2.000"),
            line(200.0, debit = "1.0000"), line(230.0, debit = "9,000,000,001.000"), line(260.0, debit = "3,000"), line(290.0, debit = "٣٬٠٠٠")
        ))))
        assertEquals(listOf(1250500L, 1250500L), preview.rows.take(2).map { it.amountBaisa })
        assertTrue(preview.rows.take(2).all { it.canImport })
        assertTrue(preview.rows.drop(2).none { it.canImport })
    }
    @Test fun longDescriptionUsesCellWidthRatherThanMidpointBetweenTitles() {
        val words = listOf(word("OMR", 20.0, 10.0), word("Date", 20.0, 40.0), word("Description", 140.0, 40.0, 90.0), word("Debit", 420.0, 40.0), word("Credit", 500.0, 40.0), word("Balance", 570.0, 40.0, 60.0)) +
            listOf(word("13/10/2026", 20.0, 80.0, 75.0), word("CARD", 140.0, 80.0), word("PURCHASE", 205.0, 80.0, 70.0), word("OMANTEL", 320.0, 80.0, 75.0), word("3.000", 430.0, 80.0), word("97.000", 580.0, 80.0))
        val p = preview(pages = listOf(StatementPage(700.0, 800.0, words)))
        assertTrue(p.rows.single().canImport)
        assertEquals(3000L, p.rows.single().amountBaisa)
        assertEquals("out", p.rows.single().direction)
        assertEquals("telecom", p.rows.single().categoryId)
        assertTrue(p.rows.single().description.contains("OMANTEL"))
    }
    private fun signedPage(amounts: List<Pair<String, String>>): StatementPage {
        val h = listOf(word("Date", 20.0, 40.0), word("Description", 140.0, 40.0, 100.0), word("Amount", 320.0, 40.0), word("DR/CR", 420.0, 40.0), word("Balance", 510.0, 40.0))
        val rows = amounts.flatMapIndexed { index, (amount, marker) -> listOf(word("13/10/2026", 20.0, 80.0 + index * 30, 75.0), word("OMANTEL", 140.0, 80.0 + index * 30, 100.0), word(amount, 320.0, 80.0 + index * 30), word(marker, 420.0, 80.0 + index * 30), word("100.000", 510.0, 80.0 + index * 30)) }
        return StatementPage(600.0, 800.0, listOf(word("OMR", 20.0, 10.0)) + h + rows)
    }
    @Test fun signedAmountAndDrCrMarkersMustAgreeAndUnsignedAmountNeedsDirection() {
        val preview = preview(pages = listOf(signedPage(listOf("(3.000)" to "DR", "+4.000" to "CR", "5.000" to "DR", "-6.000" to "CR", "7.000" to ""))))
        assertEquals(listOf("out", "in", "out"), preview.rows.take(3).map { it.direction })
        assertTrue(preview.rows.take(3).all { it.canImport })
        assertTrue(preview.rows.drop(3).none { it.canImport })
    }
    @Test fun foreignAndMixedCurrencyAlwaysBlockAndUnknownRequiresConfirmation() {
        listOf("USD", "MIXED").forEach { currency ->
            val engine = LedgerEngine()
            val p = if (currency == "MIXED") preview(engine, listOf(page(listOf(line(80.0))), page(listOf(line(80.0)), currency = "USD"))) else preview(engine, listOf(page(listOf(line(80.0)), currency = currency)))
            assertEquals(currency, p.currency)
            assertFailsWith<IllegalArgumentException> { imported(engine, p) }
            assertTrue(engine.state.transactions.isEmpty())
        }
        listOf("OMR", null).forEach { currency ->
            val engine = LedgerEngine(); val p = preview(engine, listOf(page(listOf(line(80.0)), currency = currency)))
            assertEquals(currency ?: "UNKNOWN", p.currency)
            assertFailsWith<IllegalArgumentException> { imported(engine, p, confirmed = false) }
            assertEquals(1, imported(engine, p).ids.size)
        }
    }
    @Test fun fileReplayIsIdempotentButIdenticalPurchasesInsideOneFileRemainSeparate() {
        val engine = LedgerEngine(); val document = listOf(page(listOf(line(80.0), line(110.0))))
        val p = preview(engine, document)
        assertEquals(2, imported(engine, p).ids.size)
        assertEquals(6000L, BudgetEngine.report(engine.state, now).grossExpense)
        assertTrue(engine.state.transactions.all { it.duplicateOf == null })
        val replay = preview(engine, document)
        assertTrue(replay.rows.all { it.duplicateOf != null })
        assertEquals(2, imported(engine, replay).duplicates)
        assertEquals(2, engine.state.transactions.size)
    }
    @Test fun smsAndOtherStatementCandidatesAreRetainedForReviewInsteadOfDoubleCounting() {
        val engine = LedgerEngine()
        engine.importMessage("Purchase OMR 3.000 at OMAN OIL on 13/10/2026 15:45:00", now, "sms", "bank-event")
        val p = preview(engine, listOf(page(listOf(line(80.0)))))
        assertNotNull(p.rows.single().duplicateOf)
        imported(engine, p.copy(rows = p.rows.map { it.copy(duplicateOf = null) }))
        assertEquals(2, engine.state.transactions.size)
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertFalse(engine.state.transactions.first().reviewed)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).grossExpense)
        val other = preview(engine, listOf(page(listOf(line(80.0)))), "b".repeat(64))
        assertNotNull(other.rows.single().duplicateOf)
    }
    @Test fun validTransfersRemainFinanciallyUnresolvedUntilUserReview() {
        val engine = LedgerEngine(); val p = preview(engine, listOf(page(listOf(line(80.0, description = "Transfer to MY ACCOUNT")))))
        assertTrue(p.rows.single().canImport)
        assertEquals("unresolved", p.rows.single().effect)
        imported(engine, p)
        assertFalse(engine.state.transactions.single().financialVerified)
        assertEquals(0L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun failedBatchIsAtomicAndRevalidatesRowsBeyondUiFlags() {
        val engine = LedgerEngine(); engine.seedDemo(now)
        val p = preview(engine, listOf(page(listOf(line(80.0), line(110.0)))))
        val initial = engine.stateJson()
        val changes = listOf<(StatementRow) -> StatementRow>(
            { it.copy(amountBaisa = -1) }, { it.copy(amountBaisa = StatementImport.MAX_AMOUNT + 1) },
            { it.copy(occurredAtMillis = now) }, { it.copy(dateText = "31/02/2026") },
            { it.copy(direction = "sideways") }, { it.copy(effect = "income") }, { it.copy(description = "") }, { it.copy(id = "../../row") }
        )
        changes.forEach { change ->
            val tampered = p.copy(rows = listOf(p.rows[0], change(p.rows[1]).copy(canImport = true)))
            assertFailsWith<IllegalArgumentException> { imported(engine, tampered, tampered.rows.map { it.id }) }
            assertEquals(initial, engine.stateJson())
        }
        assertFailsWith<IllegalStateException> { imported(engine, p, listOf(p.rows[0].id, "missing")) }
        assertEquals(initial, engine.stateJson())
        assertEquals(2, imported(engine, p).ids.size)
        assertTrue(engine.state.transactions.none { it.isDemo })
    }
    @Test fun emptySelectionPreservesDemoAndSensitiveDocumentTextIsNotPersisted() {
        val engine = LedgerEngine(); engine.seedDemo(now)
        val p = preview(engine, listOf(page(listOf(line(80.0))).copy(words = page(listOf(line(80.0))).words + word("ACCOUNT SECRET 123456789", 20.0, 760.0, 250.0))))
        assertTrue(imported(engine, p, emptyList()).ids.isEmpty())
        assertTrue(engine.state.transactions.any { it.isDemo })
        imported(engine, p)
        assertFalse(engine.stateJson().contains("ACCOUNT SECRET"))
        assertFalse(engine.stateJson().contains("StatementWord"))
    }
    @Test fun boundsRejectMalformedGeometryExcessPagesAndInvalidDocumentId() {
        val invalid = page(listOf(line(80.0))).copy(words = listOf(word("x", Double.NaN, 40.0)))
        assertFailsWith<IllegalArgumentException> { preview(pages = listOf(invalid)) }
        assertFailsWith<IllegalArgumentException> { preview(pages = List(51) { page(emptyList()) }) }
        assertFailsWith<IllegalArgumentException> { preview(pages = listOf(page(emptyList())), id = "not-a-sha256") }
    }
    @Test fun noHeaderAndBalanceOnlyCannotBeImportedAsExpenses() {
        val noHeader = preview(pages = listOf(page(listOf(line(80.0)), includeHeader = false)))
        assertTrue(noHeader.rows.none { it.canImport })
        val balanceOnly = preview(pages = listOf(page(listOf(line(80.0, debit = "")))))
        assertTrue(balanceOnly.rows.none { it.canImport })
        assertNull(balanceOnly.rows.single().amountBaisa)
    }
    @Test fun closeTwoLineDateColumnsUseTheirOwnModifiersInsteadOfMergingDates() {
        val words = listOf(word("OMR", 20.0, 10.0)) + listOf(
            word("Date", 20.0, 40.0), word("Date", 85.0, 40.0), word("Transaction Details", 170.0, 40.0, 130.0),
            word("Debit", 350.0, 40.0), word("Credit", 430.0, 40.0), word("Balance", 510.0, 40.0),
            word("Value", 20.0, 52.0), word("Transaction", 85.0, 52.0, 70.0),
            word("13/09/2026", 20.0, 90.0, 55.0), word("11/09/2026", 85.0, 90.0, 55.0),
            word("POS-DEMO-001", 170.0, 90.0, 140.0), word("1.200", 350.0, 90.0), word("98.800", 510.0, 90.0)
        )
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertEquals(1, p.rows.size)
        assertEquals("11/09/2026", p.rows.single().dateText)
        assertEquals(1200L, p.rows.single().amountBaisa)
        assertEquals("out", p.rows.single().direction)
        assertTrue(p.rows.single().canImport)
    }
    @Test fun stackedPostingValueAndTransactionDetailsTitlesKeepSeparateGeometry() {
        val words = listOf(word("OMR", 20.0, 10.0)) + listOf(
            word("Posting", 20.0, 40.0), word("Value", 85.0, 40.0), word("Transaction", 170.0, 40.0, 85.0),
            word("Debit", 350.0, 40.0), word("Credit", 430.0, 40.0), word("Balance", 510.0, 40.0),
            word("Date", 20.0, 54.0), word("Date", 85.0, 54.0), word("Details", 170.0, 54.0, 70.0),
            word("Amount", 350.0, 54.0), word("Amount", 430.0, 54.0),
            word("11/09/2026", 20.0, 90.0, 55.0), word("13/09/2026", 85.0, 90.0, 55.0),
            word("POS-DEMO-001", 170.0, 90.0, 140.0), word("1.200", 350.0, 90.0), word("98.800", 510.0, 90.0),
            word("12/09/2026", 20.0, 120.0, 55.0), word("14/09/2026", 85.0, 120.0, 55.0),
            word("Deposit", 170.0, 120.0, 100.0), word("2.000", 430.0, 120.0), word("100.800", 510.0, 120.0)
        )
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertEquals(listOf("11/09/2026", "12/09/2026"), p.rows.map { it.dateText })
        assertEquals(listOf(1200L, 2000L), p.rows.map { it.amountBaisa })
        assertEquals(listOf("out", "in"), p.rows.map { it.direction })
        assertTrue(p.rows.all { it.canImport })
    }
    @Test fun alignedBilingualHeadersAcrossTwoLinesDescribeOneColumnEach() {
        val english = header(40.0)
        val arabic = listOf(word("التاريخ", 20.0, 54.0), word("تفاصيل الحركة", 140.0, 54.0, 100.0), word("مدين", 320.0, 54.0), word("دائن", 415.0, 54.0), word("الرصيد", 510.0, 54.0, 60.0))
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, listOf(word("OMR", 20.0, 10.0)) + english + arabic + line(90.0))))
        assertEquals(1, p.rows.size)
        assertEquals(3000L, p.rows.single().amountBaisa)
        assertEquals("13/10/2026", p.rows.single().dateText)
        assertTrue(p.rows.single().canImport)
    }
    @Test fun statementPeriodMetadataInEitherArabicOrderAndBareRangeIsNotATransaction() {
        listOf(
            "فترة البيان 13/09/2026 to 17/10/2026",
            "17/10/2026 to 13/09/2026 البيان فترة",
            "Statement Period 13/09/2026 to 17/10/2026",
            "13/09/2026 to 17/10/2026"
        ).forEach { metadata ->
            val p = preview(pages = listOf(StatementPage(600.0, 800.0, listOf(word(metadata, 20.0, 20.0, 550.0)))))
            assertTrue(p.rows.isEmpty(), metadata)
            assertEquals(1, p.skippedRows, metadata)
        }
        val wrapped = listOf(word("فترة البيان", 20.0, 20.0, 140.0), word("13/09/2026 to 17/10/2026", 20.0, 34.0, 250.0))
        assertTrue(preview(pages = listOf(StatementPage(600.0, 800.0, wrapped))).rows.isEmpty())
    }
    @Test fun statementPeriodPhraseInsideARecognizedFinancialRowIsPreserved() {
        val p = preview(pages = listOf(page(listOf(line(90.0, description = "Statement period charge 13/09/2026 to 17/10/2026")))))
        assertEquals(1, p.rows.size)
        assertEquals(3000L, p.rows.single().amountBaisa)
        assertEquals("13/10/2026", p.rows.single().dateText)
        assertTrue(p.rows.single().canImport)
    }
    @Test fun ambiguousAdjacentDateColumnsAndPosReferenceCannotProveAnExpense() {
        val h = listOf(word("Date", 20.0, 40.0), word("Date", 85.0, 40.0), word("Description", 170.0, 40.0, 100.0), word("Amount", 350.0, 40.0), word("Balance", 510.0, 40.0))
        val row = listOf(word("13/09/2026", 20.0, 90.0, 55.0), word("11/09/2026", 85.0, 90.0, 55.0), word("POS-DEMO-001", 170.0, 90.0, 140.0), word("1.200", 350.0, 90.0), word("98.800", 510.0, 90.0))
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, h + row)))
        assertEquals(1, p.rows.size)
        assertFalse(p.rows.single().canImport)
        assertNull(p.rows.single().direction)
        assertNull(p.rows.single().amountBaisa)
        val singleDateHeader = header().filter { it.text !in listOf("Debit", "Credit") } + word("Amount", 350.0, 40.0)
        val unsigned = preview(pages = listOf(StatementPage(600.0, 800.0, singleDateHeader + line(90.0, description = "POS-DEMO-001", debit = "").filter { it.x != 510.0 } + word("1.200", 350.0, 90.0) + word("98.800", 510.0, 90.0))))
        assertFalse(unsigned.rows.single().canImport)
        assertNull(unsigned.rows.single().direction)
    }
    @Test fun unrelatedHeaderLinesTooFarApartCannotBeJoined() {
        val words = listOf(word("Date", 20.0, 40.0), word("Description", 140.0, 40.0, 100.0), word("Debit", 320.0, 100.0), word("Credit", 415.0, 100.0), word("Balance", 510.0, 100.0)) + line(140.0)
        val p = preview(pages = listOf(StatementPage(600.0, 800.0, words)))
        assertTrue(p.rows.none { it.canImport })
    }
}
