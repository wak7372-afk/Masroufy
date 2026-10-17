import Foundation
import Combine
import CryptoKit
import Security
import CommonCrypto
import ExpenseCore

enum LocalVault {
    static let account = "om.masroofi.ledger.v1"
    static func key() throws -> SymmetricKey {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: account, kSecAttrAccount as String: "key",
            kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let bytes = item as? Data {
            guard bytes.count == 32 else { throw AppError.message("مفتاح التشفير غير صالح") }
            return SymmetricKey(data: bytes)
        }
        guard status == errSecItemNotFound else { throw AppError.message("مفتاح البيانات غير متاح. افتح الجهاز أولًا.") }
        let bytes = try random(32)
        let attributes: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: account, kSecAttrAccount as String: "key",
            kSecValueData as String: bytes, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        guard SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess else { throw AppError.message("تعذر إنشاء مفتاح التشفير") }
        return SymmetricKey(data: bytes)
    }
    static func random(_ size: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: size)
        guard SecRandomCopyBytes(kSecRandomDefault, size, &bytes) == errSecSuccess else { throw AppError.message("تعذر إنشاء بيانات تشفير") }
        return Data(bytes)
    }
    static func location() throws -> URL {
        let root = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        var directory = root.appendingPathComponent("Masroofi", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var resource = URLResourceValues(); resource.isExcludedFromBackup = true
        try directory.setResourceValues(resource)
        return directory.appendingPathComponent("ledger.enc")
    }
    static func read() throws -> String {
        let url = try location()
        if !FileManager.default.fileExists(atPath: url.path) { return "" }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize
        guard size.map({ $0 >= 29 && $0 < 16_000_000 }) ?? false else { throw AppError.message("ملف البيانات غير صالح") }
        let bytes = try Data(contentsOf: url)
        guard bytes.count >= 29 && bytes.count < 16_000_000 else { throw AppError.message("ملف البيانات غير صالح") }
        let plaintext = try AES.GCM.open(AES.GCM.SealedBox(combined: bytes), using: key())
        guard let snapshot = String(data: plaintext, encoding: .utf8) else { throw AppError.message("ترميز البيانات غير صالح") }
        return snapshot
    }
    static func write(_ snapshot: String) throws {
        let url = try location()
        let plaintext = Data(snapshot.utf8)
        guard plaintext.count + 28 < 16_000_000 else { throw AppError.message("وصلت البيانات إلى حد التخزين المحلي. احتفظ بنسخة احتياطية قبل تقليل السجل.") }
        guard let sealed = try AES.GCM.seal(plaintext, using: key()).combined else { throw AppError.message("تعذر تشفير البيانات") }
        try sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

struct BackupEnvelope: Codable { let v: Int; let salt: String; let nonce: String; let data: String }
enum BackupVault {
    static let aad = Data("masroofi-backup-v1".utf8)
    static func derive(_ password: String, _ salt: Data) throws -> SymmetricKey {
        let outputSize = 32
        var output = [UInt8](repeating: 0, count: outputSize)
        let saltSize = salt.count
        let passwordSize = password.utf8.count
        let status = password.withCString { pointer in
            salt.withUnsafeBytes { saltBytes in
                output.withUnsafeMutableBufferPointer { outputBytes in
                    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), pointer, passwordSize,
                        saltBytes.bindMemory(to: UInt8.self).baseAddress, saltSize,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 310_000,
                        outputBytes.baseAddress, outputSize)
                }
            }
        }
        guard status == kCCSuccess else { throw AppError.message("تعذر اشتقاق مفتاح النسخة") }
        return SymmetricKey(data: output)
    }
    static func seal(_ snapshot: String, password: String) throws -> Data {
        guard password.count >= 8 else { throw AppError.message("استخدم كلمة مرور من 8 أحرف على الأقل") }
        let salt = try LocalVault.random(16)
        let box = try AES.GCM.seal(Data(snapshot.utf8), using: derive(password, salt), authenticating: aad)
        let envelope = BackupEnvelope(v: 1, salt: salt.base64EncodedString(), nonce: Data(box.nonce).base64EncodedString(), data: (box.ciphertext + box.tag).base64EncodedString())
        return try JSONEncoder().encode(envelope)
    }
    static func open(_ encrypted: Data, password: String) throws -> String {
        guard encrypted.count < 24_000_000 else { throw AppError.message("الملف كبير جدًا") }
        let value = try JSONDecoder().decode(BackupEnvelope.self, from: encrypted)
        guard value.v == 1, let salt = Data(base64Encoded: value.salt), salt.count == 16,
              let nonce = Data(base64Encoded: value.nonce), nonce.count == 12,
              let ciphertext = Data(base64Encoded: value.data), ciphertext.count >= 16 else { throw AppError.message("صيغة النسخة غير صالحة") }
        let bytes = try AES.GCM.open(AES.GCM.SealedBox(combined: nonce + ciphertext), using: derive(password, salt), authenticating: aad)
        guard let snapshot = String(data: bytes, encoding: .utf8) else { throw AppError.message("ترميز النسخة غير صالح") }
        return snapshot
    }
}

