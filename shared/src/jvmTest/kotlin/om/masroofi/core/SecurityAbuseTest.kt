package om.masroofi.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.Base64
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class SecurityAbuseTest {
    private val password="local-security-test-2026"
    @Test(timeout=90000) fun everyCiphertextByteAndAllNonceAndSaltBytesAreAuthenticated() {
        val original=ledgerJson.decodeFromString<BackupEnvelope>(BackupCrypto.seal("معاملة اختبارية 9.650 OMR",password))
        fun mutate(value:String,index:Int):String {
            val bytes=Base64.getDecoder().decode(value);bytes[index]=(bytes[index].toInt() xor 1).toByte()
            return Base64.getEncoder().encodeToString(bytes)
        }
        val mutations=buildList {
            repeat(Base64.getDecoder().decode(original.data).size){add(original.copy(data=mutate(original.data,it)))}
            repeat(12){add(original.copy(nonce=mutate(original.nonce,it)))}
            repeat(16){add(original.copy(salt=mutate(original.salt,it)))}
        }
        mutations.forEach{assertFails{BackupCrypto.open(ledgerJson.encodeToString(it),password)}}
    }
    @Test(timeout=90000) fun repeatedBackupsNeverReuseNoncesOrSaltAndDoNotExposePlaintext() {
        val envelopes=(1..32).map{ledgerJson.decodeFromString<BackupEnvelope>(BackupCrypto.seal("secret-ledger-value",password))}
        assertEquals(32,envelopes.map{it.nonce}.distinct().size);assertEquals(32,envelopes.map{it.salt}.distinct().size)
        envelopes.forEach{assertFalse(ledgerJson.encodeToString(it).contains("secret-ledger-value"))}
    }
    @Test(timeout=10000) fun oversizedPasswordsFieldsAndNestedEnvelopesAreRejectedBeforeKdf() {
        assertFails{BackupCrypto.seal("{}","x".repeat(1025))}
        assertFails{BackupCrypto.open("{}","x".repeat(1025))}
        val bad=BackupEnvelope(1,"x".repeat(50000),"x".repeat(16),"x".repeat(20))
        assertFails{BackupCrypto.open(ledgerJson.encodeToString(bad),password)}
        assertFails{BackupCrypto.open("[".repeat(2000)+"]".repeat(2000),password)}
    }
    @Test(timeout=10000) fun hostileSmsAndRandomInputsDoNotCreateVerifiedOtpOrOverflowAmounts() {
        val now=1_792_940_400_000L
        assertTrue(SmsParser.parse("Purchase OMR 1"+".".repeat(40)+"0",now).drafts.isEmpty())
        listOf("Purchase OMR "+"9".repeat(15000),"Purchase OMR "+"1.".repeat(7900),"OTP Purchase OMR 100.000 code 123456","Purchase OMR 9.650 <script>alert(1)</script>").forEach {
            val parsed=SmsParser.parse(it,now)
            assertTrue(parsed.drafts.all{d->d.amount==null||d.amount in 0..9_000_000_000_000L})
            if(it.startsWith("OTP"))assertTrue(parsed.drafts.isEmpty())
        }
        val random=Random(2026)
        repeat(500){val text=(1..random.nextInt(1,256)).map{random.nextInt(32,0x700).toChar()}.joinToString("")
            val parsed=SmsParser.parse(text,now);assertTrue(parsed.drafts.size<100)
        }
    }
}
