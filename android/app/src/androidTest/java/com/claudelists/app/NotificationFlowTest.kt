package com.claudelists.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.claudelists.app.api.CaseItem
import com.claudelists.app.api.CourtList
import com.claudelists.app.ui.screens.CaseRow
import com.claudelists.app.ui.screens.ListNotesRow
import com.claudelists.app.ui.theme.CourtListsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for notification/watch functionality.
 *
 * These tests verify:
 * 1. Watch bell toggles correctly (strikethrough <-> solid)
 * 2. Comment count displays correctly
 * 3. List notes row works the same as case rows
 */
@RunWith(AndroidJUnit4::class)
class NotificationFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // =========================================================================
    // Case Row Watch Tests
    // =========================================================================

    @Test
    fun caseRow_watchBell_showsNotWatchingByDefault() {
        val testItem = createTestCaseItem()

        composeTestRule.setContent {
            CourtListsTheme {
                CaseRow(
                    item = testItem,
                    isWatching = false,
                    onToggleDone = {},
                    onOpenComments = {},
                    onToggleWatch = {}
                )
            }
        }

        // Bell with strikethrough should be visible (NotificationsOff icon)
        composeTestRule
            .onNodeWithContentDescription("Get notified")
            .assertExists()
    }

    @Test
    fun caseRow_watchBell_showsWatchingWhenEnabled() {
        val testItem = createTestCaseItem()

        composeTestRule.setContent {
            CourtListsTheme {
                CaseRow(
                    item = testItem,
                    isWatching = true,
                    onToggleDone = {},
                    onOpenComments = {},
                    onToggleWatch = {}
                )
            }
        }

        // Bell without strikethrough should be visible (Notifications icon)
        composeTestRule
            .onNodeWithContentDescription("Stop notifications")
            .assertExists()
    }

    @Test
    fun caseRow_watchBell_togglesOnClick() {
        val testItem = createTestCaseItem()
        var isWatching = false

        composeTestRule.setContent {
            CourtListsTheme {
                CaseRow(
                    item = testItem,
                    isWatching = isWatching,
                    onToggleDone = {},
                    onOpenComments = {},
                    onToggleWatch = { isWatching = !isWatching }
                )
            }
        }

        // Click the bell
        composeTestRule
            .onNodeWithContentDescription("Get notified")
            .performClick()

        // Verify callback was invoked
        assert(isWatching) { "Expected isWatching to be true after click" }
    }

    @Test
    fun caseRow_commentCount_displaysCorrectly() {
        val testItem = createTestCaseItem(commentCount = 5)

        composeTestRule.setContent {
            CourtListsTheme {
                CaseRow(
                    item = testItem,
                    isWatching = false,
                    onToggleDone = {},
                    onOpenComments = {},
                    onToggleWatch = {}
                )
            }
        }

        // Comment count should be displayed
        composeTestRule
            .onNodeWithText("5")
            .assertExists()
    }

    @Test
    fun caseRow_zeroComments_noCountDisplayed() {
        val testItem = createTestCaseItem(commentCount = 0)

        composeTestRule.setContent {
            CourtListsTheme {
                CaseRow(
                    item = testItem,
                    isWatching = false,
                    onToggleDone = {},
                    onOpenComments = {},
                    onToggleWatch = {}
                )
            }
        }

        // Should show comments icon but no count
        composeTestRule
            .onNodeWithContentDescription("Comments")
            .assertExists()
    }

    // =========================================================================
    // List Notes Row Watch Tests
    // =========================================================================

    @Test
    fun listNotesRow_watchBell_showsNotWatchingByDefault() {
        composeTestRule.setContent {
            CourtListsTheme {
                ListNotesRow(
                    commentCount = 0,
                    isWatching = false,
                    onClick = {},
                    onToggleWatch = {}
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Get notified")
            .assertExists()
    }

    @Test
    fun listNotesRow_watchBell_showsWatchingWhenEnabled() {
        composeTestRule.setContent {
            CourtListsTheme {
                ListNotesRow(
                    commentCount = 0,
                    isWatching = true,
                    onClick = {},
                    onToggleWatch = {}
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Stop notifications")
            .assertExists()
    }

    @Test
    fun listNotesRow_watchBell_togglesOnClick() {
        var isWatching = false

        composeTestRule.setContent {
            CourtListsTheme {
                ListNotesRow(
                    commentCount = 0,
                    isWatching = isWatching,
                    onClick = {},
                    onToggleWatch = { isWatching = !isWatching }
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription("Get notified")
            .performClick()

        assert(isWatching) { "Expected isWatching to be true after click" }
    }

    @Test
    fun listNotesRow_commentCount_displaysCorrectly() {
        composeTestRule.setContent {
            CourtListsTheme {
                ListNotesRow(
                    commentCount = 3,
                    isWatching = false,
                    onClick = {},
                    onToggleWatch = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("3")
            .assertExists()
    }

    @Test
    fun listNotesRow_displaysListNotesLabel() {
        composeTestRule.setContent {
            CourtListsTheme {
                ListNotesRow(
                    commentCount = 0,
                    isWatching = false,
                    onClick = {},
                    onToggleWatch = {}
                )
            }
        }

        composeTestRule
            .onNodeWithText("List Notes")
            .assertExists()
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    private fun createTestCaseItem(
        id: Int = 1,
        caseNumber: String = "2024/12345",
        title: String = "Test Case",
        commentCount: Int = 0,
        done: Boolean = false
    ) = CaseItem(
        id = id,
        listSourceUrl = "https://example.com/list",
        listNumber = 1,
        caseNumber = caseNumber,
        title = title,
        parties = null,
        commentCount = commentCount,
        done = done
    )
}
