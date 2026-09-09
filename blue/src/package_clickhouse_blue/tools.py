"""OpenTofu and Ansible stages for the fixed v1 topology, the port of
io.github.getcolors.clickhouse.tools."""

from __future__ import annotations

import asyncio
import json
import math
import os
from decimal import Decimal
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_step, ansible_with_spec
from blue.cli import stage_dir
from blue.providers import tool_env
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from blue.workflow import StepError, failed

from . import utils, validate, compute, ssh_config
from colors_compute.ssh import _mode as key_mode
from colors_compute.planning import plan_deployment
from colors_compute.orchestration import orchestrate
from colors_compute.inspection import read_deployment
from colors_compute.drift import check_deployment_drift

infrastructure_tool = 'clickhouse-infrastructure'
dns_tool = 'clickhouse-dns'
ansible_tool = 'clickhouse-ansible'
dbt_tool = 'clickhouse-dbt'
acceptance_tool = 'clickhouse-acceptance'
tofu_tools = [dns_tool]

ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="clickhouse")


def template(path: str, file: str) -> dict:
    name = f"tools/{path.replace('.', '/')}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}



def spec(source: dict, target: str, data: dict) -> dict:
    return {"template": source, "target": target, "data": data, "opts": template_opts}


def raw_spec(target: str, content: str) -> dict:
    return content_spec(target, content)


def credential_env(opts: dict, *slots: str) -> dict[str, str] | None:
    return tool_env(validate.providers, opts, [*slots, "provider-backend"])


async def tofu_step(opts: dict, tool: str, specs: list[dict], slots: list[str]) -> dict:
    env = {**(credential_env(opts, *slots) or {}),
           **(opts.get("clickhouse/process-env") or {})}
    return await tofu.tofu_with_spec(opts, specs, dir=tool_dir(opts, tool),
                                     env=env or None)


async def infrastructure_step(opts):
    try:
        planning = opts.get('blue/event') == 'build' or opts.get('blue/dry-run')
        result = plan_deployment(opts, compute.TOPOLOGY, compute.requirements(opts)) if planning else await orchestrate(opts, compute.TOPOLOGY, compute.requirements(opts))
        if planning:
            for stage, documents in [('shared', result['documents']['shared']), *[('nodes/' + node, documents) for node, documents in result['documents']['nodes'].items()]]:
                for name, document in documents.items():
                    target = Path(tool_dir(opts, infrastructure_tool)) / stage / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_text(json.dumps(document, indent=2, sort_keys=True) + '\n')
        if result['status'] not in ('ready', 'planned', 'destroyed'):
            return {**opts, 'blue/exit': 1, 'blue/err': '\n'.join(result.get('errors', [])) or 'compute lifecycle refused; inspect deployment state before retrying'}
        output = {**opts, 'blue/exit': 0}
        if result.get('cluster'):
            output.update({'colors-compute/cluster': result['cluster'], 'colors-compute/shared': result['shared']})
        path = result.get('key', {}).get('private_key_path')
        if path:
            output['ssh-private-key-path'] = path.replace('$HOME/.ssh', '/home/build-placeholder/.ssh') if planning else path
        return output
    except Exception:
        return {**opts, 'blue/exit': 1, 'blue/err': 'compute lifecycle refused; inspect deployment state before retrying'}


async def load_infrastructure_step(opts):
    if opts.get('blue/event') == 'build' or opts.get('blue/dry-run'):
        return await infrastructure_step(opts)
    result = await read_deployment(opts, None, None, compute.requirements(opts))
    if result['status'] == 'destroyed' and opts.get('blue/event') == 'delete':
        return {**opts, 'clickhouse/already-destroyed': True, 'blue/exit': 0}
    if result['status'] != 'present':
        return {**opts, 'blue/exit': 1, 'blue/err': 'compute state unavailable; legacy monolithic state requires explicit migration'}
    output = {**opts, 'blue/exit': 0, 'colors-compute/cluster': result['cluster'], 'colors-compute/shared': result['shared'], 'clickhouse/infrastructure-present?': True}
    path = result.get('key', {}).get('private_key_path')
    if path:
        output['ssh-private-key-path'] = path
    return output


