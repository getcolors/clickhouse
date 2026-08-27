// OpenTofu and Ansible stages for the fixed v1 topology, the port of
// io.github.getcolors.clickhouse.tools.

import { existsSync, mkdirSync, readFileSync, rmSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import * as ansible from "red/ansible";
import { stageDir } from "red/cli";
import { toolEnv } from "red/providers";
import { PRESERVE_JINJA_DELIMITERS, contentSpec, scaffold, type Spec, type Template } from "red/scaffold";
import * as tofu from "red/tofu";
import { runtime } from "red/runtime";
import type { Opts } from "red/workflow";
import { StepError, failed } from "red/workflow";
import * as utils from "./utils.ts";
import * as validate from "./validate.ts";

import acceptancePy from "../resources/tools/acceptance/acceptance.py" with { type: "text" };
import ansibleCfg from "../resources/tools/ansible/ansible.cfg" with { type: "text" };
import ansibleCleanup from "../resources/tools/ansible/cleanup.yml" with { type: "text" };
import ansibleClickhouseConfig from "../resources/tools/ansible/clickhouse-config.xml" with { type: "text" };
import ansibleClickhouseUsers from "../resources/tools/ansible/clickhouse-users.xml" with { type: "text" };
import ansibleClickhouse from "../resources/tools/ansible/clickhouse.yml" with { type: "text" };
import ansibleCompose from "../resources/tools/ansible/docker-compose.yml" with { type: "text" };
import ansibleMain from "../resources/tools/ansible/main.yml" with { type: "text" };
import ansibleMetabase from "../resources/tools/ansible/metabase.yml" with { type: "text" };
import ansibleWireguard from "../resources/tools/ansible/wireguard.yml" with { type: "text" };
import dbtProject from "../resources/tools/dbt/dbt_project.yml" with { type: "text" };
import dbtEventsSummary from "../resources/tools/dbt/models/events_summary.sql" with { type: "text" };
import dbtSchema from "../resources/tools/dbt/models/schema.yml" with { type: "text" };
import dbtProfiles from "../resources/tools/dbt/profiles.yml" with { type: "text" };
import dbtPyproject from "../resources/tools/dbt/pyproject.toml" with { type: "text" };
import dbtEvents from "../resources/tools/dbt/seeds/events.csv" with { type: "text" };
import tofuAccessMainTf from "../resources/tools/tofu/access/main.tf" with { type: "text" };
import tofuDnsMainTf from "../resources/tools/tofu/dns/main.tf" with { type: "text" };
import tofuFirewallMainTf from "../resources/tools/tofu/firewall/main.tf" with { type: "text" };
import tofuNetworkMainTf from "../resources/tools/tofu/network/main.tf" with { type: "text" };
import tofuServerAttachTf from "../resources/tools/tofu/server/attach.tf" with { type: "text" };

export const networkTool = "clickhouse-network";
export const accessTool = "clickhouse-access";
export const firewallTool = "clickhouse-firewall";
export const dnsTool = "clickhouse-dns";
export const ansibleTool = "clickhouse-ansible";
export const dbtTool = "clickhouse-dbt";
export const acceptanceTool = "clickhouse-acceptance";
export const serverTools: Record<string, string> = {
  "node-1": "clickhouse-node-1", "node-2": "clickhouse-node-2",
  "node-3": "clickhouse-node-3", metabase: "clickhouse-metabase",
};
export const tofuTools = [
  networkTool, accessTool, ...Object.values(serverTools), firewallTool, dnsTool,
];

export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "clickhouse" });
}

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "acceptance/acceptance.py": acceptancePy,
  "ansible/ansible.cfg": ansibleCfg,
  "ansible/cleanup.yml": ansibleCleanup,
  // Bun's own types declare `*.xml` imports as Document; at runtime a
  // `with { type: "text" }` import is a string, so the cast restores the truth.
  "ansible/clickhouse-config.xml": ansibleClickhouseConfig as unknown as string,
  "ansible/clickhouse-users.xml": ansibleClickhouseUsers as unknown as string,
  "ansible/clickhouse.yml": ansibleClickhouse,
  "ansible/docker-compose.yml": ansibleCompose,
  "ansible/main.yml": ansibleMain,
  "ansible/metabase.yml": ansibleMetabase,
  "ansible/wireguard.yml": ansibleWireguard,
  "dbt/dbt_project.yml": dbtProject,
  "dbt/models/events_summary.sql": dbtEventsSummary,
  "dbt/models/schema.yml": dbtSchema,
  "dbt/profiles.yml": dbtProfiles,
  "dbt/pyproject.toml": dbtPyproject,
  "dbt/seeds/events.csv": dbtEvents,
  "tofu/access/main.tf": tofuAccessMainTf,
  "tofu/dns/main.tf": tofuDnsMainTf,
  "tofu/firewall/main.tf": tofuFirewallMainTf,
  "tofu/network/main.tf": tofuNetworkMainTf,
  "tofu/server/attach.tf": tofuServerAttachTf,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new StepError(`template not found: ${name}`);
  return { name, content };
}

