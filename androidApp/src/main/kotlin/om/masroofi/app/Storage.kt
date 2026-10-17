package om.masroofi.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import om.masroofi.core.*
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "masroofi.ledger.v1"
fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

class EncryptedStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ledger.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) return store.getKey(KEY_ALIAS, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(false).build())
        return generator.generateKey()
    }
    fun read(): String {
        val legacyBackup = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !legacyBackup.exists()) return ""
        require(maxOf(file.baseFile.length(), legacyBackup.length()) < 16_000_000) { "ملف البيانات كبير جدًا" }
        val bytes = file.readFully()
        require(bytes.size in 29..16_000_000) { "ملف البيانات غير صالح" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun write(snapshot: String) {
        require(snapshot.toByteArray(Charsets.UTF_8).size < 15_000_000) { "بلغ ملف البيانات سعته؛ احتفظ بنسخة احتياطية قبل إضافة المزيد" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(snapshot.toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

class LedgerRepository private constructor(context: Context) {
    private val store = EncryptedStore(context.applicationContext)
    var loadError: String? = null; private set
    private var engine = try { LedgerEngine(store.read()) } catch (_: Exception) { loadError = "تعذر فتح البيانات المشفّرة. يمكنك استعادة نسخة احتياطية؛ لن نكتب فوق الملف الحالي."; LedgerEngine() }
    val states = MutableStateFlow(engine.state)
    @Synchronized fun <T> update(block: (LedgerEngine) -> T): T {
        check(loadError == null) { loadError.orEmpty() }
        val before = engine.stateJson()
        try {
            val result = block(engine)
            store.write(engine.stateJson()); states.value = engine.state
            return result
        } catch (error: Exception) { engine = LedgerEngine(before); throw error }
    }
    @Synchronized fun snapshot() = engine.stateJson()
    @Synchronized fun report(now: Long = System.currentTimeMillis()) = BudgetEngine.report(engine.state, now)
    @Synchronized fun restore(snapshot: String) {
        val validated = LedgerEngine(snapshot)
        store.write(validated.stateJson()); engine = validated; loadError = null; states.value = engine.state
    }
    companion object {
        @Volatile private var instance: LedgerRepository? = null
        fun get(context: Context): LedgerRepository = instance ?: synchronized(this) { instance ?: LedgerRepository(context).also { instance = it } }
    }
}
