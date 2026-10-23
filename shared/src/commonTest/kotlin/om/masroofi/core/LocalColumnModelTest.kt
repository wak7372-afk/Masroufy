package om.masroofi.core

import kotlin.test.*
import kotlinx.serialization.encodeToString

class LocalColumnModelTest {
    @Test fun learnedHeadersNeverProduceAmountsOrClassifyNumericIdentifiers() {
        assertEquals("DEBIT", LocalColumnModel.role("Paid Out"))
        assertEquals("CREDIT", LocalColumnModel.role("Paid In"))
        listOf("1.200", "1234567890", "POS-123456", "01/10/2026", "zzqxv jzkqr").forEach { assertNull(LocalColumnModel.role(it), it) }
    }
    @Test fun unfamiliarBankHeadingsNeedExplicitReviewAndPreserveExactValues() {
        fun w(t:String,x:Double,y:Double,width:Double=55.0) = StatementWord(t,x,y,width,10.0)
        val page = StatementPage(600.0,800.0,listOf(
            w("OMR",20.0,10.0), w("Date",20.0,40.0),w("Description",130.0,40.0,100.0),
            w("Paid Out",320.0,40.0),w("Paid In",420.0,40.0),w("Balance",520.0,40.0),
            w("13/10/2026",20.0,80.0,75.0),w("TEST MERCHANT",130.0,80.0,100.0),w("1.200",320.0,80.0),w("998.800",520.0,80.0)))
        val p = StatementImport.parse(StatementDocument(listOf(page)), "a".repeat(64))
        assertEquals(1200L,p.rows.single().amountBaisa)
        assertEquals("out",p.rows.single().direction)
        assertTrue(p.rows.single().canImport)
        assertTrue(p.rows.single().reviewRequired)
    }
    @Test fun unreliableOcrNumbersAreBlockedWhileClearOcrAlwaysRequiresReview() {
        fun page(confidence:Double) = StatementPage(600.0,800.0,listOf(
            StatementWord("Date",20.0,40.0,55.0,10.0),StatementWord("Description",130.0,40.0,100.0,10.0),
            StatementWord("Debit",320.0,40.0,55.0,10.0),StatementWord("Credit",420.0,40.0,55.0,10.0),StatementWord("Balance",520.0,40.0,55.0,10.0),
            StatementWord("13/10/2026",20.0,80.0,75.0,10.0),StatementWord("TEST",130.0,80.0,100.0,10.0),
            StatementWord("1.200",320.0,80.0,55.0,10.0,confidence),StatementWord("998.800",520.0,80.0,55.0,10.0)),true)
        val low = StatementImport.parse(StatementDocument(listOf(page(0.4))),"a".repeat(64)).rows.single()
        assertFalse(low.canImport); assertTrue(low.reviewRequired)
        val high = StatementImport.parse(StatementDocument(listOf(page(0.99))),"a".repeat(64)).rows.single()
        assertTrue(high.canImport); assertTrue(high.reviewRequired); assertEquals(1200L,high.amountBaisa)
        assertFailsWith<IllegalArgumentException> { StatementImport.parse(StatementDocument(listOf(page(Double.NaN))),"a".repeat(64)) }
    }
}
