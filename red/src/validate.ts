// Desired-state and credential validation over ONCE's provider registry, the
// port of io.github.getcolors.clickhouse.validate.
//
// Green renders its keys as Clojure keywords, so every message here carries the
// same leading colon — the three colours must report identical errors for one
// colors.yml.

import { parName } from "red/cli";
import type { Opts } from "red/workflow";
import { providers } from "package-once-red";

export { providers };

export const slots = ["provider-compute", "provider-dns", "provider-backend"];

export const ownRequired = [
  "profile", "workdir", "domain", "clickhouse-cluster-name", "clickhouse-version",
  "clickhouse-shards", "clickhouse-replicas", "clickhouse-keeper-nodes",
  "clickhouse-http-port", "clickhouse-native-port",
  "clickhouse-metabase-user", "clickhouse-dbt-user",
  "metabase-image", "metabase-postgres-image", "metabase-port",
  "dbt-core-version", "dbt-clickhouse-version", "dbt-project-dir",
  "metabase-hcloud-server-type",
  "hcloud-network-zone", "hcloud-network-cidr", "hcloud-subnet-cidr",
  "wireguard-port", "wireguard-network-cidr", "wireguard-client-address",
];

export const ownSecrets = [
  "clickhouse-admin-password", "clickhouse-metabase-password",
  "clickhouse-dbt-password", "clickhouse-interserver-secret",
  "metabase-admin-email", "metabase-admin-password", "metabase-db-password",
  "metabase-encryption-secret-key",
];

export function placeholder(value: unknown): boolean {
  return value == null ||
    (typeof value === "string" && (!value.trim() || value.toUpperCase() === "REPLACE_ME"));
}

interface ProviderEntry {
  required?: string[];
  secrets?: string[];
  tofuEnv?: Record<string, string>;
}

function entry(opts: Opts, slot: string): ProviderEntry | undefined {
  return (providers as Record<string, Record<string, ProviderEntry>>)[slot]?.[String(opts[slot])];
}

export function tofuEnv(opts: Opts, slot: string): Record<string, string> {
  return entry(opts, slot)?.tofuEnv ?? {};
}

function slotKeys(opts: Opts, field: "required" | "secrets"): string[] {
  return slots.flatMap((slot) => entry(opts, slot)?.[field] ?? []);
}

function missing(opts: Opts, keys: string[]): string[] {
  return keys.filter((key) => placeholder(opts[key]));
}

export const profilePar = parName("profile");

export function envErrors(env: Record<string, string | undefined>): string[] {
  return String(env[profilePar] ?? "").length
    ? [`${profilePar} is set. ClickHouse takes profile from colors.yml only.`]
    : [];
}

const domainRe = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/;
const versionRe = /^[0-9]+(?:\.[0-9]+){3}$/;

export function positiveInt(x: unknown): boolean {
  return typeof x === "number" && Number.isInteger(x) && x > 0;
}

// pr-str, for the unsupported-provider message: green prints the offending
// value through pr-str, which quotes strings and renders nil bare.
function prStr(value: unknown): string {
  if (value == null) return "nil";
  if (typeof value === "string") return JSON.stringify(value);
  return String(value);
}

export function stateErrors(opts: Opts): string[] {
  const errors: string[] = [];
  for (const key of missing(opts, [...ownRequired, ...slotKeys(opts, "required")])) {
    errors.push(`:${key} is required`);
  }
  for (const slot of slots) {
    if (!entry(opts, slot)) {
      errors.push(`unsupported :${slot} ${prStr(opts[slot])}`);
    }
  }
  if (opts["provider-compute"] !== "hcloud") {
    errors.push(":provider-compute must be hcloud");
  }
  if (opts["provider-dns"] !== "cloudflare") {
    errors.push(":provider-dns must be cloudflare");
  }
  if (typeof opts["compute-prevent-destroy"] !== "boolean") {
    errors.push(":compute-prevent-destroy must be true or false");
  }
  if (!(placeholder(opts.domain) || domainRe.test(String(opts.domain)))) {
    errors.push(":domain must be a fully qualified Cloudflare zone");
  }
  if (!(placeholder(opts["clickhouse-version"]) ||
        versionRe.test(String(opts["clickhouse-version"])))) {
    errors.push(":clickhouse-version must be an exact four-part package version");
  }
  for (const key of ["clickhouse-shards", "clickhouse-replicas", "clickhouse-keeper-nodes",
                     "clickhouse-http-port", "clickhouse-native-port", "metabase-port",
                     "wireguard-port"]) {
    if (!positiveInt(opts[key])) errors.push(`:${key} must be a positive integer`);
  }
  if (!(opts["clickhouse-shards"] === 1 &&
        opts["clickhouse-replicas"] === 3 &&
        opts["clickhouse-keeper-nodes"] === 3)) {
    errors.push("v1 requires one shard, three replicas, and three Keeper nodes");
  }
  return errors;
}

export function secretErrors(opts: Opts): string[] {
  const errors = [...new Set(missing(opts, [...ownSecrets, ...slotKeys(opts, "secrets")]))]
    .map((key) => `required credential is not set: ${parName(key)}`);
  const key = opts["metabase-encryption-secret-key"];
  if (!placeholder(key) && String(key).length < 16) {
    errors.push("COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY must contain at least 16 characters");
  }
  return errors;
}
