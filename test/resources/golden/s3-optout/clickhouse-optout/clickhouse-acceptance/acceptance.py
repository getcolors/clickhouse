"""End-to-end acceptance checks for a converged ClickHouse deployment."""

import json
import os
import socket
import subprocess
import sys
import shutil
import time
import urllib.request

import clickhouse_connect

INVENTORY = sys.argv[1]
CLICKHOUSE_HOST = "clickhouse.fixture.example"
METABASE_HOST = "metabase.fixture.example"
METABASE_PORT = 3000
PUBLIC_PORTS = (3000, 8123, 9000, 9181, 9234)
DNS = {
    CLICKHOUSE_HOST: "10.21.0.1",
    "node-1.clickhouse.fixture.example": "10.21.0.1",
    "node-2.clickhouse.fixture.example": "10.21.0.2",
    "node-3.clickhouse.fixture.example": "10.21.0.3",
    METABASE_HOST: "10.21.0.10",
}


def retry(label, fn, attempts=24, delay=5):
    last = None
    for _ in range(attempts):
        try:
            return fn()
        except Exception as exc:  # the final error retains subsystem context
            last = exc
            time.sleep(delay)
    raise RuntimeError(f"{label} did not converge after {attempts} attempts: {last}") from last


def api(path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Metabase-Session"] = token
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        f"http://{METABASE_HOST}:{METABASE_PORT}{path}", data=data, headers=headers
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def check_dns():
    for host, expected in DNS.items():
        actual = socket.gethostbyname(host)
        assert actual == expected, f"{host} resolved to {actual}, expected {expected}"


def check_wireguard():
    wg = shutil.which("wg")
    assert wg, "wg is not available on PATH"
    subprocess.run(
        ["sudo", "-n", wg, "show", "wg-clickhouse"],
        check=True,
        stdout=subprocess.DEVNULL,
    )


def clickhouse_client():
    return clickhouse_connect.get_client(
        host=CLICKHOUSE_HOST,
        port=8123,
        username="admin",
        password=os.environ["COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD"],
    )


def check_clickhouse():
    client = clickhouse_client()
    replicas = client.query(
        "SELECT count(), sum(queue_size), min(active_replicas) "
        "FROM clusterAllReplicas('fixture', system.replicas)"
    ).result_rows[0]
    assert tuple(map(int, replicas)) == (6, 0, 3), f"unhealthy replicas: {replicas}"
    keepers = client.query(
        "SELECT countDistinct(hostName()) "
        "FROM clusterAllReplicas('fixture', system.zookeeper) "
        "WHERE path='/'"
    ).result_rows[0][0]
    assert int(keepers) == 3, f"Keeper visible on {keepers} nodes, expected 3"
    rows = client.query("SELECT count() FROM analytics.events_summary").result_rows
    assert rows == [(2,)], f"unexpected dbt model result: {rows}"


def metabase_session():
    return api(
        "/api/session",
        {
            "username": os.environ["COLORS_PAR_METABASE_ADMIN_EMAIL"],
            "password": os.environ["COLORS_PAR_METABASE_ADMIN_PASSWORD"],
        },
    )["id"]


def check_metabase():
    token = metabase_session()
    databases = api("/api/database", token=token)["data"]
    database = next((item for item in databases if item["name"] == "ClickHouse"), None)
    if database is None:
        database = api(
            "/api/database",
            {
                "engine": "clickhouse",
                "name": "ClickHouse",
                "details": {
                    "host": CLICKHOUSE_HOST,
                    "port": 8123,
                    "dbname": "analytics",
                    "user": "metabase",
                    "password": os.environ["COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD"],
                    "ssl": False,
                },
                "is_full_sync": True,
                "is_on_demand": False,
                "schedules": {},
            },
            token,
        )
    result = api(
        "/api/dataset",
        {
            "database": database["id"],
            "type": "native",
            "native": {
                "query": "SELECT count() FROM analytics.events_summary",
                "template-tags": {},
            },
            "parameters": [],
        },
        token,
    )
    assert result["data"]["rows"] == [[2]], f"Metabase query failed: {result}"


def check_public_ports():
    with open(INVENTORY, encoding="utf-8") as stream:
        hosts = json.load(stream)["all"]["children"]["managed"]["hosts"]
    for name, values in hosts.items():
        for port in PUBLIC_PORTS:
            with socket.socket() as sock:
                sock.settimeout(0.8)
                assert sock.connect_ex((values["ansible_host"], port)) != 0, (
                    f"private service is publicly reachable: {name}:{port}"
                )


check_wireguard()
retry("private DNS", check_dns)
retry("ClickHouse/Keeper/dbt", check_clickhouse)
retry("Metabase", check_metabase)
check_public_ports()
print("acceptance: WireGuard, DNS, replicas, Keeper, dbt, Metabase, and firewall are healthy")
