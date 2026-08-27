// Launcher contract and deterministic topology helpers, the port of
// io.github.getcolors.clickhouse.utils.

import type { Opts } from "red/workflow";

// Bump on any change a launcher pinned to an older commit could not survive.
export const contract = 1;

export interface Server {
  id: string;
  role: string;
  ordinal: number;
  "vpn-ip": string;
  "private-ip": string;
}

export const servers: Server[] = [
  { id: "node-1", role: "clickhouse", ordinal: 1, "vpn-ip": "10.21.0.1", "private-ip": "10.20.1.11" },
  { id: "node-2", role: "clickhouse", ordinal: 2, "vpn-ip": "10.21.0.2", "private-ip": "10.20.1.12" },
  { id: "node-3", role: "clickhouse", ordinal: 3, "vpn-ip": "10.21.0.3", "private-ip": "10.20.1.13" },
  { id: "metabase", role: "metabase", ordinal: 10, "vpn-ip": "10.21.0.10", "private-ip": "10.20.1.20" },
];

export function server(id: string): Server {
  return servers.find((s) => s.id === id)!;
}

export function clickhouseServers(): Server[] {
  return servers.filter((s) => s.role === "clickhouse");
}

export function hostAlias(opts: Opts, id: string): string {
  return `${opts.profile ?? "clickhouse"}-${id}`;
}

export function fqdn(opts: Opts, prefix: string): string {
  return `${prefix}.${opts.domain}`;
}
