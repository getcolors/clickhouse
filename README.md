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

## Shared compute library

The package uses colors-compute for its three ClickHouse nodes and one Metabase
node. The library owns provider validation, R2 or S3 state, shared networking,
SSH keys, node fan-out, and the complete inventory used by Ansible. Select
`provider-compute` and supply the selected provider's settings. Compatible
provider additions require a library dependency update only.

Generated SSH keys live at `~/.ssh/<profile>`. External SSH access uses the provider's public-key or registration settings.
`ssh-private-key-path` is optional; when omitted, SSH uses the operator's
configuration or agent.
The package keeps the WireGuard addresses and DNS records stable. Ansible uses
the actual node login user and private network address returned by the library.

Existing deployments with the former package-owned compute state require an
explicit state migration. The lifecycle refuses that state before creating
resources. Updating a launcher does not transfer state ownership.
