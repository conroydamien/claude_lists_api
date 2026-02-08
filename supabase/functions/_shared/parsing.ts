/**
 * Parsing utilities for courts.ie HTML pages.
 * Extracted for testability.
 */

import type { ParsedCase, CasesResponse } from "./types.ts";

// List type enum
export type ListType = "chancery" | "bail" | "circuit" | "commercial" | "family" | "high-court-general" | "court-of-appeal";

// Case number patterns grouped by list type
export const casePatternsByListType: Record<ListType, RegExp[]> = {
  chancery: [
    // High Court new style: H.P.2025.0002747, H.COS.2025.0000215, H.JR.2025.0001507, H.R.2024.0000186
    /(H\.[A-Z]{1,4}\.\d{4}\.\d+)/,
    // High Court old style: 2021 5113 P, 2022 24 SP, 2017 2602 S
    /(\d{4}\s+\d+\s+[A-Z]{1,2})\b/,
    // Special summons: 2019 438 SP
    /(\d{4}\s+\d+\s+SP)\b/,
  ],
  bail: [
    // Bail list: case number at end, YYYY NNNN SS format
    /(\d{4}\s+\d+\s+SS)\b/,
  ],
  circuit: [
    // Circuit/District: 2024/1234, 123/2024
    /(\d{4}\/\d+|\d+\/\d{4})/,
    // Prefixed: CC123/2024, DS1234/2024, CL1234/2024
    /([A-Z]{1,4}\d+\/\d{4})/,
  ],
  commercial: [
    // Commercial Court: 2024 3071 P, 2025 248 MCA
    /(\d{4}\s+\d+\s+[A-Z]{1,3})\b/,
  ],
  family: [
    // Family Law: 2025 91 CAF, 2025 20 HLC
    /(\d{4}\s+\d+\s+(?:CAF|HLC|FL))\b/,
  ],
  "high-court-general": [
    // High Court new style
    /(H\.[A-Z]{1,4}\.\d{4}\.\d+)/,
    // High Court spaced: 2024 No. 1234 P
    /(\d{4}\s*No\.?\s*\d+\s*[A-Z]?)/,
    // High Court old style
    /(\d{4}\s+\d+\s+[A-Z]{1,2})\b/,
    // Record numbers
    /((?:Record|Rec)\.?\s*(?:No\.?)?\s*\d+)/i,
    // Spaced format: 2024 CC 123
    /(\d{4}\s+[A-Z]{1,4}\s+\d+)/,
    // General alphanumeric ref
    /([A-Z0-9]{2,8}\/\d{4})/,
  ],
  "court-of-appeal": [
    // Court of Appeal: YYYY NNN format (e.g., 2025 217, 2026 4)
    /(\d{4}\s+\d{1,4})\b/,
  ],
};

// Combined patterns for backwards compatibility
export const caseNumberPatterns = [
  ...casePatternsByListType.chancery,
  ...casePatternsByListType.circuit,
  ...casePatternsByListType["high-court-general"],
];

// Match list number at start: "1", "1.", "4a", "4A", "10:30 1", etc.
// Captures: [1] = number (1-3 digits only to avoid matching years), [2] = optional suffix letter
export const listNumberPattern = /^(?:\d{1,2}:\d{2}\s+)?(\d{1,3})([a-zA-Z])?\.?(?=\s)/;

// Pattern to detect if a line contains legal party indicators
const partyIndicatorPattern = /(-V-|v\s|vs\.?\s|versus\s)/i;

/**
 * Detect list type from content.
 * Prioritizes the explicit "List Type:" header from courts.ie pages,
 * then falls back to keyword matching.
 */
