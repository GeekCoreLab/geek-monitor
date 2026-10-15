import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/api_client.dart';
import 'package:geek_monitor_admin/main.dart';

void main() {
  testWidgets('development setup submits the documented test credentials', (
    tester,
  ) async {
    final api = _SetupApi();
    await tester.pumpWidget(GeekMonitorApp(api: api));
    await tester.pumpAndSettle();

    expect(find.text('开始监控你的服务'), findsOneWidget);
    final fields = tester
        .widgetList<TextFormField>(find.byType(TextFormField))
        .toList();
    expect(fields, hasLength(4));
    expect(fields[0].controller!.text, 'admin@biliwind.com');
    expect(fields[1].controller!.text, 'admin@biliwind.com');
    expect(fields[2].controller!.text, 'admin@biliwind.com');

    final submit = find.text('创建管理员并进入控制台');
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();

    expect(api.submittedEmail, 'admin@biliwind.com');
    expect(api.submittedPassword, 'admin@biliwind.com');
    expect(api.submittedProjectName, '默认项目');
  });

  testWidgets('shows an actionable error when login returns an empty session', (
    tester,
  ) async {
    final api = _EmptyLoginSessionApi();
    await tester.pumpWidget(GeekMonitorApp(api: api));
    await tester.pumpAndSettle();

    expect(find.text('欢迎回来'), findsOneWidget);
    await tester.tap(find.text('登录控制台'));
    await tester.pumpAndSettle();

    expect(find.text('服务返回的会话信息不完整，请更新服务后重试。'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

class _SetupApi extends ApiClient {
  String? submittedEmail;
  String? submittedPassword;
  String? submittedProjectName;

  @override
  Future<Map<String, dynamic>> getInstallStatus() async => {
    'initialized': false,
  };

  @override
  Future<Map<String, dynamic>> setup({
    required String email,
    required String password,
    required String projectName,
  }) async {
    submittedEmail = email;
    submittedPassword = password;
    submittedProjectName = projectName;
    throw const ApiException('test submission captured');
  }
}

class _EmptyLoginSessionApi extends ApiClient {
  @override
  Future<Map<String, dynamic>> getInstallStatus() async => {
    'initialized': true,
  };

  @override
  Future<Map<String, dynamic>> me() async => throw const ApiException(
    '请先登录',
    statusCode: 401,
  );

  @override
  Future<String?> lastEmail() async => null;

  @override
  Future<void> saveSession(Map<String, dynamic> session) async {}

  @override
  Future<Map<String, dynamic>> login({
    required String email,
    required String password,
  }) async => {};
}
