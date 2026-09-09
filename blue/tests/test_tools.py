import json
from pathlib import Path
import pytest
from blue.cli import load_yaml
from blue.workflow import run
from colors_compute.planning import plan_deployment
from package_clickhouse_blue import tools, utils, compute, workflow


def fixture(tmp_path):
    opts = load_yaml((Path(__file__).parents[2] / 'test/fixtures/colors.yml').read_text())
    opts.update({'workdir': str(tmp_path), 'provider-backend': 'r2', 'blue/event': 'build'})
    opts.pop('hcloud-ssh-keys', None)
    return opts


def test_vpn_addresses_stay_application_owned():
    assert [s['vpn-ip'] for s in utils.servers] == ['10.21.0.1', '10.21.0.2', '10.21.0.3', '10.21.0.10']
    assert [s['node-id'] for s in utils.servers] == ['clickhouse-0','clickhouse-1','clickhouse-2','metabase-0']


async def test_native_full_build_managed_and_external(tmp_path, monkeypatch):
    monkeypatch.setenv('HOME', str(tmp_path / 'empty-home'))
    for external in (False, True, "agent"):
        opts = fixture(tmp_path / str(external))
        if external:
            opts.update({'ssh-keygen': False, 'hcloud-ssh-keys': 'fixture-key', 'ssh-private-key-path': '/operator/custom'})
        if external == 'agent':
            opts.pop('ssh-private-key-path', None)
        result = await run(workflow.create_workflow(), opts)
        assert result['blue/exit'] == 0, result.get('blue/err')
        root = Path(opts['workdir']) / opts['profile']
        assert (root / 'clickhouse-infrastructure/shared/shared.tf.json').is_file()
        inv = json.loads((root / 'clickhouse-ansible/inventory.json').read_text())
        hosts = inv['all']['children']['managed']['hosts']
        assert len(hosts) == 4
        assert hosts[opts['profile'] + '-metabase']['server_ordinal'] == 10
        assert hosts[opts['profile'] + '-metabase']['vpn_ip'] == '10.21.0.10'
        if external == 'agent':
            assert all('ansible_ssh_private_key_file' not in host for host in hosts.values())
            continue
        assert hosts[opts['profile'] + '-node-1']['ansible_ssh_private_key_file'] == ('/operator/custom' if external else '/home/build-placeholder/.ssh/' + opts['profile'])
    assert not (tmp_path / 'empty-home/.ssh').exists()


def test_provider_plan_sizes_and_no_public_application_ports(tmp_path):
    opts = fixture(tmp_path)
    result = plan_deployment(opts, compute.TOPOLOGY, compute.requirements(opts))
    for node_id, size in [('clickhouse-0','cx33'),('metabase-0','cx23')]:
        server = result['documents']['nodes'][node_id]['node.tf.json']['resource']['hcloud_server']['node']
        assert server['server_type'] == size
    firewall = result['documents']['shared']['shared.tf.json']['resource']['hcloud_firewall']['network']['rule']
    assert {(r['protocol'], r.get('port')) for r in firewall} == {('tcp','22'),('udp','51820'),('icmp',None)}
    assert len(compute.requirements(opts)['legacy_state_keys']) == 7


def test_inventory_uses_observed_addresses_and_refuses_partial_cluster(tmp_path):
    opts = fixture(tmp_path)
    result = plan_deployment(opts, compute.TOPOLOGY, compute.requirements(opts))
    result['cluster']['nodes'][0]['vpc_ip'] = '10.20.1.99'
    result['cluster']['nodes'][0]['user'] = 'ubuntu'
    actual = {**opts, 'colors-compute/cluster': result['cluster'], 'ssh-private-key-path': '/key'}
    node = json.loads(tools.inventory(actual))['all']['children']['managed']['hosts'][opts['profile'] + '-node-1']
    assert node['private_ip'] == '10.20.1.99' and node['ansible_user'] == 'ubuntu'
    with pytest.raises(ValueError):
        tools.all_servers({**actual, 'colors-compute/cluster': {'nodes': result['cluster']['nodes'][:3]}})


async def test_legacy_state_refusal_prevents_delete_work(monkeypatch):
    async def absent(*args): return {'status': 'absent'}
    monkeypatch.setattr(tools, 'read_deployment', absent)
    result = await tools.load_infrastructure_step({'profile':'example','blue/event':'delete'})
    assert result['blue/exit'] == 1 and 'explicit migration' in result['blue/err']

async def test_compute_drift_failure_stops_before_dns_plan(tmp_path, monkeypatch):
    async def drift(*args):return {'status':'error'}
    monkeypatch.setattr(tools,'check_deployment_drift',drift)
    async def forbidden(*args,**kwargs):pytest.fail('DNS plan must not run after compute drift failure')
    monkeypatch.setattr(tools.runtime,'exec',forbidden)
    result=await tools.drift_step({**fixture(tmp_path),'blue/event':'create'})
    assert result['blue/exit']==1 and 'compute drift' in result['blue/err']
