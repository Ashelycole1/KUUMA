// The verification page: /verify/ (drop a case file) and /verify/s/<requestId>#k=<key> (open a share).
// Everything happens in the browser: the decryption key in the #fragment is never sent anywhere.
import {
  decryptShare,
  describeReport,
  sha256Hex,
  verifyCaseFile,
  type CaseFileJson,
  type Report,
} from "./casefile.js";

const $ = (id: string) => document.getElementById(id)!;
const confirm = (root: string) => fetch(`/v1/verify/${root}`).then((r) => r.json());

function show(report: Report, file: CaseFileJson, pdf?: Uint8Array): void {
  $("result").hidden = false;
  $("result").className = `card ${report.intact ? "ok" : "bad"}`;
  $("verdict").textContent = report.intact ? "Verified" : "Not verified";
  $("summary").textContent = describeReport(report);
  $("details").textContent =
    `Case ${file.caseId} · ${file.offenderLabel} · prepared for ${file.recipientType} · generated ${file.generatedAt}\n` +
    `Chain root ${report.root}\n` +
    (report.problems.length
      ? `Problems: ${report.problems.map((p) => p.kind).join(", ")}`
      : "No problems found.");
  if (pdf) {
    const link = $("pdf") as HTMLAnchorElement;
    link.href = URL.createObjectURL(new Blob([pdf as BlobPart], { type: "application/pdf" }));
    link.download = `kuuma-case-${file.caseId.slice(0, 8)}.pdf`;
    link.hidden = false;
  }
}

function fail(message: string): void {
  $("result").hidden = false;
  $("result").className = "card bad";
  $("verdict").textContent = "Could not open";
  $("summary").textContent = message;
}

async function openShare(requestId: string, key: string): Promise<void> {
  $("status").textContent = "Fetching the shared case…";
  const res = await fetch(`/v1/verify/shares/${requestId}`);
  if (res.status === 403) return fail("This share is waiting for a parent's approval.");
  if (!res.ok) return fail("This share link has expired, was declined, or does not exist.");
  const meta = (await res.json()) as { bundleHash: string; downloadUrl: string };
  const blob = new Uint8Array(await (await fetch(meta.downloadUrl)).arrayBuffer());
  if ((await sha256Hex(blob)) !== meta.bundleHash)
    return fail("The download does not match its recorded hash.");
  try {
    const share = await decryptShare(blob, key);
    show(await verifyCaseFile(share.case, confirm), share.case, share.pdf);
  } catch {
    fail("The link's key does not open this case. Check that you copied the whole link.");
  } finally {
    $("status").textContent = "";
  }
}

async function openFile(file: File): Promise<void> {
  try {
    const json = JSON.parse(await file.text()) as CaseFileJson;
    show(await verifyCaseFile(json, confirm), json);
  } catch {
    fail("That file is not a Kuuma case file.");
  }
}

const share = location.pathname.match(/\/s\/([0-9a-f-]{36})\/?$/);
const key = new URLSearchParams(location.hash.slice(1)).get("k");
if (share && key) void openShare(share[1], key);
($("file") as HTMLInputElement).addEventListener("change", (e) => {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (f) void openFile(f);
});
