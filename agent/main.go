// Geek Monitor Linux host metrics agent. It uses only the Go standard library.
package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

var agentVersion = "0.1.0"

const (
	maxQueueBytes = 64 << 20
	maxQueueAge   = 7 * 24 * time.Hour
	maxBatchBytes = 1 << 20
	// Bound offline replay per tick so recovery traffic stays predictable.
	maxReplayBatchesPerCycle = 10
)

type agentConfig struct {
	ServerURL       string `json:"serverUrl"`
	AgentID         string `json:"agentId"`
	ResourceID      string `json:"resourceId"`
	Credential      string `json:"credential"`
	BootID          string `json:"bootId"`
	Sequence        int64  `json:"sequence"`
	ConfigVersion   int    `json:"configVersion"`
	IntervalSeconds int    `json:"intervalSeconds"`
}

type agentRuntimeConfig struct {
	AgentID                   string   `json:"agentId"`
	ResourceID                string   `json:"resourceId"`
	ConfigVersion             int      `json:"configVersion"`
	CollectionIntervalSeconds int      `json:"collectionIntervalSeconds"`
	ServerTime                string   `json:"serverTime"`
	Metrics                   []string `json:"metrics"`
}

type metric struct {
	Name   string            `json:"name"`
	Value  float64           `json:"value"`
	Labels map[string]string `json:"labels,omitempty"`
}

type batch struct {
	BootID              string   `json:"bootId"`
	Sequence            int64    `json:"sequence"`
	SampledAt           string   `json:"sampledAt"`
	ConfigVersion       int      `json:"configVersion"`
	ClientClockOffsetMs int64    `json:"clientClockOffsetMs"`
	ClockSynced         bool     `json:"clockSynced"`
	Metrics             []metric `json:"metrics"`
}

type procCounters struct {
	Total uint64
	Idle  uint64
}

type netCounters struct {
	Received uint64
	Sent     uint64
}

type collector struct {
	lastCPU       *procCounters
	lastNetwork   map[string]netCounters
	lastNetworkAt time.Time
	serverOffset  time.Duration
	clockSynced   bool
}

func main() {
	serverURL := flag.String("server", "", "Geek Monitor base URL, for example https://monitor.example.com")
	configPath := flag.String("config", "/var/lib/geek-monitor/agent.json", "agent credential and state file")
	enrollmentFile := flag.String("enrollment-token-file", "", "file containing a one-time enrollment token")
	version := flag.Bool("version", false, "print agent version")
	flag.Parse()
	if *version {
		fmt.Printf("geek-monitor-agent %s (%s/%s)\n", agentVersion, runtime.GOOS, runtime.GOARCH)
		return
	}
	if runtime.GOOS != "linux" {
		fatal("this build supports Linux only")
	}
	config, err := loadOrRegister(*serverURL, *configPath, *enrollmentFile)
	if err != nil {
		fatal(err.Error())
	}
	config.BootID, err = newUUID()
	if err != nil {
		fatal(err.Error())
	}
	config.Sequence = 0
	if err := saveConfig(*configPath, config); err != nil {
		fatal("save startup identity: " + err.Error())
	}
	if err := run(config, *configPath); err != nil {
		fatal(err.Error())
	}
}

