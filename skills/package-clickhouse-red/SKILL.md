---
name: package-clickhouse-red
description: Provision and operate a private replicated ClickHouse cluster, Metabase, WireGuard, Cloudflare DNS, and local dbt on Hetzner with Red.
license: MIT
---

# ClickHouse data stack

Read [references/configuration.md](references/configuration.md) before changing
desired state or running a real lifecycle operation.

The package creates three ClickHouse/Keeper nodes and one Metabase/Postgres
server. Cloudflare names resolve to WireGuard addresses. ClickHouse and Metabase
service ports are never public. Local dbt seeds and tests a replicated sample
model, registers ClickHouse in Metabase, runs end-to-end acceptance checks, and
proves every OpenTofu state has no remaining drift.

## Safety

- Keep secrets only in gitignored `.envrc.private` as `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE`.
- Never edit or commit `.colors/`.
- Keep `compute-prevent-destroy: true`; override it for one authorized delete.
- Run `build` and `create --dry-run` before a real lifecycle operation.
- Deployment SSH and WireGuard private keys are generated automatically and
  retained only in gitignored local or host state. Never copy or commit them.

## Commands

```sh
./red build
./red create --dry-run
./red create
./red delete
```

Bun runs the launcher; `create` and `delete` also need OpenTofu, Ansible, and
uv. Exit code 2 is validation or usage failure and lists every problem at once.
The launcher walks up from the working directory to find `colors.yml`.

After create, browse `http://metabase.<domain>:3000` and run dbt from
`.colors/<profile>/clickhouse-dbt/` through `uv run dbt ...`. Both require the
managed WireGuard interface.

## Metabase from a computer outside WireGuard

Use an SSH local port forward through a host that is connected to this
deployment's WireGuard network and can resolve its private DNS:

```sh
ssh -N \
  -o ExitOnForwardFailure=yes \
  -L 3000:metabase.<domain>:3000 \
  ubuntu@<WIREGUARD_JUMPHOST>
```

Keep that command running and open `http://localhost:3000` in the browser. The
jump host, rather than the computer running the browser, resolves
`metabase.<domain>` and opens the private WireGuard connection.

If local port 3000 is already occupied, choose another local port while keeping
the remote port unchanged:

```sh
ssh -N \
  -o ExitOnForwardFailure=yes \
  -L 3300:metabase.<domain>:3000 \
  ubuntu@<WIREGUARD_JUMPHOST>
```

Then open `http://localhost:3300`. Do not publish Metabase or weaken the
firewall as an alternative to this tunnel.

## Rules

- `colors.yml` is the only file to edit. `.colors/` is generated: never edit it,
  read it as source, or commit it.
- The installed launcher is a copy, not a symlink. After `npx skills update -p`,
  copy `.agents/skills/package-clickhouse-red/red` over the root `./red`.