// ONCE's unmodified Hetzner compute template, resolved from the installed
// package the way the airflow package resolves ONCE's compute templates.
export function onceTemplate(provider: string): Template {
  const entry = Bun.resolveSync("package-once-red", import.meta.dir);
  const path = join(dirname(entry), `../resources/tools/tofu/${provider}/main.tf`);
  return { name: `once/tools/tofu/${provider}/main.tf`, content: readFileSync(path, "utf8") };
}

function spec(source: Template, target: string, data: Opts): Spec {
  return { template: source, target, data, opts: templateOpts };
}

const rawSpec = (target: string, content: string): Spec => contentSpec(target, content);

export function credentialEnv(opts: Opts, ...slots: string[]): Record<string, string> | undefined {
  return toolEnv(validate.providers, opts, [...slots, "provider-backend"]);
}

export async function tofuStep(opts: Opts, tool: string, specs: Spec[], slots: string[]): Promise<Opts> {
  const env = { ...credentialEnv(opts, ...slots), ...(opts["clickhouse/process-env"] ?? {}) };
  return tofu.tofuWithSpec(opts, specs, {
    dir: toolDir(opts, tool),
    env: Object.keys(env).length ? env : undefined,
  });
}

export async function networkStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, networkTool);
  return tofuStep(opts, networkTool,
    [spec(template("tofu.network", "main.tf"), `${dir}/main.tf`, opts)],
    ["provider-compute"]);
}

export const placeholderSshPublicKey =
  "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 colors-build-placeholder";

export function managedSshData(opts: Opts): Opts {
  const dir = toolDir(opts, accessTool);
  const privateFile = resolve(join(dir, ".private", "id_ed25519"));
  const publicFile = `${privateFile}.pub`;
  return {
    ...opts,
    "managed-ssh-key-name": `${opts["hcloud-name"]}-managed`,
    "managed-ssh-private-key": privateFile,
    "managed-ssh-inventory-key": "../clickhouse-access/.private/id_ed25519",
    "managed-ssh-public-key": existsSync(publicFile)
      ? readFileSync(publicFile, "utf8").trim()
      : placeholderSshPublicKey,
  };
}

export async function ensureSshAgent(opts: Opts): Promise<Opts> {
  const privateFile = String(opts["managed-ssh-private-key"]);
  const socket = `/tmp/colors-${opts.profile}-ssh-agent.sock`;
  const env = { SSH_AUTH_SOCK: socket };
  const listed = await runtime.exec(["ssh-add", "-l"], { env });
  if (listed.exit !== 0) {
    rmSync(socket, { force: true });
    const started = await runtime.exec(["ssh-agent", "-a", socket]);
    if (started.exit !== 0) {
      throw new StepError("failed to start managed SSH agent", { exit: started.exit });
    }
  }
  const added = await runtime.exec(["ssh-add", privateFile], { env });
  if (added.exit === 0) return { ...opts, "clickhouse/process-env": env };
  return { ...opts, "red/exit": added.exit, "red/err": "failed to load managed SSH key" };
}

export async function accessStep(opts: Opts): Promise<Opts> {
  let data = managedSshData(opts);
  const privateFile = String(data["managed-ssh-private-key"]);
  let keyResult;
  if (opts["red/event"] === "create" && !existsSync(privateFile)) {
    mkdirSync(dirname(privateFile), { recursive: true });
    keyResult = await runtime.exec([
      "ssh-keygen", "-q", "-t", "ed25519", "-N", "",
      "-C", `${opts.profile} managed by Colors`,
      "-f", privateFile,
    ]);
  }
  data = managedSshData(opts);
  if (opts["red/event"] === "create" && (!keyResult || keyResult.exit === 0)) {
    data = await ensureSshAgent(data);
  }
  if (keyResult && keyResult.exit !== 0) {
    return { ...opts, "red/exit": keyResult.exit, "red/err": keyResult.err };
  }
  if (failed(data)) return data;
  const dir = toolDir(opts, accessTool);
  return tofuStep(data, accessTool,
    [spec(template("tofu.access", "main.tf"), `${dir}/main.tf`, data)],
    ["provider-compute"]);
}

