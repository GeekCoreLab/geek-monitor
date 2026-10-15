import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'src/http_client_factory_stub.dart'
    if (dart.library.js_interop) 'src/http_client_factory_web.dart'
    as http_client_factory;

class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode});

  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}

class StatusStreamEvent {
  const StatusStreamEvent({
    required this.id,
    required this.name,
    required this.data,
  });

  final String id;
  final String name;
  final Map<String, dynamic> data;
}

Stream<StatusStreamEvent> parseServerSentEvents(Stream<List<int>> bytes) async* {
  var lastEventId = '';
  var eventName = 'message';
  final dataLines = <String>[];

  StatusStreamEvent? dispatch() {
    if (dataLines.isEmpty) return null;
    final decoded = jsonDecode(dataLines.join('\n'));
    if (decoded is! Map) return null;
    return StatusStreamEvent(
      id: lastEventId,
      name: eventName,
      data: Map<String, dynamic>.from(decoded),
    );
  }

  await for (final line in bytes.transform(utf8.decoder).transform(const LineSplitter())) {
    if (line.isEmpty) {
      final event = dispatch();
      if (event != null) yield event;
      dataLines.clear();
      eventName = 'message';
      continue;
    }
    if (line.startsWith(':')) continue;

    final separator = line.indexOf(':');
    final field = separator < 0 ? line : line.substring(0, separator);
    var value = separator < 0 ? '' : line.substring(separator + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    switch (field) {
      case 'id':
        if (!value.contains('\u0000')) lastEventId = value;
      case 'event':
        eventName = value.isEmpty ? 'message' : value;
      case 'data':
        dataLines.add(value);
    }
  }

  final finalEvent = dispatch();
  if (finalEvent != null) yield finalEvent;
}

class ApiClient {
  ApiClient({http.Client? client, Uri? baseUri})
    : _client = client ?? http_client_factory.createHttpClient(),
      _baseUriOverride = baseUri;

  static const _emailKey = 'geek-monitor.last-email';
  static const _configuredBaseUrl = String.fromEnvironment('API_BASE_URL');

  final http.Client _client;
  final Uri? _baseUriOverride;

  Uri get _baseUri =>
      _baseUriOverride ??
      Uri.parse(
        _configuredBaseUrl.isEmpty ? Uri.base.origin : _configuredBaseUrl,
      );

  Future<String?> lastEmail() async =>
      (await SharedPreferences.getInstance()).getString(_emailKey);

  Future<void> saveSession(Map<String, dynamic> session) async {
    final preferences = await SharedPreferences.getInstance();
    final user = session['user'];
    if (user is Map && user['email'] is String) {
      await preferences.setString(_emailKey, user['email'] as String);
    }
  }

  Future<void> saveEmail(String email) async {
    await (await SharedPreferences.getInstance()).setString(_emailKey, email);
  }

  Future<Map<String, dynamic>> getInstallStatus() async =>
      (await _send('GET', '/api/v1/install/status')) as Map<String, dynamic>;

  Future<Map<String, dynamic>> setup({
    required String email,
    required String password,
    required String projectName,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/install/setup',
            body: {
              'email': email,
              'password': password,
              'projectName': projectName,
            },
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/auth/login',
            body: {'email': email, 'password': password},
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> me() async =>
      (await _send('GET', '/api/v1/auth/me')) as Map<String, dynamic>;

  Future<void> logout() async {
    await _send('POST', '/api/v1/auth/logout');
  }

  Future<List<Map<String, dynamic>>> projects() async =>
      _asMapList(await _send('GET', '/api/v1/projects'));

  Future<Map<String, dynamic>> systemStatus() async =>
      (await _send('GET', '/api/v1/system/status')) as Map<String, dynamic>;

  Future<Map<String, dynamic>> auditLog({
    int limit = 50,
    String? beforeCreatedAt,
    String? beforeId,
  }) async {
    final query = <String, String>{'limit': '$limit'};
    if (beforeCreatedAt != null && beforeId != null) {
      query['beforeCreatedAt'] = beforeCreatedAt;
      query['beforeId'] = beforeId;
    }
    return (await _send(
          'GET',
          '/api/v1/audit-log?${Uri(queryParameters: query).query}',
        ))
        as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> createProject({
    required String name,
    required String description,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/projects',
            body: {'name': name, 'description': description},
          ))
          as Map<String, dynamic>;

  Future<List<Map<String, dynamic>>> projectMembers(String projectId) async =>
      _asMapList(
        await _send(
          'GET',
          '/api/v1/projects/${Uri.encodeComponent(projectId)}/members',
        ),
      );

  Future<Map<String, dynamic>> addProjectMember({
    required String projectId,
    required String email,
    required String password,
    required String role,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/projects/${Uri.encodeComponent(projectId)}/members',
            body: {'email': email, 'password': password, 'role': role},
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> updateProjectMemberRole({
    required String projectId,
    required String memberId,
    required String role,
  }) async =>
      (await _send(
            'PATCH',
            '/api/v1/projects/${Uri.encodeComponent(projectId)}/members/${Uri.encodeComponent(memberId)}',
            body: {'role': role},
          ))
          as Map<String, dynamic>;

  Future<void> removeProjectMember({
    required String projectId,
    required String memberId,
  }) async {
    await _send(
      'DELETE',
      '/api/v1/projects/${Uri.encodeComponent(projectId)}/members/${Uri.encodeComponent(memberId)}',
    );
  }

  Future<List<Map<String, dynamic>>> projectCredentials(
    String projectId,
  ) async => _asMapList(
    await _send(
      'GET',
      '/api/v1/projects/${Uri.encodeComponent(projectId)}/credentials',
    ),
  );

  Future<Map<String, dynamic>> createProjectCredential({
    required String projectId,
    required String name,
    required Map<String, String> headers,
    required List<String> allowedOrigins,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/projects/${Uri.encodeComponent(projectId)}/credentials',
            body: {
              'name': name,
              'headers': headers,
              'allowedOrigins': allowedOrigins,
            },
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> updateProjectCredential({
    required String projectId,
    required String id,
    required String name,
    required Map<String, String> headers,
    required List<String> allowedOrigins,
    required int version,
  }) async =>
      (await _send(
            'PATCH',
            '/api/v1/projects/${Uri.encodeComponent(projectId)}/credentials/${Uri.encodeComponent(id)}',
            body: {
              'name': name,
              'headers': headers,
              'allowedOrigins': allowedOrigins,
              'version': version,
            },
          ))
          as Map<String, dynamic>;

  Future<void> deleteProjectCredential({
    required String projectId,
    required String id,
  }) async {
    await _send(
      'DELETE',
      '/api/v1/projects/${Uri.encodeComponent(projectId)}/credentials/${Uri.encodeComponent(id)}',
    );
  }

  Future<Map<String, dynamic>> updateProject({
    required String id,
    required String name,
    required String description,
  }) async =>
      (await _send(
            'PATCH',
            '/api/v1/projects/${Uri.encodeComponent(id)}',
            body: {'name': name, 'description': description},
          ))
          as Map<String, dynamic>;

  Future<List<Map<String, dynamic>>> resources({String? projectId}) async {
    final query = projectId == null
        ? ''
        : '?projectId=${Uri.encodeQueryComponent(projectId)}';
    return _asMapList(await _send('GET', '/api/v1/resources$query'));
  }

  Future<Map<String, dynamic>> createResource({
    required String projectId,
    required String name,
    required String type,
    required String environment,
    required List<String> tags,
    String? ownerEmail,
  }) async =>
      (await _send(
            'POST',
            '/api/v1/resources',
            body: {
              'projectId': projectId,
              'name': name,
              'type': type,
              'environment': environment,
              'tags': tags,
              'ownerEmail': ownerEmail,
            },
          ))
          as Map<String, dynamic>;

  String publicStatusUrl(String projectId) =>
      _baseUri.resolve('/public/${Uri.encodeComponent(projectId)}').toString();

  Future<void> setResourcePublic(String id, bool enabled) async {
    await _send(
      'PATCH',
      '/api/v1/resources/${Uri.encodeComponent(id)}/public',
      body: {'enabled': enabled},
    );
  }

  Future<Map<String, dynamic>> updateResource({
    required String id,
    required String name,
    required String environment,
    required List<String> tags,
    String? ownerEmail,
  }) async =>
      (await _send(
            'PATCH',
            '/api/v1/resources/${Uri.encodeComponent(id)}',
            body: {
              'name': name,
              'environment': environment,
              'tags': tags,
              'ownerEmail': ownerEmail,
            },
          ))
          as Map<String, dynamic>;

  Future<void> deleteResource(String id) async {
    await _send('DELETE', '/api/v1/resources/${Uri.encodeComponent(id)}');
  }

  Future<Map<String, dynamic>> setResourcePaused(
    String id, {
    required bool paused,
  }) async =>
      (await _send(
            'PATCH',
            '/api/v1/resources/${Uri.encodeComponent(id)}/pause',
            body: {'paused': paused},
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> resourceMetrics(
    String id, {
    int rangeDays = 1,
  }) async {
    final to = DateTime.now().toUtc();
    final from = to.subtract(Duration(days: rangeDays));
    final stepSeconds = rangeDays <= 3
        ? 60
        : rangeDays <= 7
        ? 300
        : 3600;
    final query = Uri(
      queryParameters: {
        'from': from.toIso8601String(),
        'to': to.toIso8601String(),
        'stepSeconds': '$stepSeconds',
      },
    ).query;
    return (await _send(
          'GET',
          '/api/v1/resources/${Uri.encodeComponent(id)}/metrics?$query',
        ))
        as Map<String, dynamic>;
  }

  Future<List<Map<String, dynamic>>> agents() async =>
      _asMapList(await _send('GET', '/api/v1/agents'));

  Future<Map<String, dynamic>> createAgentEnrollment(String resourceId) async =>
      (await _send(
            'POST',
            '/api/v1/agent-enrollments',
            body: {'resourceId': resourceId},
          ))
          as Map<String, dynamic>;

  Future<void> revokeAgent(String id) async {
    await _send('POST', '/api/v1/agents/${Uri.encodeComponent(id)}/revoke');
  }

  Future<List<Map<String, dynamic>>> monitors({String? resourceId}) async {
    final query = resourceId == null
        ? ''
        : '?resourceId=${Uri.encodeQueryComponent(resourceId)}';
    return _asMapList(await _send('GET', '/api/v1/monitors$query'));
  }

  Future<Map<String, dynamic>> createMonitor(
    Map<String, dynamic> definition,
  ) async =>
      (await _send('POST', '/api/v1/monitors', body: definition))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> rotateHeartbeatToken(String id) async =>
      (await _send(
            'POST',
            '/api/v1/heartbeats/${Uri.encodeComponent(id)}/rotate-token',
          ))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> updateMonitor(
    String id,
    Map<String, dynamic> definition,
  ) async =>
      (await _send(
            'PATCH',
            '/api/v1/monitors/${Uri.encodeComponent(id)}',
            body: definition,
          ))
          as Map<String, dynamic>;

  Future<void> deleteMonitor(String id) async {
    await _send('DELETE', '/api/v1/monitors/${Uri.encodeComponent(id)}');
  }

  Future<Map<String, dynamic>> testMonitor(String id) async =>
      (await _send('POST', '/api/v1/monitors/${Uri.encodeComponent(id)}/test'))
          as Map<String, dynamic>;

  Future<List<Map<String, dynamic>>> checkResults(String id) async =>
      _asMapList(
        await _send(
          'GET',
          '/api/v1/monitors/${Uri.encodeComponent(id)}/results?limit=100',
        ),
      );

  Future<Map<String, dynamic>> monitorAvailability(
    String id, {
    int rangeDays = 1,
  }) async {
    final now = DateTime.now().toUtc();
    final to = DateTime.utc(now.year, now.month, now.day, now.hour);
    final from = to.subtract(Duration(days: rangeDays));
    final query = Uri(
      queryParameters: {
        'from': from.toIso8601String(),
        'to': to.toIso8601String(),
      },
    ).query;
    return (await _send(
          'GET',
          '/api/v1/monitors/${Uri.encodeComponent(id)}/availability?$query',
        ))
        as Map<String, dynamic>;
  }

  Future<List<Map<String, dynamic>>> incidents({String? status}) async {
    final query = status == null
        ? ''
        : '?status=${Uri.encodeQueryComponent(status)}';
    return _asMapList(await _send('GET', '/api/v1/incidents$query'));
  }

  Stream<StatusStreamEvent> statusEvents() async* {
    String? lastEventId;
    while (true) {
      try {
        final request = http.Request('GET', _baseUri.resolve('/api/v1/events/stream'))
          ..headers['Accept'] = 'text/event-stream';
        if (lastEventId != null && lastEventId.isNotEmpty) {
          request.headers['Last-Event-ID'] = lastEventId;
        }
        final response = await _client.send(request).timeout(
          const Duration(seconds: 20),
        );
        if (response.statusCode < 200 || response.statusCode >= 300) {
          final body = await response.stream.bytesToString();
          var message = '实时状态连接失败（${response.statusCode}）';
          try {
            final decoded = jsonDecode(body);
            if (decoded is Map && decoded['message'] is String) {
              message = decoded['message'] as String;
            }
          } catch (_) {
            // Keep a short status-only message for non-JSON proxy responses.
          }
          throw ApiException(message, statusCode: response.statusCode);
        }

        await for (final event in parseServerSentEvents(response.stream)) {
          if (event.id.isNotEmpty) lastEventId = event.id;
          yield event;
        }
      } on ApiException catch (error) {
        if (error.statusCode != null && error.statusCode! >= 400 && error.statusCode! < 500) {
          rethrow;
        }
        await Future<void>.delayed(const Duration(seconds: 2));
      } catch (_) {
        await Future<void>.delayed(const Duration(seconds: 2));
      }
    }
  }

  Future<Map<String, dynamic>> acknowledgeIncident(
    String id,
    String note,
  ) async =>
      (await _send(
            'POST',
            '/api/v1/incidents/${Uri.encodeComponent(id)}/ack',
            body: {'note': note},
          ))
          as Map<String, dynamic>;

  Future<List<Map<String, dynamic>>> incidentEvents(String id) async =>
      _asMapList(
        await _send(
          'GET',
          '/api/v1/incidents/${Uri.encodeComponent(id)}/events',
        ),
      );

  Future<List<Map<String, dynamic>>> notificationChannels({
    String? projectId,
  }) async {
    final query = projectId == null
        ? ''
        : '?projectId=${Uri.encodeQueryComponent(projectId)}';
    return _asMapList(
      await _send('GET', '/api/v1/notification-channels$query'),
    );
  }

  Future<Map<String, dynamic>> createNotificationChannel(
    Map<String, dynamic> definition,
  ) async =>
      (await _send('POST', '/api/v1/notification-channels', body: definition))
          as Map<String, dynamic>;

  Future<Map<String, dynamic>> updateNotificationChannel(
    String id,
    Map<String, dynamic> definition,
  ) async =>
      (await _send(
            'PATCH',
            '/api/v1/notification-channels/${Uri.encodeComponent(id)}',
            body: definition,
          ))
          as Map<String, dynamic>;

  Future<void> deleteNotificationChannel(String id) async {
    await _send(
      'DELETE',
      '/api/v1/notification-channels/${Uri.encodeComponent(id)}',
    );
  }

  Future<Map<String, dynamic>> testNotificationChannel(String id) async =>
      (await _send(
            'POST',
            '/api/v1/notification-channels/${Uri.encodeComponent(id)}/test',
          ))
          as Map<String, dynamic>;

  Future<List<Map<String, dynamic>>> notificationDeliveries({
    String? channelId,
  }) async {
    final query = channelId == null
        ? ''
        : '?channelId=${Uri.encodeQueryComponent(channelId)}';
    return _asMapList(
      await _send('GET', '/api/v1/notification-deliveries$query'),
    );
  }

  Future<List<Map<String, dynamic>>> maintenanceWindows({
    String? projectId,
  }) async {
    final query = projectId == null
        ? ''
        : '?projectId=${Uri.encodeQueryComponent(projectId)}';
    return _asMapList(await _send('GET', '/api/v1/maintenance-windows$query'));
  }

  Future<Map<String, dynamic>> createMaintenanceWindow(
    Map<String, dynamic> definition,
  ) async =>
      (await _send('POST', '/api/v1/maintenance-windows', body: definition))
          as Map<String, dynamic>;

  Future<void> cancelMaintenanceWindow(String id) async {
    await _send(
      'DELETE',
      '/api/v1/maintenance-windows/${Uri.encodeComponent(id)}',
    );
  }

  Future<List<Map<String, dynamic>>> notificationSilences({
    String? projectId,
  }) async {
    final query = projectId == null
        ? ''
        : '?projectId=${Uri.encodeQueryComponent(projectId)}';
    return _asMapList(
      await _send('GET', '/api/v1/notification-silences$query'),
    );
  }

  Future<Map<String, dynamic>> createNotificationSilence(
    Map<String, dynamic> definition,
  ) async =>
      (await _send('POST', '/api/v1/notification-silences', body: definition))
          as Map<String, dynamic>;

  Future<void> cancelNotificationSilence(String id) async {
    await _send(
      'DELETE',
      '/api/v1/notification-silences/${Uri.encodeComponent(id)}',
    );
  }

  Future<dynamic> _send(
    String method,
    String path, {
    Map<String, dynamic>? body,
  }) async {
    final uri = _baseUri.resolve(path);
    final headers = <String, String>{'Accept': 'application/json'};
    if (body != null) headers['Content-Type'] = 'application/json';
    final request = http.Request(method, uri)..headers.addAll(headers);
    if (body != null) request.body = jsonEncode(body);
    try {
      final streamed = await _client
          .send(request)
          .timeout(const Duration(seconds: 20));
      final response = await http.Response.fromStream(streamed);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        dynamic decoded;
        if (response.body.isNotEmpty) {
          try {
            decoded = jsonDecode(response.body);
          } catch (_) {
            decoded = null;
          }
        }
        final message = decoded is Map && decoded['message'] is String
            ? decoded['message'] as String
            : '请求失败（${response.statusCode}）';
        throw ApiException(message, statusCode: response.statusCode);
      }
      if (response.statusCode == 204) return null;
      if (response.body.isEmpty) {
        throw ApiException(
          '服务返回空响应（HTTP ${response.statusCode}）',
          statusCode: response.statusCode,
        );
      }
      final decoded = jsonDecode(response.body);
      if (decoded == null) {
        throw ApiException(
          '服务返回空 JSON 响应（HTTP ${response.statusCode}）',
          statusCode: response.statusCode,
        );
      }
      return decoded;
    } on ApiException {
      rethrow;
    } catch (error) {
      throw ApiException('无法连接 Geek Monitor：$error');
    }
  }

  List<Map<String, dynamic>> _asMapList(dynamic value) =>
      (value as List<dynamic>)
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();

  void close() => _client.close();
}
