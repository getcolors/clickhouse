"""Deployment-owned S3 application buckets and scoped credentials."""
import re
import json
from pathlib import Path
from blue import tofu
from blue.cli import stage_dir
from blue.runtime import runtime
from blue.scaffold import scaffold, PRESERVE_JINJA_DELIMITERS

tool = "clickhouse-storage"


def managed(opts):
    return opts.get("clickhouse-storage-managed") is True


def directory(opts):
    return stage_dir(opts, tool, default_profile="clickhouse")


def aws_env(opts):
    mapping = {"aws-access-key-id": "AWS_ACCESS_KEY_ID", "aws-secret-access-key": "AWS_SECRET_ACCESS_KEY", "aws-session-token": "AWS_SESSION_TOKEN"}
    return {variable: str(opts[key]) for key, variable in mapping.items() if opts.get(key)}


def specs(opts):
    return [{"template": {"name": "tools/storage/main.tf", "content": (Path(__file__).parent / "resources/tools/storage/main.tf").read_text()}, "target": directory(opts) + "/main.tf", "data": opts, "opts": PRESERVE_JINJA_DELIMITERS}]


async def ownership_preflight(opts):
    config = {"cwd": directory(opts), "env": aws_env(opts)}
    result = await runtime.exec(["tofu", "init", "-input=false", "-no-color"], **config)
    if result.exit:
        raise RuntimeError("managed storage state operation failed")
    result = await runtime.exec(["tofu", "state", "list"], **config)
    if result.exit and not (result.exit == 1 and "No state file was found!" in (result.err or "")):
        raise RuntimeError("managed storage state operation failed")
    resources = []
    if not result.exit and result.out.strip():
        shown = await runtime.exec(["tofu", "show", "-json"], **config)
        if shown.exit:
            raise RuntimeError("managed storage state operation failed")
        resources = json.loads(shown.out).get("values", {}).get("root_module", {}).get("resources", [])
    for role, key in [("backup", "clickhouse-backup-bucket")]:
        if any(resource.get("address") == f'aws_s3_bucket.application["{role}"]' and resource.get("values", {}).get("bucket") == opts[key] for resource in resources):
            continue
        probe = await runtime.exec(["aws", "s3api", "head-bucket", "--bucket", opts[key], "--region", opts["clickhouse-backup-region"]], **config)
        if not (probe.exit > 0 and re.search(r"\(404\)|Not Found|NoSuchBucket", probe.err or "")):
            raise RuntimeError("managed storage refuses to adopt an existing or inaccessible bucket")


async def storage_step(opts):
    if not managed(opts):
        return {**opts, "blue/exit": 0}
    try:
        documents = specs(opts)
        if opts.get("blue/event") == "create":
            scaffold(opts, documents)
            await ownership_preflight(opts)
        return await tofu.tofu_with_spec(opts, documents, dir=directory(opts), env=aws_env(opts), output_key="clickhouse/storage-credentials")
    except Exception:
        return {**opts, "blue/exit": 1, "blue/err": "managed S3 storage failed; inspect bucket ownership, state access, and AWS permissions"}


def credential_env(opts):
    credentials = opts.get("clickhouse/storage-credentials", {}).get("credentials", {})
    environment = {"ANSIBLE_HOST_KEY_CHECKING": "False"}
    for role, prefix in [("backup", "CLICKHOUSE_BACKUP")]:
        values = credentials.get(role, {})
        access, secret = values.get("access_key_id"), values.get("secret_access_key")
        if not access or not secret or not access.strip() or not secret.strip():
            raise RuntimeError("managed storage credentials unavailable")
        environment[f"COLORS_PAR_{prefix}_ACCESS_KEY_ID"] = access
        environment[f"COLORS_PAR_{prefix}_SECRET_ACCESS_KEY"] = secret
    return environment


async def read_credentials(opts):
    if not managed(opts):
        return opts
    try:
        tofu.conventional_backend_advice(dir=directory, key=lambda o: f"{o['profile']}/{tool}.tfstate")(opts)
        scaffold({**opts, "blue/event": "build"}, specs(opts))
        initialized = await runtime.exec(["tofu", "init", "-input=false", "-no-color"], cwd=directory(opts), env=aws_env(opts))
        if initialized.exit:
            raise RuntimeError("initialization failed")
        result = {**opts, "clickhouse/storage-credentials": await tofu.outputs(directory(opts), aws_env(opts))}
        credential_env(result)
        return result
    except Exception:
        raise RuntimeError("managed storage credentials unavailable; converge storage before rehearsal") from None
