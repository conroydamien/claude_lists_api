// Edge function to fetch and parse courts.ie case detail page
// GET /cases?url=<source_url>

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"

interface ParsedCase {
  listNumber: number | null
  caseNumber: string | null
  title: string
  parties: string | null
  isCase: boolean
}

interface CasesResponse {
  cases: ParsedCase[]
  headers: string[]
}

// Multiple case number patterns for different courts
const caseNumberPatterns = [
  // Circuit/District: 2024/1234, 123/2024
  /(\d{4}\/\d+|\d+\/\d{4})/,
  // Prefixed: CC123/2024, DS1234/2024, CL1234/2024
  /([A-Z]{1,4}\d+\/\d{4})/,
  // High Court style: 2024 No. 1234 P, 2024 No 1234
  /(\d{4}\s*No\.?\s*\d+\s*[A-Z]?)/,
  // Record numbers: Record No. 1234, Rec 1234
  /((?:Record|Rec)\.?\s*(?:No\.?)?\s*\d+)/i,
  // Spaced format: 2024 CC 123
  /(\d{4}\s+[A-Z]{1,4}\s+\d+)/,
  // General alphanumeric ref
  /([A-Z0-9]{2,8}\/\d{4})/
]

const listNumberPattern = /^(?:\d{1,2}:\d{2}[\s\t]+)?(\d+)\s+/

// Parse a single line to extract case data
function parseCaseItem(line: string): ParsedCase {
  const listMatch = line.match(listNumberPattern)

  if (!listMatch) {
    return {
      listNumber: null,
      caseNumber: null,
      title: line,
      parties: null,
      isCase: false
    }
  }

  const listNumber = parseInt(listMatch[1])
  const remainder = line.substring(listMatch[0].length).trim()

  // Try each case number pattern
  let caseMatch: RegExpMatchArray | null = null
  for (const pattern of caseNumberPatterns) {
    caseMatch = remainder.match(pattern)
    if (caseMatch) break
  }

  if (!caseMatch) {
    // No case number found, but still a case if it has a list number
    return {
      listNumber,
      caseNumber: null,
      title: line,
      parties: remainder || null,
      isCase: true
    }
  }

  const caseNumber = caseMatch[1]

  // Extract parties - text after case number
  const afterCaseNumIdx = remainder.indexOf(caseNumber) + caseNumber.length
  let afterCaseNum = remainder.substring(afterCaseNumIdx).replace(/^[\t:\s]+/, '')

  // Split on colon or tab to get parties
  const partiesMatch = afterCaseNum.split(/[:\t]/)
  const parties = partiesMatch[0]?.trim().replace(/[,:;\t\s]+$/, '') || null

  return {
    listNumber,
    caseNumber,
    title: line,
    parties,
    isCase: true
  }
}

// Parse detail page HTML
function parseDetailPage(html: string): CasesResponse {
  // Find .ld-content div
  const contentMatch = html.match(/<div[^>]*class="[^"]*ld-content[^"]*"[^>]*>([\s\S]*?)<\/div>/i)

  if (!contentMatch) {
    return { cases: [], headers: [] }
  }

  let content = contentMatch[1]

  // Replace <br> tags with newlines
  content = content.replace(/<br\s*\/?>/gi, '\n')
  // Strip other HTML tags
  content = content.replace(/<[^>]+>/g, '')
  // Decode HTML entities
  content = content
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, ' ')
    .replace(/&#39;/g, "'")
    .replace(/&quot;/g, '"')

  const lines = content.split('\n').map(l => l.trim()).filter(l => l.length > 0)

  const cases: ParsedCase[] = []
  const headers: string[] = []
  const pendingHeaders: string[] = []

  for (const line of lines) {
    const parsed = parseCaseItem(line)

    if (parsed.isCase) {
      // Add pending headers
      headers.push(...pendingHeaders)
      pendingHeaders.length = 0
      cases.push(parsed)
    } else if (line.length > 3) {
      pendingHeaders.push(line)
    }
  }

  // Trailing headers
  headers.push(...pendingHeaders)

  return { cases, headers }
}

serve(async (req) => {
  // Handle CORS
  if (req.method === 'OPTIONS') {
    return new Response('ok', {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': 'Authorization, Content-Type',
      }
    })
  }

  try {
    const reqUrl = new URL(req.url)
    const sourceUrl = reqUrl.searchParams.get('url')

    if (!sourceUrl) {
      return new Response(
        JSON.stringify({ error: 'url parameter required' }),
        { status: 400, headers: { 'Content-Type': 'application/json' } }
      )
    }

    // Validate URL is from courts.ie
    if (!sourceUrl.startsWith('https://legaldiary.courts.ie')) {
      return new Response(
        JSON.stringify({ error: 'Invalid URL - must be from legaldiary.courts.ie' }),
        { status: 400, headers: { 'Content-Type': 'application/json' } }
      )
    }

    // Fetch from courts.ie
    const response = await fetch(sourceUrl, {
      headers: {
        'User-Agent': 'Mozilla/5.0 CourtsApp/1.0'
      }
    })

    if (!response.ok) {
      throw new Error(`courts.ie returned ${response.status}`)
    }

    const html = await response.text()
    const result = parseDetailPage(html)

    return new Response(
      JSON.stringify(result),
      {
        headers: {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*',
          'Cache-Control': 'public, max-age=300' // Cache for 5 minutes
        }
      }
    )

  } catch (error) {
    return new Response(
      JSON.stringify({ error: error.message }),
      {
        status: 500,
        headers: { 'Content-Type': 'application/json' }
      }
    )
  }
})
