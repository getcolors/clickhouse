import * as storage from './storage.ts';
import {finalize_backend} from 'colors-compute-red';
// Lifecycle graph and backend advice, the port of
// io.github.getcolors.clickhouse.workflow.

import { readPars, parName } from "red/cli";
import * as dryRun from "red/dry-run";
import { preflight } from "red/lifecycle";
import * as progress from "red/progress";
import * as tofu from "red/tofu";
import { adviceAdd, workflow, type Opts, type WireDecl, failed } from "red/workflow";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";

export const defaults: Opts = {
  "compute-prevent-destroy": true, "provider-compute": "hcloud",
  "provider-dns": "cloudflare", "provider-backend": "r2",
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

export async function backendFinalizeStep(opts: Opts): Promise<Opts> {
  try {
    const result = await finalize_backend(opts,{...process.env,...storage.awsEnv(opts)});
    if (!['destroyed','absent','skipped'].includes(result.status)) throw Error('finalization refused');
    return {...opts,'red/exit':0};
  } catch { return {...opts,'red/exit':1,'red/err':'managed backend finalization refused; live or unowned state remains'}; }
}

export function wireFn(step: string, runOpts: Opts): WireDecl | undefined {
  if (runOpts["red/event"] === "delete") {
    const graph: Record<string, WireDecl> = {
      "clickhouse/start": [startStep, "clickhouse/load-infrastructure"],
      "clickhouse/load-infrastructure": [tools.loadInfrastructureStep, "clickhouse/dbt"],
      "clickhouse/dbt": [tools.dbtStep, "clickhouse/acceptance"],
      "clickhouse/acceptance": [tools.acceptanceStep, "clickhouse/ansible-cleanup"],
      "clickhouse/ansible-cleanup": [tools.ansibleCleanupStep, "clickhouse/ansible-local"],
      "clickhouse/ansible-local": [tools.ansibleLocalStep, "clickhouse/dns"],
      "clickhouse/dns": [tools.dnsStep, storage.managed(runOpts)?"clickhouse/storage":"clickhouse/infrastructure"],
      "clickhouse/storage": [storage.storageStep, "clickhouse/infrastructure"],
      "clickhouse/infrastructure": runOpts["s3-bucket-mode"]==="managed"?[tools.infrastructureStep,"clickhouse/backend-finalize"]:[tools.infrastructureStep],
      "clickhouse/backend-finalize": [backendFinalizeStep],
    };
    return graph[step];
  }
  const graph: Record<string, WireDecl> = {
    "clickhouse/start": [startStep, "clickhouse/infrastructure"],
    "clickhouse/infrastructure": [tools.infrastructureStep, storage.managed(runOpts)?"clickhouse/storage":"clickhouse/dns"],
    "clickhouse/storage": [storage.storageStep,"clickhouse/dns"],
    "clickhouse/dns": [tools.dnsStep, "clickhouse/ansible-local"],
    "clickhouse/ansible-local": [tools.ansibleLocalStep, "clickhouse/ansible-render"],
    "clickhouse/ansible-render": [tools.ansibleRenderStep, "clickhouse/wireguard"],
    "clickhouse/wireguard": [tools.wireguardStep,
                             "clickhouse/clickhouse-config", "clickhouse/metabase-config"],
    "clickhouse/clickhouse-config": [tools.clickhouseConfigStep, "clickhouse/dbt"],
    "clickhouse/metabase-config": [tools.metabaseConfigStep, "clickhouse/dbt"],
    "clickhouse/dbt": [tools.dbtStep, "clickhouse/acceptance"],
    "clickhouse/acceptance": [tools.acceptanceStep, runOpts["clickhouse-backup-bucket"]?"clickhouse/rehearsal":"clickhouse/drift"],
    "clickhouse/rehearsal": [tools.rehearsalStep, "clickhouse/drift"],
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

export const sideEffecting = ['clickhouse/rehearsal','clickhouse/storage','clickhouse/backend-finalize','clickhouse/ansible-local',
  "clickhouse/infrastructure", "clickhouse/load-infrastructure",
  "clickhouse/dns", "clickhouse/wireguard", "clickhouse/clickhouse-config",
  "clickhouse/metabase-config", "clickhouse/ansible-cleanup", "clickhouse/dbt",
  "clickhouse/acceptance", "clickhouse/drift",
];

function create() {
  let wf = workflow({ start: "clickhouse/start", wireFn, nextFn: (_step, declared, opts) => failed(opts)||opts["clickhouse/already-destroyed"]?[]:opts["clickhouse/finalize-only"]?[["clickhouse/backend-finalize",{...opts,"clickhouse/finalize-only":false}]]:(declared??[]).map(step=>[step,opts] as [string,Opts]) });
  wf = progress.advise(wf);
  wf = dryRun.advise(wf, sideEffecting);
  for (const tool of tools.tofuTools) {
    wf = adviceAdd(wf, `clickhouse/${tool.slice("clickhouse-".length)}`, "before",
      `io.github.getcolors.clickhouse.workflow/backend-${tool}`, backendAdvice(tool));
  }
  return wf;
}

export const clickhouseWorkflow = create();
