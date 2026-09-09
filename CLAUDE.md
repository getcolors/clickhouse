# CLAUDE.md

## What this is

`clickhouse` is a tri-colour Package Skill (green, red, blue) provisioning
three replicated ClickHouse/Keeper nodes and one Metabase server through colors-compute,
plus Cloudflare DNS-only WireGuard names and a local dbt sample project. The
first consumer is `../clickhouse-hetzner`.

Read `plans/0001-clickhouse-v1.md` and
`plans/0002-parallel-convergent-workflow.md` for decisions; code and tests are
authoritative.

## Layout and commands

The three implementations live in the tri-colour layout, matching `netbird`:
canonical Clojure in `green/` (`green/bb.edn`, `green/deps.edn`, `green/src/`,
`green/tasks/`, tests under `green/test/clj`), TypeScript/Bun in `red/`, and
Python/uv in `blue/`. Green is canonical: a behavioural change lands in all
three colours in the same commit and passes `scripts/parity.sh`. The fixture
and the goldens are shared across colours at the repository root —
`test/fixtures/` and `test/resources/golden/` — with `green/test/fixtures` and
`green/test/resources` symlinks pointing at them. Each colour dir holds a
launcher symlink to its skill payload (`green/green`, `red/red`, `blue/blue`).

```sh
cd green && bb test
cd green && bb golden
cd green && bb golden:accept   # regenerate after an intended change — read the diff first
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, two state backends, byte for byte
./scripts/launcher.sh          # from the repository root
cd green && ./green build
cd green && ./green create --dry-run
```

Never run real create/delete without explicit authorization. Never edit
`.colors/`. Real deletion requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

## The two-backend golden and parity axis

The goldens have a second axis beside the fixture: the one
`test/fixtures/colors.yml` is rendered under the **s3** state backend and
again under **r2**, produced by overlaying `COLORS_PAR_PROVIDER_BACKEND=r2` on
the same file. The committed trees live at
`test/resources/golden/{s3,r2}/clickhouse-fixture/` and differ only in every
stage's `backend.tf.json`. `scripts/golden.sh` checks green against both;
`scripts/parity.sh` renders both variants through every colour and diffs the
trees — and the colour template trees (`red/resources`, blue's embedded
`resources/`) — byte for byte.

## Reuse surface

The package declares ClickHouse and Metabase roles and application ingress in
its compute module. colors-compute owns provider selection, remote state,
shared resources, node fan-out, SSH key ownership, and result collection.
Cloudflare DNS and the application playbooks remain package-owned.

## Coupling

All three implementations depend on the same immutable colors-compute commit.
Provider additions belong in that library. A compatible provider requires only
a dependency update here. ONCE supplies the non-compute DNS credential mapping.
Keep the dependency manifests, locks, and launcher metadata consistent.

Use `CLICKHOUSE_LIB_ROOT` (the repository root, for every colour; red also
accepts the `red/` dir directly), `GREEN_LIB_ROOT`, and `ONCE_LIB_ROOT` for
working-tree development. Final launchers use a pushed SHA managed by `bb pin`,
which stamps all three payloads from their unpinned birth forms; deployment
launchers are copies, not symlinks.

## Safety

Credentials use `COLORS_PAR_*` and never render. `COLORS_PAR_PROFILE` is
refused. The library owns generated SSH keys at ~/.ssh/<profile>. External SSH access
requires ssh-private-key-path. WireGuard keys remain on the managed hosts.
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
pins are managed only by `bb pin` (in `green/`) after a clean pushed commit;
never invent a SHA.
