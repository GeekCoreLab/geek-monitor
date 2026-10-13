import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/api_client.dart';

void main() {
  test('parses named JSON events, multi-line data, and replay cursors', () async {
    final bytes = utf8.encode(
      ':keepalive\n\n'
      'id: 2026-10-12T13:00:00.123456Z|550e8400-e29b-41d4-a716-446655440000\n'
      'event: monitor.status\n'
      'data: {"resourceId":"resource-1",\n'
      'data: "resultStatus":"DOWN"}\n\n'
      'event: incident.updated\n'
      'data: {"incidentStatus":"ACKNOWLEDGED"}\n\n',
    );

    final events = await parseServerSentEvents(Stream.value(bytes)).toList();

    expect(events, hasLength(2));
    expect(events.first.name, 'monitor.status');
    expect(events.first.id, startsWith('2026-10-12T13:00:00.123456Z|'));
    expect(events.first.data, {
      'resourceId': 'resource-1',
      'resultStatus': 'DOWN',
    });
    expect(events.last.name, 'incident.updated');
    expect(events.last.id, events.first.id);
    expect(events.last.data['incidentStatus'], 'ACKNOWLEDGED');
  });
}
