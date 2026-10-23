package om.masroofi.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal object TransferMatcher {
    private const val NOTE="تحويل بين حساباتك: تطابق المبلغ والعملة والبنكان والوقت خلال خمس دقائق"
    private const val AMBIGUOUS="يوجد أكثر من تحويل مطابق محتمل؛ لم تُستبعد العملية من الإنفاق تلقائيًا"
    fun base(txn:Transaction):Transaction=txn.transferOrigin?.let {
        txn.copy(effect=it.effect,financialVerified=it.financialVerified,reviewed=it.reviewed,warnings=it.warnings,transferOrigin=null,transferPairId=null)
    } ?: txn
    fun reconcile(state:LedgerState):LedgerState {
        val original=state.transactions.map(::base).map { it.copy(warnings=it.warnings.filterNot { warning -> warning==AMBIGUOUS }) }
        val linked=original.mapNotNull { it.originalId }.toSet()
        val eligible=original.filter { txn ->
            !txn.isDemo && txn.matchTransfers && txn.transferPairId==null && txn.duplicateOf==null && txn.originalId==null && txn.id !in linked &&
                txn.effect in listOf("expense","income","unresolved") && txn.type !in listOf("fee","refund","cash_split") &&
                txn.amountMinor!=null && txn.amountMinor>0 && txn.direction in listOf("in","out") &&
                txn.financialVerified && txn.datePrecision in listOf("second","minute") &&
                BankKnowledge.key(txn.bankName)?.let { key -> key in state.ownedBanks }==true
        }
        val zone=TimeZone.of("Asia/Muscat")
        val byAmount=eligible.groupBy { Triple(it.currency,it.amountMinor,Instant.fromEpochMilliseconds(it.occurredAtMillis).toLocalDateTime(zone).date) }.mapValues { (_,group) ->
            group.groupBy { it.direction to BankKnowledge.key(it.bankName) }.mapValues { (_,bankRows) -> bankRows.sortedBy { it.occurredAtMillis } }
        }
        val candidates=eligible.associate { txn ->
            val hits=mutableListOf<String>()
            val day=Instant.fromEpochMilliseconds(txn.occurredAtMillis).toLocalDateTime(zone).date
            for((key,bankRows) in byAmount[Triple(txn.currency,txn.amountMinor,day)].orEmpty()) {
                if(key.first==txn.direction || key.second==BankKnowledge.key(txn.bankName)) continue
                var low=0;var high=bankRows.size
                while(low<high) {val mid=(low+high)/2;if(bankRows[mid].occurredAtMillis<txn.occurredAtMillis-300_000) low=mid+1 else high=mid}
                var index=low
                while(index<bankRows.size && bankRows[index].occurredAtMillis<=txn.occurredAtMillis+300_000 && hits.size<2) {
                    val other=bankRows[index++]
                    if(Instant.fromEpochMilliseconds(other.occurredAtMillis).toLocalDateTime(zone).date==day) hits+=other.id
                }
                if(hits.size>=2) break
            }
            txn.id to hits
        }
        val partners=candidates.mapNotNull { (id,others) -> others.singleOrNull()?.takeIf { candidates[it]?.singleOrNull()==id }?.let { id to it } }.toMap()
        return state.copy(transactions=original.map { txn ->
            val partner=partners[txn.id]
            if(partner!=null) txn.copy(effect="internal",financialVerified=true,reviewed=true,transferPairId=partner,
                transferOrigin=TransferOrigin(txn.effect,txn.financialVerified,txn.reviewed,txn.warnings),warnings=txn.warnings+NOTE)
            else if(candidates[txn.id].orEmpty().isNotEmpty()) txn.copy(reviewed=false,warnings=txn.warnings+AMBIGUOUS) else txn
        })
    }
}
