import localMain from "../resources/tools/ansible-local/main.yml" with { type: "text" };
import localInventory from "../resources/tools/ansible-local/inventory.ini" with { type: "text" };
import localCfg from "../resources/tools/ansible-local/ansible.cfg" with { type: "text" };
import * as sshConfig from './ssh-config.ts';
import {keyMode} from 'colors-compute-red';
import { plan_deployment, orchestrate, read_deployment, check_deployment_drift } from 'colors-compute-red';
import * as compute from './compute.ts';
// OpenTofu and Ansible stages for the fixed v1 topology, the port of
// io.github.getcolors.clickhouse.tools.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
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
import tofuDnsMainTf from "../resources/tools/tofu/dns/main.tf" with { type: "text" };

export const infrastructureTool = "clickhouse-infrastructure";
export const dnsTool = "clickhouse-dns";
export const ansibleTool = "clickhouse-ansible";
export const dbtTool = "clickhouse-dbt";
export const acceptanceTool = "clickhouse-acceptance";
export const tofuTools = [dnsTool];

export const templateOpts = PRESERVE_JINJA_DELIMITERS;

export function toolDir(opts: Opts, tool: string): string {
  return stageDir(opts, tool, { defaultProfile: "clickhouse" });
}

// The template tree this colour carries, keyed the way green names its
// classpath resources: "<path>/<file>" with dots as directories.
const templates: Record<string, string> = {
  "ansible-local/ansible.cfg":localCfg,"ansible-local/inventory.ini":localInventory,"ansible-local/main.yml":localMain,
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
  "tofu/dns/main.tf": tofuDnsMainTf,
};

export function template(path: string, file: string): Template {
  const name = `${path.replaceAll(".", "/")}/${file}`;
  const content = templates[name];
  if (content === undefined) throw new StepError(`template not found: ${name}`);
  return { name, content };
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

function sorted(value: any): any {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key=>[key,sorted(value[key])]));
  return value;
}
export async function infrastructureStep(opts: Opts): Promise<Opts> {
  try {
    const planning=opts['red/event']==='build'||opts['red/dry-run'];
    const result:any=planning?plan_deployment(opts,compute.TOPOLOGY,compute.requirements(opts)):await orchestrate(opts,compute.TOPOLOGY,compute.requirements(opts));
    if(planning) {
      const stages:any[]=[['shared',result.documents.shared],...Object.entries(result.documents.nodes).map(([node,docs])=>['nodes/'+node,docs])];
      for(const [stage,documents] of stages) for(const [name,document] of Object.entries(documents)) {
        const target=join(toolDir(opts,infrastructureTool),stage,name); mkdirSync(dirname(target),{recursive:true});
        writeFileSync(target,JSON.stringify(sorted(document),null,2)+'\n');
      }
    }
    if(!['ready','planned','destroyed'].includes(result.status)) return {...opts,'red/exit':1,'red/err':result.errors?.join('\n')||'compute lifecycle refused; inspect deployment state before retrying'};
    const output:Opts={...opts,'red/exit':0};
    if(result.cluster) Object.assign(output,{'colors-compute/cluster':result.cluster,'colors-compute/shared':result.shared});
    const path=result.key?.private_key_path;
    if(path) output['ssh-private-key-path']=planning?path.replace('$HOME/.ssh','/home/build-placeholder/.ssh'):path;
    return output;
  } catch {return {...opts,'red/exit':1,'red/err':'compute lifecycle refused; inspect deployment state before retrying'};}
}
export async function loadInfrastructureStep(opts: Opts): Promise<Opts> {
  if(opts['red/event']==='build'||opts['red/dry-run']) return infrastructureStep(opts);
  const result:any=await read_deployment(opts,undefined,undefined,compute.requirements(opts));
  if(result.status==='destroyed'&&opts['red/event']==='delete') return {...opts,'clickhouse/already-destroyed':true,'red/exit':0};
  if(result.status!=='present') return {...opts,'red/exit':1,'red/err':'compute state unavailable; legacy monolithic state requires explicit migration'};
  const output:Opts={...opts,'red/exit':0,'colors-compute/cluster':result.cluster,'colors-compute/shared':result.shared,'clickhouse/infrastructure-present?':true};
  if(result.key?.private_key_path) output['ssh-private-key-path']=result.key.private_key_path;
  return output;
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
  const nodes=Object.fromEntries(compute.resolved(opts).map(node=>[node.node_id,node]));
  return Object.fromEntries(utils.servers.map(app=>[app.id,{...app,...nodes[app['node-id']],'private-ip':nodes[app['node-id']].vpc_ip}]));
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
  const inventoryKey = opts["ssh-private-key-path"] ?? null;
  const hosts: Record<string, Opts> = {};
  for (const [id, s] of Object.entries(servers)) {
    hosts[utils.hostAlias(opts, id)] = {
      ansible_host: s.ip, ansible_user: s.user,
      private_ip: s["private-ip"], vpn_ip: s["vpn-ip"],
      server_role: s.role, server_ordinal: s.ordinal,
      ...(inventoryKey ? {ansible_ssh_private_key_file: inventoryKey} : {}),
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
  const computeResult = await check_deployment_drift(opts, compute.TOPOLOGY, compute.requirements(opts));
  if(computeResult.status!=='clean') return {...opts,'red/exit':1,'red/err':computeResult.errors?.join('\n')||'compute drift check failed'};
  const env = credentialEnv(opts, "provider-dns");
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

export const ansibleLocalTool='clickhouse-ansible-local';
export function sshConfigHosts(opts:Opts){const nodes=compute.resolved(opts),entry=nodes.find(node=>node.node_id==='clickhouse-0');return [{...entry,name:opts.profile},...nodes.map(node=>({...node,name:opts.profile+'-'+node.node_id}))];}
export function ansibleLocalSpecs(opts:Opts):Spec[]{const data={...opts,'ssh-keygen':keyMode(opts).mode==='managed'},dir=toolDir(opts,ansibleLocalTool);return ['ansible.cfg','inventory.ini','main.yml'].map(name=>spec(template('ansible-local',name),dir+'/'+name,data));}
export async function ansibleLocalStep(opts:Opts):Promise<Opts>{
 if(opts['red/event']==='create'&&!opts['red/dry-run']){opts=sshConfig.preflight(opts);if(failed(opts))return opts;}
 return ansible.ansibleWithSpec(opts,{dir:toolDir(opts,ansibleLocalTool),inventory:'inventory.ini',playbooks:{create:'main.yml',delete:'main.yml'},extraVars:{host_alias:opts.profile,ssh_hosts:sshConfigHosts(opts),block_state:opts['red/event']==='delete'?'absent':'present'}},ansibleLocalSpecs(opts));
}