export function detectListType(content: string): ListType {
  const upperContent = content.toUpperCase();

  // Check explicit "List Type:" header first — most reliable
  const listTypeMatch = upperContent.match(/LIST TYPE:\s*(.+)/);
  if (listTypeMatch) {
    const declared = listTypeMatch[1].trim();
    if (declared.includes("BAIL")) return "bail";
    if (declared.includes("COMMERCIAL")) return "commercial";
    if (declared.includes("FAMILY")) return "family";
    if (declared.includes("CHANCERY") || declared.includes("SEANSAIREACHT")) return "chancery";
    if (declared.includes("COURT OF APPEAL")) return "court-of-appeal";
    if (declared.includes("CIRCUIT") || declared.includes("CÚIRT CHUARDA")) return "circuit";
    // Any other High Court list type (e.g. JUDICIAL REVIEW, NON-JURY, etc.)
    return "high-court-general";
  }

  // Fallback: keyword matching in full content
  if (upperContent.includes("BAIL LIST") || upperContent.includes("BAIL APPLICATIONS")) {
    return "bail";
  }
  if (upperContent.includes("COMMERCIAL LIST") || upperContent.includes("LIOSTA TRÁCHTÁLA")) {
    return "commercial";
  }
  if (upperContent.includes("FAMILY LAW") || upperContent.includes("LIOSTA DLÍ TEAGHLAIGH")) {
    return "family";
  }
  if (upperContent.includes("CHANCERY") || upperContent.includes("SEANSAIREACHT")) {
    return "chancery";
  }
  if (upperContent.includes("COURT OF APPEAL")) {
    return "court-of-appeal";
  }
  if (upperContent.includes("CIRCUIT COURT") || upperContent.includes("CÚIRT CHUARDA")) {
    return "circuit";
  }
  return "high-court-general";
}

/**
 * Check if a line looks like a legal case (contains party indicators).
 */
function looksLikeCase(line: string): boolean {
  return partyIndicatorPattern.test(line);
}

/**
 * Parse a single line to extract case data.
 */
export function parseCaseItem(line: string, listType: ListType = "high-court-general"): ParsedCase {
  const patterns = casePatternsByListType[listType] || caseNumberPatterns;
  const listMatch = line.match(listNumberPattern);

  // Court of Appeal: cases may or may not have list numbers
  // Numbered callover items: "1. 2025 262 Minister -v- Sweco..."
  // Unnumbered hearing items: "2025 217 Fennell -v- Corcoran & anor"
  if (listType === "court-of-appeal") {
    // With list number: numbered callover items
    if (listMatch) {
      const listNumber = parseInt(listMatch[1]);
      const listSuffix = listMatch[2] || null;
      const remainder = line.substring(listMatch[0].length).trim();

      for (const pattern of patterns) {
        const caseMatch = remainder.match(pattern);
        if (caseMatch) {
          const caseNumber = caseMatch[1];
          const caseIdx = remainder.indexOf(caseNumber);
          const afterCase = remainder
            .substring(caseIdx + caseNumber.length)
            .replace(/^[\t\s]+/, "")
            .trim();
          return {
            listNumber,
            listSuffix,
            caseNumber,
            title: afterCase || remainder,
            parties: afterCase || null,
            isCase: true,
          };
        }
      }
    }

    // Without list number: require case number AND party indicator (-v-)
    for (const pattern of patterns) {
      const caseMatch = line.match(pattern);
      if (caseMatch && looksLikeCase(line)) {
        const caseNumber = caseMatch[1];
        const caseIdx = line.indexOf(caseNumber);
        const afterCase = line
          .substring(caseIdx + caseNumber.length)
          .replace(/^[\t\s]+/, "")
          .trim();
        return {
          listNumber: null,
          listSuffix: null,
          caseNumber,
          title: afterCase || line,
          parties: afterCase || null,
          isCase: true,
        };
      }
    }

    return {
      listNumber: null,
      listSuffix: null,
      caseNumber: null,
      title: line,
      parties: null,
      isCase: false,
    };
  }

  // Commercial and Family Law: cases DON'T have list numbers, practice directions DO
  if (listType === "commercial" || listType === "family") {
    // If there's a list number, it's a practice direction, not a case
    if (listMatch) {
      return {
        listNumber: null,
        listSuffix: null,
        caseNumber: null,
        title: line,
        parties: null,
        isCase: false,
      };
    }
    // No list number - check for case number pattern
    for (const pattern of patterns) {
      const caseMatch = line.match(pattern);
      if (caseMatch) {
        const caseNumber = caseMatch[1];
        const caseIdx = line.indexOf(caseNumber);
        const afterCase = line
          .substring(caseIdx + caseNumber.length)
          .replace(/^[\t\s]+/, "")
          .trim();
        return {
          listNumber: null,
          listSuffix: null,
          caseNumber,
          title: afterCase || line,
          parties: afterCase || null,
          isCase: true,
        };
      }
    }
    return {
      listNumber: null,
      listSuffix: null,
      caseNumber: null,
      title: line,
      parties: null,
      isCase: false,
    };
  }

  // For other list types: No list number = not a case
  if (!listMatch) {
    return {
      listNumber: null,
      listSuffix: null,
      caseNumber: null,
      title: line,
      parties: null,
      isCase: false,
    };
  }

  const listNumber = parseInt(listMatch[1]);
  const listSuffix = listMatch[2] || null;
  const remainder = line.substring(listMatch[0].length).trim();

  // Try each case number pattern in the remainder
  let caseMatch: RegExpMatchArray | null = null;
  for (const pattern of patterns) {
    caseMatch = remainder.match(pattern);
    if (caseMatch) break;
  }

  if (!caseMatch) {
    // No case number = not a case. Every case needs a case number to form
    // a valid primary key (list_source_url, list_number, case_number).
    return {
      listNumber: null,
      listSuffix: null,
      caseNumber: null,
      title: remainder,
      parties: null,
      isCase: false,
    };
  }

  const caseNumber = caseMatch[1];
  const caseIdx = remainder.indexOf(caseNumber);

  // For bail lists, case number is at the END
  // Format: "DPP -V- JOYCE DONNA	DOCHAS		2025 2073 SS"
  // For chancery, case number is near the START
  // Format: "H.COS.2025.0000215 	MAXELA LTD & ANOR v COMPANIES ACT"

  const beforeCase = remainder.substring(0, caseIdx).trim();
  const afterCase = remainder
    .substring(caseIdx + caseNumber.length)
    .replace(/^[\t:\s]+/, "")
    .trim();

  // Determine which part is the parties/title
  let title: string;
  let parties: string | null;

  if (listType === "bail" && beforeCase.length > afterCase.length) {
    // Bail list format: parties before case number
    const beforeParts = beforeCase.split(/\t+/);
    title = beforeParts[0]?.trim() || beforeCase;
    parties = beforeParts[0]?.trim() || null;
  } else if (caseIdx < 30 || afterCase.length > beforeCase.length) {
    // Case number near start - parties come after
    title = afterCase || remainder;
    parties = afterCase || null;
  } else {
    // Case number near end - parties come before
    const beforeParts = beforeCase.split(/\t+/);
    title = beforeParts[0]?.trim() || beforeCase;
    parties = beforeParts[0]?.trim() || null;
  }

  return {
    listNumber,
    listSuffix,
    caseNumber,
    title,
    parties,
    isCase: true,
  };
}

