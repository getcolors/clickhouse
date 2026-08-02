# ClickHouse Package Skill

A green-only Package Skill for a private Hetzner data stack:

- three ClickHouse 26.3 LTS replicas with a three-member Keeper quorum;
- one Metabase server with PostgreSQL metadata storage;
- WireGuard-only database and dashboard access;
- DNS-only Cloudflare names;
- pinned local dbt with a replicated sample dataset.

See `skills/package-clickhouse-green/SKILL.md`, `plans/0001-clickhouse-v1.md`,
and `plans/0002-parallel-convergent-workflow.md`.

```sh
bb test
bb golden
./scripts/launcher.sh
./green build
./green create --dry-run
```
