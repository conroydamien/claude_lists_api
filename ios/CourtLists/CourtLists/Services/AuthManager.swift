import Foundation
import GoogleSignIn
import CryptoKit

@MainActor
class AuthManager: ObservableObject {
    @Published var currentUser: User?
    @Published var isLoading = true
    @Published var error: String?

    /// The Google ID token used for API authentication
    private(set) var idToken: String?

    /// Supabase access token for realtime subscriptions
    private(set) var supabaseAccessToken: String?

    /// UUID derived from Google ID (matches backend format)
    private(set) var userUuid: String?

    init() {
        restoreSession()
    }

    private func restoreSession() {
        GIDSignIn.sharedInstance.restorePreviousSignIn { [weak self] user, error in
            Task { @MainActor in
                if let user = user {
                    await self?.setUser(from: user)
                }
                self?.isLoading = false
            }
        }
    }

    func signIn(presenting: UIViewController) {
        let config = GIDConfiguration(clientID: Configuration.googleClientId)
        GIDSignIn.sharedInstance.configuration = config

        GIDSignIn.sharedInstance.signIn(withPresenting: presenting) { [weak self] result, error in
            Task { @MainActor in
                if let error = error {
                    print("Sign-in error: \(error.localizedDescription)")
                    self?.error = error.localizedDescription
                    return
                }

                guard let user = result?.user else {
                    self?.error = "Failed to get user"
                    return
                }

                await self?.setUser(from: user)
            }
        }
    }

    private func setUser(from googleUser: GIDGoogleUser) async {
        // Store the ID token for API calls
        idToken = googleUser.idToken?.tokenString

        // Compute UUID from Google ID (matches backend format)
        let googleId = googleUser.userID ?? ""
        userUuid = googleIdToUuid(googleId)

        // Create user from Google profile
        currentUser = User(
            id: googleId,
            email: googleUser.profile?.email,
            displayName: googleUser.profile?.name ?? "User",
            photoUrl: googleUser.profile?.imageURL(withDimension: 100)
        )

        print("User signed in: \(currentUser?.email ?? "unknown"), uuid: \(userUuid ?? "none")")

        // Exchange Google token for Supabase session (for realtime)
        if let googleIdToken = idToken {
            await exchangeForSupabaseSession(googleIdToken: googleIdToken)
        }
    }

    /// Refresh the ID token if needed (called before API requests)
    func refreshTokenIfNeeded() async {
        guard let user = GIDSignIn.sharedInstance.currentUser else { return }

        do {
            try await user.refreshTokensIfNeeded()
            await MainActor.run {
                self.idToken = user.idToken?.tokenString
            }
        } catch {
            print("Token refresh failed: \(error)")
        }
    }

    /// Exchange Google ID token for Supabase session (for realtime)
    private func exchangeForSupabaseSession(googleIdToken: String) async {
        let url = URL(string: "\(Configuration.apiBaseUrl)/auth/v1/token?grant_type=id_token")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(Configuration.apiAnonKey, forHTTPHeaderField: "apikey")

        let body: [String: Any] = [
            "provider": "google",
            "id_token": googleIdToken
        ]

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, response) = try await URLSession.shared.data(for: request)

            if let httpResponse = response as? HTTPURLResponse {
                print("Supabase token exchange status: \(httpResponse.statusCode)")
            }

            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let accessToken = json["access_token"] as? String {
                self.supabaseAccessToken = accessToken
                print("Supabase session obtained")
            } else {
                let responseStr = String(data: data, encoding: .utf8) ?? "no response"
                print("Supabase token exchange failed: \(responseStr)")
            }
        } catch {
            print("Supabase token exchange error: \(error)")
        }
    }


    func signOut() {
        // Disconnect completely to clear cached credentials
        GIDSignIn.sharedInstance.disconnect { _ in
            print("Google Sign-In disconnected")
        }
        GIDSignIn.sharedInstance.signOut()
        currentUser = nil
        idToken = nil
        supabaseAccessToken = nil
        userUuid = nil
    }

    var isSignedIn: Bool {
        currentUser != nil && idToken != nil
    }

    /// Whether realtime is available (requires Supabase token)
    var isRealtimeAvailable: Bool {
        supabaseAccessToken != nil
    }

    var userId: String? {
        currentUser?.id
    }

    var displayName: String {
        currentUser?.displayName ?? "User"
    }

    /// Convert Google ID to deterministic UUID (matches backend format exactly)
    private func googleIdToUuid(_ googleId: String) -> String {
        let data = "google:\(googleId)".data(using: .utf8)!
        let hash = SHA256.hash(data: data)
        let hashBytes = Array(hash)

        // Take first 16 bytes and format as hex string (32 chars)
        let hex = hashBytes.prefix(16).map { String(format: "%02x", $0) }.joined()

        // Format: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx
        // Backend JS: hex.slice(0,8)-hex.slice(8,12)-4+hex.slice(13,16)-variantByte+hex.slice(18,20)-hex.slice(20,32)
        let segment1 = String(hex.prefix(8))
        let segment2 = String(hex.dropFirst(8).prefix(4))
        let segment3partial = String(hex.dropFirst(13).prefix(3))

        // Parse hex chars 16-17 as byte, apply variant bits
        let hexChars16_17 = String(hex.dropFirst(16).prefix(2))
        let byte8 = UInt8(hexChars16_17, radix: 16) ?? 0
        let variantByte = (byte8 & 0x3f) | 0x80

        let segment4partial = String(hex.dropFirst(18).prefix(2))
        let segment5 = String(hex.dropFirst(20).prefix(12))

        return "\(segment1)-\(segment2)-4\(segment3partial)-\(String(format: "%02x", variantByte))\(segment4partial)-\(segment5)"
    }
}
