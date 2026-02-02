#!/usr/bin/env python3
"""
Exploratory Test Agent for Android Apps

An AI-powered agent that:
- Explores the app like a real user
- Finds bugs and issues
- Suggests UX/feature improvements
- Maintains a test journal

Usage:
    python explorer.py --app com.claudelists.app --duration 30
"""

import subprocess
import base64
import json
import os
import sys
import time
import argparse
from datetime import datetime
from pathlib import Path

# Requires: pip install anthropic
try:
    import anthropic
except ImportError:
    print("Install anthropic: pip install anthropic")
    sys.exit(1)


class ExplorerAgent:
    def __init__(self, app_id: str, output_dir: str = "test_results"):
        self.app_id = app_id
        self.client = anthropic.Anthropic()
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(exist_ok=True)

        # Session state
        self.session_id = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.screenshots_dir = self.output_dir / self.session_id / "screenshots"
        self.screenshots_dir.mkdir(parents=True, exist_ok=True)

        # Memory
        self.visited_screens = []
        self.actions_taken = []
        self.bugs_found = []
        self.improvements = []
        self.step_count = 0

        # App context
        self.app_context = """
        App: Court Lists (com.claudelists.app)
        Purpose: View court case listings, add comments, get notifications

        Key Features:
        - Home screen: List of court venues by date
        - Items screen: Cases in a selected list
        - List Notes: Comments attached to the whole list
        - Watch/Notifications: Bell icon to watch cases for updates
        - Comments: Chat bubble to view/add comments per case

        Known UI Elements:
        - Date picker at top of home screen
        - Venue filter dropdown
        - Notification bell with badge (top right)
        - "List Notes" row at top of items list
        - Bell icons on each case row (watch toggle)
        - Chat bubble icons (comments)
        - Back arrow to navigate back
        """

    def capture_screenshot(self) -> str:
        """Capture screenshot and return base64 encoded image."""
        self.step_count += 1
        screenshot_path = self.screenshots_dir / f"step_{self.step_count:04d}.png"

        # Capture via adb
        result = subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            capture_output=True
        )

        if result.returncode != 0:
            raise Exception(f"Screenshot failed: {result.stderr.decode()}")

        # Save locally
        with open(screenshot_path, "wb") as f:
            f.write(result.stdout)

        # Return base64
        return base64.standard_b64encode(result.stdout).decode("utf-8")

    def get_logcat_errors(self) -> str:
        """Get recent errors from logcat."""
        result = subprocess.run(
            ["adb", "logcat", "-d", "-t", "50", "*:E"],
            capture_output=True,
            text=True
        )
        # Filter to app-related errors
        lines = [l for l in result.stdout.split("\n") if self.app_id in l or "AndroidRuntime" in l]
        return "\n".join(lines[-10:]) if lines else "No recent errors"

    def execute_action(self, action: dict) -> str:
        """Execute an action via adb."""
        action_type = action.get("type")
        result = "Unknown action"

        try:
            if action_type == "tap":
                x, y = action["x"], action["y"]
                subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
                result = f"Tapped at ({x}, {y})"

            elif action_type == "type":
                text = action["text"].replace(" ", "%s").replace("'", "\\'")
                subprocess.run(["adb", "shell", "input", "text", text], check=True)
                result = f"Typed: {action['text']}"

            elif action_type == "swipe":
                x1, y1 = action["start_x"], action["start_y"]
                x2, y2 = action["end_x"], action["end_y"]
                duration = action.get("duration", 300)
                subprocess.run(
                    ["adb", "shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration)],
                    check=True
                )
                result = f"Swiped from ({x1},{y1}) to ({x2},{y2})"

            elif action_type == "back":
                subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_BACK"], check=True)
                result = "Pressed back button"

            elif action_type == "home":
                subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_HOME"], check=True)
                result = "Pressed home button"

            elif action_type == "wait":
                time.sleep(action.get("seconds", 2))
                result = f"Waited {action.get('seconds', 2)} seconds"

            elif action_type == "launch":
                subprocess.run(
                    ["adb", "shell", "monkey", "-p", self.app_id, "-c", "android.intent.category.LAUNCHER", "1"],
                    check=True,
                    capture_output=True
                )
                result = f"Launched {self.app_id}"

        except subprocess.CalledProcessError as e:
            result = f"Action failed: {e}"

        self.actions_taken.append({"step": self.step_count, "action": action, "result": result})
        return result

    def think(self, screenshot_b64: str) -> dict:
        """Ask Claude to analyze screen and decide next action."""

        memory_summary = f"""
        Steps taken: {self.step_count}
        Screens visited: {len(self.visited_screens)}
        Bugs found: {len(self.bugs_found)}
        Improvements suggested: {len(self.improvements)}

        Recent actions:
        {json.dumps(self.actions_taken[-5:], indent=2) if self.actions_taken else "None yet"}

        Bugs found so far:
        {json.dumps(self.bugs_found, indent=2) if self.bugs_found else "None yet"}
        """

        logcat_errors = self.get_logcat_errors()

        prompt = f"""You are an expert QA tester and UX reviewer exploring an Android app.

{self.app_context}

YOUR MEMORY:
{memory_summary}

RECENT LOGCAT ERRORS:
{logcat_errors}

Look at the current screenshot and respond with a JSON object:

{{
    "screen_description": "Brief description of what you see",
    "is_new_screen": true/false,
    "observations": {{
        "bugs": ["List any bugs, glitches, or errors you notice"],
        "ux_issues": ["List any UX problems - confusing UI, bad flow, etc"],
        "improvements": ["Suggest features or enhancements a user might want"]
    }},
    "reasoning": "What should we test next and why?",
    "action": {{
        "type": "tap|type|swipe|back|wait|launch",
        // For tap: "x": 540, "y": 1200
        // For type: "text": "hello"
        // For swipe: "start_x", "start_y", "end_x", "end_y"
        // For wait: "seconds": 2
    }}
}}

TESTING PRIORITIES:
1. Explore screens you haven't visited
2. Test edge cases (empty states, long text, special characters)
3. Try unexpected user behaviors
4. Verify notifications/watch functionality works
5. Check error handling (what if network fails?)
6. Test navigation flows (can you always get back?)

IMPORTANT - RATE LIMITING:
- Do NOT tap refresh repeatedly - the app fetches from courts.ie
- Do NOT change dates rapidly - each date change triggers an API call
- Focus on UI interactions within already-loaded screens
- Prefer testing comments, watch toggles, navigation over data refreshes
- Maximum 1 date change per 10 steps

Be creative but realistic - act like a real user who might make mistakes.
Output ONLY valid JSON, no markdown."""

        response = self.client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=1024,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "image/png",
                                "data": screenshot_b64
                            }
                        },
                        {
                            "type": "text",
                            "text": prompt
                        }
                    ]
                }
            ]
        )

        # Parse response
        response_text = response.content[0].text
        try:
            return json.loads(response_text)
        except json.JSONDecodeError:
            # Try to extract JSON from response
            import re
            match = re.search(r'\{.*\}', response_text, re.DOTALL)
            if match:
                return json.loads(match.group())
            raise

    def run(self, duration_minutes: int = 30, max_steps: int = 100):
        """Run the exploration session."""
        print(f"Starting exploration session: {self.session_id}")
        print(f"Duration: {duration_minutes} minutes, Max steps: {max_steps}")
        print(f"Output: {self.output_dir / self.session_id}")
        print("-" * 50)

        start_time = time.time()
        end_time = start_time + (duration_minutes * 60)

        # Launch app
        self.execute_action({"type": "launch"})
        time.sleep(3)

        while time.time() < end_time and self.step_count < max_steps:
            try:
                # Capture current state
                screenshot = self.capture_screenshot()

                # Think about what to do
                decision = self.think(screenshot)

                # Log observations
                print(f"\n[Step {self.step_count}] {decision.get('screen_description', 'Unknown screen')}")

                if decision.get("is_new_screen"):
                    self.visited_screens.append(decision.get("screen_description"))
                    print(f"  📍 New screen discovered!")

                # Collect bugs and improvements
                observations = decision.get("observations", {})
                for bug in observations.get("bugs", []):
                    if bug and bug not in [b["description"] for b in self.bugs_found]:
                        self.bugs_found.append({
                            "step": self.step_count,
                            "screen": decision.get("screen_description"),
                            "description": bug
                        })
                        print(f"  🐛 Bug: {bug}")

                for improvement in observations.get("improvements", []):
                    if improvement and improvement not in [i["description"] for i in self.improvements]:
                        self.improvements.append({
                            "step": self.step_count,
                            "screen": decision.get("screen_description"),
                            "description": improvement
                        })
                        print(f"  💡 Improvement: {improvement}")

                for ux_issue in observations.get("ux_issues", []):
                    if ux_issue:
                        print(f"  ⚠️  UX: {ux_issue}")

                # Execute action
                action = decision.get("action", {})
                print(f"  🎯 Action: {decision.get('reasoning', 'No reasoning')}")
                result = self.execute_action(action)
                print(f"  ✅ {result}")

                # Wait for UI to settle - longer delay to avoid API thrashing
                time.sleep(5)

            except KeyboardInterrupt:
                print("\n\nSession interrupted by user")
                break
            except Exception as e:
                print(f"\n  ❌ Error: {e}")
                time.sleep(2)

        # Generate report
        self.generate_report()

    def generate_report(self):
        """Generate final test report."""
        report_path = self.output_dir / self.session_id / "report.md"

        report = f"""# Exploratory Test Report

**Session ID:** {self.session_id}
**App:** {self.app_id}
**Date:** {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}
**Steps:** {self.step_count}

---

## Summary

| Metric | Count |
|--------|-------|
| Steps taken | {self.step_count} |
| Screens discovered | {len(self.visited_screens)} |
| Bugs found | {len(self.bugs_found)} |
| Improvements suggested | {len(self.improvements)} |

---

## Screens Visited

{chr(10).join(f"- {s}" for s in self.visited_screens) if self.visited_screens else "None recorded"}

---

## Bugs Found

"""
        if self.bugs_found:
            for i, bug in enumerate(self.bugs_found, 1):
                report += f"""### Bug {i}
- **Step:** {bug['step']}
- **Screen:** {bug['screen']}
- **Description:** {bug['description']}

"""
        else:
            report += "No bugs found during this session.\n\n"

        report += """---

## Suggested Improvements

"""
        if self.improvements:
            for i, imp in enumerate(self.improvements, 1):
                report += f"""### Improvement {i}
- **Step:** {imp['step']}
- **Screen:** {imp['screen']}
- **Suggestion:** {imp['description']}

"""
        else:
            report += "No improvements suggested during this session.\n\n"

        report += f"""---

## Actions Log

See `actions.json` for full action history.

---

## Screenshots

Screenshots saved in `screenshots/` directory.

---

*Generated by Explorer Agent*
"""

        with open(report_path, "w") as f:
            f.write(report)

        # Save actions log
        actions_path = self.output_dir / self.session_id / "actions.json"
        with open(actions_path, "w") as f:
            json.dump({
                "session_id": self.session_id,
                "app_id": self.app_id,
                "steps": self.step_count,
                "actions": self.actions_taken,
                "bugs": self.bugs_found,
                "improvements": self.improvements,
                "screens": self.visited_screens
            }, f, indent=2)

        print("\n" + "=" * 50)
        print("SESSION COMPLETE")
        print("=" * 50)
        print(f"Report: {report_path}")
        print(f"Actions: {actions_path}")
        print(f"Screenshots: {self.screenshots_dir}")
        print(f"\nBugs found: {len(self.bugs_found)}")
        print(f"Improvements: {len(self.improvements)}")


def main():
    parser = argparse.ArgumentParser(description="Exploratory Test Agent")
    parser.add_argument("--app", default="com.claudelists.app", help="App package ID")
    parser.add_argument("--duration", type=int, default=30, help="Duration in minutes")
    parser.add_argument("--steps", type=int, default=100, help="Max steps")
    parser.add_argument("--output", default="test_results", help="Output directory")

    args = parser.parse_args()

    # Check adb
    try:
        subprocess.run(["adb", "devices"], check=True, capture_output=True)
    except FileNotFoundError:
        print("Error: adb not found. Install Android SDK platform-tools.")
        sys.exit(1)

    agent = ExplorerAgent(args.app, args.output)
    agent.run(args.duration, args.steps)


if __name__ == "__main__":
    main()
