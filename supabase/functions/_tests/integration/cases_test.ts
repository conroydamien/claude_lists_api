/**
 * Integration tests for /cases endpoint.
 */
import {
  assertEquals,
  assertExists,
} from "https://deno.land/std@0.208.0/assert/mod.ts";

import { BASE_URL, getAuthHeaders, skipIfNoToken } from "../config.ts";

Deno.test("cases - requires authentication", async () => {
  const response = await fetch(
    `${BASE_URL}/cases?url=https://legaldiary.courts.ie/test`
  );
  assertEquals(response.status, 401);
});

Deno.test("cases - requires url parameter", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(`${BASE_URL}/cases`, {
    headers: getAuthHeaders(),
  });
  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.error, "url parameter required");
});

Deno.test("cases - rejects non-courts.ie URLs", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(
    `${BASE_URL}/cases?url=https://example.com/malicious`,
    { headers: getAuthHeaders() }
  );
  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.error, "Invalid URL - must be from legaldiary.courts.ie");
});

Deno.test("cases - fetches and parses circuit court case page", async () => {
  if (skipIfNoToken()) return;

  // First get a valid listing URL
  const listingsResponse = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=circuit-court`,
    { headers: getAuthHeaders() }
  );

  if (listingsResponse.status !== 200) {
    console.log("⚠️  Skipping - could not fetch listings");
    return;
  }

  const listings = await listingsResponse.json();
  if (listings.length === 0) {
    console.log("⚠️  Skipping - no listings available for test date");
    return;
  }

  // Fetch cases for the first listing
  const casesResponse = await fetch(
    `${BASE_URL}/cases?url=${encodeURIComponent(listings[0].sourceUrl)}`,
    { headers: getAuthHeaders() }
  );
  assertEquals(casesResponse.status, 200);

  const body = await casesResponse.json();
  assertExists(body.cases);
  assertExists(body.headers);
  assertEquals(Array.isArray(body.cases), true);
  assertEquals(Array.isArray(body.headers), true);
});

Deno.test("cases - fetches and parses high court case page", async () => {
  if (skipIfNoToken()) return;

  // First get a valid listing URL
  const listingsResponse = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=high-court`,
    { headers: getAuthHeaders() }
  );

  if (listingsResponse.status !== 200) {
    console.log("⚠️  Skipping - could not fetch listings");
    return;
  }

  const listings = await listingsResponse.json();
  if (listings.length === 0) {
    console.log("⚠️  Skipping - no high court listings available");
    return;
  }

  // Fetch cases for the first listing
  const casesResponse = await fetch(
    `${BASE_URL}/cases?url=${encodeURIComponent(listings[0].sourceUrl)}`,
    { headers: getAuthHeaders() }
  );
  assertEquals(casesResponse.status, 200);

  const body = await casesResponse.json();
  assertExists(body.cases);
  assertEquals(Array.isArray(body.cases), true);

  // Check case structure if any cases exist
  if (body.cases.length > 0) {
    const firstCase = body.cases[0];
    assertExists(firstCase.isCase);
    assertEquals(firstCase.isCase, true);
  }
});

Deno.test("cases - POST method works", async () => {
  if (skipIfNoToken()) return;

  // Get a valid URL first
  const listingsResponse = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=circuit-court`,
    { headers: getAuthHeaders() }
  );
  const listings = await listingsResponse.json();

  if (listings.length === 0) {
    console.log("⚠️  Skipping - no listings available");
    return;
  }

  const response = await fetch(`${BASE_URL}/cases`, {
    method: "POST",
    headers: getAuthHeaders(),
    body: JSON.stringify({ url: listings[0].sourceUrl }),
  });
  assertEquals(response.status, 200);
});