func loadOrRegister(serverURL, configPath, enrollmentFile string) (*agentConfig, error) {
	if data, err := os.ReadFile(configPath); err == nil {
		info, statErr := os.Stat(configPath)
		if statErr != nil {
			return nil, statErr
		}
		if info.Mode().Perm()&0077 != 0 {
			return nil, fmt.Errorf("%s must not be readable by group or other users (chmod 600)", configPath)
		}
		var config agentConfig
		if err := json.Unmarshal(data, &config); err != nil {
			return nil, fmt.Errorf("read agent state: %w", err)
		}
		if serverURL != "" && strings.TrimRight(serverURL, "/") != strings.TrimRight(config.ServerURL, "/") {
			return nil, errors.New("configured server URL does not match saved agent state")
		}
		if config.AgentID == "" || config.Credential == "" || config.BootID == "" {
			return nil, errors.New("agent state is incomplete")
		}
		return &config, nil
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if serverURL == "" || enrollmentFile == "" {
		return nil, errors.New("first run requires -server and -enrollment-token-file")
	}
	parsedURL, err := url.Parse(serverURL)
	if err != nil || parsedURL.Host == "" || (parsedURL.Scheme != "https" && !(parsedURL.Scheme == "http" && (parsedURL.Hostname() == "localhost" || parsedURL.Hostname() == "127.0.0.1" || parsedURL.Hostname() == "::1"))) {
		return nil, errors.New("server URL must use HTTPS (HTTP is allowed only for localhost development)")
	}
	tokenInfo, err := os.Stat(enrollmentFile)
	if err != nil {
		return nil, fmt.Errorf("read enrollment token file: %w", err)
	}
	if tokenInfo.Mode().Perm()&0077 != 0 {
		return nil, fmt.Errorf("%s must not be readable by group or other users (chmod 600)", enrollmentFile)
	}
	token, err := os.ReadFile(enrollmentFile)
	if err != nil {
		return nil, err
	}
	client := &http.Client{Timeout: 15 * time.Second}
	body, _ := json.Marshal(map[string]string{
		"enrollmentToken": strings.TrimSpace(string(token)),
		"hostname":        hostname(),
		"operatingSystem": runtime.GOOS + "/" + runtime.GOARCH,
		"agentVersion":    agentVersion,
	})
	response, err := client.Post(strings.TrimRight(serverURL, "/")+"/api/v1/agents/register", "application/json", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("register agent: %w", err)
	}
	defer response.Body.Close()
	responseBody, err := io.ReadAll(io.LimitReader(response.Body, 32<<10))
	if err != nil {
		return nil, err
	}
	if response.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("registration failed with HTTP %d: %s", response.StatusCode, safeError(responseBody))
	}
	var result struct {
		AgentID       string `json:"agentId"`
		ResourceID    string `json:"resourceId"`
		Credential    string `json:"credential"`
		ConfigVersion int    `json:"configVersion"`
	}
	if err := json.Unmarshal(responseBody, &result); err != nil {
		return nil, fmt.Errorf("decode registration response: %w", err)
	}
	bootID, err := newUUID()
	if err != nil {
		return nil, err
	}
	config := &agentConfig{
		ServerURL: strings.TrimRight(serverURL, "/"), AgentID: result.AgentID, ResourceID: result.ResourceID,
		Credential: result.Credential, BootID: bootID, ConfigVersion: result.ConfigVersion, IntervalSeconds: 30,
	}
	if err := saveConfig(configPath, config); err != nil {
		return nil, fmt.Errorf("save agent state: %w", err)
	}
	if err := os.Remove(enrollmentFile); err != nil && !os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "warning: remove consumed enrollment token file: %v\n", err)
	}
	return config, nil
}

func run(config *agentConfig, configPath string) error {
	client := &http.Client{Timeout: 20 * time.Second}
	collector := &collector{lastNetwork: make(map[string]netCounters)}
	if err := pollConfig(context.Background(), client, config, collector); err != nil {
		fmt.Fprintf(os.Stderr, "initial config sync failed; using saved interval: %v\n", err)
	}
	if err := collectAndQueue(config, configPath, collector); err != nil {
		fmt.Fprintf(os.Stderr, "initial collection failed: %v\n", err)
	} else if err := deliverQueued(client, config, configPath); err != nil {
		fmt.Fprintf(os.Stderr, "initial batch delivery deferred: %v\n", err)
	}
	ticker := time.NewTicker(time.Duration(clampInterval(config.IntervalSeconds)) * time.Second)
	defer ticker.Stop()
	fmt.Printf("Geek Monitor Agent %s connected (agent %s)\n", agentVersion, config.AgentID)
	for {
		select {
		case <-ticker.C:
			if err := collectAndQueue(config, configPath, collector); err != nil {
				fmt.Fprintf(os.Stderr, "collection failed: %v\n", err)
			}
			if err := deliverQueued(client, config, configPath); err != nil {
				fmt.Fprintf(os.Stderr, "batch delivery deferred: %v\n", err)
			}
			if err := pollConfig(context.Background(), client, config, collector); err != nil {
				fmt.Fprintf(os.Stderr, "config sync failed: %v\n", err)
			}
			if err := saveConfig(configPath, config); err != nil {
				fmt.Fprintf(os.Stderr, "state save failed: %v\n", err)
			}
			ticker.Reset(time.Duration(clampInterval(config.IntervalSeconds)) * time.Second)
		}
	}
}

