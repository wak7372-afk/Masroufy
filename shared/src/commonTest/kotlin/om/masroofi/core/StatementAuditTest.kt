package om.masroofi.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class StatementAuditTest {
    private fun row(id:String,amount:Long,direction:String,balance:Long?=null,time:Long=0)=StatementRow(
        id,1,"13/10/2026",StatementImport.dateMillis("13/10/2026"),"Transfer",amount,direction,
        if(direction=="in")"income" else "expense",null,null,emptyList(),true,balanceBaisa=balance)
        .let { if(time!=0L)it.copy(occurredAtMillis=time)else it }
    private fun document(ocr:Boolean=false)=StatementDocument(listOf(StatementPage(600.0,800.0,listOf(
        StatementWord("NIZWA OMR",10.0,5.0,100.0,10.0),
        StatementWord("Opening Balance",10.0,25.0,130.0,10.0),StatementWord("100.000",450.0,25.0,60.0,10.0),
        StatementWord("Date",10.0,50.0,50.0,10.0),StatementWord("Description",150.0,50.0,100.0,10.0),
        StatementWord("Debit",350.0,50.0,50.0,10.0),StatementWord("Credit",450.0,50.0,50.0,10.0),
        StatementWord("13/10/2026",10.0,80.0,100.0,10.0),StatementWord("Transfer sent",150.0,80.0,120.0,10.0),
        StatementWord("9.650",350.0,80.0,50.0,10.0),
        StatementWord("13/10/2026",10.0,110.0,100.0,10.0),StatementWord("Deposit",150.0,110.0,100.0,10.0),
        StatementWord("2.000",450.0,110.0,50.0,10.0),
        StatementWord("Closing Balance",10.0,160.0,130.0,10.0),StatementWord("92.350",450.0,160.0,60.0,10.0),
        StatementWord("Total Debits",10.0,190.0,130.0,10.0),StatementWord("9.650",450.0,190.0,60.0,10.0),
        StatementWord("Total Credits",10.0,220.0,130.0,10.0),StatementWord("2.000",450.0,220.0,60.0,10.0)
    ),isOcr=ocr)))
    @Test fun fullStatementChecksUseAllMovementsIncludingTransfers() {
        val preview=StatementImport.parse(document(),"a".repeat(64))
        assertEquals(2,preview.rows.size);assertEquals(100000L,preview.summary.openingBaisa)
        assertEquals(92350L,preview.summary.closingBaisa);assertEquals(9650L,preview.summary.debitTotalBaisa)
        val audit=preview.audit!!
        assertEquals("matched",audit.status);assertEquals(3,audit.checks);assertEquals(0L,audit.differenceBaisa)
        assertEquals(9650L,audit.outgoingBaisa);assertEquals(2000L,audit.incomingBaisa)
        assertFalse(audit.requiresConfirmation)
    }
    @Test fun summaryRowsAreNotPaymentsAndOriginalFieldGeometrySurvivesCorrection() {
        val engine=LedgerEngine()
        val json=engine.previewStatement(ledgerJson.encodeToString(document()),"a".repeat(64),0)
        val preview=ledgerJson.decodeFromString<StatementPreview>(json)
        val row=preview.rows.first();val field=row.fields.single{it.name=="amount"}
        assertTrue(field.region.x in 0.5..0.7);assertTrue(field.region.y in 0.09..0.11)
        assertTrue(row.region!!.width>field.region.width)
        val corrected=ledgerJson.decodeFromString<StatementPreview>(engine.confirmStatementRow(json,row.id,row.dateText,"Corrected transfer","9.650","out"))
        assertEquals(row.fields,corrected.rows.first().fields);assertEquals(row.region,corrected.rows.first().region)
        assertEquals("matched",corrected.audit!!.status)
    }
    @Test fun mismatchMustBeAcknowledgedInCoreAndEditingRevokesAcknowledgement() {
        val engine=LedgerEngine()
        val original=StatementImport.parse(document(),"b".repeat(64))
        val broken=original.copy(rows=original.rows.mapIndexed{index,it->if(index==0)it.copy(amountBaisa=8650)else it})
        val json=ledgerJson.encodeToString(broken)
        val selected=ledgerJson.encodeToString(broken.rows.map{it.id})
        assertFailsWith<IllegalArgumentException>{engine.importStatement(json,selected,0,true)}
        assertTrue(engine.state.transactions.isEmpty())
        val acknowledged=engine.acknowledgeStatementAudit(json)
        val confirmed=ledgerJson.decodeFromString<StatementPreview>(acknowledged)
        assertEquals("mismatch",confirmed.audit!!.status);assertEquals(1000L,confirmed.audit.differenceBaisa)
        val edited=engine.confirmStatementRow(acknowledged,broken.rows.first().id,"13/10/2026","Transfer sent","8.650","out")
        assertFalse(ledgerJson.decodeFromString<StatementPreview>(edited).auditAcknowledged)
        assertFailsWith<IllegalArgumentException>{engine.importStatement(edited,selected,0,true)}
        engine.importStatement(acknowledged,selected,0,true)
        assertEquals(2,engine.state.transactions.size)
        assertFalse(engine.stateJson().contains("openingBaisa"));assertFalse(engine.stateJson().contains("readingConfidence"))
    }
    @Test fun ocrSummariesNeverBecomeTrustedBecauseNumbersHappenToMatch() {
        val preview=StatementImport.parse(document(true),"c".repeat(64))
        assertEquals(0L,preview.audit!!.differenceBaisa)
        assertTrue(preview.summary.needsReview);assertTrue(preview.audit.requiresConfirmation)
        assertTrue(preview.rows.all{it.reviewRequired})
    }
    @Test fun conflictingPageBalancesAbstainAndNeverSelectTheFirstValue() {
        val first=document().pages.single()
        val second=first.copy(words=listOf(StatementWord("Opening Balance 500.000",10.0,10.0,240.0,10.0)))
        val summary=StatementAuditEngine.readSummary(StatementDocument(listOf(first,second)))
        assertNull(summary.openingBaisa);assertTrue(summary.needsReview)
        assertTrue(summary.warnings.any{it.contains("متعارضة")})
    }
    @Test fun arabicLabelsNegativeBalancesAndAmbiguousSeparatorsAreConservative() {
        fun summary(text:String)=StatementAuditEngine.readSummary(StatementDocument(listOf(StatementPage(600.0,800.0,
            listOf(StatementWord(text,10.0,10.0,500.0,12.0))))))
        assertEquals(-2000L,summary("2.000 DR الرصيد الافتتاحي").openingBaisa)
        assertEquals(3000L,summary("إجمالي الإيداعات ر.ع 3.000").creditTotalBaisa)
        val uncertain=summary("Opening Balance 3,000")
        assertNull(uncertain.openingBaisa);assertTrue(uncertain.needsReview)
        assertNull(summary("13/10/2026 Purchase Opening Balance 100.000").openingBaisa)
    }
    @Test fun adjacentBalancesWorkAcrossPagesAndDescendingOrder() {
        val a=row("p1-r1",1000,"out",9000,10)
        val b=row("p2-r1",2000,"in",11000,20).copy(page=2)
        listOf(listOf(a,b),listOf(b,a)).forEach { rows ->
            val audit=StatementAuditEngine.evaluate(StatementSummary(),rows)
            assertEquals("matched",audit.status);assertEquals(1,audit.checks)
        }
        val broken=StatementAuditEngine.evaluate(StatementSummary(),listOf(a,b.copy(balanceBaisa=12000)))
        assertEquals("mismatch",broken.status);assertTrue(broken.requiresConfirmation)
    }
    @Test fun missingRowsAndAbsentSummariesCannotLookLikeACompleteMatch() {
        val rows=listOf(row("p1-r1",1000,"out"))
        assertEquals("unavailable",StatementAuditEngine.evaluate(StatementSummary(),rows).status)
        val partial=StatementAuditEngine.evaluate(StatementSummary(openingBaisa=10000,closingBaisa=9000),rows+rows.first().copy(id="p1-r2",amountBaisa=null,canImport=false))
        assertEquals("incomplete",partial.status);assertTrue(partial.requiresConfirmation)
        assertEquals(1,partial.unresolvedRows)
    }
}
