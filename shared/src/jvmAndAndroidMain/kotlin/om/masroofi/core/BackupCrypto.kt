package om.masroofi.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Version 1 portable backup envelope, also read and written by the iOS app. */
@Serializable
data class BackupEnvelope(val v: Int, val salt: String, val nonce: String, val data: String)

/** The Android app and JVM tests use this same production implementation. */
object BackupCrypto {
    private const val MAX_ENVELOPE_LENGTH = 24_000_000
    private val json = Json
    private val aad = "masroofi-backup-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    private fun derive(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    fun seal(snapshot: String, password: String): String {
        require(password.length in 8..1024) { "استخدم كلمة مرور بين 8 و1024 حرفًا" }
        require(snapshot.length <= 15_000_000 && snapshot.toByteArray(Charsets.UTF_8).size <= 15_000_000) { "البيانات أكبر من حد النسخة الاحتياطية" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return json.encodeToString(BackupEnvelope(1, encode(salt), encode(nonce), encode(cipher.doFinal(snapshot.toByteArray(Charsets.UTF_8)))))
    }

    fun open(envelope: String, password: String): String {
        require(password.length <= 1024) { "كلمة المرور أطول من المسموح" }
        LedgerSafety.json(envelope, MAX_ENVELOPE_LENGTH)
        val value = json.decodeFromString<BackupEnvelope>(envelope)
        require(value.v == 1) { "إصدار النسخة الاحتياطية غير مدعوم" }
        require(value.salt.length <= 24 && value.nonce.length <= 16) { "حقول النسخة الاحتياطية غير صالحة" }
        val salt = decode(value.salt)
        val nonce = decode(value.nonce)
        val data = decode(value.data)
        require(salt.size == 16 && nonce.size == 12 && data.size >= 16) { "ملف النسخة الاحتياطية غير صالح" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data).toString(Charsets.UTF_8)
    }
}