/**
 * Parse detail page HTML to extract cases.
 */
export function parseDetailPage(html: string): CasesResponse {
  // Find .ld-content div
  const contentMatch = html.match(
    /<div[^>]*class="[^"]*ld-content[^"]*"[^>]*>([\s\S]*?)<\/div>/i
  );

  if (!contentMatch) {
    return { cases: [], headers: [] };
  }

  let content = contentMatch[1];

  // Replace <br> tags with newlines
  content = content.replace(/<br\s*\/?>/gi, "\n");
  // Strip other HTML tags
  content = content.replace(/<[^>]+>/g, "");
  // Decode HTML entities
  content = content
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&nbsp;/g, " ")
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"');

  // Detect list type from content
  const listType = detectListType(content);

  const lines = content
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.length > 0);

  const cases: ParsedCase[] = [];
  const headers: string[] = [];
  const pendingHeaders: string[] = [];

  // For Commercial Court, track date sections to only parse today's cases
  // Pattern matches "FOR MONDAY 10th FEBRUARY 2026" etc.
  const futureDatePattern = /^FOR\s+(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)\s+\d/i;
  let seenFirstDate = false;

  // Court of Appeal: skip preamble (notices/practice directions) until first date header
  const dayOfWeekDatePattern = /^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\s+the\s+\d/i;
  let pastPreamble = listType !== "court-of-appeal";

  for (const line of lines) {
    // Court of Appeal: skip preamble until first date header
    if (!pastPreamble) {
      if (dayOfWeekDatePattern.test(line)) {
        pastPreamble = true;
        // Fall through to process this date header line as a non-case header
      } else {
        continue;
      }
    }

    // Commercial/Family: Stop parsing at second date header (future dates)
    if (listType === "commercial" || listType === "family") {
      if (futureDatePattern.test(line)) {
        if (seenFirstDate) {
          // We've hit a second date section - stop parsing
          break;
        }
        seenFirstDate = true;
        continue;
      }
      // Also stop at term calendar indicators
      if (/^(HILARY|MICHAELMAS|TRINITY|EASTER)\s+TERM/i.test(line)) {
        break;
      }
    }

    const parsed = parseCaseItem(line, listType);

    if (parsed.isCase) {
      // Add pending headers
      headers.push(...pendingHeaders);
      pendingHeaders.length = 0;
      cases.push(parsed);
    } else if (line.length > 3) {
      pendingHeaders.push(line);
    }
  }

  // Trailing headers
  headers.push(...pendingHeaders);

  return { cases, headers };
}

