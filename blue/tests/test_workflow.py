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


async def test_managed_delete_defers_unavailable_inventory_to_authoritative_finalizer(monkeypatch):
    from package_clickhouse_blue import tools
    opts = {'profile': 'p', 'blue/event': 'delete', 's3-bucket-mode': 'managed'}
    for status in ('destroyed', 'absent', 'error'):
        async def inspect(*args):
            return {'status': status}
        monkeypatch.setattr(tools, 'read_deployment', inspect)
        ready = await tools.load_infrastructure_step(opts)
        assert ready['blue/exit'] == 0 and ready['clickhouse/finalize-only']
        denied = await tools.load_infrastructure_step({**opts, 's3-bucket-mode': 'external'})
        assert status == 'destroyed' or denied['blue/exit'] == 1
        denied = await tools.load_infrastructure_step({**opts, 'blue/event': 'describe'})
        assert denied['blue/exit'] == 1
    async def finalizer(*args):
        return {'status': 'error'}
    monkeypatch.setattr(workflow, 'finalize_backend', finalizer)
    assert (await workflow.backend_finalize_step(opts))['blue/exit'] == 1
    async def absent(*args):
        return {'status': 'absent'}
    monkeypatch.setattr(workflow, 'finalize_backend', absent)
    assert (await workflow.backend_finalize_step(opts))['blue/exit'] == 0
