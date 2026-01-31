package com.claudelists.app.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "CourtsIeService"
private const val BASE_URL = "https://legaldiary.courts.ie"
private const val USER_AGENT = "Mozilla/5.0 (Android) ClaudeLists/1.0"
private const val TIMEOUT_MS = 30000

/**
 * A diary entry from the courts.ie listing page
 */
data class DiaryEntry(
    val dateText: String,
    val dateSort: String,  // yyyymmdd
    val venue: String,
    val type: String,
    val subtitle: String,
    val updatedText: String,
    val url: String
) {
    val dateIso: String?
        get() = try {
            if (dateSort.length == 8) {
                val year = dateSort.substring(0, 4)
                val month = dateSort.substring(4, 6)
                val day = dateSort.substring(6, 8)
                "$year-$month-$day"
            } else null
        } catch (e: Exception) {
            null
        }
}

/**
 * A parsed case item from a detail page
 */
data class ParsedCase(
    val listNumber: Int?,
    val caseNumber: String?,
    val title: String,
    val parties: String?,
    val isCase: Boolean
)

/**
 * Service for fetching data directly from courts.ie
 */
object CourtsIeService {

    // Multiple case number patterns for different courts
    private val caseNumberPatterns = listOf(
        // Circuit/District: 2024/1234, 123/2024
        Regex("""(\d{4}/\d+|\d+/\d{4})"""),
        // Prefixed: CC123/2024, DS1234/2024, CL1234/2024
        Regex("""([A-Z]{1,4}\d+/\d{4})"""),
        // High Court style: 2024 No. 1234 P, 2024 No 1234
        Regex("""(\d{4}\s*No\.?\s*\d+\s*[A-Z]?)"""),
        // Record numbers with prefix: Record No. 1234, Rec 1234
        Regex("""((?:Record|Rec)\.?\s*(?:No\.?)?\s*\d+)""", RegexOption.IGNORE_CASE),
        // Spaced format: 2024 CC 123
        Regex("""(\d{4}\s+[A-Z]{1,4}\s+\d+)"""),
        // General alphanumeric ref: AB12/34, 12AB34/2024
        Regex("""([A-Z0-9]{2,8}/\d{4})""")
    )
    private val listNumberPattern = Regex("""^(?:\d{1,2}:\d{2}[\s\t]+)?(\d+)\s+""")

    /**
     * Build the listing URL for a specific date
     */
    private fun getListingUrl(date: LocalDate? = null): String {
        return if (date != null) {
            val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
            val dateStr = date.format(formatter)
            // Fetch listings for a specific date
            "$BASE_URL/legaldiary.nsf/circuit-court?OpenView&Jurisdiction=circuit-court&area=&type=&dateType=Range&dateFrom=$dateStr&dateTo=$dateStr&text="
        } else {
            // Default: fetch from yesterday onwards
            val yesterday = LocalDate.now().minusDays(1)
            val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")
            val dateFrom = yesterday.format(formatter)
            "$BASE_URL/legaldiary.nsf/circuit-court?OpenView&Jurisdiction=circuit-court&area=&type=&dateType=Range&dateFrom=$dateFrom&dateTo=&text="
        }
    }