/**
 * Parse date sort string (yyyymmdd) to ISO (yyyy-MM-dd).
 */
export function parseDateSort(dateSort: string): string | null {
  if (dateSort.length !== 8) return null;
  const year = dateSort.substring(0, 4);
  const month = dateSort.substring(4, 6);
  const day = dateSort.substring(6, 8);
  return `${year}-${month}-${day}`;
}

/**
 * Format date for courts.ie URL (dd-MM-yyyy).
 */
export function formatDateForCourtsIe(dateStr: string): string {
  const [year, month, day] = dateStr.split("-");
  return `${day}-${month}-${year}`;
}

/**
 * Parse listings page HTML to extract diary entries.
 */
export function parseListingsPage(
  html: string,
  baseUrl: string = "https://legaldiary.courts.ie"
): import("./types.ts").DiaryEntry[] {
  const entries: import("./types.ts").DiaryEntry[] = [];

  // Find all clickable rows in search results
  const rowRegex =
    /<tr[^>]*class="[^"]*clickable-row[^"]*"[^>]*data-url="([^"]+)"[^>]*>([\s\S]*?)<\/tr>/gi;

  let rowMatch;
  while ((rowMatch = rowRegex.exec(html)) !== null) {
    const dataUrl = rowMatch[1];
    const rowContent = rowMatch[2];

    const cells: { dataText: string; text: string }[] = [];
    // Match td elements and extract data-text attribute if present
    const cellRegexLocal = /<td([^>]*)>([\s\S]*?)<\/td>/gi;
    const dataTextRegex = /data-text="([^"]*)"/;
    let cellMatch;

    while ((cellMatch = cellRegexLocal.exec(rowContent)) !== null) {
      const attrs = cellMatch[1];
      const dataTextMatch = attrs.match(dataTextRegex);
      cells.push({
        dataText: dataTextMatch ? dataTextMatch[1] : "",
        text: cellMatch[2].replace(/<[^>]+>/g, "").trim(),
      });
    }

    if (cells.length >= 3) {
      const dateSort = cells[0].dataText;
      // Handle 3-column (Court of Appeal), 4-column (High Court) and 5-column (Circuit Court) formats
      if (cells.length >= 5) {
        // Circuit Court: Date, Venue, Type, Subtitle, Updated
        entries.push({
          dateText: cells[0].text,
          dateIso: parseDateSort(dateSort),
          venue: cells[1].text,
          type: cells[2].text,
          subtitle: cells[3].text,
          updated: cells[4].text,
          sourceUrl: `${baseUrl}${dataUrl}`,
        });
      } else if (cells.length === 4) {
        // High Court: Date, Venue/Type, Subtitle, Updated
        entries.push({
          dateText: cells[0].text,
          dateIso: parseDateSort(dateSort),
          venue: cells[1].text,
          type: "",
          subtitle: cells[2].text,
          updated: cells[3].text,
          sourceUrl: `${baseUrl}${dataUrl}`,
        });
      } else {
        // Court of Appeal: Date, Type, Updated (3 columns)
        entries.push({
          dateText: cells[0].text,
          dateIso: parseDateSort(dateSort),
          venue: "",
          type: cells[1].text,
          subtitle: "",
          updated: cells[2].text,
          sourceUrl: `${baseUrl}${dataUrl}`,
        });
      }
    }
  }

  return entries;
}
