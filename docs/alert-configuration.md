# Alert configuration guide

Configure monitors and notification channels inside the same project as the resources they cover. The central workflow is: create a resource, add checks, choose which checks contribute to resource health, connect a notification channel, then verify a real test delivery before relying on it.

## Checks and failure thresholds

In **监控配置**, create a check and bind it to a project resource. HTTP, TCP, DNS, TLS, and database checks use their type-specific target fields. HTTP accepts GET/HEAD by default; a POST check requires the explicit no-side-effect confirmation. See [database monitoring account permissions](database-monitoring-permissions.md) before saving database credentials, and [Agent setup](../agent/README.md) for HOST metrics.

The default interval is 60 seconds, timeout 10 seconds, failure threshold 3, and recovery threshold 2. Intervals must be 10–86,400 seconds, timeouts 1–30 seconds and shorter than the interval, and each consecutive threshold 1–20. A check becomes unhealthy after the configured number of consecutive definite failures. Recovery requires the configured number of consecutive successes. `UNKNOWN` results reset both counters; they do not prove recovery.

Mark a check **关键检查** when it should contribute to the resource's overall health. A non-critical check still runs and can open incidents; it simply does not determine the resource summary. Disable a check only when it should stop running. Use a maintenance window when checks should continue but routine notifications should be suppressed for a planned period.

## Metric threshold rules

Choose **主机指标阈值** and bind it to a HOST or DATABASE resource. Host rules support CPU, one-minute load, memory, swap, disk, inode, and network metrics. Database rules support current connections, connection limit, and connection utilization. The [metric dictionary](metrics-and-states.md) lists keys, units, labels, and collection caveats.

Set a trigger operator (`>`, `≥`, `<`, `≤`), trigger value, recovery value, observation window, and event severity (`WARNING`, `HIGH`, or `CRITICAL`). For a greater-than rule the recovery value must be lower than the trigger; for a less-than rule it must be higher. The form defaults to a five-minute window and 90/80 for the trigger/recovery values; choose values that match the selected metric's unit.

Host metrics arrive every 30 seconds, so a host window must be at least 120 seconds. Database connection metrics arrive every 60 seconds, so a database window must be at least 240 seconds. The maximum window is 86,400 seconds. Evaluation requires at least 80% expected sample coverage and no gap larger than two sample intervals at the start, end, or inside the window. Insufficient coverage produces `UNKNOWN`, not a threshold incident. For labeled series such as disk mount points or network interfaces, a sustained breach on any series can trigger the rule; recovery requires all series to satisfy the recovery value.

Threshold results still use the check's consecutive failure and recovery counts. A breach first yields `DEGRADED`; enough consecutive breach evaluations open or update the performance incident. Recovery must satisfy the recovery value and consecutive success count before the incident resolves.

## Incidents and repeat reminders

Incidents are deduplicated while active. Use the incident center to add an acknowledgement note and assign ownership. `ACKNOWLEDGED` means someone is handling the incident; it does not stop checks or declare recovery. A recovered check resolves the same incident and records the resolution in its timeline.

The default repeat reminder interval is 30 minutes. Set it to `0` to disable repeat reminders; the allowed range is 0–1,440 minutes. TLS certificate checks run every six hours by default and raise warnings at 30 days, high severity at 14 days, and critical severity at 7 days remaining. Renewing a certificate beyond the alert window resolves its active certificate incident.

## Notification channels

Create an email or HTTPS Webhook channel in the project's **通知渠道** page. SMTP server settings are configured on the service, not in the Admin console; use the [deployment SMTP guide](deployment.md#smtp-配置). Webhook credentials are encrypted and not returned as plaintext. Use **测试通知** to confirm the destination before attaching the channel to a production project.

The default channel limit is 60 batches per minute; configure 1–600. Similar events for a channel are grouped during a 15-second window, up to 50 events in one message. Every event keeps its own Outbox and delivery history. Failed attempts retry after 15 seconds, 60 seconds, 5 minutes, and 30 minutes; after the fifth attempt the delivery is marked `FAILED`.

Email incident notifications include a dynamically generated inline status snapshot. It reads the latest completed check when the message is sent and shows the monitor and resource states, last check time, failure reason, HTTP status, duration, consecutive failures, and incident duration where available. Grouped emails show up to eight incidents in the image; the full event and diagnostic list remains in the email body. The image does not include monitor targets or credentials. An HTML/text fallback remains available if a mail client does not display inline SVG.

## Maintenance windows and notification silence

Use a **maintenance window** for scheduled work: checks and results continue, while notifications are suppressed for the selected project or resource during the window. Use **通知静默** when an event should still be recorded and visible but its notifications should wait until the silence ends. Cancelling a silence resumes queued notifications; cancelling a maintenance window restores normal notification handling. Both controls can be limited to a project or a single resource.

After configuring a rule, verify the intended target and channel in a safe test project. Confirm that the incident opens once, the delivery reaches the channel, acknowledgement leaves the incident active, and recovery resolves the incident and sends the recovery notification.
