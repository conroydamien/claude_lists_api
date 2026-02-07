// Edge function to fetch and parse courts.ie listings
// POST /functions/v1/listings { "date": "YYYY-MM-DD", "court": "circuit-court" }
// Requires JWT authentication to prevent abuse
//
// Supported courts: circuit-court, high-court
// Types defined in: supabase/shared/types.ts

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import {
  validateGoogleToken,
  extractBearerToken,
  isUserBlocked,
  corsHeaders,
  errorResponse,
  handleCors,
} from "../_shared/auth.ts";
import { getDbClient } from "../_shared/db.ts";
import {
  formatDateForCourtsIe,
  parseListingsPage,
} from "../_shared/parsing.ts";

const COURTS_IE_BASE = "https://legaldiary.courts.ie";

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === "OPTIONS") {
    return handleCors();
  }

  try {
    // Validate JWT - required for all requests to prevent abuse
    const token = extractBearerToken(req.headers.get("Authorization"));
    if (!token) {
      return errorResponse("Authorization required", 401);
    }

    const user = await validateGoogleToken(token);
    if (!user) {
      return errorResponse("Invalid token", 401);
    }

    const db = getDbClient();
    if (await isUserBlocked(db, user.id)) {
      return errorResponse("Account is blocked", 403);
    }

    let date: string | null = null;
    let court: string = "circuit-court"; // Default to circuit court

    // Supported court types
    const validCourts = ["circuit-court", "high-court", "court-of-appeal"];

    // Support both GET (query params) and POST (JSON body)
    if (req.method === "POST") {
      const body = await req.json();
      date = body.date;
      if (body.court) court = body.court;
    } else {
      const url = new URL(req.url);
      date = url.searchParams.get("date");
      const courtParam = url.searchParams.get("court");
      if (courtParam) court = courtParam;
    }

    if (!date) {
      return errorResponse("date parameter required (YYYY-MM-DD)", 400);
    }

    if (!validCourts.includes(court)) {
      return errorResponse(
        `Invalid court type. Valid options: ${validCourts.join(", ")}`,
        400
      );
    }

    // Build courts.ie URL - all courts use /legaldiary.nsf/{court} pattern
    const courtsDate = formatDateForCourtsIe(date);
    const courtsUrl = `${COURTS_IE_BASE}/legaldiary.nsf/${court}?OpenView&Jurisdiction=${court}&area=&type=&dateType=Range&dateFrom=${courtsDate}&dateTo=${courtsDate}&text=`;

    // Fetch from courts.ie
    const response = await fetch(courtsUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 CourtsApp/1.0",
      },
    });

    if (!response.ok) {
      throw new Error(`courts.ie returned ${response.status}`);
    }

    const html = await response.text();
    const entries = parseListingsPage(html, COURTS_IE_BASE);

    return new Response(JSON.stringify(entries), {
      headers: {
        "Content-Type": "application/json",
        ...corsHeaders,
        "Cache-Control": "public, max-age=300", // Cache for 5 minutes
      },
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Unknown error";
    return errorResponse(message, 500);
  }
});
