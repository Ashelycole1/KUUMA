/**
 * Verifies a Nilinde case file (format kuuma-casefile/1) exactly as the phone's vault built it:
 *
 *   itemHash  = sha256("kuuma-vault/1\n" + kind + "\n" + content)
 *   chainHash = sha256(prevHash + "|" + itemHash + "|" + index), first prevHash = 64 zeros
 *   signature = ECDSA-P256-SHA256 over chainHash (ASCII), DER-encoded, by the device key
 *
 * Uses only WebCrypto, so it runs in any modern browser and in Node 22.
 */
import type { VerifyResponse } from "@kuuma/contracts";

export const GENESIS = "0".repeat(64);
const VAULT_FORMAT = "kuuma-vault/1";

export interface ChainLink {
  index: number;
  itemHash: string;
  prevHash: string;
  chainHash: string;
  signature: string;
}

export interface Exhibit {
  exhibit: string;
  itemId: string;
  kind: string;
  chainIndex: number;
  itemHash: string;
  /** Absent when withheld: the exact sealed bytes, which recipients re-hash. */
  content?: string;
  /** An anonymous report leaves out exhibits that name the child; their hash stays in the chain. */
  withheld?: boolean;
}

export interface CaseFileJson {
  format: string;
  caseId: string;
  generatedAt: string;
  recipientType: string;
  offenderLabel: string;
  summary: Record<string, unknown>;
  exhibits: Exhibit[];
  chain: { algorithm: string; publicKey: string; keyProtection: string; root: string; links: ChainLink[] };
  custody: string[];
  timestamp: { hash: string; timestamp: string; signature: string; authority: string } | null;
}

export type Problem =
  | { kind: "format" }
  | { kind: "link_missing"; index: number }
  | { kind: "chain_broken"; index: number }
  | { kind: "bad_signature"; index: number }
  | { kind: "exhibit_altered"; exhibit: string }
  | { kind: "exhibit_not_in_chain"; exhibit: string }
  | { kind: "root_mismatch" }
  | { kind: "timestamp_mismatch" }
  | { kind: "timestamp_unconfirmed" };

export interface Report {
  intact: boolean;
  problems: Problem[];
  root: string;
  links: number;
  exhibits: number;
  /** Exhibits whose content was withheld to protect the child (place in the chain checked, content not). */
  withheld: number;
  timestamp: { present: boolean; confirmed: boolean | null; at: string | null; authority: string | null };
}

const enc = new TextEncoder();

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  return Array.from(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
}

export async function sha256Hex(data: string | Uint8Array): Promise<string> {
  const bytes = typeof data === "string" ? enc.encode(data) : data;
  return hex(await crypto.subtle.digest("SHA-256", bytes as BufferSource));
}

