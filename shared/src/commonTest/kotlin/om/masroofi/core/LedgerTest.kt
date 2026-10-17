package om.masroofi.core

import kotlin.test.*
import kotlinx.datetime.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class LedgerTest {
    private val now = Instant.parse("2026-10-14T08:00:00Z").toEpochMilliseconds()
    private fun import(engine: LedgerEngine, text: String, id: String = "a", source: String = "paste"): ImportResult =
        ledgerJson.decodeFromString(engine.importMessage(text, now, source, id))
    private fun edit(engine: LedgerEngine, amount: Long, effect: String = "expense", category: String? = "fuel", time: Long = now): String =
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa = amount, direction = if (effect in listOf("income", "refund")) "in" else "out", effect = effect, categoryId = category, occurredAtMillis = time)))

    @Test fun fiveProvidedFormsExtractTransactionNotBalance() {
        val messages = listOf(
            "عزيزي الزبون، لقد قمت بإرسال OMR 9.650 إلى XXXXXXXXXXXXXXXXXXXXXXXXXXXXXX من حسابك XXXXXXXXXXXXXXX باستخدام خدمات الدفع عبر االهاتف النقال/ المحفظة الإلكترونية. رقم المعاملةMTHQXXXXXXXXXXXX. رصيدك الحالي هو OMR 22.000.",
            "تم خصم مبلغ 3.000 ر.ع من حساب التوفير رقم ############## بتاريخ 13/10/2026 18:16:08 للحساب باسم 5470 OMAN OIL ALKHOUD V\\\\AL KHOUDH\\. رصيدك الحالي 7.030 ر.ع",
            "تم تحويل مبلغ 9.650 ر.ع الى حساب التوفير رقم ############## بتاريخ 13/10/2026. رصيدك الحالي 10.730 ر.ع",
            "تم خصم 1.000 OMR من حسابك رقم XXXXXXXXXXXXXX بإستخدام بطاقة الخصم المباشر في 668633-Omantel بتاريخ 2026/10/06 18:28:09. رصيدك الحالي هو 0.029 OMR.",
            "تم إيداع ر.ع 2.000 في حساب رقم #### ###### ##### من PERSON DEMO. الرصيد المتوفر هو 10.559 ر.ع"
        )
        val parsed = messages.map { SmsParser.parse(it, now).drafts.single() }
        assertEquals(listOf(9650L, 3000L, 9650L, 1000L, 2000L), parsed.map { it.amount })
        assertEquals(listOf(22000L, 7030L, 10730L, 29L, 10559L), parsed.map { it.balance })
        assertEquals("fuel", parsed[1].category); assertEquals("telecom", parsed[3].category)
        assertNull(parsed[2].direction)
        assertEquals("in", parsed[4].direction)
        assertEquals(Instant.parse("2026-10-06T14:28:09Z").toEpochMilliseconds(), parsed[3].occurredAt)
    }
    @Test fun digitsAndBaisaRemainExact() {
        assertEquals("3.500 ر.ع", SmsParser.normalize("٣٫٥٠٠  ر.ع"))
        assertEquals(100L, SmsParser.parse("Purchase OMR 0.100 at KFC", now).drafts.single().amount)
        assertEquals(3500L, Money.input("٣٫٥٠٠")); assertEquals("3.500", Money.format(3500))
        assertEquals("−0.100", Money.format(-100)); assertNull(Money.input("12.5000"))
    }
    @Test fun noiseNeverProducesTransactions() {
        listOf("", "hello", "Your OTP for purchase OMR 5.000 is 123456", "Purchase OMR 5.000 at KFC declined", "Get offer Purchase OMR 3.000 at SHOP", "الرصيد الحالي OMR 100.000").forEach {
            assertTrue(SmsParser.parse(it, now).drafts.isEmpty(), it)
        }
    }
    @Test fun malformedAmountCannotMatchOnlyItsPrefix() {
        val parsed = SmsParser.parse("Purchase OMR 12.5000 at KFC", now).drafts.single()
        assertNull(parsed.amount); assertTrue(parsed.warnings.isNotEmpty())
        assertNull(SmsParser.parse("Purchase USD 1.235 at AMAZON", now).drafts.single().amount)
    }
    @Test fun separatorsAreNotGuessed() {
        assertEquals(1_250_500L, SmsParser.parse("Purchase OMR 1,250.500 at CARREFOUR", now).drafts.single().amount)
        assertNull(SmsParser.parse("Purchase OMR 5,200 at STORE", now).drafts.single().amount)
    }
    @Test fun feesAreIncludedSeparately() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 8.000 at CAFE NERO. Fee OMR 0.200. Avail bal OMR 99.000")
        assertEquals(2, engine.state.transactions.size)
        assertEquals(8200L, BudgetEngine.report(engine.state, now).grossExpense)
        assertEquals(200L, SmsParser.parse("Fee OMR 0.200", now).drafts.single().amount)
    }
    @Test fun multipleAmountsRequireReviewInsteadOfDroppingSecond() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 3.000 at KFC and Purchase OMR 4.000 at SHOP")
        assertEquals(2, engine.state.transactions.size)
        assertTrue(engine.state.transactions.all { !it.financialVerified })
    }
    @Test fun foreignCurrencyDoesNotEnterOmanBudget() {
        val engine = LedgerEngine(); import(engine, "Purchase USD 25.00 at AMAZON")
        assertEquals(2500L, engine.state.transactions.single().amountMinor)
        assertEquals(0L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun exactSourceReplayCreatesOnlyOneTransaction() {
        val engine = LedgerEngine()
        repeat(10) { import(engine, "Purchase OMR 3.000 at SHELL", "source-event-123", "sms") }
        assertEquals(1, engine.state.transactions.size)
        assertEquals(1, engine.state.pendingTransactionNotices.size)
    }
    @Test fun identicalRealPaymentsWithDifferentEventsSurvive() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 3.000 at SHELL Ref 123456", "one", "sms")
        import(engine, "Purchase OMR 3.000 at SHELL Ref 789012", "two", "sms")
        assertEquals(2, engine.state.transactions.size)
        assertEquals(6000L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun crossSourceSimilarityIsReviewableNotDeleted() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 3.000 at SHELL", "one", "sms")
        import(engine, "Purchase OMR 3.000 at SHELL", "two", "notification")
        assertEquals(2, engine.state.transactions.size)
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun transferEffectMustBeConfirmed() {
        val engine = LedgerEngine(); import(engine, "لقد قمت بإرسال OMR 9.650 إلى شخص من حسابك عبر المحفظة الإلكترونية")
        val txn = engine.state.transactions.single()
        assertEquals("out", txn.direction); assertFalse(txn.financialVerified)
        assertEquals(0L, BudgetEngine.report(engine.state, now).netExpense)
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(txn.id, 9650, "out", "internal", occurredAtMillis = txn.occurredAtMillis)))
        assertEquals(0L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun refundLowersExpenseNotIncome() {
        val engine = LedgerEngine(); edit(engine, 10_000); edit(engine, 4000, "refund")
        val report = BudgetEngine.report(engine.state, now)
        assertEquals(10_000L, report.grossExpense); assertEquals(4000L, report.refunds)
        assertEquals(6000L, report.netExpense); assertEquals(0L, report.incoming)
    }
    @Test fun unknownCategoryIsStillIncluded() {
        val engine = LedgerEngine(); edit(engine, 2500, category = null)
        assertEquals(2500L, BudgetEngine.report(engine.state, now).unclassified)
        assertEquals(2500L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun confirmedMerchantRuleLearnsWithoutBankName() {
        val engine = LedgerEngine(); import(engine, "تم خصم OMR 1.000 باستخدام بطاقة في 668633-Omantel", "a")
        val first = engine.state.transactions.single()
        engine.assignCategory(first.id, "subscriptions", true)
        import(engine, "Purchase OMR 2.000 at OMANTEL", "b")
        assertEquals("subscriptions", engine.state.transactions.first().categoryId)
        assertTrue(engine.state.transactions.first().reviewed)
    }
    @Test fun dateOnlyRemainsDateOnlyAndInvalidDateNeedsReview() {
        assertEquals("day", SmsParser.parse("Purchase OMR 5.000 at KFC on 13/10/2026", now).drafts.single().datePrecision)
        assertEquals("received", SmsParser.parse("Purchase OMR 5.000 at KFC", now).drafts.single().datePrecision)
        assertTrue(SmsParser.parse("Purchase OMR 5.000 at KFC on 31/02/2026", now).drafts.single().warnings.isNotEmpty())
        assertEquals(Instant.parse("2026-10-11T05:10:00Z").toEpochMilliseconds(), SmsParser.parse("ATM withdrawal OMR 50.000 on 11-Oct-2026 09:10", now).drafts.single().occurredAt)
    }
    @Test fun salaryMonthsClampToLastDayWithoutDrifting() {
        val preferences = Preferences(monthStartDay = 31)
        val feb = BudgetEngine.month(Instant.parse("2026-02-28T08:00:00Z").toEpochMilliseconds(), preferences)
        assertEquals(Instant.parse("2026-02-27T20:00:00Z").toEpochMilliseconds(), feb.start)
        assertEquals(Instant.parse("2026-03-30T20:00:00Z").toEpochMilliseconds(), feb.end)
        val next = BudgetEngine.month(feb.end, preferences)
        assertEquals(feb.end, next.start)
    }
    @Test fun budgetBoundariesAreStartInclusiveEndExclusive() {
        val engine = LedgerEngine(); val month = BudgetEngine.month(now, engine.state.preferences)
        edit(engine, 1000, time = month.start); edit(engine, 2000, time = month.end)
        assertEquals(1000L, BudgetEngine.report(engine.state, now).grossExpense)
        val week = BudgetEngine.week(now, engine.state.preferences)
        assertEquals(DayOfWeek.SATURDAY, Instant.fromEpochMilliseconds(week.start).toLocalDateTime(TimeZone.of("Asia/Muscat")).dayOfWeek)
    }
    @Test fun notificationOutboxRecoversAndAcknowledgesEachThresholdOnce() {
        val engine = LedgerEngine(); engine.setBudget("fuel", 10_000, 10_000); edit(engine, 11_000)
        val alerts = ledgerJson.decodeFromString<List<BudgetAlert>>(engine.takeBudgetAlerts(now))
        assertEquals(4, alerts.size)
        val restored = LedgerEngine(engine.stateJson())
        assertEquals(4, ledgerJson.decodeFromString<List<BudgetAlert>>(restored.takeBudgetAlerts(now)).size)
        alerts.forEach { restored.acknowledgeBudgetAlert(it.key) }
        assertTrue(ledgerJson.decodeFromString<List<BudgetAlert>>(restored.takeBudgetAlerts(now)).isEmpty())
    }
    @Test fun whatsappForwardTimeDoesNotOverrideMessageDate() {
        val engine = LedgerEngine()
        import(engine, "[10/14, 10:35 AM] ~: تم خصم 3.000 ر.ع لدى SHELL بتاريخ 13/10/2026 18:16:08\n[10/14, 10:37 AM] ~: تم خصم 1.000 OMR باستخدام بطاقة في Omantel بتاريخ 2026/10/06 18:28:09")
        assertEquals(2, engine.state.transactions.size)
        assertTrue(engine.state.transactions.all { it.datePrecision == "second" })
    }
    @Test fun rawMessageAndOtpDoNotEnterSnapshot() {
        val engine = LedgerEngine(); val text = "Your OTP for purchase OMR 10.000 is 123456"
        import(engine, text)
        assertTrue(engine.state.transactions.isEmpty()); assertFalse(engine.stateJson().contains(text)); assertFalse(engine.stateJson().contains("123456"))
    }
    @Test fun snapshotRestoreRejectsUnsupportedOrNegativeMoney() {
        val engine = LedgerEngine(); edit(engine, 1200)
        assertEquals(engine.state, LedgerEngine(engine.stateJson()).state)
        assertFails { LedgerEngine(engine.stateJson().replace("\"schemaVersion\":1", "\"schemaVersion\":9")) }
        assertFails { LedgerEngine(engine.stateJson().replace("\"amountMinor\":1200", "\"amountMinor\":-1200")) }
    }
    @Test fun demoCanBeRemovedWithoutDeletingPersonalRecords() {
        val engine = LedgerEngine(); edit(engine, 500); engine.seedDemo(now); engine.clearDemo()
        assertEquals(1, engine.state.transactions.size); assertEquals(500L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun pendingRefundAndReversedDepositCannotAlterTotals() {
        val engine = LedgerEngine()
        import(engine, "Refund request OMR 5.000 at SHELL", "pending")
        import(engine, "Deposit reversal OMR 2.000", "reversal")
        assertTrue(engine.state.transactions.all { !it.financialVerified })
        assertEquals(0L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun repeatedNotificationAndLearnedDuplicateRemainReviewable() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 3.000 at SHELL", "a", "notification")
        engine.assignCategory(engine.state.transactions.single().id, "fuel", true)
        import(engine, "Purchase OMR 3.000 at SHELL", "b", "notification")
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertFalse(engine.state.transactions.first().reviewed)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun deletingCanonicalRecordKeepsItsCandidateVisibleForReview() {
        val engine = LedgerEngine()
        import(engine, "Purchase OMR 3.000 at SHELL", "a", "sms")
        val original = engine.state.transactions.single().id
        import(engine, "Purchase OMR 3.000 at SHELL", "b", "notification")
        engine.deleteTransaction(original)
        val candidate = engine.state.transactions.single()
        assertNull(candidate.duplicateOf); assertFalse(candidate.reviewed); assertFalse(candidate.financialVerified)
    }
    @Test fun cashSplitEditAndDeletePreserveWithdrawalTotal() {
        val engine = LedgerEngine(); import(engine, "ATM withdrawal OMR 20.000", "cash")
        val original = engine.state.transactions.single().id
        val child = engine.splitCash(original, 8000, "restaurants", "طعام")
        assertEquals(20_000L, BudgetEngine.report(engine.state, now).netExpense)
        assertEquals(12_000L, engine.state.transactions.first { it.id == original }.accountingBaisa)
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(child, 10_000, "out", "expense", "groceries", note = "بقالة", occurredAtMillis = now)))
        assertEquals(original, engine.state.transactions.first { it.id == child }.originalId)
        assertEquals(10_000L, engine.state.transactions.first { it.id == original }.accountingBaisa)
        assertEquals(20_000L, BudgetEngine.report(engine.state, now).netExpense)
        assertFails { engine.deleteTransaction(original) }
        engine.deleteTransaction(child)
        assertEquals(20_000L, engine.state.transactions.single().accountingBaisa)
    }
    @Test fun linkedPartialRefundInheritsOriginalCategoryAndCannotExceedIt() {
        val engine = LedgerEngine(); val original = edit(engine, 10_000, category = "groceries")
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa = 4000, direction = "in", effect = "refund", categoryId = "fuel", originalId = original, occurredAtMillis = now)))
        assertEquals("groceries", engine.state.transactions.first().categoryId)
        assertEquals(6000L, BudgetEngine.report(engine.state, now).netExpense)
        assertFails { engine.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa = 7000, direction = "in", effect = "refund", originalId = original, occurredAtMillis = now))) }
    }
    @Test fun merchantlessNotificationRepostCannotDoubleCountEvenAfterTwoMinutes() {
        val engine = LedgerEngine(); val group = "a".repeat(64)
        import(engine, "تم خصم OMR 5.000 من حسابك", "$group:100", "notification")
        ledgerJson.decodeFromString<ImportResult>(engine.importMessage("تم خصم OMR 5.000 من حسابك", now + 600_000, "notification", "$group:200"))
        assertEquals(2, engine.state.transactions.size)
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertEquals(5000L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun returnedFeeReducesFeesNotIncreasesExpense() {
        val engine = LedgerEngine(); import(engine, "Fee OMR 0.500", "fee")
        import(engine, "Fee reversal OMR 0.200", "returned")
        val report = BudgetEngine.report(engine.state, now)
        assertEquals(300L, report.netExpense); assertEquals(200L, report.refunds)
    }
    @Test fun editingRefundOriginalMaintainsAmountsAndCategories() {
        val engine = LedgerEngine(); val original = edit(engine, 10_000, category = "groceries")
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa = 8000, direction = "in", effect = "refund", originalId = original, occurredAtMillis = now)))
        assertFails { engine.editTransaction(ledgerJson.encodeToString(EditCommand(original, 5000, "out", "expense", "groceries", occurredAtMillis = now))) }
        assertFails { engine.editTransaction(ledgerJson.encodeToString(EditCommand(original, 10_000, "out", "internal", occurredAtMillis = now))) }
        engine.assignCategory(original, "fuel", false)
        assertEquals("fuel", engine.state.transactions.first { it.effect == "refund" }.categoryId)
        assertEquals(2000L, BudgetEngine.report(engine.state, now).categories.first { it.categoryId == "fuel" }.monthlySpent)
    }
    @Test fun cashWithLinkedRefundCannotSplitReservedAmount() {
        val engine = LedgerEngine(); import(engine, "ATM withdrawal OMR 20.000")
        val cash = engine.state.transactions.single().id
        engine.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa = 15_000, direction = "in", effect = "refund", originalId = cash, occurredAtMillis = now)))
        assertFails { engine.splitCash(cash, 8000, "restaurants", "") }
        engine.splitCash(cash, 5000, "restaurants", "")
        assertEquals(5000L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun identicalBodyFromSmsAndNotificationWithoutMerchantNeedsReview() {
        val engine = LedgerEngine()
        import(engine, "تم خصم OMR 5.000 من حسابك", "sms-event", "sms")
        engine.importMessage("تم خصم OMR 5.000 من حسابك", now + 600_000, "notification", "notification-event")
        assertEquals(2, engine.state.transactions.size)
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertEquals(5000L, BudgetEngine.report(engine.state, now).grossExpense)
    }
    @Test fun aFutureScheduledChargeDoesNotCountAsCompletedSpending() {
        val engine = LedgerEngine()
        import(engine, "Your card will be charged OMR 5.000 at SHOP tomorrow")
        assertFalse(engine.state.transactions.single().financialVerified)
        assertEquals(0L, BudgetEngine.report(engine.state, now).netExpense)
        assertEquals(200L, SmsParser.parse("Service charge OMR 0.200", now).drafts.single().amount)
    }
    @Test fun realImportRemovesDemoInsteadOfMixingItsMoney() {
        val engine = LedgerEngine(); engine.seedDemo(now)
        assertTrue(engine.state.transactions.any { it.isDemo })
        import(engine, "Purchase OMR 6.000 at SHELL", "real", "sms")
        assertTrue(engine.state.transactions.none { it.isDemo })
        assertEquals(6000L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun resentBankReferenceFromSameSenderIsReviewedNotCountedTwice() {
        val engine = LedgerEngine(); val sender = "a".repeat(64); val body = "b".repeat(64)
        import(engine, "Purchase OMR 3.000 at SHELL Ref 123456", "$sender:100:$body", "sms")
        import(engine, "Purchase OMR 3.000 at SHELL Ref 123456", "$sender:200:$body", "sms")
        assertEquals(2, engine.state.transactions.size)
        assertNotNull(engine.state.transactions.first().duplicateOf)
        assertEquals(3000L, BudgetEngine.report(engine.state, now).netExpense)
    }
    @Test fun sameReferenceFromDifferentSmsSendersDoesNotMergeAccounts() {
        val engine = LedgerEngine(); val body = "b".repeat(64)
        import(engine, "Purchase OMR 3.000 at SHELL Ref 123456", "${"a".repeat(64)}:100:$body", "sms")
        import(engine, "Purchase OMR 3.000 at SHELL Ref 123456", "${"c".repeat(64)}:200:$body", "sms")
        assertEquals(2, engine.state.transactions.size)
        assertEquals(6000L, BudgetEngine.report(engine.state, now).netExpense)
    }
}
