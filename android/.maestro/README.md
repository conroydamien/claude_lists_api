# Maestro E2E Tests for Court Lists App

End-to-end tests using [Maestro](https://maestro.mobile.dev/) for the Court Lists Android app.

## Prerequisites

### Install Maestro

**macOS (Homebrew):**
```bash
brew install maestro
```

**macOS/Linux (curl):**
```bash
curl -Ls "https://get.maestro.mobile.dev" | bash
```

**Windows:**
```powershell
iwr -useb "https://get.maestro.mobile.dev/windows" | iex
```

### Verify Installation
```bash
maestro --version
```

### Requirements
- Android emulator running OR physical device connected via ADB
- App installed on the device (`com.claudelists.app`)
- User already signed in (tests assume authenticated state)

## Running Tests

### Run All Flows
```bash
cd /Users/damien/src/claude_lists_api/android
maestro test .maestro/
```

### Run a Single Flow
```bash
maestro test .maestro/flow1_list_navigation.yaml
```

### Run with Verbose Output
```bash
maestro test --debug .maestro/flow1_list_navigation.yaml
```

### Run in Continuous Mode (re-runs on file changes)
```bash
maestro studio .maestro/flow1_list_navigation.yaml
```

## Test Flows

| Flow | Description |
|------|-------------|
| `flow1_list_navigation.yaml` | Navigate from home to list items and back |
| `flow2_watch_toggle.yaml` | Toggle watch/notification bell on List Notes |
| `flow3_notification_navigation.yaml` | Open notifications and navigate to content |
| `flow4_comments.yaml` | Open and close comments sheet |

## Notes

### Test Assumptions
- All tests assume the user is already signed in
- Tests expect at least one court list to be available
- Network connectivity is required for data loading

### Content Descriptions
Tests rely on `contentDescription` attributes in Compose for element identification:
- "Notifications" - notification bell button
- "Back" - back navigation button
- "Close" - close button on sheets
- "Get notified" / "Stop notifications" - watch toggle bell

### Troubleshooting

**Element not found:**
- Ensure the app is installed and running
- Check that the user is signed in
- Verify network connectivity for data loading

**Timeout errors:**
- Increase `defaultTimeout` in config.yaml
- Check emulator/device performance

**Sheet not closing:**
- Some sheets require swipe gestures; Maestro's `back` command may work as fallback

## CI Integration

For CI/CD pipelines, you can run Maestro in headless mode:

```bash
# Start emulator
emulator -avd Pixel_6_API_33 -no-window -no-audio &

# Wait for boot
adb wait-for-device

# Install app
./gradlew installDebug

# Run tests
maestro test .maestro/ --format junit --output test-results.xml
```
