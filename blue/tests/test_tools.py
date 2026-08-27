import re

from package_clickhouse_blue import tools, utils


def test_topology_addresses_are_stable():
    assert [s["vpn-ip"] for s in utils.servers] == \
        ["10.21.0.1", "10.21.0.2", "10.21.0.3", "10.21.0.10"]


def test_each_server_reuses_once_hcloud():
    template = tools.once_template("hcloud")
    assert template["name"] == "once/tools/tofu/hcloud/main.tf"
    assert 'resource "hcloud_server" "node1"' in template["content"]


def test_server_branches_join_without_losing_results():
    joined = tools.join_server_branches(
        {"blue/branches": [
            {"clickhouse/servers": {"node-1": {"ip": "192.0.2.1"}}},
            {"clickhouse/servers": {"node-2": {"ip": "192.0.2.2"}}},
            {"clickhouse/servers": {"metabase": {"ip": "192.0.2.10"}}}]})
    assert set(joined["clickhouse/servers"]) == {"node-1", "node-2", "metabase"}


def test_inventory_has_four_remote_hosts():
    text = tools.inventory({"profile": "p"})
    assert re.search(r"p-node-1", text)
    assert re.search(r"p-metabase", text)
    assert re.search(r"10.20.1.13", text)
