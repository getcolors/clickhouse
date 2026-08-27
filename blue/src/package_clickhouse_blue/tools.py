"""OpenTofu and Ansible stages for the fixed v1 topology, the port of
io.github.getcolors.clickhouse.tools."""

from __future__ import annotations

import asyncio
import json
import math
import os
from decimal import Decimal
from importlib.resources import files
from pathlib import Path

from blue import tofu
from blue.ansible import ansible_step, ansible_with_spec
from blue.cli import stage_dir
from blue.providers import tool_env
from blue.runtime import runtime
from blue.scaffold import PRESERVE_JINJA_DELIMITERS, content_spec, scaffold
from blue.workflow import StepError, failed

from . import utils, validate

network_tool = "clickhouse-network"
access_tool = "clickhouse-access"
firewall_tool = "clickhouse-firewall"
dns_tool = "clickhouse-dns"
ansible_tool = "clickhouse-ansible"
dbt_tool = "clickhouse-dbt"
acceptance_tool = "clickhouse-acceptance"
server_tools = {"node-1": "clickhouse-node-1", "node-2": "clickhouse-node-2",
                "node-3": "clickhouse-node-3", "metabase": "clickhouse-metabase"}
tofu_tools = [network_tool, access_tool, *server_tools.values(), firewall_tool, dns_tool]

ROOT = Path(__file__).parent / "resources"
template_opts = PRESERVE_JINJA_DELIMITERS


def tool_dir(opts: dict, tool: str) -> str:
    return stage_dir(opts, tool, default_profile="clickhouse")


def template(path: str, file: str) -> dict:
    name = f"tools/{path.replace('.', '/')}/{file}"
    return {"name": name, "content": (ROOT / name).read_text()}


def once_template(provider: str) -> dict:
    """ONCE's unmodified Hetzner compute template, resolved from the installed
    package the way the airflow package resolves ONCE's compute templates."""
    name = f"tools/tofu/{provider}/main.tf"
    content = files("package_once_blue").joinpath(f"resources/{name}").read_text()
    return {"name": f"once/{name}", "content": content}


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


async def network_step(opts: dict) -> dict:
    dir = tool_dir(opts, network_tool)
    return await tofu_step(opts, network_tool,
                           [spec(template("tofu.network", "main.tf"), f"{dir}/main.tf", opts)],
                           ["provider-compute"])


placeholder_ssh_public_key = (
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4"
    " colors-build-placeholder")


def managed_ssh_data(opts: dict) -> dict:
    dir = tool_dir(opts, access_tool)
    private_file = str(Path(dir, ".private", "id_ed25519").absolute())
    public_file = Path(private_file + ".pub")
    return {**opts,
            "managed-ssh-key-name": f"{opts.get('hcloud-name')}-managed",
            "managed-ssh-private-key": private_file,
            "managed-ssh-inventory-key": "../clickhouse-access/.private/id_ed25519",
            "managed-ssh-public-key": (public_file.read_text().strip()
                                       if public_file.exists()
                                       else placeholder_ssh_public_key)}


async def ensure_ssh_agent(opts: dict) -> dict:
    private_file = str(opts.get("managed-ssh-private-key"))
    socket = f"/tmp/colors-{opts.get('profile')}-ssh-agent.sock"
    env = {"SSH_AUTH_SOCK": socket}
    listed = await runtime.exec(["ssh-add", "-l"], env=env)
    if listed.exit != 0:
        Path(socket).unlink(missing_ok=True)
        started = await runtime.exec(["ssh-agent", "-a", socket])
        if started.exit != 0:
            raise StepError("failed to start managed SSH agent", exit=started.exit)
    added = await runtime.exec(["ssh-add", private_file], env=env)
    if added.exit == 0:
        return {**opts, "clickhouse/process-env": env}
    return {**opts, "blue/exit": added.exit, "blue/err": "failed to load managed SSH key"}


