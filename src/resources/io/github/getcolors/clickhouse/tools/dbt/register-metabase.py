import json
import os
import urllib.error
import urllib.request

BASE = "http://<{ metabase-host }>:<{ metabase-port }>"


def request(path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["X-Metabase-Session"] = token
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


token = request(
    "/api/session",
    {
        "username": os.environ["COLORS_PAR_METABASE_ADMIN_EMAIL"],
        "password": os.environ["COLORS_PAR_METABASE_ADMIN_PASSWORD"],
    },
)["id"]

existing = request("/api/database", token=token)["data"]
if not any(database["name"] == "ClickHouse" for database in existing):
    request(
        "/api/database",
        {
            "engine": "clickhouse",
            "name": "ClickHouse",
            "details": {
                "host": "<{ clickhouse-host }>",
                "port": <{ clickhouse-http-port }>,
                "dbname": "analytics",
                "user": "<{ clickhouse-metabase-user }>",
                "password": os.environ["COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD"],
                "ssl": False,
            },
            "is_full_sync": True,
            "is_on_demand": False,
            "schedules": {},
        },
        token,
    )
print("Metabase ClickHouse connection is configured")
