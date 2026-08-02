#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
state="$root/test/fixtures/colors.yml"
goldens="$root/test/resources/golden"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
accept=0
[ "${1:-}" = --accept ] && accept=1

build() {
  local variant=$1; shift
  (cd "$root" && env CLICKHOUSE_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$variant" "$@" ./green build -f "$state" >/dev/null)
  if [ "$accept" = 1 ]; then
    rm -rf "$goldens/$variant"; mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
  else
    diff -qr "$goldens/$variant" "$tmp/$variant"
  fi
  echo "  ok — $variant"
}

build local
build r2 COLORS_PAR_PROVIDER_BACKEND=r2

profile=clickhouse-fixture
base="$tmp/local/$profile"
for tool in clickhouse-network clickhouse-node-1 clickhouse-node-2 clickhouse-node-3 clickhouse-metabase clickhouse-firewall clickhouse-dns; do
  [ -d "$base/$tool" ] || { echo "missing stage $tool" >&2; exit 1; }
done
for tool in clickhouse-node-1 clickhouse-node-2 clickhouse-node-3 clickhouse-metabase; do
  grep -q 'resource "hcloud_server" "node1"' "$base/$tool/main.tf"
  grep -q 'hcloud_server.node1.id' "$base/$tool/attach.tf"
done
grep -q 'data.hcloud_server.node_1.id' "$base/clickhouse-firewall/main.tf"
grep -q 'data.hcloud_server.metabase.id' "$base/clickhouse-firewall/main.tf"
firewall="$base/clickhouse-network/main.tf"
if grep -Eq 'port[[:space:]]*=[[:space:]]*"(8123|9000|3000|9181|9234)"' "$firewall"; then
  echo 'public firewall exposes a private service port' >&2; exit 1
fi
dns="$base/clickhouse-dns/main.tf"
grep -q 'proxied   = false' "$dns"
grep -q 'metabase.fixture.example' "$dns"
grep -q 'clickhouse.fixture.example' "$dns"
remote="$base/clickhouse-ansible/main.yml"
for secret in COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD COLORS_PAR_CLICKHOUSE_DBT_PASSWORD COLORS_PAR_CLICKHOUSE_INTERSERVER_SECRET COLORS_PAR_METABASE_ADMIN_PASSWORD COLORS_PAR_METABASE_DB_PASSWORD COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY; do
  grep -Rq "$secret" "$base/clickhouse-ansible" || { echo "missing runtime lookup for $secret" >&2; exit 1; }
done
if grep -rEq 'REPLACE_ME|github_pat_|ghp_' "$tmp"; then
  echo 'credential-shaped value rendered' >&2; exit 1
fi

echo 'all ClickHouse goldens and safety assertions pass'
