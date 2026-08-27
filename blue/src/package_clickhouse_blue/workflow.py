"""Lifecycle graph and backend advice, the port of
io.github.getcolors.clickhouse.workflow."""

from __future__ import annotations

from blue import dry_run, progress, tofu
from blue.cli import par_name, read_pars
from blue.lifecycle import preflight
from blue.workflow import advice_add, workflow

from . import tools, validate

DEFAULTS = {"compute-prevent-destroy": True, "provider-compute": "hcloud",
            "provider-dns": "cloudflare", "provider-backend": "local",
            "workdir": ".colors"}

LIFECYCLE_EVENTS = ("create", "delete")


async def start_step(opts: dict, env: dict | None = None) -> dict:
    return await preflight(
        opts, defaults=DEFAULTS, overlay=read_pars, env=env,
        validators=[
            lambda _o, e, _c: validate.env_errors(e),
            lambda o, _e, _c: validate.state_errors(o),
            lambda o, _e, c: (validate.secret_errors(o)
                              if c["real"] and c["event"] in LIFECYCLE_EVENTS else []),
            lambda o, _e, c: ([f"compute destruction is protected; set "
                               f"{par_name('compute-prevent-destroy')}=false to delete"]
                              if c["real"] and c["event"] == "delete"
                              and o.get("compute-prevent-destroy") else []),
        ])


def pass_step(opts: dict) -> dict:
    return {**opts, "blue/exit": 0}


def wire_fn(step: str, run_opts: dict):
    if run_opts.get("blue/event") == "delete":
        return {
            "clickhouse/start": (start_step, "clickhouse/dbt"),
            "clickhouse/dbt": (tools.dbt_step, "clickhouse/acceptance"),
            "clickhouse/acceptance": (tools.acceptance_step, "clickhouse/ansible-cleanup"),
            "clickhouse/ansible-cleanup": (tools.ansible_cleanup_step,
                                           "clickhouse/dns", "clickhouse/firewall"),
            "clickhouse/dns": (tools.dns_step, "clickhouse/infrastructure-clean"),
            "clickhouse/firewall": (tools.firewall_step, "clickhouse/infrastructure-clean"),
            "clickhouse/infrastructure-clean": (pass_step,
                                                "clickhouse/node-1", "clickhouse/node-2",
                                                "clickhouse/node-3", "clickhouse/metabase"),
            "clickhouse/node-1": (tools.node_1_step, "clickhouse/access"),
            "clickhouse/node-2": (tools.node_2_step, "clickhouse/access"),
            "clickhouse/node-3": (tools.node_3_step, "clickhouse/access"),
            "clickhouse/metabase": (tools.metabase_step, "clickhouse/access"),
            "clickhouse/access": (tools.access_step, "clickhouse/network"),
            "clickhouse/network": (tools.network_step,),
        }.get(step)
    return {
        "clickhouse/start": (start_step, "clickhouse/network"),
        "clickhouse/network": (tools.network_step, "clickhouse/access"),
        "clickhouse/access": (tools.access_step,
                              "clickhouse/node-1", "clickhouse/node-2",
                              "clickhouse/node-3", "clickhouse/metabase"),
        "clickhouse/node-1": (tools.node_1_step, "clickhouse/firewall"),
        "clickhouse/node-2": (tools.node_2_step, "clickhouse/firewall"),
        "clickhouse/node-3": (tools.node_3_step, "clickhouse/firewall"),
        "clickhouse/metabase": (tools.metabase_step, "clickhouse/firewall"),
        "clickhouse/firewall": (tools.firewall_step, "clickhouse/dns"),
        "clickhouse/dns": (tools.dns_step, "clickhouse/ansible-render"),
        "clickhouse/ansible-render": (tools.ansible_render_step, "clickhouse/wireguard"),
        "clickhouse/wireguard": (tools.wireguard_step,
                                 "clickhouse/clickhouse-config", "clickhouse/metabase-config"),
        "clickhouse/clickhouse-config": (tools.clickhouse_config_step, "clickhouse/dbt"),
        "clickhouse/metabase-config": (tools.metabase_config_step, "clickhouse/dbt"),
        "clickhouse/dbt": (tools.dbt_step, "clickhouse/acceptance"),
        "clickhouse/acceptance": (tools.acceptance_step, "clickhouse/drift"),
        "clickhouse/drift": (tools.drift_step,),
    }.get(step)


def backend_advice(tool: str):
    return tofu.conventional_backend_advice(
        dir=lambda o, tool=tool: tools.tool_dir(o, tool),
        key=lambda o, tool=tool: f"{o.get('profile')}/{tool}.tfstate")


side_effecting = ["clickhouse/network", "clickhouse/access", "clickhouse/node-1",
                  "clickhouse/node-2", "clickhouse/node-3", "clickhouse/metabase",
                  "clickhouse/firewall", "clickhouse/dns", "clickhouse/wireguard",
                  "clickhouse/clickhouse-config", "clickhouse/metabase-config",
                  "clickhouse/ansible-cleanup", "clickhouse/dbt",
                  "clickhouse/acceptance", "clickhouse/drift"]


def create_workflow():
    wf = workflow(start="clickhouse/start", wire_fn=wire_fn)
    wf = progress.advise(wf)
    wf = dry_run.advise(wf, side_effecting)
    for tool in tools.tofu_tools:
        wf = advice_add(wf, f"clickhouse/{tool[len('clickhouse-'):]}", "before",
                        f"io.github.getcolors.clickhouse.workflow/backend-{tool}",
                        backend_advice(tool))
    return wf


clickhouse_workflow = create_workflow()
