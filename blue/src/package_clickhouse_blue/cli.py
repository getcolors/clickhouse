"""CLI entry: the same verbs as the green launcher, with the logic kept here
where the test suite reaches it — the copied payload holds none of its own."""

from __future__ import annotations

import asyncio
import sys

from blue.cli import find_up, run_cli

from .workflow import clickhouse_workflow

USAGE = ("Usage: blue <build|create|delete> "
         "[-f|--file colors.yml] [--dry-run]\n"
         "\n"
         "  build     render the work directory only — contact nothing\n"
         "  create    converge ClickHouse, Metabase, WireGuard, and local dbt\n"
         "  delete    disconnect WireGuard and destroy the managed stack")

LIFECYCLE = ("build", "create", "delete")


def _find() -> str:
    return find_up("colors.yml") or "colors.yml"


def default_args(args: list[str]) -> list[str]:
    if any(a in ("-f", "--file") or str(a).startswith("--file=") for a in args):
        return args
    return [*args, "-f", _find()]


async def run(*args):
    """REPL-friendly entry point that returns the final outcome map."""
    args = default_args(list(args))
    command = args[0] if args else None
    if command in ("help", "--help", "-h"):
        return {"blue/exit": 0, "blue/err": USAGE}
    if command in LIFECYCLE:
        return await run_cli(clickhouse_workflow, args)
    return {"blue/exit": 2, "blue/err": USAGE}


def exec(args: list[str] | None = None) -> None:
    result = asyncio.run(run(*(sys.argv[1:] if args is None else args)))
    if result.get("blue/err"):
        stream = sys.stdout if (result.get("blue/exit") or 0) == 0 else sys.stderr
        print(result["blue/err"], file=stream)
        if result.get("blue/trace"):
            print(result["blue/trace"], file=stream)
    raise SystemExit(result.get("blue/exit") or 0)
