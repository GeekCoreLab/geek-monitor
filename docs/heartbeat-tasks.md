# Heartbeat tasks

Heartbeat monitors report the result of work performed by an external scheduler. Geek Monitor checks the latest report on the normal monitor schedule and sends state changes through the existing incident and notification pipeline.

## Create and configure

Create a resource with type `HEARTBEAT`, then add a monitor of type `HEARTBEAT` to it. Set **检查间隔** to the task's expected run period and **缺报宽限期** to the extra delay allowed for queueing or runtime variance. A task is marked down after `interval + grace` seconds without a fresh successful report. A report with status `FAILURE` marks it down at the next monitor check. Set the monitor's failure and recovery thresholds to control how many checks are needed to open or resolve an incident.

The create response includes `heartbeatToken` once. The Admin page displays it once and offers a copy action. Store it in the task runner's secret store or a file readable only by the task account. Administrators can rotate it from the monitor card; rotation immediately invalidates the previous token, so update the task runner at the same time.

## Report a run

Send `POST /api/v1/heartbeats/{monitorId}` with the task token as a bearer credential:

```sh
export API_URL='https://monitor.example'
export HEARTBEAT_TOKEN='read-from-your-secret-store'

run_id="$(cat /proc/sys/kernel/random/uuid)"
run_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
if /opt/jobs/nightly-backup; then
  status=SUCCESS
  summary='nightly backup completed'
else
  status=FAILURE
  summary='nightly backup command failed'
fi

curl --fail-with-body --silent --show-error \
  -X POST "$API_URL/api/v1/heartbeats/MONITOR_UUID" \
  -H "Authorization: Bearer $HEARTBEAT_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "{\"runId\":\"$run_id\",\"runAt\":\"$run_at\",\"status\":\"$status\",\"summary\":\"$summary\"}"
```

Replace `MONITOR_UUID` with the ID shown in the Admin example. Each actual run needs a new UUID. If a request times out, retry the same report with its original `runId` and `runAt`; the API records a run only once. `runAt` must be ISO-8601, no more than 30 days old, and no more than five minutes in the future. `status` is `SUCCESS` or `FAILURE`; `summary` is optional, at most 500 characters, and should not contain secrets.

Reports older than the latest run are retained as history but do not replace the current status. Paused resources, disabled monitors, and deleted resources reject reports. Disabling or deleting a monitor also stops checks and notifications.
