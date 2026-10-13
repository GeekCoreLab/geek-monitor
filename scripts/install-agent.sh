#!/usr/bin/env bash
set -Eeuo pipefail

repository="skyhhjmk/geek-monitor"
release_base="https://github.com/${repository}/releases/latest/download"
server_url=""

usage() {
  cat <<'EOF'
Usage: install-agent.sh --server https://monitor.example.com

Downloads the latest Geek Monitor Linux Agent release, verifies its SHA-256
checksum, registers this host with a hidden one-time token prompt, and installs
the systemd service.
EOF
}

while (($#)); do
  case "$1" in
    --server)
      if (($# < 2)); then
        echo "--server requires a URL" >&2
        exit 2
      fi
      server_url=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$server_url" ]]; then
  usage >&2
  exit 2
fi

https_pattern='^https://([A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])(:[0-9]{1,5})?(/[A-Za-z0-9._/-]*)?$'
localhost_pattern='^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._/-]*)?$'
if [[ ! "$server_url" =~ $https_pattern && ! "$server_url" =~ $localhost_pattern ]]; then
  echo "server URL must be HTTPS; HTTP is allowed only for localhost development" >&2
  exit 2
fi

if ((EUID != 0)); then
  echo "run this command through sudo so it can install the system service" >&2
  exit 1
fi

for command in curl sha256sum systemctl getent useradd groupadd install mktemp; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command is missing: $command" >&2
    exit 1
  fi
done

if [[ -s /var/lib/geek-monitor/agent.json ]]; then
  echo "an Agent is already registered at /var/lib/geek-monitor/agent.json" >&2
  exit 1
fi
if systemctl is-active --quiet geek-monitor-agent.service; then
  echo "geek-monitor-agent.service is already active" >&2
  exit 1
fi

case "$(uname -m)" in
  x86_64|amd64) architecture="amd64" ;;
  aarch64|arm64) architecture="arm64" ;;
  *)
    echo "unsupported Linux architecture: $(uname -m); supported: x86_64 and aarch64" >&2
    exit 1
    ;;
esac

binary_asset="geek-monitor-agent-linux-${architecture}"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

curl --fail --location --silent --show-error --retry 2 \
  "$release_base/$binary_asset" \
  --output "$temporary_directory/$binary_asset"
curl --fail --location --silent --show-error --retry 2 \
  "$release_base/SHA256SUMS" \
  --output "$temporary_directory/SHA256SUMS"
grep -E "^[0-9a-f]{64}  ${binary_asset}$" "$temporary_directory/SHA256SUMS" |
  (cd "$temporary_directory" && sha256sum --check --status)
chmod 0755 "$temporary_directory/$binary_asset"

if [[ ! -r /dev/tty ]]; then
  echo "an interactive terminal is required to enter the one-time enrollment token" >&2
  exit 1
fi
printf "One-time enrollment token (input hidden): " >/dev/tty
IFS= read -r -s enrollment_token </dev/tty || true
printf '\n' >/dev/tty
if [[ -z "${enrollment_token:-}" ]]; then
  echo "enrollment token cannot be empty" >&2
  exit 1
fi

if ! getent group geek-monitor >/dev/null 2>&1; then
  groupadd --system geek-monitor
fi
if ! getent passwd geek-monitor >/dev/null 2>&1; then
  nologin_shell="$(command -v nologin || command -v false)"
  useradd --system --gid geek-monitor --home-dir /var/lib/geek-monitor \
    --create-home --shell "$nologin_shell" geek-monitor
fi
install -d -o geek-monitor -g geek-monitor -m 0700 \
  /var/lib/geek-monitor /etc/geek-monitor
install -o root -g root -m 0755 "$temporary_directory/$binary_asset" \
  /usr/local/bin/geek-monitor-agent
printf '%s\n' "$enrollment_token" >"$temporary_directory/enrollment.token"
unset enrollment_token
install -o geek-monitor -g geek-monitor -m 0600 \
  "$temporary_directory/enrollment.token" /etc/geek-monitor/enrollment.token

cat >/etc/systemd/system/geek-monitor-agent.service <<EOF
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
ExecStart=/usr/local/bin/geek-monitor-agent -server $server_url -config /var/lib/geek-monitor/agent.json -enrollment-token-file /etc/geek-monitor/enrollment.token
Restart=on-failure
RestartSec=5s
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/geek-monitor /etc/geek-monitor

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now geek-monitor-agent.service
for _ in {1..30}; do
  if [[ -s /var/lib/geek-monitor/agent.json ]]; then
    break
  fi
  sleep 1
done
if [[ ! -s /var/lib/geek-monitor/agent.json ]]; then
  systemctl stop geek-monitor-agent.service || true
  journalctl -u geek-monitor-agent.service -n 20 --no-pager >&2 || true
  echo "Agent registration did not finish; check the server URL and enrollment token, then issue a fresh token." >&2
  exit 1
fi
rm -f /etc/geek-monitor/enrollment.token
chmod 0600 /var/lib/geek-monitor/agent.json
echo "Geek Monitor Agent installed and registered. Check status with: systemctl status geek-monitor-agent"
