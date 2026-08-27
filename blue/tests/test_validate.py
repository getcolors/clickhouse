from blue.cli import par_name
from package_clickhouse_blue import validate

base = {
    "profile": "p", "workdir": ".colors", "provider-compute": "hcloud",
    "provider-dns": "cloudflare", "provider-backend": "local",
    "compute-prevent-destroy": True, "domain": "example.com",
    "clickhouse-cluster-name": "p", "clickhouse-version": "26.3.17.56",
    "clickhouse-shards": 1, "clickhouse-replicas": 3, "clickhouse-keeper-nodes": 3,
    "clickhouse-http-port": 8123, "clickhouse-native-port": 9000,
    "clickhouse-metabase-user": "metabase", "clickhouse-dbt-user": "dbt",
    "metabase-image": "metabase/metabase:v0.63.2",
    "metabase-postgres-image": "postgres:16.14", "metabase-port": 3000,
    "dbt-core-version": "1.11.12", "dbt-clickhouse-version": "1.10.1",
    "dbt-project-dir": "dbt", "metabase-hcloud-server-type": "cx23",
    "hcloud-name": "p", "hcloud-image": "ubuntu-24.04", "hcloud-server-type": "cx33",
    "hcloud-location": "nbg1", "hcloud-ssh-keys": "key",
    "hcloud-network-zone": "eu-central", "hcloud-network-cidr": "10.20.0.0/16",
    "hcloud-subnet-cidr": "10.20.1.0/24", "wireguard-port": 51820,
    "wireguard-network-cidr": "10.21.0.0/24", "wireguard-client-address": "10.21.0.254/32",
}


def test_valid_state():
    assert validate.state_errors(base) == []


def test_topology_is_fixed():
    errors = validate.state_errors({**base, "clickhouse-replicas": 2})
    assert any("v1 requires" in e for e in errors)


def test_profile_overlay_is_refused():
    assert validate.env_errors({par_name("profile"): "other"})


def test_all_secrets_are_required():
    assert any("CLICKHOUSE_ADMIN_PASSWORD" in e for e in validate.secret_errors(base))


def test_metabase_encryption_key_has_a_minimum_length():
    opts = {**base, **{key: "long-enough-secret" for key in validate.own_secrets}}
    errors = validate.secret_errors({**opts, "metabase-encryption-secret-key": "short"})
    assert any("at least 16" in e for e in errors)
