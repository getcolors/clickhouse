# Plan 0002 — Parallel, convergent ClickHouse workflow

## Goal

Shorten provisioning and deletion, make application convergence observable, and
turn the production checks used during v1 acceptance into repeatable workflow
stages. Existing infrastructure may be destroyed and recreated; no state or data
migration is required.

## Create/build graph

```text
start
  -> network
  -> access
  -> (node-1, node-2, node-3, metabase)
  -> firewall
  -> dns
  -> ansible-render
  -> wireguard
  -> (clickhouse-config, metabase-config)
  -> dbt
  -> acceptance
  -> drift
```

The `access` stage generates and retains a deployment-specific SSH key locally,
registers only its public key with Hetzner, and loads the private key into a
managed agent for ONCE's unmodified SSH readiness provisioner. This removes the
implicit dependency on a developer's forwarded SSH agent without putting a
private key in OpenTofu state.

The four server states are independent after network and access exist. Green's
native fan-out/fan-in scheduler runs them concurrently and joins their
namespaced outputs before downstream inventory rendering. ClickHouse and
Metabase configuration also run concurrently after WireGuard is established.

`build` renders every artifact but performs no provider, host, local VPN, dbt,
acceptance, or drift side effect. `create --dry-run` walks the same graph while
Green's dry-run advice skips every side-effecting stage.

## Delete graph

```text
start -> dbt -> acceptance -> ansible-cleanup
      -> (dns, firewall)
      -> (node-1, node-2, node-3, metabase)
      -> access
      -> network
```

Acceptance has no delete side effect; it removes its generated artifact. Cleanup
disconnects the workstation before public firewall protection is removed. DNS
and firewall are independent and are destroyed concurrently. All four servers
then destroy concurrently, and their join gates removal of the managed Hetzner SSH
key and then network destruction. The local private key is retained for
recovery. The committed `compute-prevent-destroy` guard remains mandatory.

## Ansible boundaries

Replace the monolithic create playbook with separately runnable playbooks:

1. `wireguard.yml` — retained host keys, mesh, workstation interface and VPN
   reachability.
2. `clickhouse.yml` — package installation, Keeper/ClickHouse configuration and
   initial quorum readiness.
3. `metabase.yml` — Docker, PostgreSQL metadata store and Metabase readiness.
4. `cleanup.yml` — workstation VPN teardown before infrastructure deletion.

An `ansible-render` stage writes shared inventory, configuration, and all
playbooks once. Remote stages use bounded SSH/task retries already encoded in
the playbooks and report their own recap keys for failure locality.

## Convergence and acceptance

Keep dbt responsible only for `seed`, `run`, and `test`; move Metabase
registration to acceptance. The acceptance stage retries bounded health checks
and verifies:

- local WireGuard is active and all four VPN addresses answer;
- DNS resolves to the intended VPN addresses;
- Keeper is reachable through all three ClickHouse nodes;
- six replicated table replicas are active with empty queues;
- the dbt model has the expected sample result;
- Metabase has a ClickHouse database and can query that model;
- Metabase, ClickHouse and Keeper ports are closed on every public address.

A final drift stage runs `tofu plan -detailed-exitcode` against every state and
fails create if any change remains. This gives a successful create the stronger
meaning “services accepted and infrastructure converged,” not merely “apply
returned zero.”

## Tests and rollout

- Unit-test both create and delete fan-out/fan-in wiring and joined server data.
- Golden-test all split playbooks and acceptance artifacts for local and R2.
- Run launcher, Ansible syntax, OpenTofu validation, build, and credential-free
  dry-run checks.
- Destroy the existing `clickhouse-hetzner` stack with the one-run guard
  override, recreate it, and rerun production acceptance until clean.
- Update the website DAG only after the package and live deployment pass.
- Commit and push package, refreshed launcher pin, deployment copy, and website.

Backups remain explicitly outside this change and stay tracked in `TODO.md`.
