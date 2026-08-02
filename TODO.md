# TODO

Production-hardening work, in priority order:

1. Add ClickHouse backups to object storage and test restore.
2. Back up Metabase's PostgreSQL database and test restore.
3. Add monitoring and alerts for Keeper quorum, replica lag, disk usage, and service health.
4. Replace or extend the sample dbt project with real models and data ingestion.
5. Document routine operations and recovery procedures.
6. Optionally destroy the acceptance stack if it is not intended to remain live.
