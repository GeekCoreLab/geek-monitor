import 'dart:convert';

import 'package:http/browser_client.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class ApiException implements Exception {
  const ApiException(this.message, {this.statusCode});

  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}

class ApiClient {
  ApiClient();

  static const _emailKey = 'geek-monitor.last-email';
  static const _configuredBaseUrl = String.fromEnvironment('API_BASE_URL');

  final BrowserClient _client = BrowserClient()..withCredentials = true;

  Uri get _baseUri => Uri.parse(
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

  Future<void> deleteResource(String id) async {
    await _send('DELETE', '/api/v1/resources/${Uri.encodeComponent(id)}');
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

  Future<List<Map<String, dynamic>>> incidents({String? status}) async {
    final query = status == null
        ? ''
        : '?status=${Uri.encodeQueryComponent(status)}';
    return _asMapList(await _send('GET', '/api/v1/incidents$query'));
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
      if (response.statusCode == 204 || response.body.isEmpty) return null;
      final decoded = jsonDecode(response.body);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        final message = decoded is Map && decoded['message'] is String
            ? decoded['message'] as String
            : '请求失败（${response.statusCode}）';
        throw ApiException(message, statusCode: response.statusCode);
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
