# CLAUDE.md

## What this is

`clickhouse` is a green-only Package Skill provisioning three replicated
ClickHouse/Keeper nodes and one Metabase server on Hetzner, plus Cloudflare
DNS-only WireGuard names and a local dbt sample project. The first consumer is
`../clickhouse-hetzner`.

Read `plans/0001-clickhouse-v1.md` and
`plans/0002-parallel-convergent-workflow.md` for decisions; code and tests are
authoritative.

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
refused. SSH and WireGuard private keys are generated and retained in gitignored
local state or on their own hosts; `.colors/` is therefore sensitive.
Public ingress is SSH and WireGuard UDP only. ClickHouse, Keeper, and Metabase
ports must remain closed publicly.

## Documentation

`index.html` is this repository's landing page and carries two analytics tags:
GA4 measurement ID `G-4VKP1WY4QJ`, whose explicit `page_title` must exactly
equal the decoded HTML `<title>` and stay distinct and stable so one Analytics
property can separate repositories, and the self-hosted Rybbit snippet
`<script src="https://rybbit.getcolors.ai/api/script.js" data-site-id="9fb9c41a6d49" defer></script>`,
which shares one site ID across every page because `getcolors.github.io/<repo>/`
paths already encode the repository. Never add one tag without the other.

## Git

Work on the current branch. Do not push unless explicitly asked. The launcher
pin is managed only by `bb pin` after a clean pushed commit; never invent a SHA.