func pollConfig(ctx context.Context, client *http.Client, config *agentConfig, state *collector) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, config.ServerURL+"/api/v1/agents/"+config.AgentID+"/config", nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", "Bearer "+config.Credential)
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 64<<10))
	if err != nil {
		return err
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("config request failed with HTTP %d: %s", response.StatusCode, safeError(data))
	}
	var received agentRuntimeConfig
	if err := json.Unmarshal(data, &received); err != nil {
		return err
	}
	serverTime, err := time.Parse(time.RFC3339Nano, received.ServerTime)
	if err != nil {
		return fmt.Errorf("invalid server time: %w", err)
	}
	state.serverOffset = serverTime.Sub(time.Now())
	if received.AgentID != config.AgentID || received.ResourceID != config.ResourceID || received.ConfigVersion < 1 {
		return errors.New("server returned a config for a different agent")
	}
	config.ConfigVersion = received.ConfigVersion
	config.IntervalSeconds = clampInterval(received.CollectionIntervalSeconds)
	state.clockSynced = true
	return nil
}

func collectAndQueue(config *agentConfig, configPath string, state *collector) error {
	now := time.Now().Add(state.serverOffset).UTC()
	metrics := collectHostMetrics(state, now)
	if len(metrics) == 0 {
		return errors.New("no host metrics available")
	}
	config.Sequence++
	if err := saveConfig(configPath, config); err != nil {
		return err
	}
	entry := batch{
		BootID: config.BootID, Sequence: config.Sequence, SampledAt: now.Format(time.RFC3339Nano),
		ConfigVersion: config.ConfigVersion, ClientClockOffsetMs: -state.serverOffset.Milliseconds(),
		ClockSynced: state.clockSynced, Metrics: metrics,
	}
	if err := appendQueue(queuePath(configPath), entry); err != nil {
		return err
	}
	return nil
}

func deliverQueued(client *http.Client, config *agentConfig, configPath string) error {
	path := queuePath(configPath)
	for replayed := 0; replayed < maxReplayBatchesPerCycle; replayed++ {
		entry, remainder, err := readFirstQueueEntry(path)
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil || entry == nil {
			return err
		}
		body, err := json.Marshal(entry)
		if err != nil {
			return err
		}
		request, err := http.NewRequest(http.MethodPost, config.ServerURL+"/api/v1/agents/"+config.AgentID+"/batches", bytes.NewReader(body))
		if err != nil {
			return err
		}
		request.Header.Set("Authorization", "Bearer "+config.Credential)
		request.Header.Set("Content-Type", "application/json")
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		responseBody, err := io.ReadAll(io.LimitReader(response.Body, 32<<10))
		response.Body.Close()
		if err != nil {
			return err
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			return fmt.Errorf("batch request failed with HTTP %d: %s", response.StatusCode, safeError(responseBody))
		}
		if err := replaceQueue(path, remainder); err != nil {
			return err
		}
	}
	return nil
}

func collectHostMetrics(state *collector, sampledAt time.Time) []metric {
	metrics := make([]metric, 0, 32)
	if load, err := readLoad1(); err == nil {
		metrics = append(metrics, metric{Name: "load_1m", Value: load})
	}
	if total, available, swapTotal, swapFree, err := readMemory(); err == nil {
		if total > 0 {
			metrics = append(metrics,
				metric{Name: "memory_used_percent", Value: float64(total-available) / float64(total) * 100},
				metric{Name: "memory_available_bytes", Value: float64(available)},
			)
		}
		if swapTotal > 0 {
			metrics = append(metrics, metric{Name: "swap_used_percent", Value: float64(swapTotal-swapFree) / float64(swapTotal) * 100})
		}
	}
	if current, err := readCPU(); err == nil {
		if state.lastCPU != nil && current.Total > state.lastCPU.Total && current.Idle >= state.lastCPU.Idle {
			totalDelta := current.Total - state.lastCPU.Total
			idleDelta := current.Idle - state.lastCPU.Idle
			usage := 100 * (1 - float64(idleDelta)/float64(totalDelta))
			metrics = append(metrics, metric{Name: "cpu_usage_percent", Value: math.Max(0, math.Min(100, usage))})
		}
		state.lastCPU = &current
	}
	metrics = append(metrics, readDiskMetrics()...)
	metrics = append(metrics, readNetworkMetrics(state, sampledAt)...)
	return metrics
}

