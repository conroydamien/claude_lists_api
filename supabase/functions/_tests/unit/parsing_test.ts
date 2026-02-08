/**
 * Unit tests for parsing utilities.
 */
import {
  assertEquals,
  assertExists,
} from "https://deno.land/std@0.208.0/assert/mod.ts";

import {
  parseCaseItem,
  parseDetailPage,
  parseDateSort,
  formatDateForCourtsIe,
  parseListingsPage,
  caseNumberPatterns,
  casePatternsByListType,
} from "../../_shared/parsing.ts";

// ============================================
// parseDateSort tests
// ============================================

Deno.test("parseDateSort - valid date", () => {
  assertEquals(parseDateSort("20260203"), "2026-02-03");
});

Deno.test("parseDateSort - invalid length", () => {
  assertEquals(parseDateSort("2026020"), null);
  assertEquals(parseDateSort("202602031"), null);
});

Deno.test("parseDateSort - empty string", () => {
  assertEquals(parseDateSort(""), null);
});

// ============================================
// formatDateForCourtsIe tests
// ============================================

Deno.test("formatDateForCourtsIe - converts ISO to courts.ie format", () => {
  assertEquals(formatDateForCourtsIe("2026-02-03"), "03-02-2026");
});

// ============================================
// Case number pattern tests
// ============================================

Deno.test("caseNumberPatterns - High Court new style H.P.YYYY.NNNNNNN", () => {
  const pattern = caseNumberPatterns[0];
  const match = "H.P.2025.0002747".match(pattern);
  assertExists(match);
  assertEquals(match![1], "H.P.2025.0002747");
});

Deno.test("caseNumberPatterns - High Court new style H.COS.YYYY.NNNNNNN", () => {
  const pattern = caseNumberPatterns[0];
  const match = "H.COS.2025.0000215".match(pattern);
  assertExists(match);
  assertEquals(match![1], "H.COS.2025.0000215");
});

Deno.test("caseNumberPatterns - High Court new style H.JR.YYYY.NNNNNNN", () => {
  const pattern = caseNumberPatterns[0];
  const match = "H.JR.2025.0001507".match(pattern);
  assertExists(match);
  assertEquals(match![1], "H.JR.2025.0001507");
});

Deno.test("caseNumberPatterns - Circuit Court YYYY/NNNN", () => {
  const pattern = casePatternsByListType.circuit[0];
  const match = "2024/1234".match(pattern);
  assertExists(match);
  assertEquals(match![1], "2024/1234");
});

Deno.test("caseNumberPatterns - Circuit Court NNNN/YYYY", () => {
  const pattern = casePatternsByListType.circuit[0];
  const match = "1234/2024".match(pattern);
  assertExists(match);
  assertEquals(match![1], "1234/2024");
});

Deno.test("caseNumberPatterns - Prefixed CC123/2024", () => {
  const pattern = casePatternsByListType.circuit[1];
  const match = "CC123/2024".match(pattern);
  assertExists(match);
  assertEquals(match![1], "CC123/2024");
});

Deno.test("caseNumberPatterns - High Court bail style 2025 2073 SS", () => {
  const pattern = casePatternsByListType.bail[0];
  const match = "2025 2073 SS".match(pattern);
  assertExists(match);
  assertEquals(match![1], "2025 2073 SS");
});

// ============================================
// parseCaseItem tests
// ============================================

Deno.test("parseCaseItem - Circuit Court format with list number", () => {
  const result = parseCaseItem("1\t2024/1234\tSmith v Jones");
  assertEquals(result.listNumber, 1);
  assertEquals(result.caseNumber, "2024/1234");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - High Court chancery format", () => {
  const result = parseCaseItem("1.\tH.P.2025.0002747\tLEE v PEPPER FINANCE");
  assertEquals(result.listNumber, 1);
  assertEquals(result.caseNumber, "H.P.2025.0002747");
  assertEquals(result.title, "LEE v PEPPER FINANCE");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - High Court bail format (case number at end)", () => {
  const result = parseCaseItem("1\tDPP -V- JOYCE DONNA\tDOCHAS\t\t2025 2073 SS");
  assertEquals(result.listNumber, 1);
  assertEquals(result.caseNumber, "2025 2073 SS");
  assertEquals(result.isCase, true);
  // Title contains the text before case number (may include location)
  assertExists(result.title);
});

