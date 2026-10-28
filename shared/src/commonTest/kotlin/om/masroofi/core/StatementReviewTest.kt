package om.masroofi.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class StatementReviewTest {
    private val hash="c".repeat(64)
    private val now=1_792_185_600_000L
    private fun word(text:String,x:Double,y:Double,width:Double=55.0,confidence:Double=1.0)=StatementWord(text,x,y,width,10.0,confidence)
    private fun document(description:String="TEST STORE",amount:String="1.200",ocr:Boolean=false,confidence:Double=1.0,debitColumn:Boolean=false): StatementDocument {
        val header=listOf(word("OMR",20.0,10.0),word("Date",20.0,40.0),word("Description",130.0,40.0,100.0),
            word(if(debitColumn) "Debit" else "Amount",350.0,40.0),word("Balance",510.0,40.0))
        val row=listOf(word("13/10/2026",20.0,80.0,75.0),word(description,130.0,80.0,160.0),word(amount,350.0,80.0,55.0,confidence),word("98.800",510.0,80.0))
        return StatementDocument(listOf(StatementPage(600.0,800.0,header+row,ocr)))
    }
    private fun preview(doc:StatementDocument,engine:LedgerEngine=legacyCategoryLedger()):StatementPreview=
        ledgerJson.decodeFromString(engine.previewStatement(ledgerJson.encodeToString(doc),hash,now))
    private fun confirm(engine:LedgerEngine,p:StatementPreview,amount:String="1.200",direction:String="out"):StatementPreview=
        ledgerJson.decodeFromString(engine.confirmStatementRow(ledgerJson.encodeToString(p),p.rows.single().id,"13/10/2026","USER CHECKED",amount,direction))
    private fun import(engine:LedgerEngine,p:StatementPreview)=engine.importStatement(ledgerJson.encodeToString(p),ledgerJson.encodeToString(p.rows.map { it.id }),now,true)

    @Test fun narrationSeparatesReceiptsWithdrawalsAndReversals() {
        val examples=mapOf("Payment received" to "in","Credit card payment" to "out","Cash deposit" to "in",
            "ATM cash withdrawal" to "out","ATM withdrawal reversal" to "in","Debit reversal" to "in",
            "Deposit reversed" to "out","Credit reversal" to "out","Card purchase refund" to "in",
            "تم إيداع مبلغ" to "in","دفع فاتورة" to "out","عكس سحب نقدي" to "in","عكس إيداع" to "out")
        examples.forEach { (text,expected) ->
            val row=preview(document(text)).rows.single()
            assertEquals(expected,row.direction,text); assertEquals(1200L,row.amountBaisa,text)
            assertTrue(row.reviewRequired,text)
        }
        listOf("Failed payment","Deposit pending","Refund requested","Transfer from savings to current","تحويل","لم تتم عملية السحب","zzqxv jzkqr").forEach {
            assertNull(StatementDirection.suggest(it),it)
        }
    }
    @Test fun uncertainOcrRequiresConfirmationInCoreBeforeAtomicImport() {
        val engine=legacyCategoryLedger()
        val p=preview(document("ATM cash withdrawal",ocr=true,confidence=.3),engine)
        assertFalse(p.rows.single().canImport)
        assertFailsWith<IllegalArgumentException>{import(engine,p)}
        assertFailsWith<IllegalArgumentException>{import(engine,p.copy(rows=p.rows.map{it.copy(canImport=true)}))}
        assertTrue(engine.state.transactions.isEmpty())
        val checked=confirm(engine,p,"2.345","in")
        assertTrue(checked.rows.single().userConfirmed);assertTrue(checked.rows.single().canImport)
        import(engine,checked)
        val txn=engine.state.transactions.single()
        assertEquals(2345L,txn.amountMinor);assertEquals("in",txn.direction)
        assertFalse(engine.stateJson().contains("StatementWord"));assertFalse(engine.stateJson().contains("confidence"))
        assertNull(txn.balanceMinor)
        import(engine,checked);assertEquals(1,engine.state.transactions.size)
    }
    @Test fun confirmationRejectsBadValuesWithoutChangingLedger() {
        val engine=legacyCategoryLedger();val p=preview(document("Payment received"));val json=ledgerJson.encodeToString(p)
        val original=engine.stateJson()
        listOf("-1.000","0","1.0001","9000000000.001","bad").forEach { amount ->
            assertFailsWith<IllegalArgumentException>{engine.confirmStatementRow(json,p.rows.single().id,"13/10/2026","TEST",amount,"out")}
        }
        assertFails{engine.confirmStatementRow(json,p.rows.single().id,"31/02/2026","TEST","1.000","in")}
        assertFails{engine.confirmStatementRow(json,p.rows.single().id,"13/10/2026","TEST","1.000","")}
        assertEquals(original,engine.stateJson())
    }
    @Test fun columnNarrationConflictRequiresUserDecision() {
        val engine=legacyCategoryLedger();val p=preview(document("Cash deposit",debitColumn=true))
        val row=p.rows.single();assertEquals("out",row.direction);assertTrue(row.reviewRequired)
        assertTrue(row.warnings.any{it.contains("يخالف")})
        assertFailsWith<IllegalArgumentException>{import(engine,p)}
        val corrected=confirm(engine,p,direction="in");import(engine,corrected)
        assertEquals("in",engine.state.transactions.single().direction)
    }
    @Test fun adjacentBalancesFlagWrongAmountAndSuggestUnresolvedDirectionOnlyForReview() {
        val base=document("TEST",debitColumn=true).pages.single()
        val next=listOf(word("14/10/2026",20.0,110.0,75.0),word("TEST TWO",130.0,110.0,100.0),word("2.000",350.0,110.0),word("90.000",510.0,110.0))
        val p=preview(StatementDocument(listOf(base.copy(words=base.words+next))))
        assertTrue(p.rows[1].reviewRequired);assertTrue(p.rows[1].warnings.any{it.contains("فرق الرصيد")})
        val unsigned=base.copy(words=base.words.map{if(it.text=="Debit") it.copy(text="Amount") else it}+next.map{if(it.text=="90.000")it.copy(text="100.800")else it})
        val suggested=preview(StatementDocument(listOf(unsigned))).rows[1]
        assertEquals("in",suggested.direction);assertEquals("balance",suggested.directionSource)
        assertFalse(suggested.canImport);assertTrue(suggested.reviewRequired)
    }
    @Test fun splitOcrHeaderWordsRemainDistinctFromBalanceAndRequireReview() {
        val words=listOf(word("Date",20.0,40.0),word("Description",130.0,40.0,100.0),
            word("Money",320.0,40.0,28.0),word("Out",352.0,40.0,20.0),word("Money",420.0,40.0,28.0),word("In",452.0,40.0,15.0),word("Balance",520.0,40.0),
            word("13/10/2026",20.0,80.0,75.0),word("STORE",130.0,80.0,100.0),word("1.200",320.0,80.0),word("998.800",520.0,80.0))
        val row=preview(StatementDocument(listOf(StatementPage(600.0,800.0,words,true)))).rows.single()
        assertEquals(1200L,row.amountBaisa);assertEquals("out",row.direction);assertTrue(row.reviewRequired)
    }
    @Test fun trainedDirectionModelLoadsAndAbstainsOnUnknownVocabulary() {
        assertEquals("in",StatementDirection.model("payment received"))
        assertEquals("out",StatementDirection.model("cash withdrawn"))
        assertEquals(StatementDirection.Suggestion("out","model"),StatementDirection.suggest("تم اقتطاع المبلغ"))
        assertEquals(StatementDirection.Suggestion("in","model"),StatementDirection.suggest("funds recieved"))
        assertNull(StatementDirection.suggest("zzqxv jzkqr pqxv"))
        assertEquals("in",StatementDirection.explicit("CR"));assertEquals("out",StatementDirection.explicit("DR"))
    }
    @Test fun failedOrPendingNarrationRequiresConfirmationEvenWithExplicitDebitColumn() {
        val engine=legacyCategoryLedger()
        val p=preview(document("Failed payment",debitColumn=true))
        assertEquals("out",p.rows.single().direction);assertTrue(p.rows.single().reviewRequired)
        assertFailsWith<IllegalArgumentException>{import(engine,p)}
        assertTrue(engine.state.transactions.isEmpty())
    }
}
