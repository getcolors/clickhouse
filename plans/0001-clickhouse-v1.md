# ClickHouse v1 — replicated ClickHouse, private Metabase, and local dbt

Status: accepted for implementation and production-cloud verification.
Date: 2026-08-02.

## Outcome

`getcolors/clickhouse` is a green-only Package Skill. Its first consumer,
`clickhouse-hetzner`, creates three ClickHouse nodes and one Metabase node in
Hetzner, joins them to one private network, publishes DNS-only Cloudflare names
for their WireGuard addresses, configures a WireGuard mesh, runs Metabase with a
Postgres metadata database, and prepares a pinned local dbt project with sample
data.

Only dbt runs on the operator machine. ClickHouse and Metabase run in Hetzner.
Neither ClickHouse client ports nor Metabase's UI are admitted on the public
network. Public ingress is SSH and WireGuard UDP only.

## Reuse boundary

The package directly reuses:

1. Green's workflow, advice, scaffold, OpenTofu, Ansible, dry-run, and progress
   implementations.
2. ONCE's provider registry as data.
3. ONCE's unmodified Hetzner compute template once for each of four servers.
4. Green's local, S3, and R2 backend advice.

Each server gets a package-specific state stage and adds a small adjacent HCL
file that attaches ONCE's `hcloud_server.node1` to the shared network and
firewall. The package owns only infrastructure ONCE cannot express: the shared
network, subnet, firewall, attachments, and DNS-only records.

K3s supplies the repository shape, launcher contract, pin task, SSH-local
pattern, and golden regression approach. It is not a runtime dependency.

## Topology and state

```text
local dbt / browser (10.21.0.254)
             | WireGuard
             v
  ch1 .1   ch2 .2   ch3 .3   metabase .10
     \        |       /          |
      Hetzner private network 10.20.1.0/24
```

ClickHouse is one shard with three replicas. Every ClickHouse node also runs one
Keeper member, producing a three-member quorum. Inter-node ClickHouse and Keeper
addresses use the Hetzner private network. Metabase and the operator use
WireGuard names and addresses.

OpenTofu stages and remote state keys are:

- `clickhouse-network`
- `clickhouse-node-1`, `clickhouse-node-2`, `clickhouse-node-3`
- `clickhouse-metabase`
- `clickhouse-dns`

All keys are `<profile>/<stage>.tfstate`. Create builds the network first,
creates four servers in parallel, then DNS, WireGuard/services, and local dbt.
Delete reverses that dependency order. `compute-prevent-destroy` protects every
server and the shared network.

## Names and access

One `domain` key derives:

- `metabase.<domain>` -> `10.21.0.10`
- `clickhouse.<domain>` -> `10.21.0.1`
- `node-{1,2,3}.clickhouse.<domain>` -> `10.21.0.{1,2,3}`

Cloudflare records are DNS-only. Cloudflare cannot proxy RFC1918 WireGuard
addresses. Resolution is public, but service access requires the active VPN.
WireGuard already encrypts browser and database traffic, so v1 does not add a
second TLS termination layer.

WireGuard private keys are generated on and retained by their respective hosts.
They never enter `colors.yml`, OpenTofu state, or `.colors`. The rendered
Ansible playbook contains only runtime operations and public-key exchange.

## Services and credentials

ClickHouse packages, Metabase, Postgres, dbt Core, and dbt-clickhouse are exact
pins in desired state. Secrets arrive only through `COLORS_PAR_*`. Ansible reads
server-side secrets from controller environment lookups under `no_log` and
writes root-owned 0600 files.

ClickHouse creates:

- an administrative account;
- a read-only Metabase account;
- a dbt account allowed to create and transform objects;
- an interserver credential.

Metabase and Postgres run in Docker Compose on the dedicated server. Metabase is
initialized through its API and its ClickHouse database is registered against
`clickhouse.<domain>:8123`. Its metadata lives in a persistent host directory.

The local dbt environment is created with uv from the two desired-state pins. A
small seed and model provide the acceptance dataset. dbt targets
`clickhouse.<domain>:8123` with the dedicated dbt credential.

## Safety and verification

`build` and `create --dry-run` need no provider credentials and perform no side
effects. `COLORS_PAR_PROFILE` is rejected. Real delete requires a one-command
`COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` override.

The package has unit tests, committed goldens for local and R2 backends, a
launcher harness, and explicit assertions for ONCE resource addresses, state
stage names, public firewall closure, DNS-only records, and absence of secrets.

Production-cloud acceptance requires:

- all OpenTofu stages apply;
- all three ClickHouse and Keeper members become healthy;
- WireGuard reaches every VPN address;
- dbt seed/run/test succeed;
- Metabase is initialized and can query the sample model;
- public ClickHouse and Metabase ports remain closed;
- a second create converges without destructive changes.

During initial verification, resources under the `clickhouse-hetzner` profile
may be destroyed and recreated when necessary. No unrelated resource may be
changed. Replication is not backup; v1 is accepted with sample data only and no
backup promise.
