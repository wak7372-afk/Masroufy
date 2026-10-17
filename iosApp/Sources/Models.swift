import Foundation
import ExpenseCore

struct LedgerData: Decodable {
    let transactions: [LedgerTransaction]
    let categories: [LedgerCategory]
    let budgets: [LedgerBudget]
    let rules: [LedgerRule]
    let preferences: LedgerPreferences
    let pendingAlerts: [LedgerAlert]
    let pendingTransactionNotices: [String]
    static let empty = LedgerData(transactions: [], categories: [], budgets: [], rules: [], preferences: .defaults,
                                  pendingAlerts: [], pendingTransactionNotices: [])
}
struct LedgerTransaction: Decodable, Identifiable {
    let id: String
    let amountMinor: Int64?
    let currency: String
    let accountingBaisa: Int64?
    let direction: String?
    let effect: String
    let type: String
    let occurredAtMillis: Int64
    let datePrecision: String
    let counterparty: String?
    let merchant: String?
    let categoryId: String?
    let categoryStatus: String
    let financialVerified: Bool
    let reviewed: Bool
    let note: String
    let duplicateOf: String?
    let originalId: String?
    let warnings: [String]
    let isDemo: Bool
}
struct LedgerCategory: Decodable, Identifiable { let id: String; let name: String; let symbol: String }
struct LedgerBudget: Decodable { let categoryId: String; let weeklyBaisa: Int64?; let monthlyBaisa: Int64? }
struct LedgerRule: Decodable, Identifiable { let merchant: String; let categoryId: String; var id: String { merchant } }
struct LedgerPreferences: Codable {
    var weekStart: Int32
    var monthStartDay: Int32
    var timeZone: String
    var reminderHour: Int32
    var remindersEnabled: Bool
    var showAmountsOnLockScreen: Bool
    static let defaults = LedgerPreferences(weekStart: 6, monthStartDay: 1, timeZone: "Asia/Muscat",
                                           reminderHour: 20, remindersEnabled: true, showAmountsOnLockScreen: false)
}
struct LedgerAlert: Decodable { let key: String; let title: String; let body: String }
struct LedgerImport: Decodable { let ids: [String]; let ignored: Int; let duplicates: Int; let reasons: [String] }
struct LedgerReport: Decodable {
    let grossExpense: Int64; let refunds: Int64; let netExpense: Int64; let incoming: Int64
    let unclassified: Int64; let reviewCount: Int; let excludedCount: Int; let categories: [CategoryReport]
    static let empty = LedgerReport(grossExpense: 0, refunds: 0, netExpense: 0, incoming: 0,
                                    unclassified: 0, reviewCount: 0, excludedCount: 0, categories: [])
}
struct CategoryReport: Decodable, Identifiable {
    let categoryId: String; let name: String; let symbol: String
    let weeklySpent: Int64; let monthlySpent: Int64; let weeklyLimit: Int64?; let monthlyLimit: Int64?
    var id: String { categoryId }
}
struct EditPayload: Encodable {
    let id: String?; let amountBaisa: Int64; let direction: String; let effect: String
    let categoryId: String?; let counterparty: String?; let note: String
    let occurredAtMillis: Int64; let rememberMerchant: Bool
    let originalId: String?
}
func baisa(_ amount: Int64) -> String { Money.shared.format(baisa: amount) }
func millis(_ date: Date = Date()) -> Int64 { Int64(date.timeIntervalSince1970 * 1000) }
func jsonString<T: Encodable>(_ object: T) throws -> String { String(decoding: try JSONEncoder().encode(object), as: UTF8.self) }
func decode<T: Decodable>(_ json: String, as type: T.Type) throws -> T { try JSONDecoder().decode(type, from: Data(json.utf8)) }
enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
}