export function serverData(opts: Opts, id: string): Opts {
  const { role, ordinal } = utils.server(id);
  const base = String(opts["hcloud-name"]);
  return {
    ...opts,
    "server-id": id, "server-role": role, "server-ordinal": ordinal,
    "vpn-ip": utils.server(id)["vpn-ip"], "private-ip": utils.server(id)["private-ip"],
    "network-name": `${base}-network`,
    "hcloud-ssh-keys": `${base}-managed`,
    "hcloud-name": `${base}-${id}`,
    "hcloud-server-type": id === "metabase"
      ? opts["metabase-hcloud-server-type"]
      : opts["hcloud-server-type"],
  };
}

export function serverFallback(opts: Opts, id: string): Opts {
  return {
    ...utils.server(id),
    ip: `192.0.2.${10 + utils.server(id).ordinal}`,
    user: "root", sudoer: "root",
    name: `${opts.profile}-${id}`,
  };
}

export async function serverStep(opts: Opts, id: string): Promise<Opts> {
  const tool = serverTools[id]!;
  const dir = toolDir(opts, tool);
  const data = serverData(opts, id);
  const result = await tofuStep(opts, tool,
    [spec(onceTemplate("hcloud"), `${dir}/main.tf`, data),
     spec(template("tofu.server", "attach.tf"), `${dir}/attach.tf`, data)],
    ["provider-compute"]);
  const output = result["tofu/outputs"] as Record<string, unknown> | undefined;
  const params = {
    ...serverFallback(opts, id),
    ...((output?.params as Record<string, unknown> | undefined) ?? {}),
    ...(output && "private-ip" in output ? { "private-ip": output["private-ip"] } : {}),
  };
  if (failed(result)) return result;
  return {
    ...result,
    "clickhouse/servers": { ...(result["clickhouse/servers"] ?? {}), [id]: params },
  };
}

export const node1Step = (opts: Opts) => serverStep(opts, "node-1");
export const node2Step = (opts: Opts) => serverStep(opts, "node-2");
export const node3Step = (opts: Opts) => serverStep(opts, "node-3");
export const metabaseStep = (opts: Opts) => serverStep(opts, "metabase");

// Merge independently provisioned server outputs at Red's fan-in boundary.
export function joinServerBranches(opts: Opts): Opts {
  const branches = (opts["red/branches"] as Opts[] | undefined) ?? [];
  const servers = Object.assign(
    {},
    ...branches.map((branch) => branch["clickhouse/servers"]).filter(Boolean),
  ) as Record<string, unknown>;
  if (Object.keys(servers).length === 0) return opts;
  return {
    ...opts,
    "clickhouse/servers": { ...(opts["clickhouse/servers"] ?? {}), ...servers },
  };
}

export async function firewallStep(original: Opts): Promise<Opts> {
  const opts = joinServerBranches(original);
  const dir = toolDir(opts, firewallTool);
  return tofuStep(opts, firewallTool,
    [spec(template("tofu.firewall", "main.tf"), `${dir}/main.tf`, opts)],
    ["provider-compute"]);
}

export function dnsData(opts: Opts): Opts {
  return {
    ...opts,
    "metabase-host": utils.fqdn(opts, "metabase"),
    "clickhouse-host": utils.fqdn(opts, "clickhouse"),
  };
}

export async function dnsStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dnsTool);
  return tofuStep(opts, dnsTool,
    [spec(template("tofu.dns", "main.tf"), `${dir}/main.tf`, dnsData(opts))],
    ["provider-dns"]);
}

export function allServers(opts: Opts): Record<string, Opts> {
  const stored = (opts["clickhouse/servers"] as Record<string, Opts> | undefined) ?? {};
  const result: Record<string, Opts> = {};
  for (const server of utils.servers) {
    result[server.id] = {
      ...serverFallback(opts, server.id),
      ...server,
      ...(stored[server.id] ?? {}),
    };
  }
  return result;
}

