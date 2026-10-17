import SwiftUI

@main @MainActor struct MasroofiApp: App {
    @UIApplicationDelegateAdaptor(NotificationDelegate.self) var delegate
    var body: some Scene {
        WindowGroup { ContentView().environment(\.layoutDirection, .rightToLeft).tint(Color(red: 0.08, green: 0.36, blue: 0.27)) }
    }
}
