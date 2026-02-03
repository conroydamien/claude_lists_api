// Edge function to fetch and parse courts.ie listings
// POST /functions/v1/listings { "date": "YYYY-MM-DD" }
// Requires JWT authentication to prevent abuse
//
// Types defined in: supabase/shared/types.ts

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import type { DiaryEntry, ListingsRequest } from "../_shared/types.ts"
import { validateGoogleToken, extractBearerToken, corsHeaders, errorResponse, handleCors } from "../_shared/auth.ts"

const COURTS_IE_BASE = "https://legaldiary.courts.ie"

// Parse date sort string (yyyymmdd) to ISO (yyyy-MM-dd)
function parseDateSort(dateSort: string): string | null {
  if (dateSort.length !== 8) return null
  const year = dateSort.substring(0, 4)
  const month = dateSort.substring(4, 6)
  const day = dateSort.substring(6, 8)
  return `${year}-${month}-${day}`
}

// Format date for courts.ie URL (dd-MM-yyyy)
function formatDateForCourtsIe(dateStr: string): string {
  const [year, month, day] = dateStr.split('-')
  return `${day}-${month}-${year}`
}

// Parse listings page HTML
function parseListingsPage(html: string): DiaryEntry[] {
  const entries: DiaryEntry[] = []

  // Find all clickable rows in search results
  // Using regex since we don't have a DOM parser
  const rowRegex = /<tr[^>]*class="[^"]*clickable-row[^"]*"[^>]*data-url="([^"]+)"[^>]*>([\s\S]*?)<\/tr>/gi
  const cellRegex = /<td[^>]*(?:data-text="([^"]*)")?[^>]*>([\s\S]*?)<\/td>/gi

  let rowMatch
  while ((rowMatch = rowRegex.exec(html)) !== null) {
    const dataUrl = rowMatch[1]
    const rowContent = rowMatch[2]

    const cells: { dataText: string, text: string }[] = []
    let cellMatch
    const cellRegexLocal = /<td[^>]*(?:data-text="([^"]*)")?[^>]*>([\s\S]*?)<\/td>/gi

    while ((cellMatch = cellRegexLocal.exec(rowContent)) !== null) {
      cells.push({
        dataText: cellMatch[1] || '',
        text: cellMatch[2].replace(/<[^>]+>/g, '').trim()
      })
    }

    if (cells.length >= 5) {
      const dateSort = cells[0].dataText
      entries.push({
        dateText: cells[0].text,
        dateIso: parseDateSort(dateSort),
        venue: cells[1].text,
        type: cells[2].text,
        subtitle: cells[3].text,
        updated: cells[4].text,
        sourceUrl: `${COURTS_IE_BASE}${dataUrl}`
      })
    }
  }

  return entries
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return handleCors();
  }

  try {
    // Validate JWT - required for all requests to prevent abuse
    const token = extractBearerToken(req.headers.get('Authorization'));
    if (!token) {
      return errorResponse('Authorization required', 401);
    }

    const user = await validateGoogleToken(token);
    if (!user) {
      return errorResponse('Invalid token', 401);
    }

    let date: string | null = null

    // Support both GET (query params) and POST (JSON body)
    if (req.method === 'POST') {
      const body = await req.json()
      date = body.date
    } else {
      const url = new URL(req.url)
      date = url.searchParams.get('date')
    }

    if (!date) {
      return errorResponse('date parameter required (YYYY-MM-DD)', 400);
    }

    // Build courts.ie URL
    const courtsDate = formatDateForCourtsIe(date)
    const courtsUrl = `${COURTS_IE_BASE}/legaldiary.nsf/circuit-court?OpenView&Jurisdiction=circuit-court&area=&type=&dateType=Range&dateFrom=${courtsDate}&dateTo=${courtsDate}&text=`

    // Fetch from courts.ie
    const response = await fetch(courtsUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 CourtsApp/1.0'
      }
    })

    if (!response.ok) {
      throw new Error(`courts.ie returned ${response.status}`)
    }

    const html = await response.text()
    const entries = parseListingsPage(html)

    return new Response(
      JSON.stringify(entries),
      {
        headers: {
          'Content-Type': 'application/json',
          ...corsHeaders,
          'Cache-Control': 'public, max-age=300' // Cache for 5 minutes
        }
      }
    )

  } catch (error) {
    const message = error instanceof Error ? error.message : 'Unknown error';
    return errorResponse(message, 500);
  }
})
