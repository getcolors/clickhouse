import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { run } from 'red/workflow';
import { plan_deployment } from 'colors-compute-red';
import * as compute from '../src/compute.ts';
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

  test("inventory refuses unobserved live nodes", () => {
    expect(()=>tools.inventory({profile:'p','red/event':'create'})).toThrow('compute inventory unavailable');
  });
  test("planned inventory preserves role mapping and private addresses", () => {
    const opts={...base,'red/event':'build'};
    const inventory=JSON.parse(tools.inventory(opts));
    const hosts=inventory.all.children.managed.hosts;
    expect(Object.keys(hosts)).toHaveLength(4);
    expect(hosts['p-node-3'].vpn_ip).toBe('10.21.0.3');
    expect(hosts['p-metabase'].server_role).toBe('metabase');
    expect(hosts['p-node-3'].private_ip).toBeTruthy();
  });
});

// --- validate ----------------------------------------------------------------

const base: Opts = {
  profile: "p", workdir: ".colors", "provider-compute": "hcloud",
  "provider-dns": "cloudflare", "provider-backend": "s3", "s3-bucket":"fixture-state", "s3-region":"eu-west-1",
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
  "hcloud-location": "nbg1", "hcloud-ssh-keys": "key", "ssh-private-key-path":"~/.ssh/id_ed25519",
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
    expect(next("clickhouse/start", create)).toEqual(["clickhouse/infrastructure"]);
    expect(next("clickhouse/infrastructure", create)).toEqual(["clickhouse/dns"]);
    expect(next("clickhouse/wireguard", create))
      .toEqual(["clickhouse/clickhouse-config", "clickhouse/metabase-config"]);
    expect(next("clickhouse/clickhouse-config", create)).toEqual(["clickhouse/dbt"]);
    expect(next("clickhouse/metabase-config", create)).toEqual(["clickhouse/dbt"]);
    expect(next("clickhouse/acceptance", create)).toEqual(["clickhouse/drift"]);
  });

  test("delete loads inventory before application cleanup and compute destroy", () => {
    expect(next('clickhouse/start',del)).toEqual(['clickhouse/load-infrastructure']);
    expect(next('clickhouse/load-infrastructure',del)).toEqual(['clickhouse/dbt']);
    expect(next('clickhouse/ansible-cleanup',del)).toEqual(['clickhouse/ansible-local']);
    expect(next('clickhouse/ansible-local',del)).toEqual(['clickhouse/dns']);
    expect(next('clickhouse/dns',del)).toEqual(['clickhouse/infrastructure']);
    expect(next('clickhouse/infrastructure',del)).toEqual([]);
  });
});

 test('native full build uses shared library and preserves application inventory', async()=> {
  const dir=mkdtempSync(join(tmpdir(),'clickhouse-build-'));
  try {
   for(const external of [false,true,"agent"]) {
    const opts:Opts={...base,workdir:join(dir,String(external)),'red/event':'build'};
    if(!external) {delete opts['hcloud-ssh-keys'];delete opts['ssh-private-key-path'];}
    if(external==='agent') delete opts['ssh-private-key-path'];
    const result=await run(workflow.clickhouseWorkflow,opts);
    expect(result['red/exit']).toBe(0);
    const inventory=JSON.parse(readFileSync(join(opts.workdir,'p/clickhouse-ansible/inventory.json'),'utf8'));
    const host=inventory.all.children.managed.hosts['p-metabase'];
    expect(host.server_ordinal).toBe(10);
    if(external==='agent') {expect(Object.hasOwn(host,'ansible_ssh_private_key_file')).toBe(false);continue;}
    expect(host.ansible_ssh_private_key_file).toBe(external?'~/.ssh/id_ed25519':'/home/build-placeholder/.ssh/p');
   }
  } finally {rmSync(dir,{recursive:true,force:true});}
 });
 test('inventory takes observed user and refuses partial node collection',()=> {
  const planned=plan_deployment(base,compute.TOPOLOGY,compute.requirements(base));
  planned.cluster.nodes[0].user='ubuntu';planned.cluster.nodes[0].vpc_ip='10.20.1.99';
  const opts={...base,'colors-compute/cluster':planned.cluster};
  const host=JSON.parse(tools.inventory(opts)).all.children.managed.hosts['p-node-1'];
  expect(host.ansible_user).toBe('ubuntu');expect(host.private_ip).toBe('10.20.1.99');
  expect(()=>tools.allServers({...opts,'colors-compute/cluster':{nodes:planned.cluster.nodes.slice(0,3)}})).toThrow();
 });

test('backup never shares remote state or unsafe prefix',()=>{
  const opts={...base,'clickhouse-storage-managed':true,'clickhouse-backup-region':'us-east-1','clickhouse-backup-bucket':'test-state','s3-bucket':'test-state'};
  expect(validate.stateErrors(opts)).toContain(':clickhouse-backup-bucket must not be the OpenTofu state bucket');
  expect(validate.stateErrors({...opts,'clickhouse-backup-bucket':'separate-backup','clickhouse-backup-prefix':'../bad'})).toContain(':clickhouse-backup-prefix must contain safe nonempty path segments');
  expect(validate.stateErrors({...opts,'clickhouse-backup-bucket':'separate-backup'})).toEqual([]);
});

test('managed storage deletion precedes compute and backend finalization',()=>{
  const opts={'red/event':'delete','clickhouse-storage-managed':true,'s3-bucket-mode':'managed'};
  expect(workflow.wireFn('clickhouse/dns',opts)?.slice(1)).toEqual(['clickhouse/storage']);
  expect(workflow.wireFn('clickhouse/storage',opts)?.slice(1)).toEqual(['clickhouse/infrastructure']);
  expect(workflow.wireFn('clickhouse/infrastructure',opts)?.slice(1)).toEqual(['clickhouse/backend-finalize']);
});
