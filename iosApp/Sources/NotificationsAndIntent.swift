import Foundation
import AppIntents
import UserNotifications
import UIKit

struct ImportBankMessageIntent: AppIntent {
    static var title: LocalizedStringResource = "تسجيل رسالة مالية"
    static var description = IntentDescription("استخراج المبلغ والجهة من رسالة، وحفظ العملية محليًا دون الاحتفاظ بالنص.")
    static var openAppWhenRun = false
    static var authenticationPolicy: IntentAuthenticationPolicy = .alwaysAllowed
    @Parameter(title: "نص الرسالة") var message: String
    @MainActor func perform() async throws -> some IntentResult {
        _ = try LedgerStore.shared.importMessage(message, source: "shortcuts")
        await Notifications.shared.flush()
        return .result()
    }
}
struct MasroofiShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: ImportBankMessageIntent(), phrases: ["سجل رسالة في \(.applicationName)"], shortTitle: "تسجيل رسالة", systemImageName: "text.bubble")
    }
}

@MainActor final class Notifications {
    static let shared = Notifications()
    private var flushing = false
    private var flushRequested = false
    func ask() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
        await flush()
    }
    func flush() async {
        guard !flushing else { flushRequested = true; return }
        flushing = true; defer { flushing = false }
        repeat {
            flushRequested = false
            await flushOnce()
        } while flushRequested
    }
    private func flushOnce() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        let store = LedgerStore.shared
        do { try store.reloadIfNeeded() } catch { return }
        let categories = store.state.transactions.filter { !$0.reviewed }.prefix(64).map { transaction -> UNNotificationCategory in
            let ids = ([transaction.categoryId].compactMap { $0 } + ["fuel", "telecom", "groceries"]).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.prefix(2)
            let canConfirmCategory = transaction.financialVerified && transaction.effect != "unresolved" && transaction.duplicateOf == nil
            let suggestions = canConfirmCategory ? ids.compactMap { id -> UNNotificationAction? in
                guard let category = store.state.categories.first(where: { $0.id == id }) else { return nil }
                return UNNotificationAction(identifier: "cat:\(id)", title: category.name, options: [.authenticationRequired])
            } : []
            let actions = suggestions + [UNNotificationAction(identifier: "review", title: canConfirmCategory ? "أخرى" : "مراجعة", options: [.foreground, .authenticationRequired])]
            return UNNotificationCategory(identifier: "txn.\(transaction.id)", actions: actions, intentIdentifiers: [])
        }
        center.setNotificationCategories(Set(categories))
        for id in store.state.pendingTransactionNotices {
            guard let transaction = store.state.transactions.first(where: { $0.id == id }) else { continue }
            let content = UNMutableNotificationContent()
            content.title = "مصروفي — عملية جديدة"
            content.body = store.state.preferences.showAmountsOnLockScreen
                ? "\(transaction.accountingBaisa.map(baisa) ?? "راجع المبلغ") ر.ع • \(transaction.counterparty ?? "حدد الفئة")"
                : "وصلت عملية مالية. افتح التطبيق لمراجعة المبلغ والسبب."
            content.categoryIdentifier = "txn.\(id)"
            content.userInfo = ["transaction": id]; content.sound = .default
            do {
                try await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
                try store.change { $0.acknowledgeTransactionNotice(id: id) }
            } catch { return }
        }
        do { _ = try store.change { $0.takeBudgetAlerts(nowMillis: millis()) } } catch { return }
        for alert in store.state.pendingAlerts {
            let content = UNMutableNotificationContent()
            content.title = store.state.preferences.showAmountsOnLockScreen ? alert.title : "مصروفي — حدود الإنفاق"
            content.body = store.state.preferences.showAmountsOnLockScreen ? alert.body : "راجع حدود الإنفاق داخل التطبيق."
            content.sound = .default
            do {
                try await center.add(UNNotificationRequest(identifier: alert.key, content: content, trigger: nil))
                try store.change { $0.acknowledgeBudgetAlert(key: alert.key) }
            } catch { return }
        }
        center.removePendingNotificationRequests(withIdentifiers: ["daily-review"])
        let count = store.state.transactions.filter { !$0.reviewed }.count
        if count > 0 && store.state.preferences.remindersEnabled {
            let content = UNMutableNotificationContent(); content.title = "مراجعة عملياتك"; content.body = "لديك \(count) عمليات تحتاج مراجعة"; content.sound = .default
            var components = DateComponents(); components.hour = Int(store.state.preferences.reminderHour); components.minute = 0
            components.timeZone = TimeZone(identifier: store.state.preferences.timeZone)
            try? await center.add(UNNotificationRequest(identifier: "daily-review", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: true)))
        }
    }
}
final class NotificationDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        return true
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        Task { @MainActor in
            defer { completionHandler() }
            guard response.actionIdentifier != UNNotificationDismissActionIdentifier else { return }
            if let id = response.notification.request.content.userInfo["transaction"] as? String {
                if response.actionIdentifier.hasPrefix("cat:") {
                    guard let transaction = LedgerStore.shared.state.transactions.first(where: { $0.id == id }),
                          transaction.financialVerified, transaction.effect != "unresolved", transaction.duplicateOf == nil else {
                        LedgerStore.shared.selectedTransaction = id; return
                    }
                    LedgerStore.shared.perform { try LedgerStore.shared.change { try $0.assignCategory(id: id, categoryId: String(response.actionIdentifier.dropFirst(4)), remember: false) } }
                    center.removeDeliveredNotifications(withIdentifiers: [id])
                } else { LedgerStore.shared.selectedTransaction = id }
            }
        }
    }
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}
