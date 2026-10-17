import XCTest
@testable import Masroofi

final class BackupVaultTests: XCTestCase {
    func testIndependentUtf8Fixture() throws {
        // Created independently with Node crypto, also tested by the Android JVM suite.
        let fixture = Data(#"{"v":1,"salt":"AAECAwQFBgcICQoLDA0ODw==","nonce":"EBESExQVFhcYGRob","data":"YZdkN2mcNmo+SeeIg0DY8J4ToHv+eQNIFds7v5ulL0gCNYQ0fQ4PRtj4mISlRsFPmCxQQAxFqfNz9P7HdfS/w0CP"}"#.utf8)
        let snapshot = try BackupVault.open(fixture, password: "كلمة-مرور-آمنة🔐")
        XCTAssertEqual(snapshot, #"{"transactions":[],"note":"تجربة عربية"}"#)
    }

    func testEncryptedBackupRoundTripAndAuthentication() throws {
        let snapshot = #"{"schemaVersion":1,"note":"نص عربي 🔐"}"#
        let encrypted = try BackupVault.seal(snapshot, password: "محفظة-محلية-آمنة")
        XCTAssertEqual(try BackupVault.open(encrypted, password: "محفظة-محلية-آمنة"), snapshot)
        XCTAssertThrowsError(try BackupVault.open(encrypted, password: "كلمة-مرور-خاطئة"))
        var envelope = try JSONDecoder().decode(BackupEnvelope.self, from: encrypted)
        var ciphertext = try XCTUnwrap(Data(base64Encoded: envelope.data))
        ciphertext[0] ^= 1
        envelope = BackupEnvelope(v: envelope.v, salt: envelope.salt, nonce: envelope.nonce, data: ciphertext.base64EncodedString())
        XCTAssertThrowsError(try BackupVault.open(JSONEncoder().encode(envelope), password: "محفظة-محلية-آمنة"))
    }

    func testRejectsInvalidEnvelopeAndShortExportPassword() throws {
        XCTAssertThrowsError(try BackupVault.seal("{}", password: "123"))
        let invalid = Data(#"{"v":2,"salt":"","nonce":"","data":""}"#.utf8)
        XCTAssertThrowsError(try BackupVault.open(invalid, password: "password"))
    }
}
