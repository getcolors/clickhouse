# Configuration

`colors.yml` is a flat, non-secret desired-state map. The complete first
consumer is `clickhouse-hetzner/colors.yml`.

## Topology

V1 requires one shard, three replicas, and three Keeper members. It creates
three `hcloud-server-type` nodes plus one `metabase-hcloud-server-type` node on
the shared private subnet.

`domain` derives DNS-only Cloudflare records:

- `clickhouse.<domain>` and `node-1.clickhouse.<domain>` -> `10.21.0.1`
- `node-2.clickhouse.<domain>` -> `10.21.0.2`
- `node-3.clickhouse.<domain>` -> `10.21.0.3`
- `metabase.<domain>` -> `10.21.0.10`

Cloudflare proxying is disabled because those addresses are reachable only over
WireGuard.

## Credentials

```sh
COLORS_PAR_HCLOUD_TOKEN
COLORS_PAR_CLOUDFLARE_API_TOKEN
COLORS_PAR_R2_ACCESS_KEY_ID
COLORS_PAR_R2_SECRET_ACCESS_KEY
COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD
COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD
COLORS_PAR_CLICKHOUSE_DBT_PASSWORD
COLORS_PAR_CLICKHOUSE_INTERSERVER_SECRET
COLORS_PAR_METABASE_ADMIN_EMAIL
COLORS_PAR_METABASE_ADMIN_PASSWORD
COLORS_PAR_METABASE_DB_PASSWORD
COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY
```

The Metabase encryption key must contain at least 16 characters and must remain
stable. Never export `COLORS_PAR_PROFILE`.

## State

R2 state keys are `<profile>/<stage>.tfstate` for network, four servers,
firewall, DNS, and related stages. `.colors/` contains rendered artifacts and
local dbt state; it is generated and must not be committed.
