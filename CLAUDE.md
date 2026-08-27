# CLAUDE.md

## What this is

`clickhouse` is a tri-colour Package Skill (green, red, blue) provisioning
three replicated ClickHouse/Keeper nodes and one Metabase server on Hetzner,
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
`test/fixtures/colors.yml` is rendered under the **local** state backend and
again under **r2**, produced by overlaying `COLORS_PAR_PROVIDER_BACKEND=r2` on
the same file. The committed trees live at
`test/resources/golden/{local,r2}/clickhouse-fixture/` and differ only in every
stage's `backend.tf.json`. `scripts/golden.sh` checks green against both;
`scripts/parity.sh` renders both variants through every colour and diffs the
trees — and the colour template trees (`red/resources`, blue's embedded
`resources/`) — byte for byte.

## Reuse surface

The package consumes ONCE's provider registry and unmodified Hetzner compute
template — in every colour: green by classpath keyword, red by resolving
`package-once-red` and reading `red/resources/tools/tofu/hcloud/main.tf`, blue
through `importlib.resources` on `package_once_blue`. Each server stage adds
only private-network attachment HCL. The shared network, firewall, and DNS-only
records are package-owned. Nothing upstream promises this internal surface;
golden tests assert the reused resource address.

## Coupling

The package pins Green and ONCE in `green/deps.edn`, the Red SDK and
`package-once-red` in `red/package.json`, and the Blue SDK and
`package-once-blue` in `blue/pyproject.toml`. All three colours pin ONCE at the
**same rev** (`98d3cfa`) — ONCE's own parity is what guarantees its colours
agree per commit. This package deliberately stays on that older ONCE pin: a
bump would adopt the SSH-keypair default and churn every golden, and is its own
change. `blue/pyproject.toml` carries a `[tool.uv] override-dependencies`
block because `package-once-blue@98d3cfa` pins an older Blue rev
(`369c5aa`); the override makes this package's Blue pin win.

Use `CLICKHOUSE_LIB_ROOT` (the repository root, for every colour; red also
accepts the `red/` dir directly), `GREEN_LIB_ROOT`, and `ONCE_LIB_ROOT` for
working-tree development. Final launchers use a pushed SHA managed by `bb pin`,
which stamps all three payloads from their unpinned birth forms; deployment
launchers are copies, not symlinks.

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
pins are managed only by `bb pin` (in `green/`) after a clean pushed commit;
never invent a SHA.