    /**
     * Fetch and parse the listing page to get diary entries
     * @param date Optional date to filter listings (null = from yesterday onwards)
     */
    suspend fun fetchListing(date: LocalDate? = null): List<DiaryEntry> = withContext(Dispatchers.IO) {
        try {
            val url = getListingUrl(date)
            Log.d(TAG, "Fetching listing from $url")

            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get()

            parseListing(doc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch listing: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parse the listing page HTML
     */
    private fun parseListing(doc: Document): List<DiaryEntry> {
        val entries = mutableListOf<DiaryEntry>()
        val resultsDiv = doc.selectFirst("#searchResults") ?: return entries

        for (tr in resultsDiv.select("tr.clickable-row")) {
            val tds = tr.select("td")
            if (tds.size < 5) continue

            val dataUrl = tr.attr("data-url")
            if (dataUrl.isBlank()) continue

            entries.add(DiaryEntry(
                dateText = tds[0].text().trim(),
                dateSort = tds[0].attr("data-text").trim(),
                venue = tds[1].text().trim(),
                type = tds[2].text().trim(),
                subtitle = tds[3].text().trim(),
                updatedText = tds[4].text().trim(),
                url = "$BASE_URL$dataUrl"
            ))
        }

        Log.d(TAG, "Parsed ${entries.size} diary entries")
        return entries
    }

    /**
     * Fetch and parse a detail page to get cases and headers
     */
    suspend fun fetchDetail(url: String): Pair<List<ParsedCase>, List<String>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching detail from $url")

            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get()

            parseDetail(doc)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch detail: ${e.message}", e)
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Parse a detail page to extract cases and headers
     */
    private fun parseDetail(doc: Document): Pair<List<ParsedCase>, List<String>> {
        val contentDiv = doc.selectFirst(".ld-content") ?: return Pair(emptyList(), emptyList())

        // Get inner HTML and convert <br> tags to newlines
        // This preserves tabs which are important for parsing case numbers
        var html = contentDiv.html()

        // Replace <br>, <br/>, <br /> with newlines
        html = html.replace(Regex("<br\\s*/?>"), "\n")

        // Remove other HTML tags but keep text
        html = html.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        html = html.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
            .replace("&quot;", "\"")

        // Split into lines and filter empty ones
        val lines = html.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        Log.d(TAG, "Extracted ${lines.size} lines from detail page")
        if (lines.isNotEmpty()) {
            Log.d(TAG, "First few lines: ${lines.take(5)}")
        }

        return parseLines(lines)
    }

    /**
     * Parse lines into cases and headers
     */
    private fun parseLines(lines: List<String>): Pair<List<ParsedCase>, List<String>> {
        val cases = mutableListOf<ParsedCase>()
        val headers = mutableListOf<String>()
        val pendingHeaders = mutableListOf<String>()

        for (line in lines) {
            val parsed = parseCaseItem(line)
            if (parsed.isCase) {
                // Add pending headers
                headers.addAll(pendingHeaders)
                pendingHeaders.clear()
                cases.add(parsed)
            } else {
                pendingHeaders.add(line)
            }
        }

        // Trailing headers
        headers.addAll(pendingHeaders)

        Log.d(TAG, "Parsed ${cases.size} cases, ${headers.size} headers")
        return Pair(cases, headers)
    }

    /**
     * Parse a single line to extract case data
     */
    private fun parseCaseItem(line: String): ParsedCase {
        // Check for list number at start (optionally preceded by time like "10:30")
        val listMatch = listNumberPattern.find(line)
        if (listMatch == null) {
            return ParsedCase(
                listNumber = null,
                caseNumber = null,
                title = line,
                parties = null,
                isCase = false
            )
        }

        val listNumber = listMatch.groupValues[1].toIntOrNull()
        val remainder = line.substring(listMatch.range.last + 1).trim()

        // Try each case number pattern until one matches
        var caseMatch: MatchResult? = null
        for (pattern in caseNumberPatterns) {
            caseMatch = pattern.find(remainder)
            if (caseMatch != null) break
        }

        if (caseMatch == null) {
            // No case number found, but still treat as a case if it has a list number
            return ParsedCase(
                listNumber = listNumber,
                caseNumber = null,
                title = line,
                parties = remainder.takeIf { it.isNotBlank() },
                isCase = true  // Has list number, so it's a case entry
            )
        }

        val caseNumber = caseMatch.groupValues[1]

        // Extract parties - text after case number
        val afterCaseNum = remainder.substring(caseMatch.range.last + 1)
            .trimStart('\t', ':', ' ')

        // Split on colon or tab to get parties before solicitors
        val parties = afterCaseNum.split(Regex("[:\t]"), limit = 2)
            .firstOrNull()
            ?.trim()
            ?.trimEnd(':', ',', '\t', ' ')
            ?.takeIf { it.isNotEmpty() }

        return ParsedCase(
            listNumber = listNumber,
            caseNumber = caseNumber,
            title = line,
            parties = parties,
            isCase = true
        )
    }

    /**
     * Convert DiaryEntry to CourtList for UI compatibility
     */
    fun diaryEntryToCourtList(entry: DiaryEntry, index: Int): CourtList {
        val name = entry.subtitle.ifBlank { "${entry.venue} - ${entry.dateText}" }
        return CourtList(
            id = index, // Use index as temporary ID
            name = name,
            description = if (entry.type.isNotBlank()) "${entry.type} - ${entry.venue}" else entry.venue,
            metadata = ListMetadata(
                date = entry.dateIso,
                dateText = entry.dateText,
                venue = entry.venue,
                type = entry.type.takeIf { it.isNotBlank() },
                subtitle = entry.subtitle.takeIf { it.isNotBlank() },
                updated = null,
                sourceUrl = entry.url,
                headers = null
            )
        )
    }

    /**
     * Convert ParsedCase to Item for UI compatibility
     */
    fun parsedCaseToItem(case: ParsedCase, index: Int, listId: Int): Item {
        return Item(
            id = index,
            listId = listId,
            title = case.title,
            done = false,
            metadata = ItemMetadata(
                listNumber = case.listNumber,
                caseNumber = case.caseNumber,
                parties = case.parties,
                isCase = case.isCase,
                caseType = null
            )
        )
    }
}
