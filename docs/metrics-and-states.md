# Metrics and status dictionary

Metric timestamps are stored in UTC. A missing sample is a gap, not a zero. The console leaves gaps visible, and threshold rules require enough recent samples before making a decision.

## Host metrics

Linux Agent samples these metrics from `/proc`, `statfs`, and local filesystem information. Disk metrics are labeled by `mountpoint`; network metrics are labeled by `interface`.

| Metric key | Meaning | Unit and notes |
| --- | --- | --- |
| `cpu_usage_percent` | CPU busy time between two Agent samples | Percent, 0–100. The first sample after Agent start has no previous counter and is omitted. |
| `load_1m` | Linux one-minute load average | Load average, not a percentage and not normalized by CPU count. |
| `memory_used_percent` | `(MemTotal - MemAvailable) / MemTotal` | Percent, 0–100. |
| `memory_available_bytes` | Linux `MemAvailable` | Bytes. |
| `swap_used_percent` | `(SwapTotal - SwapFree) / SwapTotal` | Percent, 0–100; omitted when the host has no swap. |
| `disk_used_percent` | Allocated blocks relative to total blocks | Percent by mount point. Collected for supported local filesystems. |
| `disk_inode_used_percent` | Used inodes relative to total inodes | Percent by mount point; omitted when inode capacity is unavailable. |
| `network_receive_bytes_per_second` | Bytes received per second | Bytes/second by interface; loopback is excluded and the first counter sample is omitted. |
| `network_transmit_bytes_per_second` | Bytes sent per second | Bytes/second by interface; loopback is excluded and the first counter sample is omitted. |

## Database metrics

PostgreSQL, MySQL, and Redis checks sample connection statistics every 60 seconds when the optional read-only query succeeds.

| Metric key | Meaning | Unit and notes |
| --- | --- | --- |
| `database_connections` | Current connections | Count. |
| `database_connection_limit` | Configured connection limit | Count. |
| `database_connections_percent` | Current connections divided by limit | Percent. |

Missing statistics do not make a successful `SELECT 1` or Redis `PING` fail. Threshold checks for a database metric show `UNKNOWN` until the rule has sufficient samples. See [database monitoring account permissions](database-monitoring-permissions.md) for the optional queries and least-privilege grants.

## Health states

| State | Meaning |
| --- | --- |
| `PENDING` | No usable result has been recorded yet, or no enabled critical check contributes to the resource summary. |
| `UP` | The check succeeded. |
| `DEGRADED` | A performance or metric threshold is breached while the service is still considered available. |
| `DOWN` | The check produced a definite failure after its configured consecutive-failure threshold. |
| `UNKNOWN` | The system cannot make a trustworthy health decision, for example because configuration is invalid, access is blocked by policy, or metric coverage is insufficient. |

The resource summary uses enabled checks marked **关键检查**. A `DOWN` critical check makes the resource `DOWN`; otherwise `UNKNOWN` takes precedence over `PENDING`, then `DEGRADED`, then `UP`. The critical flag affects only the resource summary; the individual check and its incidents remain active. Pausing and maintenance are separate controls and do not overwrite the underlying health result.

For a check, consecutive `DOWN` or `DEGRADED` results count toward its failure threshold. Consecutive `UP` results count toward recovery. `UNKNOWN` resets those counters and does not resolve an existing `DOWN` or `DEGRADED` state by itself.

## Incident states

| State | Meaning |
| --- | --- |
| `OPEN` | An active incident needs attention. |
| `ACKNOWLEDGED` | A project member has taken ownership; the underlying check may still be failing. |
| `RESOLVED` | The underlying failure recovered or the related Agent was revoked. Resolution is recorded in the event timeline. |

Acknowledging an incident never marks a service healthy. An incident stays active through `OPEN` and `ACKNOWLEDGED` and is resolved by recovery or the relevant administrative action.

## Agent states

| State | Meaning |
| --- | --- |
| `WAITING_INSTALL` | A valid one-time enrollment token was issued, but the Agent has not registered yet. |
| `INSTALL_EXPIRED` | The 15-minute enrollment token expired; issue a new one. |
| `ONLINE` | The Agent reported within the last 90 seconds. |
| `OFFLINE` | No report arrived within the 90-second online window. A separate collection incident is raised; host health is not automatically marked `DOWN`. |
| `REVOKED` | The administrator revoked the Agent credential. |

## Notification delivery states

Outbox states are `PENDING` (queued or waiting for a retry), `RUNNING` (claimed by a sender), `DELIVERED`, `FAILED` (retry limit reached), and `CANCELLED` (the event was superseded or delivery was disabled). Each delivery attempt is recorded as `RETRYING`, `DELIVERED`, `FAILED`, or `CANCELLED`.

## Availability statistics

`DEGRADED` time counts as available time and is also reported separately. `DOWN` time counts as unavailable. `UNKNOWN` time lowers coverage rather than being treated as downtime. Paused periods and maintenance windows are excluded from eligible time. A result carries for no more than one configured check interval.
