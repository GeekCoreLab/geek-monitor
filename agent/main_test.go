package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

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
