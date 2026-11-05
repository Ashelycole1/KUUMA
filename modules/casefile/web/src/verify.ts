// Casefile web skeleton. Casefile owner: replace with the real verification viewer.
import type { VerifyResponse } from "@kuuma/contracts";
import { PATTERNS } from "@kuuma/contracts";

export const SHA256 = new RegExp(PATTERNS.Sha256);

/** Fetch the verification result for an evidence hash from /v1/verify. */
export async function verifyHash(baseUrl: string, hash: string, fetchFn = fetch): Promise<VerifyResponse> {
  if (!SHA256.test(hash)) throw new Error("not a sha256 hash");
  const res = await fetchFn(`${baseUrl}/v1/verify/${hash}`);
  if (!res.ok) throw new Error(`verify failed: ${res.status}`);
  return (await res.json()) as VerifyResponse;
}

export function describe(result: VerifyResponse): string {
  if (!result.found) return "This evidence hash is not known to Kuuma.";
  return result.chainValid
    ? `Timestamped at ${result.timestamp}; the evidence chain is intact.`
    : `Timestamped at ${result.timestamp}; the evidence chain could NOT be verified.`;
}
