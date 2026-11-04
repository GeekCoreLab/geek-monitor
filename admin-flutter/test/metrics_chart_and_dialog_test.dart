import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/main.dart';

void main() {
  group('Metric formatting and chart card', () {
    testWidgets('renders metric chart with statistics and values', (tester) async {
      final now = DateTime.now();
      final points = [
        {
          'bucketAt': now.subtract(const Duration(minutes: 10)).toIso8601String(),
          'average': 15.5,
          'minimum': 10.0,
          'maximum': 20.0,
          'sampleCount': 10,
        },
        {
          'bucketAt': now.toIso8601String(),
          'average': 42.0,
          'minimum': 30.0,
          'maximum': 50.0,
          'sampleCount': 10,
        },
      ];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 600,
                child: MetricChartCard(
                  metricName: 'cpu_usage_percent',
                  labels: const {'host': 'test-server'},
                  points: points,
                  stepSeconds: 60,
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Check title and labels
      expect(find.text('CPU 使用率（%）'), findsOneWidget);
      expect(find.text('host: test-server'), findsOneWidget);

      // Check statistics bar with values
      expect(find.text('最新数值: '), findsOneWidget);
      expect(find.text('42.0%'), findsOneWidget);
      expect(find.textContaining('最高: 50.0% · 最低: 10.0%'), findsOneWidget);
    });

    test('availableMonitorTypesForResource filters types properly', () {
      final hostTypes = availableMonitorTypesForResource('HOST');
      expect(hostTypes, contains('METRIC_THRESHOLD'));
      expect(hostTypes, contains('TCP'));
      expect(hostTypes, isNot(contains('POSTGRESQL')));
      expect(hostTypes, isNot(contains('HEARTBEAT')));

      final dbTypes = availableMonitorTypesForResource('DATABASE');
      expect(dbTypes, contains('POSTGRESQL'));
      expect(dbTypes, contains('MYSQL'));
      expect(dbTypes, contains('REDIS'));
      expect(dbTypes, contains('METRIC_THRESHOLD'));
      expect(dbTypes, isNot(contains('HTTP')));
      expect(dbTypes, isNot(contains('HEARTBEAT')));

      final hbTypes = availableMonitorTypesForResource('HEARTBEAT');
      expect(hbTypes, equals(['HEARTBEAT']));
    });
  });
}
