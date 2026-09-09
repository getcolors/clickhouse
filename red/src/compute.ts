import type { Opts } from 'red/workflow';
import { collect, expand, plan_deployment } from 'colors-compute-red';
export const TOPOLOGY = [{role:'clickhouse',count:3},{role:'metabase',count:1}];
const legacyTools = ['clickhouse-network','clickhouse-access','clickhouse-node-1','clickhouse-node-2','clickhouse-node-3','clickhouse-metabase','clickhouse-firewall'];
export function requirements(opts: Opts) {
  return {security:{ingress:[
    {id:'ssh',protocol:'tcp',from_port:22,to_port:22,sources:['0.0.0.0/0']},
    {id:'wireguard',protocol:'udp',from_port:opts['wireguard-port'],to_port:opts['wireguard-port'],sources:['0.0.0.0/0']},
    {id:'ping',protocol:'icmp',from_port:null,to_port:null,sources:['0.0.0.0/0']}],egress:'all',private_filter:false},private:true,
    legacy_state_keys:legacyTools.map(tool=>opts.profile+'/'+tool+'.tfstate')};
}
export function resolved(opts: Opts): any[] {
  const cluster=opts['colors-compute/cluster'];
  if(cluster) return collect(expand(TOPOLOGY).map(node=>({...node,private:true,provider:opts['provider-compute']})),cluster.nodes,'clickhouse-0').nodes;
  if(opts['red/event']==='build'||opts['red/dry-run']) return plan_deployment(opts,TOPOLOGY,requirements(opts)).cluster.nodes;
  throw Error('compute inventory unavailable');
}
