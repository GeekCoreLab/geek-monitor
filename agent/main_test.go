package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestLoadOrRegisterRemovesEnrollmentTokenAfterSuccess(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "state", "agent.json")
	tokenPath := filepath.Join(t.TempDir(), "enrollment.token")
	if err := os.WriteFile(tokenPath, []byte("token-once\n"), 0600); err != nil {
		t.Fatalf("write enrollment token: %v", err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/agents/register" {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		var request struct {
			EnrollmentToken string `json:"enrollmentToken"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Errorf("decode enrollment request: %v", err)
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if request.EnrollmentToken != "token-once" {
			t.Errorf("unexpected enrollment token %q", request.EnrollmentToken)
			http.Error(w, "invalid token", http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"agentId":       "agent-test",
			"resourceId":    "resource-test",
			"credential":    "credential-test",
			"configVersion": 1,
		})
	}))
	defer server.Close()

	config, err := loadOrRegister(server.URL, configPath, tokenPath)
	if err != nil {
		t.Fatalf("register agent: %v", err)
	}
	if config.AgentID != "agent-test" {
		t.Fatalf("unexpected registered agent: %+v", config)
	}
	if _, err := os.Stat(tokenPath); !os.IsNotExist(err) {
		t.Fatalf("consumed enrollment token remains on disk: stat err=%v", err)
	}
	stateInfo, err := os.Stat(configPath)
	if err != nil {
		t.Fatalf("stat saved state: %v", err)
	}
	if stateInfo.Mode().Perm() != 0600 {
		t.Fatalf("agent state permissions = %o, want 600", stateInfo.Mode().Perm())
	}
}

func TestDeliverQueuedRetriesInOrderAndKeepsFailedBatch(t *testing.T) {
	var received []int64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/api/v1/agents/agent-test/batches" {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer credential-test" {
			t.Errorf("missing agent authorization header")
		}
		var receivedBatch batch
		if err := json.NewDecoder(r.Body).Decode(&receivedBatch); err != nil {
			t.Errorf("decode posted batch: %v", err)
			http.Error(w, "invalid batch", http.StatusBadRequest)
			return
		}
		received = append(received, receivedBatch.Sequence)
		if len(received) == 1 {
			http.Error(w, "temporary outage", http.StatusServiceUnavailable)
			return
		}
		if receivedBatch.Sequence == 1 {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		// A duplicate batch is acknowledged with HTTP 200 by the real API.
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	configPath := filepath.Join(t.TempDir(), "agent.json")
	config := &agentConfig{ServerURL: server.URL, AgentID: "agent-test", Credential: "credential-test"}
	for sequence := int64(1); sequence <= maxReplayBatchesPerCycle+2; sequence++ {
		entry := batch{
			BootID: "boot-test", Sequence: sequence, SampledAt: time.Now().UTC().Format(time.RFC3339Nano),
			ConfigVersion: 1, ClockSynced: true, Metrics: []metric{{Name: "load_1m", Value: float64(sequence)}},
		}
		if err := appendQueue(queuePath(configPath), entry); err != nil {
			t.Fatalf("append queued batch %d: %v", sequence, err)
		}
	}

	if err := deliverQueued(server.Client(), config, configPath); err == nil {
		t.Fatal("expected the first upload to remain queued after HTTP 503")
	}
	first, remainder, err := readFirstQueueEntry(queuePath(configPath))
	if err != nil || first == nil || first.Sequence != 1 || queuedLineCount(remainder) != maxReplayBatchesPerCycle+1 {
		t.Fatalf("failed upload did not preserve FIFO queue: first=%+v remainder=%d err=%v", first, len(remainder), err)
	}

	if err := deliverQueued(server.Client(), config, configPath); err != nil {
		t.Fatalf("replay queued batches: %v", err)
	}
	first, remainder, err = readFirstQueueEntry(queuePath(configPath))
	if err != nil || first == nil || first.Sequence != int64(maxReplayBatchesPerCycle+1) || queuedLineCount(remainder) != 1 {
		t.Fatalf("replay exceeded its per-cycle limit or skipped FIFO order: first=%+v remainder=%d err=%v", first, queuedLineCount(remainder), err)
	}
	if err := deliverQueued(server.Client(), config, configPath); err != nil {
		t.Fatalf("replay remaining queued batches: %v", err)
	}
	first, _, err = readFirstQueueEntry(queuePath(configPath))
	if err != nil || first != nil {
		t.Fatalf("queue is not empty after replay: first=%+v err=%v", first, err)
	}
	expected := []int64{1}
	for sequence := int64(1); sequence <= maxReplayBatchesPerCycle+2; sequence++ {
		expected = append(expected, sequence)
	}
	if !reflect.DeepEqual(received, expected) {
		t.Fatalf("uploads were not retried in order: got %v", received)
	}
}

func queuedLineCount(lines [][]byte) int {
	count := 0
	for _, line := range lines {
		if len(line) > 0 {
			count++
		}
	}
	return count
}

func TestParseServerURLs(t *testing.T) {
	urls, err := parseServerURLs("https://s1.example.com", "https://s2.example.com,https://s3.example.com/", "")
	if err != nil {
		t.Fatalf("parseServerURLs: %v", err)
	}
	expected := []string{"https://s1.example.com", "https://s2.example.com", "https://s3.example.com"}
	if !reflect.DeepEqual(urls, expected) {
		t.Fatalf("unexpected parsed URLs: got %v, want %v", urls, expected)
	}
}

func TestLoadOrRegisterFailoverToBackupServer(t *testing.T) {
	primaryFailed := false
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		primaryFailed = true
		http.Error(w, "server unavailable", http.StatusServiceUnavailable)
	}))
	defer primary.Close()

	backupHit := false
	backup := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		backupHit = true
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"agentId":       "agent-failover",
			"resourceId":    "res-failover",
			"credential":    "cred-failover",
			"configVersion": 1,
		})
	}))
	defer backup.Close()

	configPath := filepath.Join(t.TempDir(), "agent.json")
	tokenPath := filepath.Join(t.TempDir(), "enrollment.token")
	if err := os.WriteFile(tokenPath, []byte("token-backup\n"), 0600); err != nil {
		t.Fatalf("write enrollment token: %v", err)
	}

	config, err := loadOrRegister(primary.URL+","+backup.URL, configPath, tokenPath)
	if err != nil {
		t.Fatalf("register with failover: %v", err)
	}
	if !primaryFailed || !backupHit {
		t.Fatalf("expected primary to fail and backup to be hit: primaryFailed=%v backupHit=%v", primaryFailed, backupHit)
	}
	if config.AgentID != "agent-failover" {
		t.Fatalf("unexpected agentId: %+v", config)
	}
	allURLs := config.GetAllServerURLs()
	if len(allURLs) != 2 || allURLs[0] != primary.URL || allURLs[1] != backup.URL {
		t.Fatalf("unexpected stored server URLs: %v", allURLs)
	}
}

func TestDeliverQueuedFailoverToBackupServer(t *testing.T) {
	primaryHitCount := 0
	primary := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		primaryHitCount++
		http.Error(w, "primary down", http.StatusBadGateway)
	}))
	defer primary.Close()

	var backupReceived []int64
	backup := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var receivedBatch batch
		if err := json.NewDecoder(r.Body).Decode(&receivedBatch); err != nil {
			http.Error(w, "invalid", http.StatusBadRequest)
			return
		}
		backupReceived = append(backupReceived, receivedBatch.Sequence)
		w.WriteHeader(http.StatusOK)
	}))
	defer backup.Close()

	configPath := filepath.Join(t.TempDir(), "agent.json")
	config := &agentConfig{
		ServerURL:  primary.URL,
		ServerURLs: []string{primary.URL, backup.URL},
		AgentID:    "agent-test",
		Credential: "credential-test",
	}
	entry := batch{
		BootID: "boot-test", Sequence: 1, SampledAt: time.Now().UTC().Format(time.RFC3339Nano),
		ConfigVersion: 1, ClockSynced: true, Metrics: []metric{{Name: "load_1m", Value: 1.0}},
	}
	if err := appendQueue(queuePath(configPath), entry); err != nil {
		t.Fatalf("append queue: %v", err)
	}

	client := &http.Client{Timeout: 5 * time.Second}
	if err := deliverQueued(client, config, configPath); err != nil {
		t.Fatalf("deliverQueued failover: %v", err)
	}

	if primaryHitCount == 0 {
		t.Fatal("expected primary server to be attempted first")
	}
	if len(backupReceived) != 1 || backupReceived[0] != 1 {
		t.Fatalf("expected backup server to receive batch: got %v", backupReceived)
	}
	// Queue should be empty now
	first, _, err := readFirstQueueEntry(queuePath(configPath))
	if err != nil || first != nil {
		t.Fatalf("queue should be empty after backup accepted: first=%+v, err=%v", first, err)
	}
}