Deno.test("parseCaseItem - line with list number but no case number (with parties)", () => {
  // No case number = not a case, even with party indicators.
  // Every case needs a case number for a valid primary key.
  const result = parseCaseItem("5\tSMITH -V- JONES (Appeal)");
  assertEquals(result.listNumber, null);
  assertEquals(result.caseNumber, null);
  assertEquals(result.title, "SMITH -V- JONES (Appeal)");
  assertEquals(result.isCase, false);
});

Deno.test("parseCaseItem - line with list number but no case number (practice direction)", () => {
  // Lines like "1. Parties must comply..." should NOT be cases
  const result = parseCaseItem("5\tSome description without case number");
  assertEquals(result.listNumber, null); // Not treated as a case
  assertEquals(result.caseNumber, null);
  assertEquals(result.title, "Some description without case number");
  assertEquals(result.isCase, false);
});

Deno.test("parseCaseItem - header line (no list number)", () => {
  const result = parseCaseItem("FOR MENTION AT 10:30");
  assertEquals(result.listNumber, null);
  assertEquals(result.caseNumber, null);
  assertEquals(result.isCase, false);
});

Deno.test("parseCaseItem - unlisted case with case number only (not a case)", () => {
  // Lines without list numbers are NOT cases, even if they have case numbers
  const result = parseCaseItem("CALL OVER: 2021 5113 P EASTWOOD & ANOR -V- RICHARDS");
  assertEquals(result.listNumber, null);
  assertEquals(result.caseNumber, null); // Not extracted - no list number
  assertEquals(result.isCase, false);
});

Deno.test("parseCaseItem - time prefix format", () => {
  const result = parseCaseItem("10:30\t1\t2024/5678\tMorning case");
  assertEquals(result.listNumber, 1);
  assertEquals(result.caseNumber, "2024/5678");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - list number with suffix 4a", () => {
  const result = parseCaseItem("4a\t2024/1234\tSmith v Jones");
  assertEquals(result.listNumber, 4);
  assertEquals(result.listSuffix, "a");
  assertEquals(result.caseNumber, "2024/1234");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - list number with suffix 10A", () => {
  const result = parseCaseItem("10A\t2024/5678\tDoe v Roe");
  assertEquals(result.listNumber, 10);
  assertEquals(result.listSuffix, "A");
  assertEquals(result.caseNumber, "2024/5678");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - list number without suffix", () => {
  const result = parseCaseItem("5\t2024/9999\tTest case");
  assertEquals(result.listNumber, 5);
  assertEquals(result.listSuffix, null);
  assertEquals(result.caseNumber, "2024/9999");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - year not parsed as list number (Chancery regression)", () => {
  // Regression test: 4-digit years should NOT be parsed as list numbers
  // Lines without list numbers are not cases
  const result = parseCaseItem("2021 5113 P EASTWOOD & ANOR -V- RICHARDS");
  assertEquals(result.listNumber, null); // Should NOT be 2021
  assertEquals(result.caseNumber, null); // No list number = not a case
  assertEquals(result.isCase, false);
});

Deno.test("parseCaseItem - 3-digit list number is valid", () => {
  // List numbers up to 999 should still work
  const result = parseCaseItem("123\t2024/1234\tLarge list case");
  assertEquals(result.listNumber, 123);
  assertEquals(result.caseNumber, "2024/1234");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - H.R. case number with list number", () => {
  // H.R. is a valid case number prefix (High Court Revenue)
  const result = parseCaseItem("1.\tH.R.2024.0000186\tREVENUE COMMISSIONERS -V- AVAYA");
  assertEquals(result.listNumber, 1);
  assertEquals(result.caseNumber, "H.R.2024.0000186");
  assertEquals(result.title, "REVENUE COMMISSIONERS -V- AVAYA");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - H.COS case number with list number", () => {
  // H.COS is Companies Act format
  const result = parseCaseItem("2.\tH.COS.2025.0000169\tAL MAKTOUM FOUNDATION CLG -V- COS ACT");
  assertEquals(result.listNumber, 2);
  assertEquals(result.caseNumber, "H.COS.2025.0000169");
  assertEquals(result.title, "AL MAKTOUM FOUNDATION CLG -V- COS ACT");
  assertEquals(result.isCase, true);
});

