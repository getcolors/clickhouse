"""Desired-state and credential validation over ONCE's provider registry, the
port of io.github.getcolors.clickhouse.validate.

Green renders its keys as Clojure keywords, so every message here carries the
same leading colon — the three colours must report identical errors for one
colors.yml.
"""

from __future__ import annotations

import re

from blue.cli import par_name
from package_once_blue.validate import providers

__all__ = ["providers"]

slots = ["provider-compute", "provider-dns", "provider-backend"]

own_required = [
    "profile", "workdir", "domain", "clickhouse-cluster-name", "clickhouse-version",
    "clickhouse-shards", "clickhouse-replicas", "clickhouse-keeper-nodes",
    "clickhouse-http-port", "clickhouse-native-port",
    "clickhouse-metabase-user", "clickhouse-dbt-user",
    "metabase-image", "metabase-postgres-image", "metabase-port",
    "dbt-core-version", "dbt-clickhouse-version", "dbt-project-dir",
    "metabase-hcloud-server-type",
    "hcloud-network-zone", "hcloud-network-cidr", "hcloud-subnet-cidr",
    "wireguard-port", "wireguard-network-cidr", "wireguard-client-address",
]

own_secrets = [
    "clickhouse-admin-password", "clickhouse-metabase-password",
    "clickhouse-dbt-password", "clickhouse-interserver-secret",
    "metabase-admin-email", "metabase-admin-password", "metabase-db-password",
    "metabase-encryption-secret-key",
]


def placeholder(x) -> bool:
    return x is None or (isinstance(x, str) and (not x.strip() or x.upper() == "REPLACE_ME"))


def _entry(opts: dict, slot: str) -> dict | None:
    value = opts.get(slot)
    return providers.get(slot, {}).get(value) if isinstance(value, str) else None


def tofu_env(opts: dict, slot: str) -> dict[str, str]:
    return (_entry(opts, slot) or {}).get("tofu-env", {})


def _slot_keys(opts: dict, field: str) -> list[str]:
    return [key for slot in slots for key in (_entry(opts, slot) or {}).get(field, [])]


def _missing(opts: dict, keys: list[str]) -> list[str]:
    return [key for key in keys if placeholder(opts.get(key))]


profile_par = par_name("profile")


def env_errors(env: dict) -> list[str]:
    if str(env.get(profile_par) or ""):
        return [f"{profile_par} is set. ClickHouse takes profile from colors.yml only."]
    return []


_domain_re = re.compile(r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
_version_re = re.compile(r"[0-9]+(?:\.[0-9]+){3}")


def positive_int(x) -> bool:
    return isinstance(x, int) and not isinstance(x, bool) and x > 0


def _pr_str(value) -> str:
    """pr-str, for the unsupported-provider message: green prints the offending
    value through pr-str, which quotes strings and renders nil bare."""
    if value is None:
        return "nil"
    if isinstance(value, str):
        return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
    return str(value)


def state_errors(opts: dict) -> list[str]:
    errors: list[str] = []
    for key in _missing(opts, [*own_required, *_slot_keys(opts, "required")]):
        errors.append(f":{key} is required")
    for slot in slots:
        if _entry(opts, slot) is None:
            errors.append(f"unsupported :{slot} {_pr_str(opts.get(slot))}")
    if opts.get("provider-compute") != "hcloud":
        errors.append(":provider-compute must be hcloud")
    if opts.get("provider-dns") != "cloudflare":
        errors.append(":provider-dns must be cloudflare")
    if not isinstance(opts.get("compute-prevent-destroy"), bool):
        errors.append(":compute-prevent-destroy must be true or false")
    if not (placeholder(opts.get("domain"))
            or _domain_re.fullmatch(str(opts.get("domain")))):
        errors.append(":domain must be a fully qualified Cloudflare zone")
    if not (placeholder(opts.get("clickhouse-version"))
            or _version_re.fullmatch(str(opts.get("clickhouse-version")))):
        errors.append(":clickhouse-version must be an exact four-part package version")
    for key in ["clickhouse-shards", "clickhouse-replicas", "clickhouse-keeper-nodes",
                "clickhouse-http-port", "clickhouse-native-port", "metabase-port",
                "wireguard-port"]:
        if not positive_int(opts.get(key)):
            errors.append(f":{key} must be a positive integer")
    if not (opts.get("clickhouse-shards") == 1
            and opts.get("clickhouse-replicas") == 3
            and opts.get("clickhouse-keeper-nodes") == 3):
        errors.append("v1 requires one shard, three replicas, and three Keeper nodes")
    return errors


def secret_errors(opts: dict) -> list[str]:
    errors = [f"required credential is not set: {par_name(key)}"
              for key in dict.fromkeys(
                  _missing(opts, [*own_secrets, *_slot_keys(opts, "secrets")]))]
    key = opts.get("metabase-encryption-secret-key")
    if not placeholder(key) and len(str(key)) < 16:
        errors.append("COLORS_PAR_METABASE_ENCRYPTION_SECRET_KEY"
                      " must contain at least 16 characters")
    return errors
