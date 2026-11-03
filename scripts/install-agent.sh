#!/usr/bin/env bash
set -Eeuo pipefail

repository="skyhhjmk/geek-monitor"
github_release_base="https://github.com/${repository}/releases/latest/download"
server_url=""
backup_servers=""
download_source=""
enrollment_token=""
non_interactive=0

usage() {
  cat <<'EOF'
Geek Monitor Linux Agent 安装程序

用法:
  install-agent.sh [选项]

选项:
  --server <URL>           监控中心主地址 (支持逗号分隔多个地址)
  --backup-server <URL>    监控中心备用地址 (可选，支持逗号分隔多个地址)
  --source <site|github>   Agent 二进制下载源 (site: 当前站点, github: GitHub Releases)
  --token <TOKEN>          一次性注册令牌 (如未提供将在终端提示输入)
  -y, --non-interactive    非交互模式 (要求已提供 --server 与 --token)
  -h, --help               显示帮助信息

示例:
  # 交互式向导安装 (推荐)
  sudo bash install-agent.sh

  # 从当前监控中心下载并安装 (单地址)
  sudo bash install-agent.sh --server https://monitor.example.com --source site

  # 配置主地址与备用地址
  sudo bash install-agent.sh --server https://primary.example.com --backup-server https://backup.example.com --source site
EOF
}

is_valid_url() {
  local u="$1"
  local https_pattern='^https://([A-Za-z0-9.-]+|\[[0-9A-Fa-f:]+\])(:[0-9]{1,5})?(/[A-Za-z0-9._/-]*)?$'
  local localhost_pattern='^http://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._/-]*)?$'
  if [[ "$u" =~ $https_pattern || "$u" =~ $localhost_pattern ]]; then
    return 0
  fi
  return 1
}