@MainActor final class LedgerStore: ObservableObject {
    static let shared = LedgerStore()
    private var engine: LedgerEngine?
    @Published private(set) var state = LedgerData.empty
    @Published var selectedTransaction: String?
    @Published var error: String?
    @Published var modalDepth = 0
    @Published private(set) var loadFailed = false
    private init() {
        do {
            let candidate = try LedgerEngine(snapshot: LocalVault.read())
            let decoded = try decode(candidate.stateJson(), as: LedgerData.self)
            engine = candidate; state = decoded
        }
        catch { loadFailed = true; self.error = "تعذر فتح البيانات المشفّرة. استعد نسخة احتياطية؛ لن نكتب فوق الملف الحالي." }
    }
    func reloadIfNeeded() throws {
        guard loadFailed else { return }
        let candidate = try LedgerEngine(snapshot: LocalVault.read())
        let decoded = try decode(candidate.stateJson(), as: LedgerData.self)
        engine = candidate; state = decoded; loadFailed = false; error = nil
    }
    func change<T>(_ action: (LedgerEngine) throws -> T) throws -> T {
        try reloadIfNeeded()
        guard !loadFailed, let engine else { throw AppError.message("تعذر فتح البيانات المشفّرة. افتح الجهاز أو استعد نسخة احتياطية.") }
        // Mutate a separate engine so a failed validation/write leaves memory and disk unchanged.
        let candidate = try LedgerEngine(snapshot: engine.stateJson())
        let result = try action(candidate)
        let snapshot = candidate.stateJson()
        let decoded = try decode(snapshot, as: LedgerData.self)
        try LocalVault.write(snapshot)
        self.engine = candidate; state = decoded
        return result
    }
    func snapshot() throws -> String {
        try reloadIfNeeded()
        guard !loadFailed, let engine else { throw AppError.message("البيانات غير متاحة للنسخ الاحتياطي") }
        return engine.stateJson()
    }
    func report() -> LedgerReport {
        guard let engine else { return .empty }
        return (try? decode(engine.reportJson(nowMillis: millis()), as: LedgerReport.self)) ?? .empty
    }
    func importMessage(_ message: String, source: String = "paste", id: String = UUID().uuidString) throws -> LedgerImport {
        guard message.utf8.count <= 250_000 else { throw AppError.message("النص طويل جدًا. قسم الرسائل إلى مجموعات أصغر.") }
        return try change { engine in try decode(engine.importMessage(text: message, receivedAtMillis: millis(), source: source, sourceId: id), as: LedgerImport.self) }
    }
    func edit(_ payload: EditPayload) throws { _ = try change { try $0.editTransaction(commandJson: jsonString(payload)) } }
    func restore(_ snapshot: String) throws {
        let candidate = try LedgerEngine(snapshot: snapshot)
        let decoded = try decode(candidate.stateJson(), as: LedgerData.self)
        try LocalVault.write(candidate.stateJson()); engine = candidate; state = decoded; loadFailed = false
    }
    func perform(_ operation: () throws -> Void) {
        do { try operation(); Task { await Notifications.shared.flush() } }
        catch { self.error = (error as? AppError)?.errorDescription ?? "تعذر تنفيذ العملية. تحقق من المدخلات أو كلمة مرور النسخة الاحتياطية." }
    }
}