Deno.test("parseCaseItem - H.SP case number with list number", () => {
  // H.SP is Special Summons format
  const result = parseCaseItem("3.\tH.SP.2024.0000131\tHNW LENDING LTD -V- DOOLEY");
  assertEquals(result.listNumber, 3);
  assertEquals(result.caseNumber, "H.SP.2024.0000131");
  assertEquals(result.title, "HNW LENDING LTD -V- DOOLEY");
  assertEquals(result.isCase, true);
});

// ============================================
// parseDetailPage tests
// ============================================

Deno.test("parseDetailPage - extracts cases from ld-content div", () => {
  const html = `
    <div class="ld-content">
      FOR MENTION<br />
      1\t2024/1234\tSmith v Jones<br />
      2\t2024/5678\tDoe v Roe<br />
    </div>
  `;
  const result = parseDetailPage(html);
  assertEquals(result.cases.length, 2);
  assertEquals(result.cases[0].listNumber, 1);
  assertEquals(result.cases[0].caseNumber, "2024/1234");
  assertEquals(result.cases[1].listNumber, 2);
});

Deno.test("parseDetailPage - returns empty for no ld-content", () => {
  const html = `<div class="other-content">Nothing here</div>`;
  const result = parseDetailPage(html);
  assertEquals(result.cases.length, 0);
  assertEquals(result.headers.length, 0);
});

Deno.test("parseDetailPage - decodes HTML entities", () => {
  const html = `
    <div class="ld-content">
      1\t2024/1234\tSmith &amp; Co v Jones &amp; Partners<br />
    </div>
  `;
  const result = parseDetailPage(html);
  assertEquals(result.cases[0].title, "Smith & Co v Jones & Partners");
});

Deno.test("parseDetailPage - collects headers", () => {
  const html = `
    <div class="ld-content">
      CHANCERY<br />
      FOR MENTION<br />
      1\tH.P.2024.0001234\tTest v Case<br />
    </div>
  `;
  const result = parseDetailPage(html);
  assertEquals(result.cases.length, 1);
  assertEquals(result.headers.includes("CHANCERY"), true);
  assertEquals(result.headers.includes("FOR MENTION"), true);
});

// ============================================
// parseListingsPage tests
// ============================================

Deno.test("parseListingsPage - extracts entries from clickable rows", () => {
  // HTML must be on single line for regex to match properly
  const html = `<tr class="clickable-row" data-url="/legaldiary.nsf/circuit-court/ABC123"><td data-text="20260203">Monday 3rd February</td><td>Dublin</td><td>Criminal</td><td>Judge Smith</td><td>02/02/2026</td></tr>`;
  const result = parseListingsPage(html);
  assertEquals(result.length, 1);
  assertEquals(result[0].venue, "Dublin");
  assertEquals(result[0].type, "Criminal");
  assertEquals(result[0].dateIso, "2026-02-03");
  assertEquals(
    result[0].sourceUrl,
    "https://legaldiary.courts.ie/legaldiary.nsf/circuit-court/ABC123"
  );
});

Deno.test("parseListingsPage - handles High Court 4-column format", () => {
  const html = `<tr class="clickable-row" data-url="/legaldiary.nsf/high-court/XYZ789"><td data-text="20260203">Monday 3rd February</td><td>Chancery</td><td>Mr Justice Cregan</td><td>02/02/2026</td></tr>`;
  const result = parseListingsPage(html);
  assertEquals(result.length, 1);
  assertEquals(result[0].venue, "Chancery");
  assertEquals(result[0].type, ""); // High Court has no type column
  assertEquals(result[0].subtitle, "Mr Justice Cregan");
});

Deno.test("parseListingsPage - uses custom base URL", () => {
  const html = `<tr class="clickable-row" data-url="/test/path"><td data-text="20260203">Date</td><td>Venue</td><td>Type</td><td>Subtitle</td><td>Updated</td></tr>`;
  const result = parseListingsPage(html, "https://custom.url");
  assertEquals(result[0].sourceUrl, "https://custom.url/test/path");
});

Deno.test("parseListingsPage - returns empty for no clickable rows", () => {
  const html = `<table><tr><td>No clickable rows</td></tr></table>`;
  const result = parseListingsPage(html);
  assertEquals(result.length, 0);
});
