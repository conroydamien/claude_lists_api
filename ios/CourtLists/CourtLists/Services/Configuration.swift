import Foundation

enum Configuration {
    // API Configuration
    static let apiBaseUrl = "https://fbiissfiqgtlenxkjuwv.supabase.co"
    static let apiAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZiaWlzc2ZpcWd0bGVueGtqdXd2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjUzMTU1NDYsImV4cCI6MjA4MDg5MTU0Nn0.QlPzr2rxOihNz-NeaLdivG2ItDOX6Qz84yIsKU0Mc2E"

    // Google Sign-In Client ID (Web Client - you may need to create an iOS client in Google Console)
    static let googleClientId = "807765424446-baktcv20bbq38s3t4u7j744cl558qbot.apps.googleusercontent.com"

    // WebSocket Configuration
    static var realtimeUrl: String {
        apiBaseUrl.replacingOccurrences(of: "https://", with: "wss://") + "/realtime/v1/websocket"
    }
}
