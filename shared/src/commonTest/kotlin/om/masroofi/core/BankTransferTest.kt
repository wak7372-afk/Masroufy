package om.masroofi.core

import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlin.test.*

class BankTransferTest {
    private val now=Instant.parse("2026-10-13T10:00:00Z").toEpochMilliseconds()
    private fun engine()=LedgerEngine().apply { setOwnedBank("MTHQ",true);setOwnedBank("NIZWA",true);setOwnedBank("بنك صحار الإسلامي",true) }
    private fun send(e:LedgerEngine,bank:String,whenText:String,amount:String="9.650",incoming:Boolean=false,id:String=bank+whenText)=
        e.importMessage("$bank Transfer ${if(incoming) "credited" else "debited"} OMR $amount ${if(incoming) "from" else "to"} MY ACCOUNT on 13/10/2026 $whenText",now,"paste",id)
    private fun paired()=engine().apply {send(this,"MTHQ","10:00:00");send(this,"NIZWA","10:05:00",incoming=true)}
    @Test fun outgoingTotalNeverSubtractsDepositsOrRefunds() {
        val e=LedgerEngine()
        e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=10000,direction="out",effect="expense",occurredAtMillis=now)))
        e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=3000,direction="in",effect="refund",occurredAtMillis=now)))
        e.editTransaction(ledgerJson.encodeToString(EditCommand(amountBaisa=50000,direction="in",effect="income",occurredAtMillis=now)))
        val report=BudgetEngine.report(e.state,now)
        assertEquals(10000L,report.netExpense);assertEquals(3000L,report.refunds);assertEquals(50000L,report.incoming)
    }
    @Test fun bankAliasesAndAmbiguousBankMentionsAreHandledLocally() {
        assertEquals("بنك ميثاق",BankKnowledge.detect("رقم المعاملةMTHQXXXXXXXX"))
        assertEquals("بنك نزوى",BankKnowledge.detect("NIZWA"));assertEquals("بنك صحار الإسلامي",BankKnowledge.detect("Sohar Islamic"))
        assertEquals(BankKnowledge.UNKNOWN,BankKnowledge.detect("MTHQ transfer to NIZWA"))
        val e=LedgerEngine();e.importBankMessage("Transfer debited OMR 1.000 to MTHQ on 13/10/2026 10:00:00",now,"sms","test","NIZWA")
        assertEquals("بنك نزوى",e.state.transactions.single().bankName)
    }
    @Test fun matchingIsSymmetricAndInclusiveAtFiveMinutes() {
        listOf("09:55:00","10:05:00").forEach { time ->
            val e=engine();send(e,"MTHQ","10:00:00");assertEquals(9650L,BudgetEngine.report(e.state,now).netExpense)
            send(e,"NIZWA",time,incoming=true)
            assertTrue(e.state.transactions.all{it.effect=="internal" && it.transferPairId!=null})
            assertEquals(0L,BudgetEngine.report(e.state,now).netExpense)
            assertEquals(0L,BudgetEngine.report(e.state,now).incoming)
        }
        val reversed=engine();send(reversed,"NIZWA","10:01:00",incoming=true);send(reversed,"MTHQ","10:00:00")
        assertTrue(reversed.state.transactions.all{it.effect=="internal"})
    }
    @Test fun unmatchedOrUnknownTimesBanksAmountsDoNotHideSpending() {
        val outside=engine();send(outside,"MTHQ","10:00:00");send(outside,"NIZWA","10:05:01",incoming=true)
        assertEquals(9650L,BudgetEngine.report(outside.state,now).netExpense)
        val amount=engine();send(amount,"MTHQ","10:00:00");send(amount,"NIZWA","10:01:00","9.649",true)
        assertTrue(amount.state.transactions.none{it.effect=="internal"})
        val dayOnly=engine();send(dayOnly,"MTHQ","");send(dayOnly,"NIZWA","",incoming=true)
        assertTrue(dayOnly.state.transactions.none{it.effect=="internal"})
        val unowned=LedgerEngine();send(unowned,"MTHQ","10:00:00");send(unowned,"NIZWA","10:00:00",incoming=true)
        assertTrue(unowned.state.transactions.none{it.effect=="internal"})
        val sameBank=engine();send(sameBank,"MTHQ","10:00:00");send(sameBank,"MTHQ","10:00:00",incoming=true,id="another")
        assertTrue(sameBank.state.transactions.none{it.effect=="internal"})
    }
    @Test fun newAmbiguityReversesEarlierAutomaticPairAndWarns() {
        val e=paired();send(e,"Sohar Islamic","10:03:00",incoming=true)
        assertEquals(9650L,BudgetEngine.report(e.state,now).netExpense)
        assertTrue(e.state.transactions.none{it.effect=="internal"})
        assertTrue(e.state.transactions.any{it.warnings.any{w->w.contains("أكثر من")}})
    }
    @Test fun deletingEditingAndUnlinkingRestoreBothSidesCorrectly() {
        val deleted=paired();deleted.deleteTransaction(deleted.state.transactions.first{it.direction=="in"}.id)
        assertEquals(9650L,BudgetEngine.report(deleted.state,now).netExpense)
        val edited=paired();val credit=edited.state.transactions.first{it.direction=="in"}
        edited.editTransaction(ledgerJson.encodeToString(EditCommand(credit.id,9649,"in","income",occurredAtMillis=credit.occurredAtMillis)))
        assertTrue(edited.state.transactions.none{it.effect=="internal"});assertEquals(9650L,BudgetEngine.report(edited.state,now).netExpense)
        val unlinked=paired();unlinked.unlinkTransfer(unlinked.state.transactions.first().id)
        assertTrue(unlinked.state.transactions.none{it.effect=="internal"});assertEquals(9650L,BudgetEngine.report(unlinked.state,now).netExpense)
        assertTrue(LedgerEngine(unlinked.stateJson()).state.transactions.none{it.effect=="internal"})
    }
    @Test fun ownershipChangeAndRestoreReconcileMatches() {
        val e=paired();assertTrue(LedgerEngine(e.stateJson()).state.transactions.all{it.effect=="internal"})
        e.setOwnedBank("NIZWA",false);assertTrue(e.state.transactions.none{it.effect=="internal"})
        e.setOwnedBank("NIZWA",true);assertTrue(e.state.transactions.all{it.effect=="internal"})
    }
    @Test fun matchingRequiresSameCurrencyAndSameLocalCalendarDate() {
        val e=paired()
        val differentCurrency=e.state.copy(transactions=e.state.transactions.map { if(it.direction=="in") it.copy(currency="USD") else it })
        assertTrue(LedgerEngine(ledgerJson.encodeToString(differentCurrency)).state.transactions.none{it.effect=="internal"})
        val midnight=engine()
        send(midnight,"MTHQ","23:59:00")
        midnight.importMessage("NIZWA Transfer credited OMR 9.650 from MY ACCOUNT on 14/10/2026 00:01:00",now,"paste","next-day")
        assertTrue(midnight.state.transactions.none{it.effect=="internal"})
        assertEquals(9650L,BudgetEngine.report(midnight.state,now).netExpense)
    }
    @Test fun sourceTimesRespectAmPmAndInvalidDatesStayUnverified() {
        listOf("12:00 AM" to "2026-10-12T20:00:00Z","12:00 PM" to "2026-10-13T08:00:00Z","10:01:02 PM" to "2026-10-13T18:01:02Z").forEach { (time,expected) ->
            val draft=SmsParser.parse("Purchase OMR 1.000 at SHOP on 13/10/2026 $time",now).drafts.single()
            val date=Instant.parse(expected).toEpochMilliseconds()
            assertEquals(date,draft.occurredAt)
            assertEquals(date,StatementImport.dateMillis("13/10/2026 $time"))
            assertEquals(if(time.contains("01:02")) "second" else "minute",draft.datePrecision)
            assertEquals(draft.datePrecision,StatementImport.datePrecision("13/10/2026 $time"))
        }
        val invalid=LedgerEngine()
        invalid.importMessage("MTHQ Purchase OMR 1.000 at SHOP on 13/10/9999 10:00",now,"paste","invalid-year")
        invalid.importMessage("MTHQ Purchase OMR 1.000 at SHOP on 13/10/2026 19:00 PM",now,"paste","invalid-hour")
        assertTrue(invalid.state.transactions.all{!it.financialVerified && it.datePrecision=="received"})
        assertEquals(invalid.state.transactions,LedgerEngine(invalid.stateJson()).state.transactions)
        assertNull(StatementImport.dateMillis("13/10/2026 19:00 PM"))
    }
    @Test fun separateTimeColumnPreservesTimestampPrecision() {
        val document=StatementDocument(listOf(StatementPage(650.0,800.0,listOf(
            StatementWord("NIZWA OMR",20.0,5.0,100.0,10.0),
            StatementWord("Date",20.0,40.0,50.0,10.0),StatementWord("Time",120.0,40.0,40.0,10.0),
            StatementWord("Description",240.0,40.0,100.0,10.0),StatementWord("Debit",440.0,40.0,50.0,10.0),StatementWord("Credit",540.0,40.0,50.0,10.0),
            StatementWord("13/10/2026",20.0,80.0,80.0,10.0),StatementWord("10:04 PM",120.0,80.0,70.0,10.0),
            StatementWord("Transfer received",240.0,80.0,120.0,10.0),StatementWord("9.650",540.0,80.0,50.0,10.0)))))
        val preview=StatementImport.parse(document,"f".repeat(64))
        val row=preview.rows.single()
        assertEquals("minute",row.datePrecision)
        assertEquals(Instant.parse("2026-10-13T18:04:00Z").toEpochMilliseconds(),row.occurredAtMillis)
        assertEquals("in",row.direction);assertEquals(9650L,row.amountBaisa)
        assertTrue(row.canImport)
        val combined=document.copy(pages=document.pages.map { page -> page.copy(words=page.words.mapNotNull { word ->
            when(word.text) {
                "Time","10:04 PM" -> null
                "Date" -> word.copy(text="Date / Time")
                "13/10/2026" -> word.copy(text="13/10/2026 10:04 PM")
                else -> word
            }
        }) })
        assertEquals(row.occurredAtMillis,StatementImport.parse(combined,"e".repeat(64)).rows.single().occurredAtMillis)
    }
    @Test fun keywordCorrectionsAreEncryptedStateKnowledgeAndNeverAlterAmounts() {
        val e=LedgerEngine();e.learnKeyword("STATIONXYZ","fuel","");e.learnKeyword("BNZ","","بنك نزوى")
        e.importMessage("BNZ Purchase OMR 2.300 at STATIONXYZ on 13/10/2026 10:00:00",now,"paste","learned")
        val txn=e.state.transactions.single();assertEquals("fuel",txn.categoryId);assertEquals("بنك نزوى",txn.bankName);assertEquals(2300L,txn.amountMinor)
        val restored=LedgerEngine(e.stateJson());assertEquals(e.state.keywordRules,restored.state.keywordRules)
        assertFailsWith<IllegalArgumentException>{restored.learnKeyword("1234","fuel","")}
        restored.removeKeyword("STATIONXYZ");assertNull(BankKnowledge.category("STATIONXYZ",restored.state.keywordRules))
    }
    @Test fun statementBanksComeFromHeaderAndTimeIsNeverInvented() {
        fun doc(bank:String,date:String,debit:Boolean)=StatementDocument(listOf(StatementPage(600.0,800.0,listOf(
            StatementWord(bank,10.0,5.0,120.0,10.0),StatementWord("OMR",200.0,5.0,30.0,10.0),
            StatementWord("Date",20.0,40.0,50.0,10.0),StatementWord("Description",160.0,40.0,100.0,10.0),StatementWord("Debit",340.0,40.0,50.0,10.0),StatementWord("Credit",440.0,40.0,50.0,10.0),
            StatementWord(date,20.0,80.0,125.0,10.0),StatementWord("TRANSFER MTHQ",160.0,80.0,130.0,10.0),StatementWord("9.650",if(debit)340.0 else 440.0,80.0,50.0,10.0)))))
        val e=LedgerEngine()
        listOf(Triple("Meethaq","13/10/2026 10:00:00",true),Triple("NIZWA","13/10/2026 10:04:00",false)).forEachIndexed { index,(bank,date,debit) ->
            val preview=e.previewStatement(ledgerJson.encodeToString(doc(bank,date,debit)),('a'+index).toString().repeat(64),now)
            val parsed=ledgerJson.decodeFromString<StatementPreview>(preview)
            assertEquals(if(index==0) "بنك ميثاق" else "بنك نزوى",parsed.bankName)
            assertEquals("second",parsed.rows.single().datePrecision)
            val configured=e.configureStatementBank(preview,parsed.bankName,true)
            e.importStatement(configured,ledgerJson.encodeToString(parsed.rows.map{it.id}),now,true)
        }
        assertTrue(e.state.transactions.all{it.effect=="internal"});assertEquals(0L,BudgetEngine.report(e.state,now).netExpense)
    }
}
