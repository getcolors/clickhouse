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
  (cd "$root/green" && env CLICKHOUSE_LIB_ROOT="$root" COLORS_PAR_WORKDIR="$tmp/$variant" "$@" ./green build -f "$state" >/dev/null)
  if [ "$accept" = 1 ]; then
    rm -rf "$goldens/$variant"; mkdir -p "$goldens/$variant"
    cp -r "$tmp/$variant/." "$goldens/$variant/"
  else
    diff -qr "$goldens/$variant" "$tmp/$variant"
  fi
  echo "  ok — $variant"
}

build s3 COLORS_PAR_PROVIDER_BACKEND=s3
build r2 COLORS_PAR_PROVIDER_BACKEND=r2
state="$root/test/fixtures/optout.yml"
build s3-optout COLORS_PAR_PROVIDER_BACKEND=s3
build r2-optout COLORS_PAR_PROVIDER_BACKEND=r2
state="$root/test/fixtures/aws.yml"
build aws COLORS_PAR_PROVIDER_BACKEND=s3

profile=clickhouse-fixture
base="$tmp/s3/$profile"
for tool in clickhouse-infrastructure clickhouse-dns clickhouse-ansible clickhouse-dbt clickhouse-acceptance; do
  [ -d "$base/$tool" ] || { echo "missing stage $tool" >&2; exit 1; }
done
for node in clickhouse-0 clickhouse-1 clickhouse-2 metabase-0; do
  [ -d "$base/clickhouse-infrastructure/nodes/$node" ] || exit 1
done
python3 - "$base" <<'CHECK'
import json, pathlib, sys
base=pathlib.Path(sys.argv[1])
docs=[json.loads(p.read_text()) for p in (base/'clickhouse-infrastructure/shared').glob('*.tf.json')]
for doc in docs:
 for firewall in doc.get('resource',{}).get('hcloud_firewall',{}).values():
  for rule in firewall.get('rule',[]):
   assert rule.get('port') not in ['8123','9000','3000','9181','9234']
inventory=json.loads((base/'clickhouse-ansible/inventory.json').read_text())
hosts=inventory['all']['children']['managed']['hosts']
assert len(hosts)==4
assert all(h['ansible_ssh_private_key_file']=='/home/build-placeholder/.ssh/clickhouse-fixture' for h in hosts.values())
CHECK
# Parse the enabled backup path too; unresolved scaffold tags are invalid YAML.
python3 - "$tmp/aws/clickhouse-fixture/clickhouse-ansible" <<'CHECK'
import pathlib, sys, yaml
root = pathlib.Path(sys.argv[1])
for path in root.glob('*.yml'):
    text = path.read_text()
    assert '<%' not in text and '[%' not in text, path
    yaml.safe_load(text)
assert "ON CLUSTER 'clickhouse-aws'" in (root / 'clickhouse-rehearsal.yml').read_text(), 'Hyphenated cluster must be SQL quoted'
assert (root / 'wireguard.yml').read_text().count('MTU = 1420') == 2, 'Both tunnel ends need Internet-safe MTU'
for name in ['clickhouse-backup.py', 'clickhouse-monitor.py']:
    compile((root / name).read_text(), name, 'exec')
CHECK
dns="$base/clickhouse-dns/main.tf"
grep -q 'proxied   = false' "$dns"
grep -q 'metabase.fixture.example' "$dns"
grep -q 'clickhouse.fixture.example' "$dns"
for playbook in wireguard.yml clickhouse.yml metabase.yml cleanup.yml; do
  [ -f "$base/clickhouse-ansible/$playbook" ] || { echo "missing split playbook $playbook" >&2; exit 1; }
done
grep -q 'clusterAllReplicas' "$base/clickhouse-acceptance/acceptance.py"
grep -q 'private service is publicly reachable' "$base/clickhouse-acceptance/acceptance.py"
if find "$tmp" -type f -path '*/.private/*' | grep -q .; then
  echo 'build generated a private key' >&2; exit 1
fi
for secret in COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD COLORS_PAR_CLICKHOUSE_DBT_PASSWORD COLORS_PAR_CLICKHOUSE_INTERSERVER_SECRET COLORS_PAR_METABASE_ADMIN_PASSWORD COLORS_PAR_METABASE_DB_PASSWORD COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY; do
  grep -Rq "$secret" "$base/clickhouse-ansible" || { echo "missing runtime lookup for $secret" >&2; exit 1; }
done
if grep -rEq 'client-key-data|client-certificate-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|REPLACE_ME|github_pat_|ghp_|gho_|ghu_|ghs_|ghr_' "$tmp"; then
  echo 'credential-shaped value rendered' >&2; exit 1
fi

python3 "$root/test/backup_set_test.py"
echo 'all ClickHouse goldens and safety assertions pass'
