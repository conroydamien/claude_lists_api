/**
 * API Test Report - Shows request/response details for all endpoints.
 *
 * Usage:
 *   GOOGLE_ID_TOKEN=xxx deno run --allow-net --allow-env api_report.ts
 */

const BASE_URL = Deno.env.get("SUPABASE_FUNCTIONS_URL") ||
  "https://fbiissfiqgtlenxkjuwv.supabase.co/functions/v1";

const TOKEN = Deno.env.get("GOOGLE_ID_TOKEN");

if (!TOKEN) {
  console.error("❌ GOOGLE_ID_TOKEN environment variable required");
  console.error("   Get it from app logs after signing in");
  Deno.exit(1);
}

const headers = {
  "Authorization": `Bearer ${TOKEN}`,
  "Content-Type": "application/json",
};

interface TestResult {
  endpoint: string;
  method: string;
  request: unknown;
  status: number;
  response: unknown;
  duration: number;
}

const results: TestResult[] = [];

async function testEndpoint(
  name: string,
  method: string,
  path: string,
  body?: unknown
): Promise<unknown> {
  const url = `${BASE_URL}${path}`;
  console.log(`\n${"=".repeat(60)}`);
  console.log(`📡 ${method} ${name}`);
  console.log(`${"=".repeat(60)}`);
  console.log(`URL: ${url}`);

  if (body) {
    console.log(`\n📤 Request Body:`);
    console.log(JSON.stringify(body, null, 2));
  }

  const start = Date.now();
  const response = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const duration = Date.now() - start;

  const responseBody = await response.json();

  console.log(`\n📥 Response [${response.status}] (${duration}ms):`);
  console.log(JSON.stringify(responseBody, null, 2).slice(0, 2000));
  if (JSON.stringify(responseBody).length > 2000) {
    console.log("... (truncated)");
  }

  results.push({
    endpoint: name,
    method,
    request: body || path,
    status: response.status,
    response: responseBody,
    duration,
  });

  return responseBody;
}

async function runReport() {
  console.log("🧪 Court Lists API - Integration Test Report");
  console.log(`🔗 Base URL: ${BASE_URL}`);
  console.log(`🕐 Started: ${new Date().toISOString()}`);

  // Test 1: Listings - Circuit Court
  const circuitListings = await testEndpoint(
    "/listings (Circuit Court)",
    "POST",
    "/listings",
    { date: new Date().toISOString().split("T")[0], court: "circuit-court" }
  ) as Array<{ sourceUrl: string }>;

  // Test 2: Listings - High Court
  const highCourtListings = await testEndpoint(
    "/listings (High Court)",
    "POST",
    "/listings",
    { date: new Date().toISOString().split("T")[0], court: "high-court" }
  ) as Array<{ sourceUrl: string }>;

  // Test 3: Cases - Circuit Court (if listings available)
  if (circuitListings.length > 0) {
    await testEndpoint(
      "/cases (Circuit Court)",
      "POST",
      "/cases",
      { url: circuitListings[0].sourceUrl }
    );
  } else {
    console.log("\n⚠️  No Circuit Court listings available to test /cases");
  }

  // Test 4: Cases - High Court (if listings available)
  if (highCourtListings.length > 0) {
    await testEndpoint(
      "/cases (High Court)",
      "POST",
      "/cases",
      { url: highCourtListings[0].sourceUrl }
    );
  } else {
    console.log("\n⚠️  No High Court listings available to test /cases");
  }

  // Test 5: Comments - GET
  const testListUrl = circuitListings[0]?.sourceUrl || "https://legaldiary.courts.ie/test";
  const testCaseKey = "TEST|1";
  await testEndpoint(
    "/comments (GET)",
    "GET",
    `/comments?list_source_url=${encodeURIComponent(testListUrl)}&case_key=${encodeURIComponent(testCaseKey)}`,
  );

  // Test 6: Case Status - GET
  await testEndpoint(
    "/case-status (GET)",
    "GET",
    `/case-status?list_source_url=${encodeURIComponent(testListUrl)}`,
  );

  // Test 7: Watched Cases - GET
  await testEndpoint(
    "/watched-cases (GET)",
    "GET",
    "/watched-cases",
  );

  // Test 8: Notifications - GET
  await testEndpoint(
    "/notifications (GET)",
    "GET",
    "/notifications",
  );

  // Summary
  console.log(`\n${"=".repeat(60)}`);
  console.log("📊 SUMMARY");
  console.log(`${"=".repeat(60)}`);
  console.log(`\nTotal tests: ${results.length}`);
  console.log(`Passed: ${results.filter(r => r.status >= 200 && r.status < 300).length}`);
  console.log(`Failed: ${results.filter(r => r.status >= 400).length}`);
  console.log(`\nResults by endpoint:`);

  for (const r of results) {
    const icon = r.status >= 200 && r.status < 300 ? "✅" : "❌";
    console.log(`  ${icon} ${r.method} ${r.endpoint} - ${r.status} (${r.duration}ms)`);
  }

  console.log(`\n🕐 Completed: ${new Date().toISOString()}`);

  // Save results to JSON file
  const reportFile = `api_report_${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  await Deno.writeTextFile(reportFile, JSON.stringify(results, null, 2));
  console.log(`\n📁 Results saved to: ${reportFile}`);
}

runReport().catch(console.error);
