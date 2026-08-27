import { describe, expect, test } from "bun:test";
import type { Opts } from "red/workflow";
import * as tools from "../src/tools.ts";
import * as utils from "../src/utils.ts";
import * as validate from "../src/validate.ts";
import * as workflow from "../src/workflow.ts";

// --- tools -------------------------------------------------------------------

describe("tools", () => {
  test("topology addresses are stable", () => {
    expect(utils.servers.map((s) => s["vpn-ip"]))
      .toEqual(["10.21.0.1", "10.21.0.2", "10.21.0.3", "10.21.0.10"]);
  });

  test("each server reuses ONCE's hcloud template", () => {
    const template = tools.onceTemplate("hcloud");
    expect(template.name).toBe("once/tools/tofu/hcloud/main.tf");
    expect(template.content).toContain('resource "hcloud_server" "node1"');
  });

  test("server branches join without losing results", () => {
    const joined = tools.joinServerBranches({
      "red/branches": [
        { "clickhouse/servers": { "node-1": { ip: "192.0.2.1" } } },
        { "clickhouse/servers": { "node-2": { ip: "192.0.2.2" } } },
        { "clickhouse/servers": { metabase: { ip: "192.0.2.10" } } },
      ],
    });
    expect(new Set(Object.keys(joined["clickhouse/servers"] as Record<string, unknown>)))
      .toEqual(new Set(["node-1", "node-2", "metabase"]));
  });

  test("inventory has four remote hosts", () => {
    const text = tools.inventory({ profile: "p" });
    expect(text).toMatch(/p-node-1/);
    expect(text).toMatch(/p-metabase/);
    expect(text).toMatch(/10.20.1.13/);
  });
});

// --- validate ----------------------------------------------------------------

const base: Opts = {
  profile: "p", workdir: ".colors", "provider-compute": "hcloud",
  "provider-dns": "cloudflare", "provider-backend": "local",
  "compute-prevent-destroy": true, domain: "example.com",
  "clickhouse-cluster-name": "p", "clickhouse-version": "26.3.17.56",
  "clickhouse-shards": 1, "clickhouse-replicas": 3, "clickhouse-keeper-nodes": 3,
  "clickhouse-http-port": 8123, "clickhouse-native-port": 9000,
  "clickhouse-metabase-user": "metabase", "clickhouse-dbt-user": "dbt",
  "metabase-image": "metabase/metabase:v0.63.2",
  "metabase-postgres-image": "postgres:16.14", "metabase-port": 3000,
  "dbt-core-version": "1.11.12", "dbt-clickhouse-version": "1.10.1",
  "dbt-project-dir": "dbt", "metabase-hcloud-server-type": "cx23",
  "hcloud-name": "p", "hcloud-image": "ubuntu-24.04", "hcloud-server-type": "cx33",
  "hcloud-location": "nbg1", "hcloud-ssh-keys": "key",
  "hcloud-network-zone": "eu-central", "hcloud-network-cidr": "10.20.0.0/16",
  "hcloud-subnet-cidr": "10.20.1.0/24", "wireguard-port": 51820,
  "wireguard-network-cidr": "10.21.0.0/24", "wireguard-client-address": "10.21.0.254/32",
};

describe("validate", () => {
  test("valid state", () => {
    expect(validate.stateErrors(base)).toEqual([]);
  });

  test("topology is fixed", () => {
    expect(validate.stateErrors({ ...base, "clickhouse-replicas": 2 })
      .some((e) => /v1 requires/.test(e))).toBe(true);
  });

  test("profile overlay is refused", () => {
    expect(validate.envErrors({ [validate.profilePar]: "other" }).length).toBeGreaterThan(0);
  });

  test("all secrets are required", () => {
    expect(validate.secretErrors(base)
      .some((e) => /CLICKHOUSE_ADMIN_PASSWORD/.test(e))).toBe(true);
  });

  test("metabase encryption key has a minimum length", () => {
    const opts = {
      ...base,
      ...Object.fromEntries(validate.ownSecrets.map((key) => [key, "long-enough-secret"])),
    };
    expect(validate.secretErrors({ ...opts, "metabase-encryption-secret-key": "short" })
      .some((e) => /at least 16/.test(e))).toBe(true);
  });
});

// --- workflow ----------------------------------------------------------------

const create: Opts = { "red/event": "create" };
const del: Opts = { "red/event": "delete" };

const next = (step: string, opts: Opts): string[] =>
  (workflow.wireFn(step, opts) ?? []).slice(1).map(String);

describe("workflow", () => {
  test("create fans out and joins", () => {
    expect(next("clickhouse/network", create)).toEqual(["clickhouse/access"]);
    expect(next("clickhouse/access", create)).toEqual([
      "clickhouse/node-1", "clickhouse/node-2",
      "clickhouse/node-3", "clickhouse/metabase",
    ]);
    for (const step of ["clickhouse/node-1", "clickhouse/node-2",
                        "clickhouse/node-3", "clickhouse/metabase"]) {
      expect(next(step, create)).toEqual(["clickhouse/firewall"]);
    }
    expect(next("clickhouse/wireguard", create))
      .toEqual(["clickhouse/clickhouse-config", "clickhouse/metabase-config"]);
    expect(next("clickhouse/clickhouse-config", create)).toEqual(["clickhouse/dbt"]);
    expect(next("clickhouse/metabase-config", create)).toEqual(["clickhouse/dbt"]);
    expect(next("clickhouse/acceptance", create)).toEqual(["clickhouse/drift"]);
  });

  test("delete cleans and destroys in parallel", () => {
    expect(next("clickhouse/start", del)).toEqual(["clickhouse/dbt"]);
    expect(next("clickhouse/acceptance", del)).toEqual(["clickhouse/ansible-cleanup"]);
    expect(next("clickhouse/ansible-cleanup", del))
      .toEqual(["clickhouse/dns", "clickhouse/firewall"]);
    expect(next("clickhouse/infrastructure-clean", del)).toEqual([
      "clickhouse/node-1", "clickhouse/node-2",
      "clickhouse/node-3", "clickhouse/metabase",
    ]);
    for (const step of ["clickhouse/node-1", "clickhouse/node-2",
                        "clickhouse/node-3", "clickhouse/metabase"]) {
      expect(next(step, del)).toEqual(["clickhouse/access"]);
    }
    expect(next("clickhouse/access", del)).toEqual(["clickhouse/network"]);
  });
});
