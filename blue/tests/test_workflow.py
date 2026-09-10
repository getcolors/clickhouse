from package_clickhouse_blue import workflow


def test_compute_gates_app_and_application_parallelism_remains():
    opts = {'blue/event':'create'}
    assert workflow.wire_fn('clickhouse/start', opts)[1:] == ('clickhouse/infrastructure',)
    assert workflow.wire_fn('clickhouse/infrastructure', opts)[1:] == ('clickhouse/dns',)
    assert workflow.wire_fn('clickhouse/wireguard', opts)[1:] == ('clickhouse/clickhouse-config','clickhouse/metabase-config')
    assert workflow.wire_fn('clickhouse/clickhouse-config', opts)[1:] == ('clickhouse/dbt',)
    assert workflow.wire_fn('clickhouse/metabase-config', opts)[1:] == ('clickhouse/dbt',)
    assert workflow.wire_fn('clickhouse/acceptance', opts)[1:] == ('clickhouse/drift',)


def test_delete_reads_state_and_cleans_application_before_compute():
    opts = {'blue/event':'delete'}
    assert workflow.wire_fn('clickhouse/start', opts)[1:] == ('clickhouse/load-infrastructure',)
    assert workflow.wire_fn('clickhouse/ansible-cleanup', opts)[1:] == ('clickhouse/ansible-local',)
    assert workflow.wire_fn('clickhouse/dns', opts)[1:] == ('clickhouse/infrastructure',)


def test_managed_storage_order_and_retired_backend_retry():
    opts = {'blue/event': 'delete', 'clickhouse-storage-managed': True, 's3-bucket-mode': 'managed'}
    assert workflow.wire_fn('clickhouse/dns', opts)[1:] == ('clickhouse/storage',)
    assert workflow.wire_fn('clickhouse/storage', opts)[1:] == ('clickhouse/infrastructure',)
    assert workflow.wire_fn('clickhouse/infrastructure', opts)[1:] == ('clickhouse/backend-finalize',)