// Java's Double.toString, which is what Cheshire renders floats through and
// therefore what green's committed inventory bytes would carry. Integral
// numbers print as longs. JS's shortest-round-trip digits are the same digits
// Java chooses; only the layout differs.
function javaNumber(value: number): string {
  if (Number.isInteger(value)) return String(value);
  const negative = value < 0;
  const [mantissa, exponentPart] = Math.abs(value).toExponential().split("e");
  const exponent = Number(exponentPart);
  const digits = mantissa!.replace(".", "");
  let body: string;
  if (exponent >= -3 && exponent < 7) {
    if (exponent >= 0) {
      const intPart = digits.padEnd(exponent + 1, "0").slice(0, exponent + 1);
      const fracPart = digits.slice(exponent + 1);
      body = `${intPart}.${fracPart.length > 0 ? fracPart : "0"}`;
    } else {
      body = `0.${"0".repeat(-exponent - 1)}${digits}`;
    }
  } else {
    const rest = digits.slice(1);
    body = `${digits[0]}.${rest.length > 0 ? rest : "0"}E${exponent}`;
  }
  return negative ? `-${body}` : body;
}

// Cheshire's pretty printer, byte for byte: spaces around colons, arrays
// inline, nested objects newline-indented, floats in Java notation.
function pretty(value: unknown, indent = 0): string {
  if (Array.isArray(value)) {
    if (value.length === 0) return "[ ]";
    return `[ ${value.map((item) => pretty(item, indent)).join(", ")} ]`;
  }
  if (value !== null && typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length === 0) return "{ }";
    const pad = " ".repeat(indent + 2);
    return `{\n${entries
      .map(([key, nested]) => `${pad}${JSON.stringify(key)} : ${pretty(nested, indent + 2)}`)
      .join(",\n")}\n${" ".repeat(indent)}}`;
  }
  if (typeof value === "number") return javaNumber(value);
  return JSON.stringify(value ?? null);
}

export function inventory(opts: Opts): string {
  const servers = allServers(opts);
  const inventoryKey = String(managedSshData(opts)["managed-ssh-inventory-key"]);
  const hosts: Record<string, Opts> = {};
  for (const [id, s] of Object.entries(servers)) {
    hosts[utils.hostAlias(opts, id)] = {
      ansible_host: s.ip, ansible_user: "root",
      private_ip: s["private-ip"], vpn_ip: s["vpn-ip"],
      server_role: s.role, server_ordinal: s.ordinal,
      ansible_ssh_private_key_file: inventoryKey,
    };
  }
  const selectKeys = (keys: string[]): Record<string, Opts> =>
    Object.fromEntries(keys.filter((key) => key in hosts).map((key) => [key, hosts[key]!]));
  return pretty({
    all: {
      children: {
        managed: { hosts },
        clickhouse: {
          hosts: selectKeys(utils.clickhouseServers().map((s) => utils.hostAlias(opts, s.id))),
        },
        metabase: { hosts: selectKeys([utils.hostAlias(opts, "metabase")]) },
        local: { hosts: { localhost: { ansible_connection: "local" } } },
      },
    },
  });
}

export function ansibleData(opts: Opts): Opts {
  return {
    ...opts,
    "metabase-host": utils.fqdn(opts, "metabase"),
    "clickhouse-host": utils.fqdn(opts, "clickhouse"),
    "local-wg-address": String(opts["wireguard-client-address"] ?? "").split("/")[0],
  };
}

export function ansibleSpecs(opts: Opts): Spec[] {
  const dir = toolDir(opts, ansibleTool);
  const data = ansibleData(opts);
  return [
    spec(template("ansible", "ansible.cfg"), `${dir}/ansible.cfg`, data),
    spec(template("ansible", "main.yml"), `${dir}/main.yml`, data),
    spec(template("ansible", "wireguard.yml"), `${dir}/wireguard.yml`, data),
    spec(template("ansible", "clickhouse.yml"), `${dir}/clickhouse.yml`, data),
    spec(template("ansible", "metabase.yml"), `${dir}/metabase.yml`, data),
    spec(template("ansible", "cleanup.yml"), `${dir}/cleanup.yml`, data),
    spec(template("ansible", "clickhouse-config.xml"), `${dir}/clickhouse-config.xml`, data),
    spec(template("ansible", "clickhouse-users.xml"), `${dir}/clickhouse-users.xml`, data),
    spec(template("ansible", "docker-compose.yml"), `${dir}/docker-compose.yml`, data),
    rawSpec(`${dir}/inventory.json`, inventory(opts)),
  ];
}

export function ansibleRenderStep(opts: Opts): Opts {
  return scaffold(opts, ansibleSpecs(opts));
}

export async function ansiblePlaybookStep(opts: Opts, playbook: string, recapKey: string): Promise<Opts> {
  if (opts["red/event"] === "build") return { ...opts, "red/exit": 0 };
  return ansible.ansibleStep(opts, {
    dir: toolDir(opts, ansibleTool),
    inventory: "inventory.json",
    playbooks: { create: playbook },
    hostKeyChecking: false,
    recapKey,
  });
}