func readCPU() (procCounters, error) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return procCounters{}, err
	}
	fields := strings.Fields(strings.SplitN(string(data), "\n", 2)[0])
	if len(fields) < 5 || fields[0] != "cpu" {
		return procCounters{}, errors.New("invalid /proc/stat")
	}
	var values []uint64
	for _, field := range fields[1:] {
		value, parseErr := strconv.ParseUint(field, 10, 64)
		if parseErr != nil {
			return procCounters{}, parseErr
		}
		values = append(values, value)
	}
	var total uint64
	for _, value := range values {
		total += value
	}
	return procCounters{Total: total, Idle: values[3] + valueAt(values, 4)}, nil
}

func valueAt(values []uint64, index int) uint64 {
	if index >= len(values) {
		return 0
	}
	return values[index]
}

func readLoad1() (float64, error) {
	data, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return 0, err
	}
	fields := strings.Fields(string(data))
	if len(fields) == 0 {
		return 0, errors.New("invalid /proc/loadavg")
	}
	return strconv.ParseFloat(fields[0], 64)
}

func readMemory() (total, available, swapTotal, swapFree uint64, err error) {
	file, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0, 0, 0, err
	}
	defer file.Close()
	values := make(map[string]uint64)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		value, parseErr := strconv.ParseUint(fields[1], 10, 64)
		if parseErr == nil {
			values[strings.TrimSuffix(fields[0], ":")] = value * 1024
		}
	}
	if err := scanner.Err(); err != nil {
		return 0, 0, 0, 0, err
	}
	return values["MemTotal"], values["MemAvailable"], values["SwapTotal"], values["SwapFree"], nil
}

func readDiskMetrics() []metric {
	file, err := os.Open("/proc/mounts")
	if err != nil {
		return nil
	}
	defer file.Close()
	metrics := make([]metric, 0, 16)
	seen := make(map[string]bool)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 3 || !localFilesystem(fields[2]) {
			continue
		}
		mountpoint := unescapeMount(fields[1])
		if mountpoint == "" || len(mountpoint) > 255 || seen[mountpoint] || len(metrics) >= 100 {
			continue
		}
		seen[mountpoint] = true
		var stat syscall.Statfs_t
		if err := syscall.Statfs(mountpoint, &stat); err != nil || stat.Blocks == 0 {
			continue
		}
		used := float64(stat.Blocks-stat.Bavail) / float64(stat.Blocks) * 100
		metrics = append(metrics, metric{Name: "disk_used_percent", Value: math.Max(0, math.Min(100, used)), Labels: map[string]string{"mountpoint": mountpoint}})
		if stat.Files > 0 {
			inodeUsed := float64(stat.Files-stat.Ffree) / float64(stat.Files) * 100
			metrics = append(metrics, metric{Name: "disk_inode_used_percent", Value: math.Max(0, math.Min(100, inodeUsed)), Labels: map[string]string{"mountpoint": mountpoint}})
		}
	}
	return metrics
}

func localFilesystem(value string) bool {
	switch value {
	case "ext2", "ext3", "ext4", "xfs", "btrfs", "zfs", "f2fs", "vfat", "overlay":
		return true
	default:
		return false
	}
}

func unescapeMount(value string) string {
	replacer := strings.NewReplacer("\\040", " ", "\\011", "\t", "\\134", "\\", "\\012", "\n")
	return replacer.Replace(value)
}

func readNetworkMetrics(state *collector, sampledAt time.Time) []metric {
	file, err := os.Open("/proc/net/dev")
	if err != nil {
		return nil
	}
	defer file.Close()
	current := make(map[string]netCounters)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if !strings.Contains(line, ":") {
			continue
		}
		parts := strings.SplitN(line, ":", 2)
		name := strings.TrimSpace(parts[0])
		fields := strings.Fields(parts[1])
		if name == "lo" || len(fields) < 9 {
			continue
		}
		received, errRx := strconv.ParseUint(fields[0], 10, 64)
		sent, errTx := strconv.ParseUint(fields[8], 10, 64)
		if errRx == nil && errTx == nil && len(name) <= 64 {
			current[name] = netCounters{Received: received, Sent: sent}
		}
	}
	if scanner.Err() != nil {
		return nil
	}
	metrics := make([]metric, 0, len(current)*2)
	delta := sampledAt.Sub(state.lastNetworkAt).Seconds()
	if delta > 0 && delta <= 600 {
		for name, value := range current {
			previous, ok := state.lastNetwork[name]
			if !ok || value.Received < previous.Received || value.Sent < previous.Sent {
				continue
			}
			labels := map[string]string{"interface": name}
			metrics = append(metrics,
				metric{Name: "network_receive_bytes_per_second", Value: float64(value.Received-previous.Received) / delta, Labels: labels},
				metric{Name: "network_transmit_bytes_per_second", Value: float64(value.Sent-previous.Sent) / delta, Labels: labels},
			)
		}
	}
	state.lastNetwork = current
	state.lastNetworkAt = sampledAt
	return metrics
}