def dns_data(opts: dict) -> dict:
    return {**opts,
            "metabase-host": utils.fqdn(opts, "metabase"),
            "clickhouse-host": utils.fqdn(opts, "clickhouse")}


async def dns_step(opts: dict) -> dict:
    dir = tool_dir(opts, dns_tool)
    return await tofu_step(opts, dns_tool,
                           [spec(template("tofu.dns", "main.tf"), f"{dir}/main.tf",
                                 dns_data(opts))],
                           ["provider-dns"])


def all_servers(opts):
    nodes = {node['node_id']: node for node in compute.resolved(opts)}
    return {app['id']: {**app, **nodes[app['node-id']], 'private-ip': nodes[app['node-id']]['vpc_ip']} for app in utils.servers}


def _java_double(x: float) -> str:
    """Java's Double.toString, which is what Green's cheshire JSON emits for
    floats: decimal between 1e-3 and 1e7, `d.dddE±e` scientific outside it.
    Python's own repr disagrees exactly where scientific notation starts
    (0.0001 -> "1.0E-4"), and the goldens carry the Java form."""
    if math.isnan(x):
        return "NaN"
    if math.isinf(x):
        return "Infinity" if x > 0 else "-Infinity"
    negative = math.copysign(1.0, x) < 0
    magnitude = abs(x)
    if magnitude == 0.0:
        return "-0.0" if negative else "0.0"
    _sign, digits, exponent = Decimal(repr(magnitude)).as_tuple()
    digit_str = "".join(map(str, digits)).rstrip("0") or "0"
    dec_exp = exponent + len(digits) - 1
    if -3 <= dec_exp < 7:
        if dec_exp >= 0:
            whole = digit_str[:dec_exp + 1].ljust(dec_exp + 1, "0")
            frac = digit_str[dec_exp + 1:] or "0"
        else:
            whole = "0"
            frac = "0" * (-dec_exp - 1) + digit_str
        rendered = f"{whole}.{frac}"
    else:
        mantissa = digit_str[0] + "." + (digit_str[1:] or "0")
        rendered = f"{mantissa}E{dec_exp}"
    return ("-" if negative else "") + rendered


def _pretty(value, indent=0):
    """Cheshire's pretty JSON, byte for byte — Green's artifact contract."""
    if isinstance(value, list):
        if not value:
            return "[ ]"
        return "[ " + ", ".join(_pretty(item, indent) for item in value) + " ]"
    if isinstance(value, dict):
        if not value:
            return "{ }"
        pad = " " * (indent + 2)
        body = ",\n".join(f"{pad}{json.dumps(str(k))} : {_pretty(v, indent + 2)}"
                          for k, v in value.items())
        return "{\n" + body + "\n" + " " * indent + "}"
    if isinstance(value, float) and not isinstance(value, bool):
        return _java_double(value)
    return json.dumps(value)


def inventory(opts: dict) -> str:
    servers = all_servers(opts)
    inventory_key = opts.get("ssh-private-key-path")
    hosts = {utils.host_alias(opts, id): {
        "ansible_host": s.get("ip"), "ansible_user": s.get("user"),
        "private_ip": s.get("private-ip"), "vpn_ip": s.get("vpn-ip"),
        "server_role": s.get("role"), "server_ordinal": s.get("ordinal"),
        **({"ansible_ssh_private_key_file": inventory_key} if inventory_key else {}),
    } for id, s in servers.items()}

    def select_keys(keys: list[str]) -> dict:
        return {key: hosts[key] for key in keys if key in hosts}

    return _pretty(
        {"all": {"children": {
            "managed": {"hosts": hosts},
            "clickhouse": {"hosts": select_keys(
                [utils.host_alias(opts, s["id"]) for s in utils.clickhouse_servers()])},
            "metabase": {"hosts": select_keys([utils.host_alias(opts, "metabase")])},
            "local": {"hosts": {"localhost": {"ansible_connection": "local"}}}}}})


