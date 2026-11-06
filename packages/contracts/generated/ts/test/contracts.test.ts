import { describe, expect, it } from "vitest";
import { EVENTS, type SentinelMessageFlagged } from "../src/models.js";
import { assertPublishes, ContractViolation, validateBody } from "../src/testing.js";

const REF = "or1_" + "A".repeat(43);

const flagged: SentinelMessageFlagged = {
  eventId: "3f1c6a2e-8b7d-4c1e-9a2b-1d2e3f4a5b6c",
  type: "sentinel.message.flagged",
  version: 1,
  occurredAt: "2026-11-05T09:30:00Z",
  deviceId: "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b",
  payload: {
    flagId: "f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c",
    messageRef: REF,
    appSource: "whatsapp",
    conversationRef: REF,
    senderRef: REF,
    category: "insult",
    severity: "high",
    confidence: 0.9,
    language: "lg",
  },
};

describe("contracts", () => {
  it("has 20 events", () => expect(EVENTS).toHaveLength(20));
  it("accepts a valid event from its publisher", () => assertPublishes("sentinel", flagged));
  it("rejects another publisher", () =>
    expect(() => assertPublishes("guardian", flagged)).toThrow(ContractViolation));
  it("validates API bodies", () =>
    validateBody("api/verify-response.json", {
      hash: "c5bf1e54aa40f41226e9d2f6ab25a1c41aa5cb53e65f1f338fd6cd2d738d35a6",
      found: false,
    }));
});
