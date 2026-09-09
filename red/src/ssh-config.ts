
import { existsSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Opts } from "red/workflow";
import {expand} from "colors-compute-red";
import {TOPOLOGY} from "./compute.ts";

// The profile, unchanged. Standard §2: the profile already keys remote state,
// which is what makes it unique enough to name a host by.
export function hostAlias(opts: Opts): string {
  return String(opts.profile ?? "clickhouse");
}

// `~/.ssh/<profile>`, written with a literal tilde rather than an expanded home
// directory. OpenSSH expands it, and leaving it unexpanded is what keeps the
// rendered block identical on every workstation.
export function identityFile(opts: Opts): string {
  return `~/.ssh/${hostAlias(opts)}`;
}

export function aliases(opts: Opts): string[] {
  return [hostAlias(opts),...expand(TOPOLOGY).map(node=>hostAlias(opts)+'-'+node.node_id)];
}

export function configPath(): string {
  return join(process.env.HOME ?? homedir(), ".ssh", "config");
}

// The alias alone. A profile is `<package>-<suffix>`, so it already names the
// package, and two packages sharing one profile would be fighting over
// `~/.ssh/<profile>` long before they reached this file.
export function beginMarker(alias: string): string {
  return `# BEGIN ${alias} ANSIBLE MANAGED BLOCK`;
}

export function endMarker(alias: string): string {
  return `# END ${alias} ANSIBLE MANAGED BLOCK`;
}

// Every begin/end pair this package recognises as its own.
//
// A set rather than a pair because a marker change is a migration: while one is
// in flight this holds the superseded marker too, so the ownership check below
// does not read the package's own block as a hand-written stanza and refuse the
// migration meant to clean it up. Nothing is in flight now.
export function ownedMarkers(alias: string): { begin: Set<string>; end: Set<string> } {
  return { begin: new Set([beginMarker(alias)]), end: new Set([endMarker(alias)]) };
}

// The patterns a `Host` line declares, or undefined when the line is not one.
export function hostPatterns(line: string): string[] | undefined {
  const match = /^\s*Host\s+(.*?)\s*$/i.exec(line);
  if (!match) return undefined;
  return match[1]!.split(/\s+/).filter((pattern) => pattern.length > 0);
}

// The 1-based line number of a `Host <alias>` stanza that this package did not
// write, or undefined. Lines between our own markers are ours and are skipped.
//
// `alias` is the stanza being searched for; `markerAlias` names the managed
// block, and the two are not the same thing. This deployment writes ONE block,
// marked with the profile, containing a `Host` stanza for the profile and for
// every node. Deriving the marker from the stanza being searched — the obvious
// reading, and the one a single-node package can get away with — makes the
// check look for `# BEGIN clickhouse-digitalocean-0 …`, never find it, conclude it is
// outside a managed block, and refuse to converge because of a block this
// package wrote itself.
export function foreignStanzaLine(
  lines: string[], alias: string, markerAlias: string = alias,
): number | undefined {
  const { begin, end } = ownedMarkers(markerAlias);
  let inside = false;
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]!;
    if (begin.has(line.trim())) inside = true;
    else if (end.has(line.trim())) inside = false;
    else if (!inside && (hostPatterns(line) ?? []).includes(alias)) return i + 1;
  }
  return undefined;
}

// The 1-based line number of an option standing above the first `Host` or
// `Match` line, or undefined.
//
// Such an option is global: it applies to every host the operator reaches. The
// block is written with `insertbefore: BOF`, so it would land above that option
// and capture it into this deployment's stanza, silently narrowing a global
// setting to one host. Blank lines and comments are not options.
export function leadingOptionLine(lines: string[]): number | undefined {
  for (let i = 0; i < lines.length; i += 1) {
    const trimmed = String(lines[i]).trim();
    if (trimmed === "" || trimmed.startsWith("#")) continue;
    if (/^\s*(Host|Match)\s+.*/i.test(lines[i]!)) return undefined;
    return i + 1;
  }
  return undefined;
}

function configFileLines(): string[] | undefined {
  const path = configPath();
  if (!existsSync(path) || !statSync(path).isFile()) return undefined;
  return readFileSync(path, "utf8").split("\n");
}

// The standard's never-adopt rule (§5). A hand-written `Host <alias>` stanza
// may be the operator's only record of how to reach something, so it stops the
// run rather than being overwritten.
//
// Every alias is checked, not just the bare profile: this package claims
// `<profile>` and `<profile>-<i>` for each node, and adopting any one of them
// silently would lose exactly as much as adopting the first.
export function adoptError(opts: Opts): string | undefined {
  const lines = configFileLines();
  if (!lines) return undefined;
  const marker = hostAlias(opts);
  for (const alias of aliases(opts)) {
    const line = foreignStanzaLine(lines, alias, marker);
    if (line === undefined) continue;
    return `refusing to manage ${configPath()}: it already declares ` +
      `\`Host ${alias}\` at line ${line}` +
      " outside this package's managed block. Remove or rename that " +
      "stanza if it is stale, or change `profile` if it belongs to " +
      "something else; this package will not overwrite it.";
  }
  return undefined;
}

// The standard's placement rule (§5), in the one shape that cannot be honoured
// without changing the meaning of the operator's file.
export function placementError(_opts: Opts): string | undefined {
  const lines = configFileLines();
  if (!lines) return undefined;
  const line = leadingOptionLine(lines);
  if (line === undefined) return undefined;
  return `refusing to manage ${configPath()}: line ${line}` +
    " sets an option above the first `Host` line, so it applies to " +
    "every host. This package inserts its block at the top of the " +
    "file, which would capture that option into one stanza. Move " +
    "those global options below the managed block, or into an " +
    "explicit `Host *` stanza at the end of the file, and retry.";
}

export interface PreflightChecks {
  adoptError: (opts: Opts) => string | undefined;
  placementError: (opts: Opts) => string | undefined;
}

// Run the local checks. Real create only: build and dry-run must not read
// `~/.ssh/config` at all (§6). The checks are injectable so tests can cover the
// refusal without a doctored home directory.
export function preflight(
  opts: Opts,
  checks: PreflightChecks = { adoptError, placementError },
): Opts {
  const error = checks.adoptError(opts) ?? checks.placementError(opts);
  return error ? { ...opts, "red/exit": 1, "red/err": error } : opts;
}
