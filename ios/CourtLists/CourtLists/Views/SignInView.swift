import SwiftUI
import GoogleSignIn

struct SignInView: View {
    @EnvironmentObject var authManager: AuthManager

    var body: some View {
        VStack(spacing: 32) {
            Spacer()

            // App icon/logo
            Image(systemName: "building.columns")
                .font(.system(size: 80))
                .foregroundColor(.blue)

            // App name
            Text("Court Lists")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Manage your court cases")
                .font(.subheadline)
                .foregroundColor(.secondary)

            Spacer()

            // Sign in button
            Button {
                signIn()
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "person.circle.fill")
                        .font(.title2)
                    Text("Sign in with Google")
                        .font(.headline)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(Color.blue)
                .foregroundColor(.white)
                .cornerRadius(12)
            }
            .padding(.horizontal, 32)

            Spacer()
                .frame(height: 48)
        }
    }

    private func signIn() {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootViewController = windowScene.windows.first?.rootViewController else {
            return
        }

        authManager.signIn(presenting: rootViewController)
    }
}

#Preview {
    SignInView()
        .environmentObject(AuthManager())
}
