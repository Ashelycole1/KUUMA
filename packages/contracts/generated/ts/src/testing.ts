/**
 * Contract-test harness for TypeScript builds (e.g. the Casefile web viewer).
 *
 *   import { assertPublishes, validateBody } from "@kuuma/contracts/testing";
 */
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormatsModule from "ajv-formats";
import { EVENTS, type Namespace } from "./models.js";
import bundleJson from "./schema-bundle.json" with { type: "json" };

const bundle = bundleJson as Record<string, object>;
const ID_BASE = "https://schemas.kuuma.ug/";

const addFormats = addFormatsModule as unknown as (ajv: Ajv2020) => Ajv2020;
const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);
for (const schema of Object.values(bundle)) ajv.addSchema(schema);

export class ContractViolation extends Error {}

function check(schemaId: string, data: unknown): void {
  const validate = ajv.getSchema(schemaId);
  if (!validate) throw new ContractViolation(`no schema ${schemaId}`);
  if (!validate(data)) {
    const detail = (validate.errors ?? [])
      .slice(0, 10)
      .map((e) => `${e.instancePath || "<root>"}: ${e.message}`)
      .join("; ");
    throw new ContractViolation(`${schemaId} rejected the data: ${detail}`);
  }
}

export function validateEvent(event: unknown): { type: string; version: number } {
  const e = event as { type?: string; version?: number };
  const spec = EVENTS.find((s) => s.type === e.type && s.version === e.version);
  if (!spec) throw new ContractViolation(`unknown event ${e.type}.v${e.version}`);
  check(`${ID_BASE}events/${spec.type}.v${spec.version}.json`, event);
  return { type: spec.type, version: spec.version };
}

export function assertPublishes(namespace: Namespace, event: unknown, leavingPhone = false): void {
  const { type, version } = validateEvent(event);
  const spec = EVENTS.find((s) => s.type === type && s.version === version)!;
  if (spec.publisher !== namespace) {
    throw new ContractViolation(`${namespace} may not publish ${type}; it belongs to ${spec.publisher}`);
  }
  if (leavingPhone && spec.phoneOnly) {
    throw new ContractViolation(`${type} is phone only and must never leave the phone`);
  }
}

/** Validate an API body, e.g. validateBody("api/verify-response.json", body). */
export function validateBody(schemaFile: string, body: unknown): void {
  check(ID_BASE + schemaFile, body);
}
