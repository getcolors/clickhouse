---
name: package-clickhouse-green
description: Provision and operate a private replicated ClickHouse cluster, Metabase, WireGuard, Cloudflare DNS, and local dbt on Hetzner with Green.
license: MIT
---

# ClickHouse data stack

Read [references/configuration.md](references/configuration.md) before changing
desired state or running a real lifecycle operation.

The package creates three ClickHouse/Keeper nodes and one Metabase/Postgres
server. Cloudflare names resolve to WireGuard addresses. ClickHouse and Metabase
service ports are never public. Local dbt seeds and tests a replicated sample
model and then registers ClickHouse in Metabase.

## Safety

- Keep secrets only in gitignored `.envrc.private` as `COLORS_PAR_*` exports.
- Never set `COLORS_PAR_PROFILE`.
- Never edit or commit `.colors/`.
- Keep `compute-prevent-destroy: true`; override it for one authorized delete.
- Run `build` and `create --dry-run` before a real lifecycle operation.
- WireGuard private keys are generated automatically and must not be copied into
  desired state or generated output.

## Commands

```sh
./green build
./green create --dry-run
./green create
./green delete
```

After create, browse `http://metabase.<domain>:3000` and run dbt from
`.colors/<profile>/clickhouse-dbt/` through `uv run dbt ...`. Both require the
managed WireGuard interface.
