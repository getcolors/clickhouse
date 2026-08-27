"""Launcher contract and deterministic topology helpers, the port of
io.github.getcolors.clickhouse.utils."""

from __future__ import annotations

# Bump on any change a launcher pinned to an older commit could not survive.
CONTRACT = 1

servers = [
    {"id": "node-1", "role": "clickhouse", "ordinal": 1,
     "vpn-ip": "10.21.0.1", "private-ip": "10.20.1.11"},
    {"id": "node-2", "role": "clickhouse", "ordinal": 2,
     "vpn-ip": "10.21.0.2", "private-ip": "10.20.1.12"},
    {"id": "node-3", "role": "clickhouse", "ordinal": 3,
     "vpn-ip": "10.21.0.3", "private-ip": "10.20.1.13"},
    {"id": "metabase", "role": "metabase", "ordinal": 10,
     "vpn-ip": "10.21.0.10", "private-ip": "10.20.1.20"},
]


def server(id: str) -> dict:
    return next(s for s in servers if s["id"] == id)


def clickhouse_servers() -> list[dict]:
    return [s for s in servers if s["role"] == "clickhouse"]


def host_alias(opts: dict, id: str) -> str:
    return f"{opts.get('profile') or 'clickhouse'}-{id}"


def fqdn(opts: dict, prefix: str) -> str:
    return f"{prefix}.{opts.get('domain')}"