async def access_step(opts: dict) -> dict:
    data = managed_ssh_data(opts)
    private_file = str(data["managed-ssh-private-key"])
    key_result = None
    if opts.get("blue/event") == "create" and not Path(private_file).exists():
        Path(private_file).parent.mkdir(parents=True, exist_ok=True)
        key_result = await runtime.exec(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "",
             "-C", f"{opts.get('profile')} managed by Colors",
             "-f", private_file])
    data = managed_ssh_data(opts)
    if opts.get("blue/event") == "create" and (key_result is None or key_result.exit == 0):
        data = await ensure_ssh_agent(data)
    if key_result is not None and key_result.exit != 0:
        return {**opts, "blue/exit": key_result.exit, "blue/err": key_result.err}
    if failed(data):
        return data
    dir = tool_dir(opts, access_tool)
    return await tofu_step(data, access_tool,
                           [spec(template("tofu.access", "main.tf"), f"{dir}/main.tf", data)],
                           ["provider-compute"])


def server_data(opts: dict, id: str) -> dict:
    server = utils.server(id)
    base = str(opts.get("hcloud-name"))
    return {**opts,
            "server-id": id, "server-role": server["role"],
            "server-ordinal": server["ordinal"],
            "vpn-ip": server["vpn-ip"], "private-ip": server["private-ip"],
            "network-name": f"{base}-network",
            "hcloud-ssh-keys": f"{base}-managed",
            "hcloud-name": f"{base}-{id}",
            "hcloud-server-type": (opts.get("metabase-hcloud-server-type")
                                   if id == "metabase"
                                   else opts.get("hcloud-server-type"))}


def server_fallback(opts: dict, id: str) -> dict:
    return {**utils.server(id),
            "ip": f"192.0.2.{10 + utils.server(id)['ordinal']}",
            "user": "root", "sudoer": "root",
            "name": f"{opts.get('profile')}-{id}"}


async def server_step(opts: dict, id: str) -> dict:
    tool = server_tools[id]
    dir = tool_dir(opts, tool)
    data = server_data(opts, id)
    result = await tofu_step(opts, tool,
                             [spec(once_template("hcloud"), f"{dir}/main.tf", data),
                              spec(template("tofu.server", "attach.tf"),
                                   f"{dir}/attach.tf", data)],
                             ["provider-compute"])
    output = result.get("tofu/outputs")
    params = {**server_fallback(opts, id),
              **((output or {}).get("params") or {}),
              **({"private-ip": output["private-ip"]}
                 if output and "private-ip" in output else {})}
    if failed(result):
        return result
    return {**result,
            "clickhouse/servers": {**(result.get("clickhouse/servers") or {}), id: params}}


async def node_1_step(opts: dict) -> dict:
    return await server_step(opts, "node-1")


async def node_2_step(opts: dict) -> dict:
    return await server_step(opts, "node-2")


async def node_3_step(opts: dict) -> dict:
    return await server_step(opts, "node-3")


async def metabase_step(opts: dict) -> dict:
    return await server_step(opts, "metabase")


def join_server_branches(opts: dict) -> dict:
    """Merge independently provisioned server outputs at Blue's fan-in boundary."""
    servers: dict = {}
    for branch in opts.get("blue/branches") or []:
        servers.update(branch.get("clickhouse/servers") or {})
    if not servers:
        return opts
    return {**opts, "clickhouse/servers": {**(opts.get("clickhouse/servers") or {}), **servers}}


async def firewall_step(original: dict) -> dict:
    opts = join_server_branches(original)
    dir = tool_dir(opts, firewall_tool)
    return await tofu_step(opts, firewall_tool,
                           [spec(template("tofu.firewall", "main.tf"), f"{dir}/main.tf", opts)],
                           ["provider-compute"])


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


def all_servers(opts: dict) -> dict:
    stored = opts.get("clickhouse/servers") or {}
    return {server["id"]: {**server_fallback(opts, server["id"]),
                           **server,
                           **(stored.get(server["id"]) or {})}
            for server in utils.servers}


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
    inventory_key = str(managed_ssh_data(opts)["managed-ssh-inventory-key"])
    hosts = {utils.host_alias(opts, id): {
        "ansible_host": s.get("ip"), "ansible_user": "root",
        "private_ip": s.get("private-ip"), "vpn_ip": s.get("vpn-ip"),
        "server_role": s.get("role"), "server_ordinal": s.get("ordinal"),
        "ansible_ssh_private_key_file": inventory_key,
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
    env = credential_env(opts, "provider-compute", "provider-dns")

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
