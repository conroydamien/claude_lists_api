/**
 * Integration tests for /listings endpoint.
 */
import {
  assertEquals,
  assertExists,
} from "https://deno.land/std@0.208.0/assert/mod.ts";

import { BASE_URL, getAuthHeaders, skipIfNoToken } from "../config.ts";

Deno.test("listings - requires authentication", async () => {
  const response = await fetch(`${BASE_URL}/listings?date=2026-02-03`);
  assertEquals(response.status, 401);
  const body = await response.json();
  assertEquals(body.error, "Authorization required");
});

Deno.test("listings - requires date parameter", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(`${BASE_URL}/listings`, {
    headers: getAuthHeaders(),
  });
  assertEquals(response.status, 400);
  const body = await response.json();
  assertEquals(body.error, "date parameter required (YYYY-MM-DD)");
});

Deno.test("listings - rejects invalid court type", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=invalid-court`,
    { headers: getAuthHeaders() }
  );
  assertEquals(response.status, 400);
  const body = await response.json();
  assertExists(body.error);
  assertEquals(body.error.includes("Invalid court type"), true);
});

Deno.test("listings - fetches circuit court listings", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=circuit-court`,
    { headers: getAuthHeaders() }
  );
  assertEquals(response.status, 200);

  const body = await response.json();
  assertEquals(Array.isArray(body), true);

  // If there are results, check structure
  if (body.length > 0) {
    assertExists(body[0].dateText);
    assertExists(body[0].venue);
    assertExists(body[0].sourceUrl);
  }
});

Deno.test("listings - fetches high court listings", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(
    `${BASE_URL}/listings?date=2026-02-03&court=high-court`,
    { headers: getAuthHeaders() }
  );
  assertEquals(response.status, 200);

  const body = await response.json();
  assertEquals(Array.isArray(body), true);
});

Deno.test("listings - POST method works", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(`${BASE_URL}/listings`, {
    method: "POST",
    headers: getAuthHeaders(),
    body: JSON.stringify({ date: "2026-02-03", court: "circuit-court" }),
  });
  assertEquals(response.status, 200);

  const body = await response.json();
  assertEquals(Array.isArray(body), true);
});

Deno.test("listings - defaults to circuit court", async () => {
  if (skipIfNoToken()) return;

  const response = await fetch(`${BASE_URL}/listings?date=2026-02-03`, {
    headers: getAuthHeaders(),
  });
  assertEquals(response.status, 200);
  // Should succeed without specifying court
});
