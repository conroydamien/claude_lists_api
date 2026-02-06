// Edge function to fetch and parse courts.ie case detail page
// POST /functions/v1/cases { "url": "<source_url>" }
// Requires JWT authentication to prevent abuse
//
// Types defined in: supabase/shared/types.ts

import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import {
  validateGoogleToken,
  extractBearerToken,
  corsHeaders,
  errorResponse,
  handleCors,
} from "../_shared/auth.ts";
import { parseDetailPage } from "../_shared/parsing.ts";

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

    let sourceUrl: string | null = null;

    // Support both GET (query params) and POST (JSON body)
    if (req.method === "POST") {
      const body = await req.json();
      sourceUrl = body.url;
    } else {
      const reqUrl = new URL(req.url);
      sourceUrl = reqUrl.searchParams.get("url");
    }

    if (!sourceUrl) {
      return errorResponse("url parameter required", 400);
    }

    // Validate URL is from courts.ie
    if (!sourceUrl.startsWith("https://legaldiary.courts.ie")) {
      return errorResponse(
        "Invalid URL - must be from legaldiary.courts.ie",
        400
      );
    }

    // Fetch from courts.ie
    const response = await fetch(sourceUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 CourtsApp/1.0",
      },
    });

    if (!response.ok) {
      throw new Error(`courts.ie returned ${response.status}`);
    }

    const html = await response.text();
    const result = parseDetailPage(html);

    return new Response(JSON.stringify(result), {
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
