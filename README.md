# ClickHouse Package Skill

A tri-colour Package Skill (green, red, blue) for a private Hetzner data stack:

- three ClickHouse 26.3 LTS replicas with a three-member Keeper quorum;
- one Metabase server with PostgreSQL metadata storage;
- WireGuard-only database and dashboard access;
- DNS-only Cloudflare names;
- pinned local dbt with a replicated sample dataset.

The three implementations render byte-identical output: canonical Clojure in
`green/`, TypeScript/Bun in `red/`, and Python/uv in `blue/`, with
`scripts/parity.sh` as the cross-colour net.

See `skills/package-clickhouse-green/SKILL.md`, `plans/0001-clickhouse-v1.md`,
and `plans/0002-parallel-convergent-workflow.md`.

```sh
cd green && bb test
cd green && bb golden
cd red && bun test && bun run typecheck
cd blue && uv run pytest
./scripts/parity.sh            # three colours, two state backends, byte for byte
./scripts/launcher.sh
cd green && ./green build
cd green && ./green create --dry-run
```
