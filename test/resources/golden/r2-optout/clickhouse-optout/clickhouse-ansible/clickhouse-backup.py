#!/usr/bin/python3
"""Native backup sets verified against S3, and isolated live restore checks."""
import datetime
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path
import boto3

CONFIG = json.loads(Path('/etc/clickhouse-server/backup-credentials.json').read_text())
S3 = boto3.client('s3', region_name=CONFIG['region'],
                  aws_access_key_id=CONFIG['access_key_id'],
                  aws_secret_access_key=CONFIG['secret_access_key'])
BUCKET = CONFIG['bucket']
PREFIX = CONFIG['prefix'].strip('/') + '/'

def query(sql):
    return subprocess.check_output(['clickhouse-client', '--config-file',
        '/etc/clickhouse-server/backup-client.xml', '--query', sql], text=True).strip()

def objects(prefix):
    return [o for page in S3.get_paginator('list_objects_v2').paginate(Bucket=BUCKET, Prefix=prefix)
            for o in page.get('Contents', [])]

def put(key, value):
    data = value.encode()
    S3.put_object(Bucket=BUCKET, Key=key, Body=data)
    assert S3.get_object(Bucket=BUCKET, Key=key)['Body'].read() == data

def verify_set(metadata, listed, prefix, stats, backup_id):
    """Reconcile logical files, deduplicated payloads, and complete physical storage."""
    import xml.etree.ElementTree as ET
    root = ET.fromstring(metadata)
    assert root.findtext('uuid') == backup_id, 'Backup metadata belongs to another operation'
    assert root.findtext('base_backup') is None, 'Incremental backup is unsupported by this full-set verifier'
    files = root.findall('./contents/file')
    assert len(files) == stats['num_files'], 'Logical file count differs from system.backups'
    assert sum(int(file.findtext('size')) for file in files) == stats['total_size'], 'Logical bytes differ from system.backups'
    physical = {}
    identities = {}
    names = set()
    for file in files:
        name = file.findtext('name')
        assert name and name not in names, 'Missing or duplicate logical file name'
        names.add(name)
        size = int(file.findtext('size'))
        assert size >= 0 and int(file.findtext('base_size', '0')) == 0, 'Invalid full-backup file size'
        if not size:
            continue  # Empty logical files have no physical entry in ClickHouse backups.
        data_file = file.findtext('data_file') or name
        assert all(segment not in ('', '.', '..') for segment in data_file.split('/')), 'Unsafe backup object path'
        checksum = file.findtext('checksum')
        assert checksum, 'Nonempty file lacks checksum'
        identity = (size, checksum)
        assert data_file not in identities or identities[data_file] == identity, 'Inconsistent deduplicated reference'
        identities[data_file] = identity
        physical[prefix + data_file] = size
    assert len(physical) == stats['num_entries'], 'Physical payload count differs from system.backups'
    physical[prefix + '.backup'] = len(metadata)
    observed = {obj['Key']: obj['Size'] for obj in listed}
    assert len(observed) == len(listed), 'Duplicate S3 listing keys'
    assert observed == physical, 'S3 object names or sizes differ from complete backup metadata'
    object_bytes = sum(observed.values())
    assert len(observed) == stats['num_entries'] + 1, 'Physical object count excludes only the metadata entry'
    assert object_bytes == stats['uncompressed_size'] == stats['compressed_size'], 'Stored bytes differ from system.backups'
    return {**stats, 'object_count': len(observed), 'object_bytes': object_bytes, 'metadata_bytes': len(metadata)}


