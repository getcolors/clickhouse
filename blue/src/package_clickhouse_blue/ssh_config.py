"""Application facts derived from the shared compute library."""

from __future__ import annotations

import os
import re
from pathlib import Path

from colors_compute import expand

from .compute import TOPOLOGY


def host_alias(opts: dict) -> str:
    """The profile, unchanged. Standard §2: the profile already keys remote
    state, which is what makes it unique enough to name a host by."""
    return opts.get("profile") or "clickhouse"


def identity_file(opts: dict) -> str:
    """`~/.ssh/<profile>`, written with a literal tilde rather than an expanded
    home directory. OpenSSH expands it, and leaving it unexpanded is what keeps
    the rendered block identical on every workstation."""
    return f"~/.ssh/{host_alias(opts)}"


def aliases(opts: dict) -> list[str]:
    """Application facts derived from the shared compute library."""
    return [host_alias(opts), *[host_alias(opts) + '-' + node['node_id'] for node in expand(TOPOLOGY)]]


def config_path() -> Path:
    home = os.environ.get("HOME")
    return (Path(home) if home else Path.home()) / ".ssh" / "config"


# The alias alone. A profile is `<package>-<suffix>`, so it already names the
# package, and two packages sharing one profile would be fighting over
# `~/.ssh/<profile>` long before they reached this file.
def begin_marker(alias: str) -> str:
    return f"# BEGIN {alias} ANSIBLE MANAGED BLOCK"


def end_marker(alias: str) -> str:
    return f"# END {alias} ANSIBLE MANAGED BLOCK"


def owned_markers(alias: str) -> dict:
    """Every begin/end pair this package recognises as its own.

    A set rather than a pair because a marker change is a migration: while one
    is in flight this holds the superseded marker too, so the ownership check
    below does not read the package's own block as a hand-written stanza and
    refuse the migration meant to clean it up. Nothing is in flight now."""
    return {"begin": {begin_marker(alias)}, "end": {end_marker(alias)}}


def host_patterns(line: str) -> list[str] | None:
    """The patterns a `Host` line declares, or None when the line is not one."""
    match = re.fullmatch(r"(?i)\s*Host\s+(.*?)\s*", str(line))
    if not match:
        return None
    return [p for p in re.split(r"\s+", match.group(1)) if p.strip()]


def foreign_stanza_line(lines: list, alias: str, marker_alias: str | None = None) -> int | None:
    """The 1-based line number of a `Host <alias>` stanza that this package did
    not write, or None. Lines between our own markers are ours and are skipped.

    `alias` is the stanza being searched for; `marker_alias` names the managed
    block, and the two are not the same thing. This deployment writes ONE block,
    marked with the profile, containing a `Host` stanza for the profile and for
    every node. Deriving the marker from the stanza being searched — the obvious
    reading, and the one a single-node package can get away with — makes the
    check look for `# BEGIN clickhouse-digitalocean-0 …`, never find it, conclude it is
    outside a managed block, and refuse to converge because of a block this
    package wrote itself."""
    markers = owned_markers(alias if marker_alias is None else marker_alias)
    inside = False
    for n, line in enumerate(lines, start=1):
        trimmed = str(line).strip()
        if trimmed in markers["begin"]:
            inside = True
        elif trimmed in markers["end"]:
            inside = False
        elif not inside and alias in (host_patterns(line) or []):
            return n
    return None


def leading_option_line(lines: list) -> int | None:
    """The 1-based line number of an option standing above the first `Host` or
    `Match` line, or None.

    Such an option is global: it applies to every host the operator reaches.
    The block is written with `insertbefore: BOF`, so it would land above that
    option and capture it into this deployment's stanza, silently narrowing a
    global setting to one host. Blank lines and comments are not options."""
    for n, line in enumerate(lines, start=1):
        trimmed = str(line).strip()
        if not trimmed or trimmed.startswith("#"):
            continue
        if re.fullmatch(r"(?i)\s*(Host|Match)\s+.*", str(line)):
            return None
        return n
    return None


def adopt_error(opts: dict) -> str | None:
    """The standard's never-adopt rule (§5). A hand-written `Host <alias>`
    stanza may be the operator's only record of how to reach something, so it
    stops the run rather than being overwritten.

    Every alias is checked, not just the bare profile: this package claims
    `<profile>` and `<profile>-<i>` for each node, and adopting any one of them
    silently would lose exactly as much as adopting the first."""
    file = config_path()
    if not file.is_file():
        return None
    lines = file.read_text().splitlines()
    marker = host_alias(opts)
    for alias in aliases(opts):
        n = foreign_stanza_line(lines, alias, marker)
        if n is None:
            continue
        return (f"refusing to manage {file}: it already declares "
                f"`Host {alias}` at line {n}"
                " outside this package's managed block. Remove or rename that "
                "stanza if it is stale, or change `profile` if it belongs to "
                "something else; this package will not overwrite it.")
    return None


def placement_error(_opts: dict) -> str | None:
    """The standard's placement rule (§5), in the one shape that cannot be
    honoured without changing the meaning of the operator's file."""
    file = config_path()
    if not file.is_file():
        return None
    n = leading_option_line(file.read_text().splitlines())
    if n is None:
        return None
    return (f"refusing to manage {file}: line {n}"
            " sets an option above the first `Host` line, so it applies to "
            "every host. This package inserts its block at the top of the "
            "file, which would capture that option into one stanza. Move "
            "those global options below the managed block, or into an "
            "explicit `Host *` stanza at the end of the file, and retry.")


def preflight(opts: dict) -> dict:
    """Run the local checks. Real create only: build and dry-run must not read
    `~/.ssh/config` at all (§6)."""
    error = adopt_error(opts) or placement_error(opts)
    if error:
        return {**opts, "blue/exit": 1, "blue/err": error}
    return opts
