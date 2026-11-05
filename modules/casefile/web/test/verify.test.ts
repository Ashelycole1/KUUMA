import { validateBody } from "@kuuma/contracts/testing";
import { describe as d, expect, it } from "vitest";
import { describe, verifyHash } from "../src/verify.js";

const HASH = "c5bf1e54aa40f41226e9d2f6ab25a1c41aa5cb53e65f1f338fd6cd2d738d35a6";

d("casefile web skeleton", () => {
  it("parses a contract-valid verify response", async () => {
    const body = { hash: HASH, found: true, timestamp: "2026-11-05T09:30:00Z", chainValid: true };
    validateBody("api/verify-response.json", body);
    const fake = async () => new Response(JSON.stringify(body), { status: 200 });
    const result = await verifyHash("http://localhost:8080", HASH, fake as typeof fetch);
    expect(describe(result)).toContain("intact");
  });

  it("refuses non-hashes", async () => {
    await expect(verifyHash("http://x", "nope")).rejects.toThrow();
  });
});
