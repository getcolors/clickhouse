# CLAUDE.md

## What this is

`clickhouse` is a green-only Package Skill provisioning three replicated
ClickHouse/Keeper nodes and one Metabase server on Hetzner, plus Cloudflare
DNS-only WireGuard names and a local dbt sample project. The first consumer is
`../clickhouse-hetzner`.

Read `plans/0001-clickhouse-v1.md` for decisions; code and tests are authoritative.

## Commands

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
```

Never run real create/delete without explicit authorization. Never edit
`.colors/`. Real deletion requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

## Reuse surface

The package consumes ONCE's provider registry and unmodified Hetzner compute
template. Each server stage adds only private-network attachment HCL. The shared
network, firewall, and DNS-only records are package-owned. Nothing upstream
promises this internal surface; golden tests assert the reused resource address.

## Safety

Credentials use `COLORS_PAR_*` and never render. `COLORS_PAR_PROFILE` is
refused. WireGuard private keys are generated and retained on their own hosts.
Public ingress is SSH and WireGuard UDP only. ClickHouse, Keeper, and Metabase
ports must remain closed publicly.

## Git

Work on the current branch. Do not push unless explicitly asked. The launcher
pin is managed only by `bb pin` after a clean pushed commit; never invent a SHA.
