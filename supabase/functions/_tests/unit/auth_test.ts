/**
 * Unit tests for auth helpers.
 */
import { assertEquals, assertExists } from "https://deno.land/std@0.208.0/assert/mod.ts";

// Import the functions we're testing
import { extractBearerToken } from "../../_shared/auth.ts";

Deno.test("extractBearerToken - valid bearer token", () => {
  const result = extractBearerToken("Bearer abc123xyz");
  assertEquals(result, "abc123xyz");
});

Deno.test("extractBearerToken - null header", () => {
  const result = extractBearerToken(null);
  assertEquals(result, null);
});

Deno.test("extractBearerToken - empty header", () => {
  const result = extractBearerToken("");
  assertEquals(result, null);
});

Deno.test("extractBearerToken - wrong scheme", () => {
  const result = extractBearerToken("Basic abc123");
  assertEquals(result, null);
});

Deno.test("extractBearerToken - case insensitive", () => {
  const result = extractBearerToken("bearer abc123xyz");
  assertEquals(result, "abc123xyz");
});

Deno.test("extractBearerToken - no token after Bearer", () => {
  const result = extractBearerToken("Bearer ");
  // Returns empty string which is falsy but not null
  assertEquals(result, "");
});

Deno.test("extractBearerToken - malformed (no space)", () => {
  const result = extractBearerToken("Bearerabc123");
  assertEquals(result, null);
});