export const wireguardStep = (opts: Opts) =>
  ansiblePlaybookStep(opts, "wireguard.yml", "clickhouse/wireguard-recap");
export const clickhouseConfigStep = (opts: Opts) =>
  ansiblePlaybookStep(opts, "clickhouse.yml", "clickhouse/clickhouse-recap");
export const metabaseConfigStep = (opts: Opts) =>
  ansiblePlaybookStep(opts, "metabase.yml", "clickhouse/metabase-recap");

export async function ansibleCleanupStep(opts: Opts): Promise<Opts> {
  return ansible.ansibleWithSpec(opts, {
    dir: toolDir(opts, ansibleTool),
    inventory: "inventory.json",
    playbooks: { delete: "cleanup.yml" },
    hostKeyChecking: false,
    recapKey: "clickhouse/cleanup-recap",
  }, ansibleSpecs(opts));
}

export async function dbtStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, dbtTool);
  const data = ansibleData(opts);
  const specs = [
    spec(template("dbt", "pyproject.toml"), `${dir}/pyproject.toml`, data),
    spec(template("dbt", "dbt_project.yml"), `${dir}/dbt_project.yml`, data),
    spec(template("dbt", "profiles.yml"), `${dir}/profiles.yml`, data),
    spec(template("dbt", "seeds/events.csv"), `${dir}/seeds/events.csv`, data),
    spec(template("dbt", "models/events_summary.sql"), `${dir}/models/events_summary.sql`, data),
    spec(template("dbt", "models/schema.yml"), `${dir}/models/schema.yml`, data),
  ];
  const rendered = scaffold(opts, specs);
  if (opts["red/event"] === "build" || opts["red/event"] === "delete") return rendered;
  const env = {
    DBT_PROFILES_DIR: dir,
    COLORS_DBT_PASSWORD: String(opts["clickhouse-dbt-password"] ?? ""),
  };
  const seed = await runtime.exec(["uv", "run", "dbt", "seed"], { cwd: dir, env });
  if (seed.exit !== 0) return { ...rendered, "red/exit": seed.exit, "red/err": seed.err };
  const run = await runtime.exec(["uv", "run", "dbt", "run", "--fail-fast"], { cwd: dir, env });
  if (run.exit !== 0) return { ...rendered, "red/exit": run.exit, "red/err": run.err };
  const test = await runtime.exec(["uv", "run", "dbt", "test"], { cwd: dir, env });
  if (test.exit !== 0) return { ...rendered, "red/exit": test.exit, "red/err": test.err };
  return rendered;
}

export async function acceptanceStep(opts: Opts): Promise<Opts> {
  const dir = toolDir(opts, acceptanceTool);
  const dbtDir = toolDir(opts, dbtTool);
  const data = ansibleData(opts);
  const script = `${dir}/acceptance.py`;
  const inventoryFile = join(toolDir(opts, ansibleTool), "inventory.json");
  const rendered = scaffold(opts, [spec(template("acceptance", "acceptance.py"), script, data)]);
  if (opts["red/event"] === "build" || opts["red/event"] === "delete") return rendered;
  const env = {
    COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD: String(opts["clickhouse-admin-password"] ?? ""),
    COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD: String(opts["clickhouse-metabase-password"] ?? ""),
    COLORS_PAR_METABASE_ADMIN_EMAIL: String(opts["metabase-admin-email"] ?? ""),
    COLORS_PAR_METABASE_ADMIN_PASSWORD: String(opts["metabase-admin-password"] ?? ""),
  };
  const result = await runtime.exec(["uv", "run", "python", script, inventoryFile],
    { cwd: dbtDir, env });
  if (result.exit !== 0) return { ...rendered, "red/exit": result.exit, "red/err": result.err };
  return rendered;
}

export async function driftStep(opts: Opts): Promise<Opts> {
  if (opts["red/event"] !== "create") return { ...opts, "red/exit": 0 };
  const env = credentialEnv(opts, "provider-compute", "provider-dns");
  const results = await Promise.all(tofuTools.map(async (tool) =>
    [tool, await runtime.exec(
      ["tofu", `-chdir=${toolDir(opts, tool)}`, "plan", "-detailed-exitcode", "-input=false", "-no-color"],
      { env })] as const));
  const bad = results.find(([, result]) => result.exit !== 0);
  if (bad) {
    const [tool, result] = bad;
    return {
      ...opts, "red/exit": result.exit,
      "red/err": `OpenTofu drift remains in ${tool}\n${result.out}${result.err}`,
    };
  }
  return { ...opts, "red/exit": 0 };
}
