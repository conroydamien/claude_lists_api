import SwiftUI

struct ContentView: View {
    @EnvironmentObject var authManager: AuthManager
    @EnvironmentObject var viewModel: MainViewModel

    var body: some View {
        Group {
            if authManager.isLoading {
                // Splash screen
                VStack {
                    ProgressView()
                    Text("Loading...")
                        .foregroundColor(.secondary)
                }
            } else if authManager.isSignedIn {
                ListsView()
                    .onAppear {
                        viewModel.setup(authManager: authManager)
                    }
            } else {
                SignInView()
            }
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(AuthManager())
        .environmentObject(MainViewModel())
}
