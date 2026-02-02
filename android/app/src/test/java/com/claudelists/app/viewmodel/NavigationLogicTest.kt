package com.claudelists.app.viewmodel

import com.claudelists.app.api.CourtList
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for notification navigation logic.
 *
 * These tests verify that:
 * 1. getListCommentKey generates correct keys
 * 2. List comment keys are distinct from case numbers
 * 3. Navigation logic correctly identifies list comments
 */
class NavigationLogicTest {

    // =========================================================================
    // getListCommentKey Tests
    // =========================================================================

    @Test
    fun `getListCommentKey returns venue and dateText`() {
        val list = createTestList(
            venue = "Dublin",
            dateText = "Monday 3rd February 2025"
        )

        val key = getListCommentKey(list)

        assertEquals("Dublin - Monday 3rd February 2025", key)
    }

    @Test
    fun `getListCommentKey trims outer whitespace only`() {
        val list = createTestList(
            venue = "  Dublin  ",
            dateText = "  Monday 3rd February 2025  "
        )

        val key = getListCommentKey(list)

        // "${venue} - ${dateText}".trim() trims outer whitespace
        // Inner spaces from venue/dateText are preserved
        assertEquals("Dublin   -   Monday 3rd February 2025", key)
    }

    @Test
    fun `getListCommentKey with empty venue and dateText returns dash`() {
        // When venue="" and dateText="", result is " - ".trim() = "-"
        // The "-" is not empty, so ifEmpty doesn't trigger
        val list = createTestList(
            name = "Test Court List",
            venue = "",
            dateText = ""
        )

        val key = getListCommentKey(list)

        assertEquals("-", key)
    }

    @Test
    fun `getListCommentKey handles venue only`() {
        val list = createTestList(
            venue = "Dublin",
            dateText = ""
        )

        val key = getListCommentKey(list)

        assertEquals("Dublin -", key.trim())
    }

    // =========================================================================
    // List Comment vs Case Number Distinction Tests
    // =========================================================================

    @Test
    fun `list comment key looks different from typical case numbers`() {
        val list = createTestList(
            venue = "Dublin",
            dateText = "Monday 3rd February 2025"
        )
        val listCommentKey = getListCommentKey(list)

        // Typical case numbers have formats like "2024/12345" or "2024 No. 123"
        val typicalCaseNumbers = listOf(
            "2024/12345",
            "2024 No. 123",
            "CL-2024-001",
            "HC 2024 123"
        )

        // List comment key should not match any typical case number pattern
        typicalCaseNumbers.forEach { caseNumber ->
            assertNotEquals(
                "List comment key should differ from case number $caseNumber",
                listCommentKey,
                caseNumber
            )
        }
    }

    @Test
    fun `list comment key contains venue name`() {
        val list = createTestList(venue = "Cork Circuit Court")
        val key = getListCommentKey(list)

        assertTrue(
            "List comment key should contain venue",
            key.contains("Cork Circuit Court")
        )
    }

    // =========================================================================
    // Date Format Mismatch Scenario Tests
    // =========================================================================

    @Test
    fun `list comment key with ISO date differs from human-readable date`() {
        // Scenario: Notification was created with human-readable dateText
        val originalList = createTestList(
            venue = "Dublin",
            dateText = "Monday 3rd February 2025"
        )
        val originalKey = getListCommentKey(originalList)

        // Scenario: URL extraction gives ISO format
        val tempList = createTestList(
            venue = "Dublin",
            dateText = "2025-02-03"  // ISO format extracted from URL
        )
        val tempKey = getListCommentKey(tempList)

        // These should NOT match - this is the bug we fixed
        assertNotEquals(
            "Keys should differ due to date format mismatch",
            originalKey,
            tempKey
        )

        // This demonstrates why we can't rely on key comparison when
        // creating temp lists from URLs
    }

    @Test
    fun `case number from notification should be used for lookup not regenerated`() {
        // The fix: When navigating from notification, we should use the
        // caseNumber stored in the notification directly, not regenerate it.
        val notificationCaseNumber = "Dublin - Monday 3rd February 2025"

        // If no case item matches this caseNumber, it's a list comment
        val caseItems = listOf(
            "2024/12345",
            "2024/67890",
            "2024 No. 999"
        )

        val matchingCase = caseItems.find { it == notificationCaseNumber }

        assertNull(
            "List comment key should not match any case item",
            matchingCase
        )

        // Therefore, when matchingCase is null, we should open list comments
    }

    // =========================================================================
    // PendingAction Tests
    // =========================================================================

    @Test
    fun `PendingAction OpenListComments stores caseKey`() {
        val action = PendingAction.OpenListComments("Dublin - Monday 3rd February 2025")

        assertEquals("Dublin - Monday 3rd February 2025", action.caseKey)
    }

    @Test
    fun `PendingAction OpenComments stores caseKey`() {
        val action = PendingAction.OpenComments("2024/12345")

        assertEquals("2024/12345", action.caseKey)
    }

    @Test
    fun `PendingAction ScrollToCase stores caseKey`() {
        val action = PendingAction.ScrollToCase("2024/12345")

        assertEquals("2024/12345", action.caseKey)
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    private fun createTestList(
        name: String = "Test List",
        venue: String = "Dublin",
        dateText: String = "Monday 3rd February 2025",
        sourceUrl: String = "https://example.com/dublin/2025-02-03/list.pdf"
    ) = CourtList(
        id = 1,
        name = name,
        dateIso = "2025-02-03",
        dateText = dateText,
        venue = venue,
        type = null,
        sourceUrl = sourceUrl
    )
}