export function base64ToBytes(s: string): Uint8Array {
  const std = s.replace(/-/g, "+").replace(/_/g, "/");
  const padded = std + "=".repeat((4 - (std.length % 4)) % 4);
  const bin = atob(padded);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

/** Java's SHA256withECDSA writes DER; WebCrypto wants raw r||s (32 + 32 bytes). */
export function derToRaw(der: Uint8Array): Uint8Array {
  if (der[0] !== 0x30) throw new Error("not a DER signature");
  let i = 2;
  if (der[1] & 0x80) i = 2 + (der[1] & 0x7f);
  const read = (): Uint8Array => {
    if (der[i] !== 0x02) throw new Error("bad DER integer");
    const len = der[i + 1];
    let v = der.slice(i + 2, i + 2 + len);
    i += 2 + len;
    while (v.length > 32 && v[0] === 0) v = v.slice(1);
    const out = new Uint8Array(32);
    out.set(v, 32 - v.length);
    return out;
  };
  const r = read();
  const s = read();
  const raw = new Uint8Array(64);
  raw.set(r, 0);
  raw.set(s, 32);
  return raw;
}

export const itemHash = (kind: string, content: string) => sha256Hex(`${VAULT_FORMAT}\n${kind}\n${content}`);
export const chainHash = (prev: string, item: string, index: number) => sha256Hex(`${prev}|${item}|${index}`);

/**
 * Checks a case file. `confirmTimestamp` (optional) asks the timestamp service about the root,
 * e.g. (root) => fetch(`/v1/verify/${root}`).then((r) => r.json()).
 */
export async function verifyCaseFile(
  file: CaseFileJson,
  confirmTimestamp?: (root: string) => Promise<VerifyResponse>,
): Promise<Report> {
  const problems: Problem[] = [];
  if (file.format !== "kuuma-casefile/1" || !file.chain?.links) {
    return {
      intact: false,
      problems: [{ kind: "format" }],
      root: "",
      links: 0,
      exhibits: 0,
      withheld: 0,
      timestamp: { present: false, confirmed: null, at: null, authority: null },
    };
  }
  const links = [...file.chain.links].sort((a, b) => a.index - b.index);
  const key = await crypto.subtle.importKey(
    "spki",
    base64ToBytes(file.chain.publicKey) as BufferSource,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );

  let prev = GENESIS;
  for (let pos = 0; pos < links.length; pos++) {
    const l = links[pos];
    if (l.index !== pos) problems.push({ kind: "link_missing", index: pos });
    if (l.prevHash !== prev || (await chainHash(l.prevHash, l.itemHash, l.index)) !== l.chainHash) {
      problems.push({ kind: "chain_broken", index: l.index });
    }
    let ok = false;
    try {
      ok = await crypto.subtle.verify(
        { name: "ECDSA", hash: "SHA-256" },
        key,
        derToRaw(base64ToBytes(l.signature)) as BufferSource,
        enc.encode(l.chainHash),
      );
    } catch {
      ok = false;
    }
    if (!ok) problems.push({ kind: "bad_signature", index: l.index });
    prev = l.chainHash;
  }
  if (prev !== file.chain.root) problems.push({ kind: "root_mismatch" });

  const byIndex = new Map(links.map((l) => [l.index, l]));
  let withheld = 0;
  for (const x of file.exhibits) {
    const link = byIndex.get(x.chainIndex);
    if (!link) {
      problems.push({ kind: "exhibit_not_in_chain", exhibit: x.exhibit });
      continue;
    }
    if (x.withheld === true && x.content === undefined) {
      // Its place in the signed chain is checked; its content cannot be.
      withheld++;
      if (x.itemHash !== link.itemHash) problems.push({ kind: "exhibit_altered", exhibit: x.exhibit });
      continue;
    }
    const h = await itemHash(x.kind, x.content ?? "");
    if (h !== link.itemHash || h !== x.itemHash)
      problems.push({ kind: "exhibit_altered", exhibit: x.exhibit });
  }

  const ts = file.timestamp;
  let confirmed: boolean | null = null;
  if (ts) {
    if (ts.hash !== file.chain.root) problems.push({ kind: "timestamp_mismatch" });
    if (confirmTimestamp) {
      try {
        const r = await confirmTimestamp(file.chain.root);
        confirmed = r.found && r.timestamp === ts.timestamp && r.chainValid !== false;
      } catch {
        confirmed = false;
      }
      if (!confirmed) problems.push({ kind: "timestamp_unconfirmed" });
    }
  }
  return {
    intact: problems.length === 0,
    problems,
    root: file.chain.root,
    links: links.length,
    exhibits: file.exhibits.length,
    withheld,
    timestamp: { present: !!ts, confirmed, at: ts?.timestamp ?? null, authority: ts?.authority ?? null },
  };
}

/** A shared case: AES-256-GCM, "KCF1" + 12-byte IV + ciphertext. The key comes from the link's #fragment. */
export async function decryptShare(
  blob: Uint8Array,
  keyB64Url: string,
): Promise<{ case: CaseFileJson; pdf: Uint8Array }> {
  const magic = new TextDecoder().decode(blob.slice(0, 4));
  if (magic !== "KCF1") throw new Error("not a Nilinde share");
  const key = await crypto.subtle.importKey(
    "raw",
    base64ToBytes(keyB64Url) as BufferSource,
    "AES-GCM",
    false,
    ["decrypt"],
  );
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: blob.slice(4, 16) as BufferSource },
    key,
    blob.slice(16) as BufferSource,
  );
  const container = JSON.parse(new TextDecoder().decode(plain)) as {
    format: string;
    case: CaseFileJson;
    pdf: string;
  };
  if (container.format !== "kuuma-share/1") throw new Error("unknown share format");
  return { case: container.case, pdf: base64ToBytes(container.pdf) };
}

/** Plain-language summary of a report, for the page. */
export function describeReport(r: Report): string {
  if (!r.intact) {
    if (r.problems.some((p) => p.kind === "exhibit_altered"))
      return "An exhibit was changed after it was sealed. Do not rely on this case file.";
    if (
      r.problems.some(
        (p) => p.kind === "bad_signature" || p.kind === "chain_broken" || p.kind === "link_missing",
      )
    ) {
      return "The evidence chain is broken: items were changed, removed or reordered. Do not rely on this case file.";
    }
    if (r.problems.some((p) => p.kind === "timestamp_unconfirmed" || p.kind === "timestamp_mismatch")) {
      return "The exhibits are intact, but the timestamp could not be confirmed.";
    }
    return "This file could not be verified.";
  }
  const when = r.timestamp.present
    ? ` Its chain root was timestamped at ${r.timestamp.at} by ${r.timestamp.authority}.`
    : " It has no trusted timestamp.";
  const shown = r.exhibits - r.withheld;
  const anon = r.withheld
    ? ` ${r.withheld} exhibit${r.withheld === 1 ? " was" : "s were"} withheld to protect the child's identity: ${r.withheld === 1 ? "its" : "their"} place in the chain is verified, but not ${r.withheld === 1 ? "its" : "their"} content.`
    : "";
  return `All ${shown} exhibits match their sealed hashes and all ${r.links} chain links are signed by the same device.${anon}${when}`;
}