def ansible_data(opts: dict) -> dict:
    address = opts.get("wireguard-client-address")
    return {**opts,
            "metabase-host": utils.fqdn(opts, "metabase"),
            "clickhouse-host": utils.fqdn(opts, "clickhouse"),
            "local-wg-address": ("" if address is None else str(address)).split("/")[0]}


def ansible_specs(opts: dict) -> list[dict]:
    dir = tool_dir(opts, ansible_tool)
    data = ansible_data(opts)
    return [spec(template("ansible", "ansible.cfg"), f"{dir}/ansible.cfg", data),
            spec(template("ansible", "main.yml"), f"{dir}/main.yml", data),
            spec(template("ansible", "wireguard.yml"), f"{dir}/wireguard.yml", data),
            spec(template("ansible", "clickhouse.yml"), f"{dir}/clickhouse.yml", data),
            spec(template("ansible", "metabase.yml"), f"{dir}/metabase.yml", data),
            spec(template("ansible", "cleanup.yml"), f"{dir}/cleanup.yml", data),
            spec(template("ansible", "clickhouse-config.xml"),
                 f"{dir}/clickhouse-config.xml", data),
            spec(template("ansible", "clickhouse-users.xml"),
                 f"{dir}/clickhouse-users.xml", data),
            spec(template("ansible", "docker-compose.yml"),
                 f"{dir}/docker-compose.yml", data),
            raw_spec(f"{dir}/inventory.json", inventory(opts))]


def ansible_render_step(opts: dict) -> dict:
    return scaffold(opts, ansible_specs(opts))


async def ansible_playbook_step(opts: dict, playbook: str, recap_key: str) -> dict:
    if opts.get("blue/event") == "build":
        return {**opts, "blue/exit": 0}
    return await ansible_step(opts, dir=tool_dir(opts, ansible_tool),
                              inventory="inventory.json",
                              playbooks={"create": playbook},
                              host_key_checking=False,
                              recap_key=recap_key)


async def wireguard_step(opts: dict) -> dict:
    return await ansible_playbook_step(opts, "wireguard.yml", "clickhouse/wireguard-recap")


async def clickhouse_config_step(opts: dict) -> dict:
    return await ansible_playbook_step(opts, "clickhouse.yml", "clickhouse/clickhouse-recap")


async def metabase_config_step(opts: dict) -> dict:
    return await ansible_playbook_step(opts, "metabase.yml", "clickhouse/metabase-recap")


async def ansible_cleanup_step(opts: dict) -> dict:
    return await ansible_with_spec(opts, ansible_specs(opts),
                                   dir=tool_dir(opts, ansible_tool),
                                   inventory="inventory.json",
                                   playbooks={"delete": "cleanup.yml"},
                                   host_key_checking=False,
                                   recap_key="clickhouse/cleanup-recap")


async def dbt_step(opts: dict) -> dict:
    dir = tool_dir(opts, dbt_tool)
    data = ansible_data(opts)
    specs = [spec(template("dbt", "pyproject.toml"), f"{dir}/pyproject.toml", data),
             spec(template("dbt", "dbt_project.yml"), f"{dir}/dbt_project.yml", data),
             spec(template("dbt", "profiles.yml"), f"{dir}/profiles.yml", data),
             spec(template("dbt", "seeds/events.csv"), f"{dir}/seeds/events.csv", data),
             spec(template("dbt", "models/events_summary.sql"),
                  f"{dir}/models/events_summary.sql", data),
             spec(template("dbt", "models/schema.yml"), f"{dir}/models/schema.yml", data)]
    rendered = scaffold(opts, specs)
    if opts.get("blue/event") in ("build", "delete"):
        return rendered
    password = opts.get("clickhouse-dbt-password")
    env = {"DBT_PROFILES_DIR": dir,
           "COLORS_DBT_PASSWORD": "" if password is None else str(password)}
    for args in [["uv", "run", "dbt", "seed"],
                 ["uv", "run", "dbt", "run", "--fail-fast"],
                 ["uv", "run", "dbt", "test"]]:
        result = await runtime.exec(args, cwd=dir, env=env)
        if result.exit != 0:
            return {**rendered, "blue/exit": result.exit, "blue/err": result.err}
    return rendered


