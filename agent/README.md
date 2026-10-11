# Geek Monitor Linux Agent

The Agent is a small Linux-only Go program that collects CPU usage, load, memory, swap, disk space/inodes, and network throughput. It uses only the Go standard library and makes outbound requests to Geek Monitor; the center never connects back to the host.

## One-command installation

In the Admin console, add a resource with type **HOST**, open **Agent 节点**, and choose **注册主机 Agent**. After issuing a one-time token, the node appears in the list as **等待安装** and the console displays a copyable install command. Run that command on the target Linux host, then enter the token when the installer prompts; input is hidden and the token is not embedded in shell history. Once registration succeeds, that pending row is replaced by the Agent's normal online status. Expired tokens stay visible as **令牌已过期** until a new token is issued.

The command downloads `install-agent.sh` from the latest GitHub Release. The script downloads the matching Linux amd64 or arm64 binary and `SHA256SUMS`, verifies the binary, creates a restricted `geek-monitor` service account, registers the node, and enables its systemd service. It removes the one-time token after registration and refuses to overwrite an existing Agent state file.

Agent releases are published by pushing a `agent-vX.Y.Z` tag. GitHub Actions runs the Agent tests and attaches `install-agent.sh`, `geek-monitor-agent-linux-amd64`, `geek-monitor-agent-linux-arm64`, and `SHA256SUMS` to the Release. The Admin command always resolves the latest Release.

## Build

Use Go 1.24 or newer:

```sh
cd agent
go build -trimpath -o geek-monitor-agent ./...
```

The collector reads Linux `/proc` data and filesystem statistics. It needs no root privileges for these metrics.

## Enroll a host

1. In the admin console, add a resource with type **HOST**, open **Agent 节点**, and issue a registration token for that resource. The token expires in 15 minutes and can be used once.
2. Install the binary and create a service account and private state directory:

   ```sh
   sudo install -m 0755 geek-monitor-agent /usr/local/bin/geek-monitor-agent
   sudo useradd --system --home-dir /var/lib/geek-monitor --create-home --shell /usr/bin/nologin geek-monitor
   sudo install -d -o geek-monitor -g geek-monitor -m 0700 /etc/geek-monitor
   sudo install -o geek-monitor -g geek-monitor -m 0600 enrollment.token /etc/geek-monitor/enrollment.token
   ```

3. Start the Agent once. Replace the URL with the center's HTTPS URL. HTTP is accepted only for localhost development.

   ```sh
   sudo -u geek-monitor /usr/local/bin/geek-monitor-agent \
     -server https://monitor.example.com \
     -config /var/lib/geek-monitor/agent.json \
     -enrollment-token-file /etc/geek-monitor/enrollment.token
   ```

   After the console shows the node online, stop the foreground process before starting the systemd service. The Agent removes the consumed token file after successful registration and stores its long-lived credential in `agent.json` with mode `0600` inside a directory restricted to mode `0700`.

## systemd

After the first enrollment, use this unit as `/etc/systemd/system/geek-monitor-agent.service`:

```ini
[Unit]
Description=Geek Monitor Linux Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=geek-monitor
Group=geek-monitor
StateDirectory=geek-monitor
StateDirectoryMode=0700
ExecStart=/usr/local/bin/geek-monitor-agent -server https://monitor.example.com -config /var/lib/geek-monitor/agent.json
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/geek-monitor

[Install]
WantedBy=multi-user.target
```

Enable it with `sudo systemctl daemon-reload && sudo systemctl enable --now geek-monitor-agent`. Re-enrollment is required after an administrator revokes the node credential.

## Collection and delivery behavior

- The Agent polls its collection configuration and samples immediately on startup, then follows the server interval (30 seconds by default).
- CPU and network rates need a previous counter sample, so their first sample is omitted.
- Failed uploads are stored in a local JSONL queue, capped at 64 MiB and seven days. Once over the cap, the oldest batches are dropped. The Agent retries queued batches in order, at most one batch per collection interval.
- Every batch carries a boot ID, sequence, sample time, config version, and the last client clock offset measured against server time. The API is idempotent for repeated batches; an unsynced Agent or a reported clock offset beyond five minutes is untrusted. Delayed samples from a synced host remain available in history, while samples older than 30 days are rejected.
- The API stores and graphs trusted host metrics. PostgreSQL, MySQL, and Redis checks publish connection metrics when the database account permits the optional read-only statistics queries. Host and database metric threshold rules, heartbeat tasks, and separate Agent-loss incidents are available in the management console.
