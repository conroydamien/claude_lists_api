# Explorer Agent

An AI-powered exploratory test agent that acts like a real user - finding bugs and suggesting improvements.

## What It Does

```
┌─────────────────────────────────────────────────────────────┐
│                     EXPLORER AGENT                          │
├─────────────────────────────────────────────────────────────┤
│  1. Capture screenshot                                      │
│  2. Send to Claude: "What do you see? What should we try?"  │
│  3. Claude analyzes and suggests:                           │
│     - Bugs/glitches noticed                                 │
│     - UX issues                                             │
│     - Feature improvements                                  │
│     - Next action to take                                   │
│  4. Execute action via adb                                  │
│  5. Repeat until done                                       │
├─────────────────────────────────────────────────────────────┤
│  OUTPUT:                                                    │
│  - Test report (markdown)                                   │
│  - Screenshots of every step                                │
│  - Actions log (JSON)                                       │
│  - Bugs found                                               │
│  - Improvement suggestions                                  │
└─────────────────────────────────────────────────────────────┘
```

## Setup

```bash
# Install dependencies
pip install anthropic

# Ensure ANTHROPIC_API_KEY is set
export ANTHROPIC_API_KEY=your-key

# Ensure adb is available and emulator/device is connected
adb devices
```

## Usage

```bash
# Run for 30 minutes (default)
python explorer.py

# Run for 10 minutes, max 50 steps
python explorer.py --duration 10 --steps 50

# Specify app package
python explorer.py --app com.claudelists.app.dev

# Custom output directory
python explorer.py --output ./my_test_results
```

## Output

After a session, you'll find:

```
test_results/
└── 20250202_143000/          # Session ID (timestamp)
    ├── report.md             # Summary report
    ├── actions.json          # Full action log
    └── screenshots/
        ├── step_0001.png
        ├── step_0002.png
        └── ...
```

### Sample Report

```markdown
# Exploratory Test Report

## Summary
| Metric | Count |
|--------|-------|
| Steps taken | 47 |
| Screens discovered | 8 |
| Bugs found | 3 |
| Improvements suggested | 5 |

## Bugs Found

### Bug 1
- **Screen:** Items list
- **Description:** Comment count badge overlaps with case number on long titles

### Bug 2
- **Screen:** Notifications sheet
- **Description:** Tapping notification doesn't close the sheet first

## Suggested Improvements

### Improvement 1
- **Screen:** Home screen
- **Suggestion:** Add pull-to-refresh on the lists screen

### Improvement 2
- **Screen:** Comments sheet
- **Suggestion:** Show timestamp for each comment
```

## How It Thinks

The agent is given:
1. **App context** - What the app does, key features
2. **Memory** - Screens visited, actions taken, bugs found
3. **Current screenshot** - What's on screen now
4. **Logcat errors** - Recent crashes/errors

It then decides:
- Is this a new screen?
- Any bugs visible?
- Any UX issues?
- What improvements would help?
- What action to take next?

## Customization

Edit `explorer.py` to modify:

- `app_context` - Describe your app's features
- Testing priorities in the prompt
- Action types supported
- Report format

## Safety

- The agent can only interact via adb (tap, type, swipe)
- It cannot access files outside the app
- Run on an emulator for safety
- Monitor the session - Ctrl+C to stop anytime

## Cost

Uses Claude Sonnet with vision. Approximate costs:
- ~$0.01-0.02 per step (screenshot + response)
- 100 steps ≈ $1-2
- 30 minute session ≈ $1-3
