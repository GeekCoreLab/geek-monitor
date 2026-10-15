import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/api_client.dart';
import 'package:geek_monitor_admin/main.dart';

void main() {
  testWidgets(
    'shows platform telemetry and marks the last snapshot after refresh fails',
    (tester) async {
      final api = _SystemSettingsApi();
      await tester.pumpWidget(
        MaterialApp(home: SystemSettingsPage(api: api, isAdmin: true)),
      );
      await tester.pumpAndSettle();

      expect(find.text('平台采集正常'), findsOneWidget);
      expect(find.text('到期检查积压'), findsOneWidget);
      expect(find.text('4'), findsOneWidget);
      expect(find.text('近 1 小时检查执行 P95'), findsOneWidget);
      expect(find.text('70 ms（50 次）'), findsOneWidget);
      expect(find.text('近 1 小时调度至完成 P95'), findsOneWidget);
      expect(find.text('83 ms（50 次）'), findsOneWidget);
      expect(find.textContaining('62.5%（5 / 8）'), findsOneWidget);

      await tester.tap(find.text('刷新状态'));
      await tester.pumpAndSettle();

      expect(find.text('平台采集正常'), findsOneWidget);
      expect(find.textContaining('最新状态刷新失败，以下内容是上次成功读取的快照。'), findsOneWidget);
      expect(find.text('2026-10-12 12:00:00 UTC'), findsOneWidget);
    },
  );

  testWidgets('audit history moves between cursor pages', (tester) async {
    final api = _PaginatedSystemSettingsApi();
    await tester.pumpWidget(
      MaterialApp(home: SystemSettingsPage(api: api, isAdmin: true)),
    );
    await tester.pumpAndSettle();

    expect(find.text('第 1 页 · 1 条'), findsOneWidget);
    expect(find.textContaining('测试 记录1'), findsOneWidget);

    await tester.ensureVisible(find.text('下一页'));
    await tester.tap(find.text('下一页'));
    await tester.pumpAndSettle();

    expect(find.text('第 2 页 · 1 条'), findsOneWidget);
    expect(find.textContaining('测试 记录2'), findsOneWidget);
    expect(api.auditCalls.last, {
      'limit': 20,
      'beforeCreatedAt': '2026-10-12T11:00:00Z',
      'beforeId': 'cursor-1',
    });

    await tester.ensureVisible(find.text('上一页'));
    await tester.tap(find.text('上一页'));
    await tester.pumpAndSettle();

    expect(find.text('第 1 页 · 1 条'), findsOneWidget);
    expect(find.textContaining('测试 记录1'), findsOneWidget);
    expect(api.auditCalls.last, {
      'limit': 20,
      'beforeCreatedAt': null,
      'beforeId': null,
    });
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
  }) async => {'items': const <Object>[], 'next': null};
}

class _PaginatedSystemSettingsApi extends _SystemSettingsApi {
  final auditCalls = <Map<String, Object?>>[];

  @override
  Future<Map<String, dynamic>> auditLog({
    int limit = 50,
    String? beforeCreatedAt,
    String? beforeId,
  }) async {
    auditCalls.add({
      'limit': limit,
      'beforeCreatedAt': beforeCreatedAt,
      'beforeId': beforeId,
    });
    final pageNumber = beforeCreatedAt == null ? 1 : 2;
    return {
      'items': [
        {
          'id': 'entry-$pageNumber',
          'createdAt': '2026-10-12T12:00:00Z',
          'actorEmail': 'admin@example.com',
          'action': '测试操作',
          'subjectType': '测试',
          'subjectId': '记录$pageNumber',
          'summary': const <String, Object?>{},
        },
      ],
      'next': pageNumber == 1
          ? {'createdAt': '2026-10-12T11:00:00Z', 'id': 'cursor-1'}
          : null,
    };
  }
}
