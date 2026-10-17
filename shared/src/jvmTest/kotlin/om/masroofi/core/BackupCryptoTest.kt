package om.masroofi.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class BackupCryptoTest {
    private val password = "personal-backup-2026"
    private val snapshot = """{"schemaVersion":1,"note":"تجربة عربية","amountBaisa":9650}"""

    private fun read(encrypted: String): BackupEnvelope = Json.decodeFromString(encrypted)
    private fun write(envelope: BackupEnvelope): String = Json.encodeToString(envelope)
    private fun bytes(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size))
    private fun flip(value: String, offset: Int = 0): String {
        val data = Base64.getDecoder().decode(value)
        data[offset] = (data[offset].toInt() xor 1).toByte()
        return Base64.getEncoder().encodeToString(data)
    }

    @Test fun productionImplementationRoundTripsLedgerAndUnicodeText() {
        listOf(LedgerEngine().stateJson(), snapshot, "").forEach { plaintext ->
            assertEquals(plaintext, BackupCrypto.open(BackupCrypto.seal(plaintext, password), password))
        }
    }

    @Test fun arabicAndSupplementaryUnicodePasswordsRoundTrip() {
        listOf("كلمة-مرور-آمنة🔐", "كَلِمَةُ-مُرُورٍ-٢٠٢٦").forEach { unicodePassword ->
            assertEquals(snapshot, BackupCrypto.open(BackupCrypto.seal(snapshot, unicodePassword), unicodePassword))
        }
    }

    @Test fun eachBackupHasFreshSaltAndNonce() {
        val first = read(BackupCrypto.seal(snapshot, password))
        val second = read(BackupCrypto.seal(snapshot, password))
        assertEquals(1, first.v)
        assertEquals(16, Base64.getDecoder().decode(first.salt).size)
        assertEquals(12, Base64.getDecoder().decode(first.nonce).size)
        assertEquals(snapshot.toByteArray(Charsets.UTF_8).size + 16, Base64.getDecoder().decode(first.data).size)
        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.nonce, second.nonce)
        assertNotEquals(first.data, second.data)
    }

    @Test fun wrongPasswordDoesNotReturnPlaintext() {
        val encrypted = BackupCrypto.seal(snapshot, password)
        assertFailsWith<AEADBadTagException> { BackupCrypto.open(encrypted, "wrong-password-2026") }
    }

    @Test fun changedCiphertextTagSaltOrNonceFailsAuthentication() {
        val original = read(BackupCrypto.seal(snapshot, password))
        val lastDataByte = Base64.getDecoder().decode(original.data).lastIndex
        listOf(
            original.copy(data = flip(original.data)),
            original.copy(data = flip(original.data, lastDataByte)),
            original.copy(salt = flip(original.salt)),
            original.copy(nonce = flip(original.nonce))
        ).forEach { tampered ->
            assertFailsWith<AEADBadTagException> { BackupCrypto.open(write(tampered), password) }
        }
    }

    @Test fun malformedOrMissingFieldsAreRejected() {
        listOf(
            "not json", "{}", "[]", "null",
            """{"v":1,"salt":"","nonce":""}""",
            """{"v":"one","salt":"","nonce":"","data":""}""",
            """{"v":1,"salt":null,"nonce":"","data":""}""",
            """{"v":1,"salt":"","nonce":"","data":"","unexpected":true}"""
        ).forEach { invalid -> assertFails { BackupCrypto.open(invalid, password) } }
    }

    @Test fun unsupportedVersionsAreRejected() {
        val shape = BackupEnvelope(1, bytes(16), bytes(12), bytes(16))
        listOf(0, 2, -1).forEach { version ->
            assertFailsWith<IllegalArgumentException> { BackupCrypto.open(write(shape.copy(v = version)), password) }
        }
    }

    @Test fun saltNonceAndCiphertextLengthsAreValidated() {
        val shape = BackupEnvelope(1, bytes(16), bytes(12), bytes(16))
        listOf(
            shape.copy(salt = bytes(15)), shape.copy(salt = bytes(17)),
            shape.copy(nonce = bytes(11)), shape.copy(nonce = bytes(13)),
            shape.copy(data = bytes(15)), shape.copy(data = "")
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { BackupCrypto.open(write(invalid), password) }
        }
    }

    @Test fun invalidBase64IsRejected() {
        val shape = BackupEnvelope(1, bytes(16), bytes(12), bytes(16))
        listOf(shape.copy(salt = "%%%"), shape.copy(nonce = "%%%"), shape.copy(data = "%%%"))
            .forEach { invalid ->
                assertFailsWith<IllegalArgumentException> { BackupCrypto.open(write(invalid), password) }
            }
    }

    @Test fun oversizedEnvelopeIsRejectedBeforeParsing() {
        assertFailsWith<IllegalArgumentException> { BackupCrypto.open("x".repeat(24_000_001), password) }
    }

    @Test fun weakExportPasswordIsRejected() {
        listOf("", "short", "1234567").forEach { weak ->
            assertFailsWith<IllegalArgumentException> { BackupCrypto.seal(snapshot, weak) }
        }
    }

    @Test fun opensIndependentNodeFixtureWithArabicPasswordAndFixedParameters() {
        // Generated with Node's crypto.pbkdf2Sync(password, salt, 310000, 32, "sha256")
        // and createCipheriv("aes-256-gcm", key, nonce), UTF-8 AAD "masroofi-backup-v1".
        // The authentication tag is appended after ciphertext, matching the iOS format.
        val fixture = BackupEnvelope(
            v = 1,
            salt = "AAECAwQFBgcICQoLDA0ODw==",
            nonce = "EBESExQVFhcYGRob",
            data = "YZdkN2mcNmo+SeeIg0DY8J4ToHv+eQNIFds7v5ulL0gCNYQ0fQ4PRtj4mISlRsFPmCxQQAxFqfNz9P7HdfS/w0CP"
        )
        assertEquals(
            """{"transactions":[],"note":"تجربة عربية"}""",
            BackupCrypto.open(write(fixture), "كلمة-مرور-آمنة🔐")
        )
    }
}
