import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { decryptShare, describeReport, verifyCaseFile, type CaseFileJson } from "../src/casefile.js";

// Produced by the Kotlin vault (CasefileTest.FixtureWriter), from an invented scenario.
const dir = join(dirname(fileURLToPath(import.meta.url)), "fixtures");
const caseFile = (): CaseFileJson => JSON.parse(readFileSync(join(dir, "case-file.json"), "utf8"));

describe("case file verification (against the Kotlin vault's real output)", () => {
  it("an untouched case file verifies", async () => {
    const r = await verifyCaseFile(caseFile());
    expect(r.problems).toEqual([]);
    expect(r.intact).toBe(true);
    expect(r.exhibits).toBeGreaterThan(0);
    expect(describeReport(r)).toContain("match their sealed hashes");
  });

  it("an edited exhibit is caught", async () => {
    const f = caseFile();
    const msg = f.exhibits.find((x) => x.kind === "MESSAGE")!;
    msg.content = msg.content!.replace("hurt", "meet");
    const r = await verifyCaseFile(f);
    expect(r.intact).toBe(false);
    expect(r.problems).toContainEqual({ kind: "exhibit_altered", exhibit: msg.exhibit });
  });

  it("a withheld exhibit keeps its place in the chain but not its content", async () => {
    const f = caseFile();
    const msg = f.exhibits.find((x) => x.kind === "MESSAGE")!;
    delete msg.content;
    msg.withheld = true;
    const r = await verifyCaseFile(f);
    expect(r.problems).toEqual([]);
    expect(r.withheld).toBe(1);
    expect(describeReport(r)).toContain("withheld to protect the child");
    // Claiming a different hash for a withheld exhibit is still caught.
    msg.itemHash = "0".repeat(64);
    expect((await verifyCaseFile(f)).problems).toContainEqual({
      kind: "exhibit_altered",
      exhibit: msg.exhibit,
    });
  });

  it("a removed or re-hashed link is caught", async () => {
    const removed = caseFile();
    removed.chain.links.splice(0, 1);
    expect((await verifyCaseFile(removed)).problems.map((p) => p.kind)).toContain("link_missing");

    const rehashed = caseFile();
    rehashed.chain.links[0].itemHash = "f".repeat(64);
    const kinds = (await verifyCaseFile(rehashed)).problems.map((p) => p.kind);
    expect(kinds).toContain("chain_broken");
  });

  it("a forged signature is caught", async () => {
    const f = caseFile();
    const sigs = f.chain.links.map((l) => l.signature);
    f.chain.links[0].signature = sigs[sigs.length - 1];
    expect((await verifyCaseFile(f)).problems).toContainEqual({ kind: "bad_signature", index: 0 });
  });

  it("checks the timestamp against the service", async () => {
    const f = caseFile();
    f.timestamp = {
      hash: f.chain.root,
      timestamp: "2026-11-05T09:30:00Z",
      signature: "c2ln",
      authority: "kuuma-local-mock-tsa",
    };
    const good = await verifyCaseFile(f, async (root) => ({
      hash: root,
      found: true,
      timestamp: "2026-11-05T09:30:00Z",
      chainValid: true,
    }));
    expect(good.intact).toBe(true);
    expect(good.timestamp.confirmed).toBe(true);
    const bad = await verifyCaseFile(f, async (root) => ({ hash: root, found: false }));
    expect(bad.problems).toContainEqual({ kind: "timestamp_unconfirmed" });
  });

  it("decrypts a share made on the phone with the key from the link", async () => {
    const blob = new Uint8Array(readFileSync(join(dir, "share.bin")));
    const key = readFileSync(join(dir, "share.key"), "utf8").trim();
    const share = await decryptShare(blob, key);
    expect(new TextDecoder().decode(share.pdf.slice(0, 8))).toBe("%PDF-1.4");
    expect((await verifyCaseFile(share.case)).intact).toBe(true);
    await expect(decryptShare(blob, "A".repeat(43))).rejects.toThrow();
  });
});