def backup():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8]
    backup_id, status = query("BACKUP DATABASE analytics, DATABASE default TO Disk('backups', '%s/') SETTINGS async = 0 FORMAT TSV" % stamp).split('\t')
    assert status == 'BACKUP_CREATED', status
    stats = json.loads(query("SELECT num_files,total_size,num_entries,uncompressed_size,compressed_size FROM system.backups WHERE id = '%s' FORMAT JSONEachRow" % backup_id))
    stats = {key: int(value) for key, value in stats.items()}
    listed = objects(PREFIX + stamp + '/')
    metadata = S3.get_object(Bucket=BUCKET, Key=PREFIX + stamp + '/.backup')['Body'].read()
    evidence = verify_set(metadata, listed, PREFIX + stamp + '/', stats, backup_id)
    manifest = {'stamp': stamp, 'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'version': query('SELECT version()'), **evidence}
    put(PREFIX + stamp + '/manifest.json', json.dumps(manifest))
    put(PREFIX + stamp + '/.complete', stamp + '\n')
    # Check credentials on the host, never insert the secret into a logged SQL query.
    query('SYSTEM FLUSH LOGS')
    logged = query('SELECT query FROM system.query_log FORMAT TSVRaw')
    assert CONFIG['secret_access_key'] not in logged, 'Backup secret leaked into query log'
    # Retain seven days, keeping the freshly verified set even on clock anomalies.
    now = datetime.datetime.now(datetime.timezone.utc)
    sets = {}
    for obj in objects(PREFIX):
        relative = obj['Key'][len(PREFIX):]
        if '/' in relative:
            sets.setdefault(relative.split('/')[0], []).append(obj)
    for old_stamp, contents in sets.items():
        if old_stamp == stamp:
            continue
        complete = any(o['Key'].endswith('/.complete') and o['Size'] > 0 for o in contents)
        newest = max(o['LastModified'] for o in contents)
        if (now - newest).total_seconds() > (7 if complete else 1) * 86400:
            for offset in range(0, len(contents), 1000):
                response = S3.delete_objects(Bucket=BUCKET, Delete={'Objects': [
                    {'Key': o['Key']} for o in contents[offset:offset + 1000]]})
                assert not response.get('Errors'), response.get('Errors')
    print(json.dumps(manifest))

def restore(stamp=None):
    if stamp is None:
        completed = sorted(o['Key'] for o in objects(PREFIX) if o['Key'].endswith('/.complete') and o['Size'] > 0)
        assert completed, 'No completed backup sets'
        stamp = completed[-1][len(PREFIX):].split('/')[0]
    assert re.fullmatch(r'[A-Za-z0-9_-]+', stamp), 'Invalid backup stamp'
    marker = S3.get_object(Bucket=BUCKET, Key=PREFIX + stamp + '/.complete')['Body'].read()
    assert marker.strip() == stamp.encode(), 'Invalid completion marker'
    query('DROP DATABASE IF EXISTS restore_check SYNC')
    try:
        result = query("RESTORE DATABASE analytics AS restore_check FROM Disk('backups', '%s/') SETTINGS async = 0 FORMAT TSV" % stamp)
        assert result.split('\t')[-1] == 'RESTORED', result
        assert int(query("SELECT count() FROM system.tables WHERE database = 'restore_check'")) > 0
        assert query("SELECT count() FROM system.replicas WHERE database = 'restore_check' AND zookeeper_path IN (SELECT zookeeper_path FROM system.replicas WHERE database = 'analytics')") == '0'
        live = query('SELECT * FROM analytics.events_summary ORDER BY event_type FORMAT TSV')
        restored = query('SELECT * FROM restore_check.events_summary ORDER BY event_type FORMAT TSV')
        assert live == restored, (live, restored)
        print(json.dumps({'stamp': stamp, 'status': 'RESTORED', 'restored_analytics_rows': restored, 'keeper_collisions': 0}))
    finally:
        query('DROP DATABASE IF EXISTS restore_check SYNC')

if __name__ == '__main__':
    if len(sys.argv) < 2 or sys.argv[1] not in ('backup', 'restore'):
        raise SystemExit('Usage: clickhouse-backup backup | restore [stamp]')
    backup() if sys.argv[1] == 'backup' else restore(sys.argv[2] if len(sys.argv) > 2 else None)