# Parse command line options
while (($#)); do
  case "$1" in
    --server)
      if (($# < 2)); then
        echo "错误: --server 需要指定 URL" >&2
        exit 2
      fi
      if [[ -z "$server_url" ]]; then
        server_url="$2"
      else
        server_url="${server_url},$2"
      fi
      shift 2
      ;;
    --backup-server)
      if (($# < 2)); then
        echo "错误: --backup-server 需要指定 URL" >&2
        exit 2
      fi
      if [[ -z "$backup_servers" ]]; then
        backup_servers="$2"
      else
        backup_servers="${backup_servers},$2"
      fi
      shift 2
      ;;
    --source|--download-source)
      if (($# < 2)); then
        echo "错误: --source 需要指定 site 或 github" >&2
        exit 2
      fi
      download_source="$2"
      shift 2
      ;;
    --token)
      if (($# < 2)); then
        echo "错误: --token 需要指定注册令牌" >&2
        exit 2
      fi
      enrollment_token="$2"
      shift 2
      ;;
    -y|--non-interactive|--batch)
      non_interactive=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "未知选项: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

# If executed via pipe (e.g. curl ... | sudo bash), reconnect stdin to terminal
if [[ ! -t 0 ]] && [[ -r /dev/tty ]]; then
  exec </dev/tty
fi

# Ensure root permissions
if ((EUID != 0)); then
  echo "错误: 请通过 sudo 或 root 权限执行此脚本以安装 systemd 服务" >&2
  exit 1
fi

# Check required commands
for command in sha256sum systemctl getent useradd groupadd install mktemp; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "错误: 系统缺少必要命令: $command" >&2
    exit 1
  fi
done

if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
  echo "错误: 系统未检测到 curl 或 wget，请先安装其中之一" >&2
  exit 1
fi

# Helper for HTTP download
download_file() {
  local target_url="$1"
  local output_path="$2"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --connect-timeout 10 --max-time 180 --retry 2 \
      "$target_url" --output "$output_path"
  else
    wget --quiet --timeout=10 --tries=2 "$target_url" -O "$output_path"
  fi
}

# Determine if we should enter interactive wizard
can_interactive=0
if [[ -t 0 || -r /dev/tty ]] && ((non_interactive == 0)); then
  can_interactive=1
fi

echo "=========================================================="
echo "          Geek Monitor Linux Agent 安装配置向导           "
echo "=========================================================="

# 1. Server URL prompt
if [[ -z "$server_url" ]]; then
  if ((can_interactive == 0)); then
    echo "错误: 未提供 --server 参数，非交互模式下无法继续" >&2
    usage >&2
    exit 2
  fi
  while true; do
    printf "[1/4] 请输入监控中心主地址 (例如 https://monitor.example.com): "
    read -r input_server
    input_server="$(echo "$input_server" | tr -d ' ')"
    if [[ -z "$input_server" ]]; then
      echo "  提示: 监控中心地址不能为空"
      continue
    fi
    # If multiple URLs separated by comma
    first_url="${input_server%%,*}"
    if is_valid_url "$first_url"; then
      server_url="$input_server"
      break
    else
      echo "  错误: 地址格式无效。生产环境必须为 HTTPS，仅 localhost 允许 HTTP"
    fi
  done
else
  # Validate provided server_url
  IFS=',' read -ra ADDR_LIST <<< "$server_url"
  for s in "${ADDR_LIST[@]}"; do
    s="$(echo "$s" | tr -d ' ')"
    if ! is_valid_url "$s"; then
      echo "错误: 监控中心地址 \"$s\" 格式无效 (必须为 HTTPS，仅 localhost 允许 HTTP)" >&2
      exit 2
    fi
  done
fi

# Normalize primary server URL
primary_server="${server_url%%,*}"
primary_server="${primary_server%/}"

# 2. Backup servers prompt
if [[ -z "$backup_servers" ]] && ((can_interactive == 1)) && [[ "$server_url" != *","* ]]; then
  printf "[2/4] 请输入备用监控中心地址 (可选，多个以逗号分隔，直接回车跳过): "
  read -r input_backups
  input_backups="$(echo "$input_backups" | tr -d ' ')"
  if [[ -n "$input_backups" ]]; then
    IFS=',' read -ra BACKUP_LIST <<< "$input_backups"
    valid_all=1
    for b in "${BACKUP_LIST[@]}"; do
      if ! is_valid_url "$b"; then
        echo "  警告: 备用地址 \"$b\" 格式无效，已忽略"
        valid_all=0
      fi
    done
    if ((valid_all == 1)); then
      backup_servers="$input_backups"
    fi
  fi
fi

# Build combined server URL list for agent
combined_servers="$server_url"
if [[ -n "$backup_servers" ]]; then
  combined_servers="${combined_servers},${backup_servers}"
fi

# 3. Download source prompt
if [[ -z "$download_source" ]]; then
  if ((can_interactive == 1)); then
    echo "[3/4] 请选择 Agent 二进制下载源:"
    echo "  1) 从当前监控中心下载 [推荐，无需公网访问 GitHub]"
    echo "  2) 从 GitHub Releases 下载"
    printf "请选择 [1/2] (默认: 1): "
    read -r choice_source
    case "$choice_source" in
      2) download_source="github" ;;
      *) download_source="site" ;;
    esac
  else
    download_source="site"
  fi
fi

if [[ "$download_source" != "site" && "$download_source" != "github" ]]; then
  echo "警告: 未知下载源 $download_source，默认使用 site (当前监控站点)"
  download_source="site"
fi

# 4. Enrollment token prompt
if [[ -z "$enrollment_token" ]]; then
  if [[ ! -r /dev/tty && ! -t 0 ]]; then
    echo "错误: 需要交互终端输入一次性注册令牌，或使用 --token 参数传递" >&2
    exit 1
  fi
  printf "[4/4] 请输入一次性注册令牌 (输入内容已隐藏): "
  if [[ -r /dev/tty ]]; then
    IFS= read -r -s enrollment_token </dev/tty || true
  else
    IFS= read -r -s enrollment_token || true
  fi
  printf '\n'
fi

enrollment_token="$(echo "$enrollment_token" | tr -d ' \r\n')"
if [[ -z "$enrollment_token" ]]; then
  echo "错误: 注册令牌不能为空" >&2
  exit 1
fi

# Check architecture
case "$(uname -m)" in
  x86_64|amd64) architecture="amd64" ;;
  aarch64|arm64) architecture="arm64" ;;
  *)
    echo "错误: 不支持的 Linux 架构: $(uname -m) (仅支持 x86_64 和 aarch64)" >&2
    exit 1
    ;;
