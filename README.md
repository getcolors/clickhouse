# ClickHouse Package Skill

A tri-colour Package Skill (green, red, blue) for a private replicated data stack on AWS or Hetzner:

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

## AWS and managed S3 storage

The AWS fixture in `test/fixtures/aws.yml` shows the complete non-secret
configuration. `provider-compute: aws` uses colors-compute for VPC, subnet,
security groups, four EC2 instances, and the generated SSH key pair. The
ClickHouse security group admits HTTP/native queries from Metabase and native,
interserver, Keeper, and Raft traffic from ClickHouse peers. Public access is
limited to SSH, WireGuard, and ICMP.

Set `provider-backend: s3`, `s3-bucket-mode: managed`, `s3-bucket`, and
`s3-region` to create the state bucket during deployment. Set
`clickhouse-storage-managed: true`, `clickhouse-backup-bucket`,
`clickhouse-backup-region`, and optionally `clickhouse-backup-prefix` to create a
separate private encrypted backup bucket with a bucket-scoped IAM identity.
Credentials enter Ansible through its process environment and remain out of
rendered artifacts. Delete stops backup scheduling, destroys DNS and backup
storage, retires compute and SSH resources, then deletes the owned state bucket.
The managed backup bucket is emptied on deletion; destruction protection must
be explicitly disabled with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`.

`cloudflare-zone` selects the parent zone independently of `domain`. For example,
zone `bigconfig.online` and domain `clickhouse-aws.bigconfig.online` create
private WireGuard names beneath the deployment domain without sharing another
deployment's DNS records. The current WireGuard interface name and address range
remain shared workstation defaults, so concurrent deployments require separate
workstations or explicit interface isolation.

With backups enabled, every successful `create` also runs a durability rehearsal:
a fresh replicated row, an S3 backup of `analytics` and `default`, an analytics
restore beside the live database with row equality and zero Keeper-path
collisions, and fresh writes/reads while replica 1 is stopped. Recovery runs even
if a probe fails. Sets use `s3_plain`, match S3 object count/bytes to
`system.backups`, and publish a completion marker last. Nightly backups retain
seven days; monitors run every fifteen minutes and write
`/var/lib/clickhouse/colors-health.json` on each replica. Backup freshness is
checked only on node zero. Metabase points at node zero; this is data replication,
not automatic client failover after losing node zero.

WireGuard explicitly uses MTU 1420 on the workstation and every server. On EC2,
automatic sizing inherited the jumbo ENI MTU (8920/8921 inside the tunnel): ping
and small SQL requests passed while `clickhouse_connect` initialization and dbt
hung on larger responses. Setting both tunnel ends to 1420 restored the same
client probe immediately. This follows the [WireGuard auto-sizing code](https://git.zx2c4.com/wireguard-tools/tree/src/wg-quick/linux.bash#n126)
and the [EC2 Internet gateway MTU limit](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/network_mtu.html).

Native backup counters distinguish logical files from stored entries. ClickHouse
26.3.29.7 deduplicated a live 33-file/3875-byte set into 29 payload objects;
`.backup` added 5254 bytes, making the stored set 30 objects/9107 bytes.
The verifier parses `.backup`, matches logical count/bytes to `num_files` and
`total_size`, resolves `data_file` aliases, and requires the exact stored object
names and sizes. Physical count is `num_entries + 1`; physical bytes equal both
`uncompressed_size` and `compressed_size` for this nonarchive disk. Missing,
extra, incorrectly named, or incorrectly sized objects prevent publication.
See [the exact version's accounting implementation](https://github.com/ClickHouse/ClickHouse/blob/v26.3.29.7-lts/src/Backups/BackupImpl.cpp#L464).