async def acceptance_step(opts: dict) -> dict:
    dir = tool_dir(opts, acceptance_tool)
    dbt_dir = tool_dir(opts, dbt_tool)
    data = ansible_data(opts)
    script = f"{dir}/acceptance.py"
    inventory_file = os.path.join(tool_dir(opts, ansible_tool), "inventory.json")
    rendered = scaffold(opts, [spec(template("acceptance", "acceptance.py"), script, data)])
    if opts.get("blue/event") in ("build", "delete"):
        return rendered

    def par(key: str) -> str:
        value = opts.get(key)
        return "" if value is None else str(value)

    env = {"COLORS_PAR_CLICKHOUSE_ADMIN_PASSWORD": par("clickhouse-admin-password"),
           "COLORS_PAR_CLICKHOUSE_METABASE_PASSWORD": par("clickhouse-metabase-password"),
           "COLORS_PAR_METABASE_ADMIN_EMAIL": par("metabase-admin-email"),
           "COLORS_PAR_METABASE_ADMIN_PASSWORD": par("metabase-admin-password")}
    result = await runtime.exec(["uv", "run", "python", script, inventory_file],
                                cwd=dbt_dir, env=env)
    if result.exit != 0:
        return {**rendered, "blue/exit": result.exit, "blue/err": result.err}
    return rendered


async def drift_step(opts: dict) -> dict:
    if opts.get("blue/event") != "create":
        return {**opts, "blue/exit": 0}
    compute_drift = await check_deployment_drift(opts, compute.TOPOLOGY, compute.requirements(opts))
    if compute_drift.get("status") != "clean":
        return {**opts, "blue/exit": 1, "blue/err": "compute drift remains or could not be checked"}
    env = credential_env(opts, "provider-dns")

    async def plan(tool: str):
        return (tool, await runtime.exec(
            ["tofu", f"-chdir={tool_dir(opts, tool)}",
             "plan", "-detailed-exitcode", "-input=false", "-no-color"],
            env=env))

    results = await asyncio.gather(*(plan(tool) for tool in tofu_tools))
    bad = next(((tool, result) for tool, result in results if result.exit != 0), None)
    if bad:
        tool, result = bad
        return {**opts, "blue/exit": result.exit,
                "blue/err": f"OpenTofu drift remains in {tool}\n{result.out}{result.err}"}
    return {**opts, "blue/exit": 0}


ansible_local_tool = 'clickhouse-ansible-local'

def ssh_config_hosts(opts):
    nodes = compute.resolved(opts)
    entry = next(node for node in nodes if node['node_id'] == 'clickhouse-0')
    return [{**entry,'name':opts['profile']}, *[{**node,'name':opts['profile']+'-'+node['node_id']} for node in nodes]]

def ansible_local_specs(opts):
    data = {**opts, 'ssh-keygen':key_mode(opts)['mode']=='managed'}
    directory = tool_dir(opts,ansible_local_tool)
    return [spec(template('ansible-local',name),directory+'/'+name,data) for name in ['ansible.cfg','inventory.ini','main.yml']]

async def ansible_local_step(opts):
    if opts.get('blue/event')=='create' and not opts.get('blue/dry-run'):
        opts=ssh_config.preflight(opts)
        if failed(opts): return opts
    return await ansible_with_spec(opts,ansible_local_specs(opts),dir=tool_dir(opts,ansible_local_tool),inventory='inventory.ini',playbooks={'create':'main.yml','delete':'main.yml'},extra_vars={'host_alias':opts['profile'],'ssh_hosts':ssh_config_hosts(opts),'block_state':'absent' if opts.get('blue/event')=='delete' else 'present'})
