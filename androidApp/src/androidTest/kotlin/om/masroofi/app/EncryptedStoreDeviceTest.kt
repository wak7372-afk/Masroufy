package om.masroofi.app

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated files; never touches the user's ledger or deletes their Keystore key. */
@RunWith(AndroidJUnit4::class)
class EncryptedStoreDeviceTest {
    @Test fun keystoreCiphertextSurvivesReopenAndRejectsTampering() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(application.cacheDir, "store-security-test-${UUID.randomUUID()}")
        check(directory.canonicalFile.parentFile == application.cacheDir.canonicalFile)
        check(directory.mkdir())
        val context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val secret = "{\"note\":\"اختبار خاص 9.650\"}"
        val file = File(directory, "ledger.enc")
        try {
            val store = EncryptedStore(context)
            store.write(secret)
            val first = file.readBytes()
            assertEquals(secret, EncryptedStore(context).read())
            assertFalse(first.toString(Charsets.UTF_8).contains(secret))
            store.write(secret)
            val second = file.readBytes()
            assertFalse("Every write needs a fresh IV", first.take(12) == second.take(12))
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .getKey("masroofi.ledger.v1", null)
            assertNotNull(key)
            assertNull("Keystore key must not be exportable", key.encoded)
            for (index in listOf(0, 11, 12, second.lastIndex)) {
                val corrupt = second.copyOf()
                corrupt[index] = (corrupt[index].toInt() xor 1).toByte()
                file.writeBytes(corrupt)
                var rejected = false
                try { EncryptedStore(context).read() } catch (_: Exception) { rejected = true }
                assertTrue("Tampered IV/ciphertext/tag must fail authentication", rejected)
                corrupt.fill(0)
            }
            file.writeBytes(second)
            assertEquals(secret, EncryptedStore(context).read())
            first.fill(0); second.fill(0)
        } finally {
            check(directory.canonicalFile.parentFile == application.cacheDir.canonicalFile)
            check(directory.deleteRecursively())
        }
        assertFalse(directory.exists())
    }
}
