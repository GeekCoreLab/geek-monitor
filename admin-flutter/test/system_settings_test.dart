import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/api_client.dart';
import 'package:geek_monitor_admin/main.dart';

void main() {
  testWidgets('shows platform telemetry and marks the last snapshot after refresh fails', (
    tester,
  ) async {
    final api = _SystemSettingsApi();
    await tester.pumpWidget(
      MaterialApp(home: SystemSettingsPage(api: api, isAdmin: true)),
    );
    await tester.pumpAndSettle();

    expect(find.text('平台采集正常'), findsOneWidget);
    expect(find.text('到期检查积压'), findsOneWidget);
    expect(find.text('4'), findsOneWidget);
    expect(find.text('近 1 小时规则处理 P95'), findsOneWidget);
    expect(find.text('83 ms'), findsOneWidget);
    expect(find.textContaining('62.5%（5 / 8）'), findsOneWidget);

    await tester.tap(find.text('刷新状态'));
    await tester.pumpAndSettle();

    expect(find.text('平台采集正常'), findsOneWidget);
    expect(find.textContaining('最新状态刷新失败，以下内容是上次成功读取的快照。'), findsOneWidget);
    expect(find.text('2026-10-12 12:00:00 UTC'), findsOneWidget);
  });
}

class _SystemSettingsApi extends ApiClient {
  var statusCalls = 0;

  @override
  Future<Map<String, dynamic>> systemStatus() async {
    statusCalls++;
    if (statusCalls > 1) {
      throw const ApiException('connection refused');
    }
    return {
      'generatedAt': '2026-10-12T12:00:00Z',
      'schemaVersion': '28',
      'databaseSizeBytes': 1024,
      'retentionPolicies': const <Object>[],
      'backup': {'status': 'NOT_RECORDED'},
      'platformHealth': {
        'status': 'HEALTHY',
        'dueChecks': 4,
        'oldestScheduleDelaySeconds': 0,
        'runningChecks': 2,
        'enabledChecks': 20,
        'staleChecks': 0,
        'recentCheckCount': 50,
        'recentCheckAverageDurationMs': 23.5,
        'recentCheckP95DurationMs': 70,
        'recentResultAverageLatencyMs': 42.4,
        'recentResultP95LatencyMs': 83,
        'rejectedAgentBatchesSinceStart': 3,
        'processStartedAt': '2026-10-12T10:00:00Z',
        'notificationAttempts24h': 8,
        'notificationDelivered24h': 5,
        'notificationSuccessRate24h': 62.5,
        'retention': {
          'status': 'CURRENT',
          'lastSuccessAt': '2026-10-12T11:59:00Z',
          'consecutiveFailures': 0,
        },
      },
    };
  }

  @override
  Future<Map<String, dynamic>> auditLog({
    int limit = 50,
    String? beforeCreatedAt,
    String? beforeId,
  }) async => {
    'items': const <Object>[],
    'next': null,
  };
}