func appendQueue(path string, entry batch) error {
	if err := secureDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	line, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	if len(line) > maxBatchBytes {
		return errors.New("collected batch exceeds local size limit")
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	if _, err := file.Write(append(line, '\n')); err != nil {
		file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return trimQueue(path)
}

func trimQueue(path string) error {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	lines := bytes.Split(data, []byte{'\n'})
	kept := make([][]byte, 0, len(lines))
	cutoff := time.Now().Add(-maxQueueAge)
	var total int
	for _, line := range lines {
		if len(line) == 0 {
			continue
		}
		if len(line) > maxBatchBytes {
			continue
		}
		var entry batch
		if json.Unmarshal(line, &entry) != nil {
			continue
		}
		sampledAt, parseErr := time.Parse(time.RFC3339Nano, entry.SampledAt)
		if parseErr == nil && sampledAt.Before(cutoff) {
			continue
		}
		kept = append(kept, line)
		total += len(line) + 1
	}
	for total > maxQueueBytes && len(kept) > 0 {
		total -= len(kept[0]) + 1
		kept = kept[1:]
		fmt.Fprintln(os.Stderr, "local offline queue reached its size cap; dropping the oldest batch")
	}
	return replaceQueue(path, kept)
}

func readFirstQueueEntry(path string) (*batch, [][]byte, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) || len(data) == 0 {
		return nil, nil, err
	}
	if err != nil {
		return nil, nil, err
	}
	lines := bytes.Split(data, []byte{'\n'})
	for len(lines) > 0 && len(lines[0]) == 0 {
		lines = lines[1:]
	}
	if len(lines) == 0 {
		return nil, nil, nil
	}
	var entry batch
	if err := json.Unmarshal(lines[0], &entry); err != nil {
		return nil, nil, fmt.Errorf("decode local queued batch: %w", err)
	}
	return &entry, lines[1:], nil
}

func replaceQueue(path string, lines [][]byte) error {
	if err := secureDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	temporary := path + ".tmp"
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	for _, line := range lines {
		if len(line) == 0 {
			continue
		}
		if _, err := file.Write(append(line, '\n')); err != nil {
			file.Close()
			return err
		}
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporary, path); err != nil {
		return err
	}
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		dir.Sync()
		dir.Close()
	}
	return nil
}

func saveConfig(path string, config *agentConfig) error {
	if err := secureDirectory(filepath.Dir(path)); err != nil {
		return err
	}
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, append(data, '\n'), 0600); err != nil {
		return err
	}
	if err := os.Chmod(temporary, 0600); err != nil {
		return err
	}
	if err := os.Rename(temporary, path); err != nil {
		return err
	}
	return nil
}

func secureDirectory(path string) error {
	if err := os.MkdirAll(path, 0700); err != nil {
		return err
	}
	return os.Chmod(path, 0700)
}

func hostname() string {
	value, err := os.Hostname()
	if err != nil || strings.TrimSpace(value) == "" {
		return "unknown-host"
	}
	return value
}

func queuePath(configPath string) string {
	return filepath.Join(filepath.Dir(configPath), "outbox.jsonl")
}

func clampInterval(value int) int {
	if value < 10 || value > 300 {
		return 30
	}
	return value
}

func newUUID() (string, error) {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(bytes[:])
	return encoded[:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" + encoded[16:20] + "-" + encoded[20:], nil
}

func safeError(data []byte) string {
	var body struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if json.Unmarshal(data, &body) == nil && body.Message != "" {
		return body.Code + ": " + body.Message
	}
	if len(data) > 512 {
		data = data[:512]
	}
	return strings.TrimSpace(string(data))
}

func fatal(message string) {
	fmt.Fprintln(os.Stderr, "geek-monitor-agent:", message)
	os.Exit(1)
}
