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
