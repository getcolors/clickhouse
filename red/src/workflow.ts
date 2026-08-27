// Lifecycle graph and backend advice, the port of
// io.github.getcolors.clickhouse.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type WireDecl } from "red/workflow";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "compute-prevent-destroy": true, "provider-compute": "hcloud",
  "provider-dns": "cloudflare", "provider-backend": "local",
  workdir: ".colors",
};

export const lifecycleEvents = ["create", "delete"];

export async function startStep(
  opts: Opts,
  env: Record<string, string | undefined> = process.env,
): Promise<Opts> {
  return preflight(opts, {
    defaults,
    overlay: readPars,
    validators: [
      (_opts, environment) => validate.envErrors(environment),
      (current) => validate.stateErrors(current),
      (current, _environment, { event, real }) =>
        real && lifecycleEvents.includes(String(event))
          ? validate.secretErrors(current)
          : [],
      (current, _environment, { event, real }) =>
        real && event === "delete" && current["compute-prevent-destroy"]
          ? [`compute destruction is protected; set ${parName("compute-prevent-destroy")}=false to delete`]
          : [],
    ],
  }, env);
}

export function passStep(opts: Opts): Opts {
  return { ...opts, "red/exit": 0 };
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "clickhouse/start": [startStep, "clickhouse/dbt"],
      "clickhouse/dbt": [tools.dbtStep, "clickhouse/acceptance"],
      "clickhouse/acceptance": [tools.acceptanceStep, "clickhouse/ansible-cleanup"],
      "clickhouse/ansible-cleanup": [tools.ansibleCleanupStep,
                                     "clickhouse/dns", "clickhouse/firewall"],
      "clickhouse/dns": [tools.dnsStep, "clickhouse/infrastructure-clean"],
      "clickhouse/firewall": [tools.firewallStep, "clickhouse/infrastructure-clean"],
      "clickhouse/infrastructure-clean": [passStep,
                                          "clickhouse/node-1", "clickhouse/node-2",
                                          "clickhouse/node-3", "clickhouse/metabase"],
      "clickhouse/node-1": [tools.node1Step, "clickhouse/access"],
      "clickhouse/node-2": [tools.node2Step, "clickhouse/access"],
      "clickhouse/node-3": [tools.node3Step, "clickhouse/access"],
      "clickhouse/metabase": [tools.metabaseStep, "clickhouse/access"],
      "clickhouse/access": [tools.accessStep, "clickhouse/network"],
      "clickhouse/network": [tools.networkStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "clickhouse/start": [startStep, "clickhouse/network"],
    "clickhouse/network": [tools.networkStep, "clickhouse/access"],
    "clickhouse/access": [tools.accessStep,
                          "clickhouse/node-1", "clickhouse/node-2",
                          "clickhouse/node-3", "clickhouse/metabase"],
    "clickhouse/node-1": [tools.node1Step, "clickhouse/firewall"],
    "clickhouse/node-2": [tools.node2Step, "clickhouse/firewall"],
    "clickhouse/node-3": [tools.node3Step, "clickhouse/firewall"],
    "clickhouse/metabase": [tools.metabaseStep, "clickhouse/firewall"],
    "clickhouse/firewall": [tools.firewallStep, "clickhouse/dns"],
    "clickhouse/dns": [tools.dnsStep, "clickhouse/ansible-render"],
    "clickhouse/ansible-render": [tools.ansibleRenderStep, "clickhouse/wireguard"],
    "clickhouse/wireguard": [tools.wireguardStep,
                             "clickhouse/clickhouse-config", "clickhouse/metabase-config"],
    "clickhouse/clickhouse-config": [tools.clickhouseConfigStep, "clickhouse/dbt"],
    "clickhouse/metabase-config": [tools.metabaseConfigStep, "clickhouse/dbt"],
    "clickhouse/dbt": [tools.dbtStep, "clickhouse/acceptance"],
    "clickhouse/acceptance": [tools.acceptanceStep, "clickhouse/drift"],
    "clickhouse/drift": [tools.driftStep],
  };
  return graph[step];
}

export function backendAdvice(tool: string) {
  return tofu.conventionalBackendAdvice({
    dir: (opts) => tools.toolDir(opts, tool),
    key: (opts) => `${opts.profile}/${tool}.tfstate`,
  });
}

export const sideEffecting = [
  "clickhouse/network", "clickhouse/access", "clickhouse/node-1", "clickhouse/node-2",
  "clickhouse/node-3", "clickhouse/metabase", "clickhouse/firewall",
  "clickhouse/dns", "clickhouse/wireguard", "clickhouse/clickhouse-config",
  "clickhouse/metabase-config", "clickhouse/ansible-cleanup", "clickhouse/dbt",
  "clickhouse/acceptance", "clickhouse/drift",
];

function create() {
  let wf = workflow({ start: "clickhouse/start", wireFn });
  wf = progress.advise(wf);
  wf = dryRun.advise(wf, sideEffecting);
  for (const tool of tools.tofuTools) {
    wf = adviceAdd(wf, `clickhouse/${tool.slice("clickhouse-".length)}`, "before",
      `io.github.getcolors.clickhouse.workflow/backend-${tool}`, backendAdvice(tool));
  }
  return wf;
}

export const clickhouseWorkflow = create();
