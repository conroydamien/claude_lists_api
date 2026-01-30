# Court Lists Android App

Native Android app for viewing Irish Circuit Court legal diary data.

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

## Setup

1. Open the `android/` folder in Android Studio
2. Sync Gradle files
3. Configure the API URL in `app/build.gradle.kts`:

```kotlin
// For emulator connecting to host machine
buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/api\"")

// For physical device on same LAN
buildConfigField("String", "API_BASE_URL", "\"http://192.168.x.x:8080/api\"")
```

## Running

### With Emulator

1. Start the backend: `podman-compose up -d`
2. Run the app on an emulator (10.0.2.2 maps to host localhost)

### With Physical Device

1. Start the backend: `podman-compose up -d`
2. Start LAN proxy: `./lan-proxy.sh`
3. Update `API_BASE_URL` to your LAN IP
4. Run the app on device (must be on same network)

## Features

- [x] List selection with date/venue filters
- [x] Items display with headers
- [x] Mark items as done
- [x] Comment counts
- [ ] Comments screen (TODO)
- [ ] WebSocket real-time updates (TODO)
- [ ] OAuth2 authentication (TODO - currently stubbed)

## Architecture

- **Kotlin** + **Jetpack Compose** for UI
- **Retrofit** + **OkHttp** for networking
- **MVVM** with StateFlow for state management

## Auth

Authentication is currently stubbed. The `ApiClient` has placeholder methods for setting auth tokens. When integrating Google/Azure OAuth:

1. Add AppAuth dependency
2. Configure OAuth client in Google/Azure console
3. Implement login flow in `AuthManager` (to be created)
4. Store tokens securely with EncryptedSharedPreferences
