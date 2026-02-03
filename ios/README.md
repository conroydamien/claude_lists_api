# Court Lists iOS App

Native iOS app for Court Lists, built with SwiftUI.

## Requirements

- Xcode 15.0 or later
- iOS 16.0+ deployment target
- macOS Sonoma or later (for development)

## Setup

### 1. Open the Project

```bash
open ios/CourtLists/CourtLists.xcodeproj
```

### 2. Configure API Settings

Edit `CourtLists/Services/Configuration.swift`:

```swift
enum Configuration {
    static let apiBaseUrl = "https://your-project.supabase.co"
    static let apiAnonKey = "your-anon-key"
    static let googleClientId = "your-google-client-id.apps.googleusercontent.com"
}
```

### 3. Set Up Google Sign-In

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create or select your project
3. Enable Google Sign-In API
4. Create an iOS OAuth Client ID
5. Download the `GoogleService-Info.plist` (optional, but recommended)

Update `CourtLists/Info.plist`:
- Replace `YOUR_CLIENT_ID` in `CFBundleURLSchemes` with your actual client ID
- Replace `YOUR_CLIENT_ID` in `GIDClientID` with your actual client ID

### 4. Build and Run

1. Select your target device or simulator
2. Press Cmd+R or click the Run button

## Project Structure

```
CourtLists/
├── CourtListsApp.swift      # App entry point
├── ContentView.swift        # Root view with auth routing
├── Info.plist              # App configuration
├── Models/
│   ├── Models.swift        # API and UI models
│   └── User.swift          # User model
├── Views/
│   ├── ListsView.swift     # Court lists screen
│   ├── ItemsView.swift     # Cases screen
│   ├── CommentsSheet.swift # Comments modal
│   ├── NotificationsView.swift
│   └── SignInView.swift
├── ViewModels/
│   └── MainViewModel.swift # Main state management
├── Services/
│   ├── Configuration.swift # API configuration
│   ├── AuthManager.swift   # Google Sign-In
│   ├── CourtListsAPI.swift # REST API client
│   └── RealtimeClient.swift # WebSocket client
└── Resources/
    └── Assets.xcassets     # Images and colors
```

## Features

- View court lists by date
- Browse cases within lists
- Mark cases as done/undone
- Add/delete comments with urgency flag
- Watch cases for notifications
- Real-time updates via WebSocket
- Push notifications (TODO)

## Testing

### Simulator

The app works on iOS Simulator. Google Sign-In will use a web-based flow on simulator.

### Device

For physical device testing:
1. Connect your iPhone/iPad
2. Select it as the run destination
3. You may need to trust the developer certificate in Settings > General > Device Management

## Differences from Android

- Uses native URLSession instead of Ktor
- SwiftUI instead of Jetpack Compose
- @StateObject/@EnvironmentObject instead of ViewModel with StateFlow
- Native WebSocket API instead of OkHttp

## Known Issues

- Push notifications not yet implemented
- Deep linking from notifications not yet implemented
