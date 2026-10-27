package om.masroofi.core

import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.*

class CategoryAndSafetyTest {
    private val now = 1_792_940_400_000L
    private fun expense(e: LedgerEngine, id: String?, amount: Long = 1250): String = e.editTransaction(ledgerJson.encodeToString(
        EditCommand(amountBaisa = amount, direction = "out", effect = "expense", categoryId = id, occurredAtMillis = now, bankName = "MTHQ")))
    @Test fun freshInstallAndClearStartWithUserOwnedEmptyCatalog() {
        val e = LedgerEngine(); assertTrue(e.state.categories.isEmpty())
        e.addCategory("رحلاتي", "◈"); e.clearAll(); assertTrue(e.state.categories.isEmpty())
    }
    @Test fun existingCatalogAndTransactionsSurviveUpgrade() {
        val e = legacyCategoryLedger(); expense(e,"fuel")
        val restored = LedgerEngine(e.stateJson()); assertEquals(e.state,restored.state)
        restored.renameCategory("fuel","بنزين السيارة"); assertEquals("fuel",restored.state.transactions.single().categoryId)
    }
    @Test fun categoriesCanBeCreatedSelectedRenamedAndRestored() {
        val e = LedgerEngine(); val id = e.addCategory("  هواياتي  ","◈"); expense(e,id)
        e.renameCategory(id,"هوايات وكتب"); val restored = LedgerEngine(e.stateJson())
        assertEquals("هوايات وكتب",restored.state.categories.single().name)
        assertEquals(id,restored.state.transactions.single().categoryId)
    }
    @Test fun deletingCategoryPreservesEveryAmountAndClearsItsReferences() {
        val e=LedgerEngine(); val id=e.addCategory("قهوة", "◈"); expense(e,id)
        e.setBudget(id,1000,5000); e.learnKeyword("COFFEE HOUSE",id,"")
        val total=BudgetEngine.report(e.state,now).netExpense; val before=e.state.transactions.map{it.id to it.accountingBaisa}
        e.deleteCategory(id)
        assertEquals(before,e.state.transactions.map{it.id to it.accountingBaisa})
        assertEquals(total,BudgetEngine.report(e.state,now).netExpense)
        assertTrue(e.state.transactions.all{it.categoryId==null}); assertTrue(e.state.budgets.isEmpty());assertTrue(e.state.keywordRules.isEmpty())
        assertEquals(e.state,LedgerEngine(e.stateJson()).state)
    }
    @Test fun duplicateNamesIncludingArabicNormalizationAreRejectedAtomically() {
        val e=LedgerEngine(); val id=e.addCategory("إجازة", "◈"); val other=e.addCategory("كتب", "◈"); val before=e.stateJson()
        assertFails{e.addCategory("اجازه","◈")}; assertFails{e.renameCategory(other,"  إجازة ")}
        assertEquals(before,e.stateJson());e.renameCategory(id,"إجازة")
    }
    @Test fun invalidNamesAndMissingCategoryOperationsNeverChangeLedger() {
        val e=LedgerEngine();val before=e.stateJson()
        listOf("", " ", "x".repeat(41), "مصاريف\u202e123", "a\u0000b").forEach{assertFails{e.addCategory(it,"◈")}}
        assertFails{e.deleteCategory("missing")};assertFails{e.renameCategory("missing","كتب")};assertEquals(before,e.stateJson())
    }
    @Test fun maximumCategoryCountIsEnforcedWithoutDataLoss() {
        val e=LedgerEngine();repeat(200){e.addCategory("فئة $it","◈")};val before=e.stateJson()
        assertFails{e.addCategory("زيادة","◈")};assertEquals(before,e.stateJson())
    }
    @Test fun removedCategoryCannotBeUsedToSaveOrTeach() {
        val e=LedgerEngine();val id=e.addCategory("كتب","◈");e.deleteCategory(id);val before=e.stateJson()
        assertFails{expense(e,id)};assertFails{e.learnKeyword("BOOK SHOP",id,"")};assertEquals(before,e.stateJson())
    }
    @Test fun importsDoNotReintroduceFixedOrDanglingCategories() {
        val e=LedgerEngine();e.importMessage("Purchase OMR 3.000 at SHELL",now,"paste","one")
        assertTrue(e.state.categories.isEmpty());assertNull(e.state.transactions.single().categoryId)
        assertEquals(e.state,LedgerEngine(e.stateJson()).state)
    }
    @Test fun invalidSnapshotsAreRejectedAndRestoreKeepsPreviousData() {
        val e=LedgerEngine();expense(e,null);val before=e.stateJson();val t=e.state.transactions.single()
        val corrupt=listOf(e.state.copy(sequence=Long.MAX_VALUE),e.state.copy(transactions=listOf(t,t)),
            e.state.copy(transactions=listOf(t.copy(effect="income"))),e.state.copy(transactions=listOf(t.copy(currency="<script>"))),
            e.state.copy(transactions=listOf(t.copy(categoryId="ghost"))),e.state.copy(transactions=listOf(t.copy(note="x".repeat(2001)))),
            e.state.copy(transactions=listOf(t.copy(bankName="a\u202eb"))),e.state.copy(categories=listOf(Category("id"," ","◈"))))
        corrupt.forEach{value->assertFails{e.restore(ledgerJson.encodeToString(value))};assertEquals(before,e.stateJson())}
    }
    @Test fun malformedAndDeeplyNestedJsonIsRejectedBeforeDecoderRecursion() {
        val e=LedgerEngine();val before=e.stateJson()
        assertFails{LedgerEngine(" ")};assertFails{LedgerEngine(" ".repeat(16_000_001))}
        listOf("{} trailing", "[", "\"unterminated", "[".repeat(2000)+"]".repeat(2000),"x".repeat(16_000_001)).forEach{
            assertFails{e.restore(it)};assertEquals(before,e.stateJson())
        }
        // Braces in notes and escaped quotes are data, not JSON nesting.
        e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=1000,direction="out",effect="expense",note="[".repeat(100)+"\"\\",occurredAtMillis=now)))
        assertEquals(e.state,LedgerEngine(e.stateJson()).state)
    }
    @Test fun oversizedManualCommandAndMessageAreRejectedAtomically() {
        val e=LedgerEngine();val before=e.stateJson()
        assertFails{e.editTransaction(" ".repeat(16_001))}
        assertFails{e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=1,direction="out",effect="expense",note="x".repeat(2001),occurredAtMillis=now)))}
        assertFails{e.importMessage("x".repeat(200_001),now,"paste","one")};assertEquals(before,e.stateJson())
    }
    @Test fun randomMoneyRoundTripsWithoutFloatingPointLoss() {
        val random=Random(1729)
        repeat(2000){val amount=random.nextLong(0,9_000_000_000_001L);assertEquals(amount,Money.input(Money.format(amount)))}
        listOf("NaN","Infinity","-1","1e9","1.0001","<img src=x>","9".repeat(1000)).forEach{assertNull(Money.input(it))}
        assertEquals(1250L,Money.input("١٫٢٥٠"));assertEquals(1250L,Money.input("۱٫۲۵۰"))
    }
    @Test fun hundredsOfMixedTransactionsKeepOutgoingSeparateFromIncomeAndRefunds() {
        val e=LedgerEngine();val random=Random(19);var outgoing=0L;var incoming=0L;var refunds=0L
        repeat(300){val value=random.nextLong(1,100000);val type=listOf("expense","income","refund")[it%3]
            e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=value,direction=if(type=="expense")"out" else "in",effect=type,occurredAtMillis=now)))
            when(type){"expense"->outgoing+=value;"income"->incoming+=value;else->refunds+=value}
        }
        val report=BudgetEngine.report(e.state,now);assertEquals(outgoing,report.netExpense);assertEquals(incoming,report.incoming);assertEquals(refunds,report.refunds)
        assertEquals(e.state,LedgerEngine(e.stateJson()).state)
    }
}
