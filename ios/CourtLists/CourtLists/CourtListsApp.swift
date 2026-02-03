import SwiftUI
import GoogleSignIn

@main
struct CourtListsApp: App {
    @StateObject private var authManager = AuthManager()
    @StateObject private var viewModel = MainViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(authManager)
                .environmentObject(viewModel)
                .onOpenURL { url in
                    GIDSignIn.sharedInstance.handle(url)
                }
        }
    }
}
