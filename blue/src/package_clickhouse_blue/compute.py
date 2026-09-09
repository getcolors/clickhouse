"""Application topology and connectivity supplied to the compute library."""
from colors_compute.contract import collect, expand
from colors_compute.planning import plan_deployment

TOPOLOGY = [{'role': 'clickhouse', 'count': 3}, {'role': 'metabase', 'count': 1}]
LEGACY_TOOLS = ['clickhouse-network', 'clickhouse-access', 'clickhouse-node-1', 'clickhouse-node-2', 'clickhouse-node-3', 'clickhouse-metabase', 'clickhouse-firewall']


def requirements(opts):
    return {'security': {'ingress': [
        {'id': 'ssh', 'protocol': 'tcp', 'from_port': 22, 'to_port': 22, 'sources': ['0.0.0.0/0']},
        {'id': 'wireguard', 'protocol': 'udp', 'from_port': opts.get('wireguard-port'), 'to_port': opts.get('wireguard-port'), 'sources': ['0.0.0.0/0']},
        {'id': 'ping', 'protocol': 'icmp', 'from_port': None, 'to_port': None, 'sources': ['0.0.0.0/0']}],
        'egress': 'all', 'private_filter': False}, 'private': True,
        'legacy_state_keys': [opts['profile'] + '/' + tool + '.tfstate' for tool in LEGACY_TOOLS]}


def resolved(opts):
    cluster = opts.get('colors-compute/cluster')
    if cluster is not None:
        declarations = [{**node, 'private': True, 'provider': opts.get('provider-compute')} for node in expand(TOPOLOGY)]
        return collect(declarations, cluster['nodes'], 'clickhouse-0')['nodes']
    if opts.get('blue/event') == 'build' or opts.get('blue/dry-run'):
        return plan_deployment(opts, TOPOLOGY, requirements(opts))['cluster']['nodes']
    raise ValueError('compute inventory unavailable')
