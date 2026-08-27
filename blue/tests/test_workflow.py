from package_clickhouse_blue import workflow

create = {"blue/event": "create"}
delete = {"blue/event": "delete"}


def test_create_fans_out_and_joins():
    assert workflow.wire_fn("clickhouse/network", create)[1:] == ("clickhouse/access",)
    assert workflow.wire_fn("clickhouse/access", create)[1:] == \
        ("clickhouse/node-1", "clickhouse/node-2",
         "clickhouse/node-3", "clickhouse/metabase")
    for step in ["clickhouse/node-1", "clickhouse/node-2",
                 "clickhouse/node-3", "clickhouse/metabase"]:
        assert workflow.wire_fn(step, create)[1:] == ("clickhouse/firewall",)
    assert workflow.wire_fn("clickhouse/wireguard", create)[1:] == \
        ("clickhouse/clickhouse-config", "clickhouse/metabase-config")
    assert workflow.wire_fn("clickhouse/clickhouse-config", create)[1:] == ("clickhouse/dbt",)
    assert workflow.wire_fn("clickhouse/metabase-config", create)[1:] == ("clickhouse/dbt",)
    assert workflow.wire_fn("clickhouse/acceptance", create)[1] == "clickhouse/drift"


def test_delete_cleans_and_destroys_in_parallel():
    assert workflow.wire_fn("clickhouse/start", delete)[1] == "clickhouse/dbt"
    assert workflow.wire_fn("clickhouse/acceptance", delete)[1] == "clickhouse/ansible-cleanup"
    assert workflow.wire_fn("clickhouse/ansible-cleanup", delete)[1:] == \
        ("clickhouse/dns", "clickhouse/firewall")
    assert workflow.wire_fn("clickhouse/infrastructure-clean", delete)[1:] == \
        ("clickhouse/node-1", "clickhouse/node-2",
         "clickhouse/node-3", "clickhouse/metabase")
    for step in ["clickhouse/node-1", "clickhouse/node-2",
                 "clickhouse/node-3", "clickhouse/metabase"]:
        assert workflow.wire_fn(step, delete)[1:] == ("clickhouse/access",)
    assert workflow.wire_fn("clickhouse/access", delete)[1:] == ("clickhouse/network",)
