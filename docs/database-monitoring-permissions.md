# Database monitoring account permissions

The monitor opens a connection and runs only fixed, read-only probes. PostgreSQL and MySQL run `SELECT 1`; Redis runs `PING`. Optional connection metrics are read with the built-in queries listed below. A denied optional metrics query is omitted and does not turn a healthy database into a failure. The monitor does not execute user-provided SQL or read application tables.

Create a separate account for each target, restrict its source address at the database firewall, and enable TLS where the database is exposed beyond a trusted private network. Do not use a database owner, superuser, or administrator account.

## PostgreSQL

The account needs `LOGIN`, permission to connect to the selected database, and a matching `pg_hba.conf` rule. It does not need privileges on application tables:

```sql
CREATE ROLE geek_monitor LOGIN PASSWORD 'replace-with-a-secret';
GRANT CONNECT ON DATABASE appdb TO geek_monitor;
```

For connection metrics the monitor runs `SELECT count(*) FROM pg_stat_activity` and reads `max_connections` from `pg_settings`. PostgreSQL exposes session-row existence to ordinary users; the monitor counts rows and does not read other sessions' query text or details. Do not grant `pg_read_all_stats` or `pg_monitor` for this check. PostgreSQL documents the distinction between visible session existence and restricted session details in its [statistics guide](https://www.postgresql.org/docs/17/monitoring-stats.html), and describes the monitoring roles in its [predefined roles reference](https://www.postgresql.org/docs/17/predefined-roles.html).

Verify the account without table access:

```sql
SELECT 1;
SELECT count(*) FROM pg_stat_activity;
SELECT setting FROM pg_settings WHERE name = 'max_connections';
```

## MySQL

The monitor connects with the configured schema as the default database. MySQL requires a privilege on that schema or one of its objects to select it. To avoid granting `SELECT` on business tables, create a constant probe view and grant `SHOW VIEW` only on that view:

```sql
CREATE USER 'geek_monitor'@'10.0.0.5' IDENTIFIED BY 'replace-with-a-secret';
CREATE VIEW appdb.geek_monitor_access_probe AS SELECT 1 AS ok;
GRANT SHOW VIEW ON appdb.geek_monitor_access_probe TO 'geek_monitor'@'10.0.0.5';
```

The probe account can connect to `appdb`, but it cannot select from the view or application tables. `SHOW VIEW` permits inspection of that constant view's definition. If adding a probe view is not practical, granting `SHOW VIEW` on `appdb.*` is broader because it exposes definitions for every view in that schema. MySQL documents the database privilege requirement for [`USE`](https://dev.mysql.com/doc/refman/8.4/en/use.html).

The optional metrics use `SHOW GLOBAL STATUS LIKE 'Threads_connected'` and `SHOW GLOBAL VARIABLES LIKE 'max_connections'`. MySQL 8.4 states that these statements require only a connection and no additional privilege ([status](https://dev.mysql.com/doc/refman/8.4/en/show-status.html), [variables](https://dev.mysql.com/doc/refman/8.4/en/show-variables.html)). Do not grant `PROCESS`, `SUPER`, global `SELECT`, or write privileges for this monitor.

Verify the account:

```sql
SELECT 1;
SHOW GLOBAL STATUS LIKE 'Threads_connected';
SHOW GLOBAL VARIABLES LIKE 'max_connections';
```

## Redis

Use an ACL user with only the commands needed by the selected database. For Redis database 0, `SELECT` is not sent; for a nonzero database, allow `SELECT` as shown:

```text
ACL SETUSER geek-monitor on >replace-with-a-secret resetkeys resetchannels -@all +auth +select +ping
```

The required probe is `PING`. Optional metrics use `INFO clients`, `INFO memory`, `INFO stats`, and `CONFIG GET maxclients`. `INFO` exposes client counts, memory use and limit, and the cumulative number of evicted keys. On Redis 7 or later, grant these read-only commands separately if the additional server information is acceptable:

```text
ACL SETUSER geek-monitor +info +config|get
```

`CONFIG GET` can reveal server configuration values, so omit this optional grant if that exposure is not acceptable; the availability check still succeeds without the connection limit metric. Each optional command is queried independently, so a restricted ACL can still provide whichever `INFO` sections it permits. A Redis `maxmemory` value of zero means no configured limit: the used-memory metric remains available, while limit and utilization metrics are omitted. Redis 7 supports ACL rules for individual command subcommands such as `config|get` ([Redis ACL guide](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/)). No key patterns or data commands are needed.

Verify the required and optional commands using the monitor account:

```text
PING
INFO clients
INFO memory
INFO stats
CONFIG GET maxclients
```

## Local verification

These permission sets were checked against isolated PostgreSQL 17, MySQL 8.4, and Redis 7 containers. PostgreSQL `CONNECT` alone allowed the fixed health and connection-count queries. MySQL access to one constant view with `SHOW VIEW` allowed the configured database connection and global status queries while application-table reads were denied. The restricted Redis ACL allowed `PING`, the optional `INFO` sections, `CONFIG GET`, and selecting database 2 without granting key commands. A separate PING-only Redis ACL verified that optional permission failures leave the availability result healthy and omit metrics.
