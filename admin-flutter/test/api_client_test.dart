import 'package:flutter_test/flutter_test.dart';
import 'package:geek_monitor_admin/api_client.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test(
    'empty HTTP errors keep their status instead of becoming null casts',
    () async {
      final api = ApiClient(
        client: MockClient((_) async => http.Response('', 403)),
        baseUri: Uri.parse('http://localhost'),
      );
      addTearDown(api.close);

      await expectLater(
        api.login(email: 'admin@example.test', password: 'password'),
        throwsA(
          isA<ApiException>()
              .having((error) => error.statusCode, 'statusCode', 403)
              .having((error) => error.message, 'message', '请求失败（403）'),
        ),
      );
    },
  );

  test('empty successful responses explain the unexpected HTTP body', () async {
    final api = ApiClient(
      client: MockClient((_) async => http.Response('', 200)),
      baseUri: Uri.parse('http://localhost'),
    );
    addTearDown(api.close);

    await expectLater(
      api.login(email: 'admin@example.test', password: 'password'),
      throwsA(
        isA<ApiException>()
            .having((error) => error.statusCode, 'statusCode', 200)
            .having((error) => error.message, 'message', '服务返回空响应（HTTP 200）'),
      ),
    );
  });
}