esac

binary_asset="geek-monitor-agent-linux-${architecture}"

echo "----------------------------------------------------------"
echo "配置摘要:"
echo "  - 主监控地址:  $primary_server"
if [[ "$combined_servers" == *","* ]]; then
  echo "  - 全部上报地址: $combined_servers"
fi
echo "  - 下载来源:    $([[ "$download_source" == "site" ]] && echo "当前监控站点 ($primary_server)" || echo "GitHub Releases")"
echo "  - 系统架构:    $(uname -m) ($architecture)"
echo "----------------------------------------------------------"

if [[ -s /var/lib/geek-monitor/agent.json ]]; then
  echo "警告: /var/lib/geek-monitor/agent.json 已存在已有注册状态。"
  if ((can_interactive == 1)); then
    printf "是否覆盖并重新注册? [y/N]: "
    read -r confirm_overwrite
    if [[ "$confirm_overwrite" != "y" && "$confirm_overwrite" != "Y" ]]; then
      echo "操作已取消。"
      exit 0
    fi
  else
    echo "错误: 目标主机已注册，非交互模式下请先卸载或清理状态文件" >&2
    exit 1
  fi
fi

if systemctl is-active --quiet geek-monitor-agent.service; then
  echo "[*] 停止正在运行的 geek-monitor-agent.service..."
  systemctl stop geek-monitor-agent.service || true
fi

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

# Perform download with fallback
download_success=0
if [[ "$download_source" == "site" ]]; then
  site_binary_url="${primary_server}/api/v1/agent-downloads/${binary_asset}"
  site_sums_url="${primary_server}/api/v1/agent-downloads/SHA256SUMS"
  echo "[*] 正在从监控中心下载 Agent 二进制 (${binary_asset})..."
  if download_file "$site_binary_url" "$temporary_directory/$binary_asset"; then
    echo "  [✓] 二进制下载成功"
    download_file "$site_sums_url" "$temporary_directory/SHA256SUMS" || true
    download_success=1
  else
    echo "  [!] 从监控中心下载失败，尝试从 GitHub Releases 下载..."
    download_source="github"
  fi
fi

if [[ "$download_success" -eq 0 && "$download_source" == "github" ]]; then
  gh_binary_url="${github_release_base}/${binary_asset}"
  gh_sums_url="${github_release_base}/SHA256SUMS"
  echo "[*] 正在从 GitHub Releases 下载 Agent 二进制 (${binary_asset})..."
  if download_file "$gh_binary_url" "$temporary_directory/$binary_asset"; then
    echo "  [✓] 二进制下载成功"
    download_file "$gh_sums_url" "$temporary_directory/SHA256SUMS" || true
    download_success=1
  else
    echo "  [!] 从 GitHub 下载失败 (可能是网络连接超时或受限)。"
    # Try site fallback if not tried yet
    if [[ "$download_source" == "github" ]]; then
      site_binary_url="${primary_server}/api/v1/agent-downloads/${binary_asset}"
      site_sums_url="${primary_server}/api/v1/agent-downloads/SHA256SUMS"
      echo "[*] 正在尝试从监控中心下载作为备用方案..."
      if download_file "$site_binary_url" "$temporary_directory/$binary_asset"; then
        echo "  [✓] 从监控中心下载成功"
        download_file "$site_sums_url" "$temporary_directory/SHA256SUMS" || true
        download_success=1
      fi
    fi
  fi
fi

