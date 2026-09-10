#!/usr/bin/python3
"""Publish measurable ClickHouse health; backup freshness applies to node zero."""
import datetime
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

checks = {}
errors = {}

def check(name, fn):
    try:
        value = fn()
        checks[name] = value
        if not value:
            errors[name] = 'Threshold not satisfied'
    except Exception as error:
        checks[name] = False
        errors[name] = str(error)

def query(sql):
    return subprocess.check_output(['clickhouse-client', '--config-file',
        '/etc/clickhouse-server/backup-client.xml', '--query', sql],
        text=True, timeout=60).strip()

def ping():
    with urllib.request.urlopen('http://127.0.0.1:8123/ping', timeout=10) as response:
        return response.status == 200 and response.read().strip() == b'Ok.'

def backup_fresh():
    import boto3
    config = json.loads(Path('/etc/clickhouse-server/backup-credentials.json').read_text())
    s3 = boto3.client('s3', region_name=config['region'],
                      aws_access_key_id=config['access_key_id'],
                      aws_secret_access_key=config['secret_access_key'])
    prefix = config['prefix'].strip('/') + '/'
    completed = [obj for page in s3.get_paginator('list_objects_v2').paginate(
        Bucket=config['bucket'], Prefix=prefix) for obj in page.get('Contents', [])
        if obj['Key'].endswith('/.complete') and obj['Size'] > 0]
    if not completed:
        return False
    newest = max(completed, key=lambda obj: obj['LastModified'])
    marker = s3.get_object(Bucket=config['bucket'], Key=newest['Key'])['Body'].read()
    stamp = newest['Key'][len(prefix):].split('/')[0]
    age = (datetime.datetime.now(datetime.timezone.utc) - newest['LastModified']).total_seconds()
    return marker.strip() == stamp.encode() and 0 <= age < 30 * 3600

check('ping', ping)
check('replication_queue', lambda: int(query('SELECT count() FROM system.replication_queue')) < 100)
check('keeper_quorum', lambda: int(query("SELECT count() FROM system.zookeeper WHERE path = '/'")) > 0)
check('three_replicas', lambda: query("SELECT count() FROM clusterAllReplicas('clickhouse-aws', system.one)") == '3')
check('disk_under_80_percent', lambda: shutil.disk_usage('/var/lib/clickhouse').used / shutil.disk_usage('/var/lib/clickhouse').total < 0.8)
if Path('/etc/clickhouse-server/backup-credentials.json').exists():
    check('backup_younger_than_30_hours', backup_fresh)
result = {'healthy': not errors, 'checked_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'checks': checks, 'errors': errors}
target = Path('/var/lib/clickhouse/colors-health.json')
temporary = target.with_suffix('.tmp')
temporary.write_text(json.dumps(result, indent=2) + '\n')
temporary.chmod(0o644)
temporary.replace(target)
print(json.dumps(result))
raise SystemExit(0 if result['healthy'] else 1)