if [[ "$download_success" -eq 0 ]]; then
  echo "错误: 无法下载 Agent 二进制文件。请检查网络连通性或当前监控站点的下载服务。" >&2
  exit 1
fi

# Check SHA256 if sums file is present
if [[ -s "$temporary_directory/SHA256SUMS" ]]; then
  echo "[*] 正在校验 SHA-256 完整性..."
  if grep -E "^[0-9a-f]{64}[[:space:]]+${binary_asset}$" "$temporary_directory/SHA256SUMS" >/dev/null 2>&1; then
    if (cd "$temporary_directory" && grep -E "^[0-9a-f]{64}[[:space:]]+${binary_asset}$" SHA256SUMS | sha256sum --check --status); then
      echo "  [✓] SHA-256 校验通过"
    else
      echo "错误: SHA-256 校验失败，下载的文件可能已损坏" >&2
      exit 1
    fi
  else
    echo "  [i] 校验文件中未包含 ${binary_asset}，跳过校验"
  fi
else
  echo "  [i] 未检测到 SHA256SUMS 校验文件，跳过哈希校验"
fi

chmod 0755 "$temporary_directory/$binary_asset"

# User and group management
echo "[*] 配置 geek-monitor 系统账户与权限..."
if ! getent group geek-monitor >/dev/null 2>&1; then
  groupadd --system geek-monitor
fi
if ! getent passwd geek-monitor >/dev/null 2>&1; then
  nologin_shell="$(command -v nologin || command -v false)"
  useradd --system --gid geek-monitor --home-dir /var/lib/geek-monitor \
    --create-home --shell "$nologin_shell" geek-monitor
fi

install -d -o geek-monitor -g geek-monitor -m 0700 /var/lib/geek-monitor /etc/geek-monitor
install -o root -g root -m 0755 "$temporary_directory/$binary_asset" /usr/local/bin/geek-monitor-agent

# Write enrollment token file
printf '%s\n' "$enrollment_token" >"$temporary_directory/enrollment.token"
unset enrollment_token
install -o geek-monitor -g geek-monitor -m 0600 "$temporary_directory/enrollment.token" /etc/geek-monitor/enrollment.token

# Write systemd service file
echo "[*] 生成并配置 systemd 服务 (geek-monitor-agent.service)..."
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
ExecStart=/usr/local/bin/geek-monitor-agent -server "$combined_servers" -config /var/lib/geek-monitor/agent.json -enrollment-token-file /etc/geek-monitor/enrollment.token
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

# Start service and verify registration
echo "[*] 启动服务并等待首次注册..."
systemctl daemon-reload
systemctl enable --now geek-monitor-agent.service

registered=0
for i in {1..30}; do
  if [[ -s /var/lib/geek-monitor/agent.json ]]; then
    registered=1
    break
  fi
  printf "."
  sleep 1
done
printf "\n"

if ((registered == 0)); then
  echo "错误: Agent 注册超时 (30秒内未完成注册)" >&2
  systemctl stop geek-monitor-agent.service || true
  echo "--- systemd 最近日志 ---" >&2
  journalctl -u geek-monitor-agent.service -n 25 --no-pager >&2 || true
  echo "-----------------------" >&2
  echo "排查建议:" >&2
  echo "  1. 确认监控中心地址 ($primary_server) 在此主机上能否正常通信" >&2
  echo "  2. 确认一次性注册令牌是否有效或已被消费，如已失效请在管理后台重新签发" >&2
  exit 1
fi

rm -f /etc/geek-monitor/enrollment.token
chmod 0600 /var/lib/geek-monitor/agent.json

echo "=========================================================="
echo "✓ Geek Monitor Agent 安装并注册成功！"
echo "  - 服务名称:    geek-monitor-agent.service"
echo "  - 运行状态:    active (running)"
echo "  - 上报地址:    $combined_servers"
echo "  - 查看服务状态: systemctl status geek-monitor-agent"
echo "  - 查看实时日志: journalctl -u geek-monitor-agent -f"
echo "=========================================================="
