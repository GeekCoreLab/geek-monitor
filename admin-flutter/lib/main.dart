import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'api_client.dart';

void main() {
  runApp(GeekMonitorApp());
}

const _ink = Color(0xFF182433);
const _muted = Color(0xFF6F7C8C);
const _blue = Color(0xFF315CA8);
const _green = Color(0xFF16805D);
const _amber = Color(0xFFAD6800);
const _red = Color(0xFFB42318);
const _canvas = Color(0xFFF4F6F9);
const _showDevCredentials =
    kDebugMode || bool.fromEnvironment('GEEK_MONITOR_DEV_CREDENTIALS');
const _agentInstallScriptUrl =
    'https://github.com/skyhhjmk/geek-monitor/releases/latest/download/install-agent.sh';

class GeekMonitorApp extends StatefulWidget {
  GeekMonitorApp({super.key, ApiClient? api}) : api = api ?? ApiClient();

  final ApiClient api;

  @override
  State<GeekMonitorApp> createState() => _GeekMonitorAppState();
}

class _GeekMonitorAppState extends State<GeekMonitorApp> {
  ApiClient get _api => widget.api;
  _GateState _gate = _GateState.loading;
  Map<String, dynamic>? _user;
  String? _startupError;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    try {
      final status = await _api.getInstallStatus();
      if (status['initialized'] != true) {
        setState(() => _gate = _GateState.setup);
        return;
      }
      try {
        _user = await _api.me();
        setState(() => _gate = _GateState.dashboard);
      } on ApiException {
        setState(() => _gate = _GateState.login);
      }
    } catch (error) {
      setState(() {
        _startupError = error.toString();
        _gate = _GateState.unavailable;
      });
    }
  }

  void _authenticated(Map<String, dynamic> session) {
    setState(() {
      _user = Map<String, dynamic>.from(session['user'] as Map);
      _gate = _GateState.dashboard;
      _startupError = null;
    });
  }

  Future<void> _logout() async {
    try {
      await _api.logout();
    } on ApiException catch (error) {
      if (error.statusCode != 401) rethrow;
    }
    if (!mounted) return;
    setState(() => _gate = _GateState.login);
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: _blue,
      brightness: Brightness.light,
      surface: Colors.white,
    );
    return MaterialApp(
      title: 'Geek Monitor',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: _canvas,
        fontFamily: 'Roboto',
        fontFamilyFallback: const ['NotoSansSC'],
        textTheme: ThemeData.light().textTheme.apply(
          bodyColor: _ink,
          displayColor: _ink,
        ),
        inputDecorationTheme: InputDecorationTheme(
          filled: true,
          fillColor: const Color(0xFFF8F9FB),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFFE1E6EC)),
          ),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: Color(0xFFE1E6EC)),
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(12),
            borderSide: const BorderSide(color: _blue, width: 1.5),
          ),
          contentPadding: const EdgeInsets.symmetric(
            horizontal: 16,
            vertical: 15,
          ),
        ),
        cardTheme: CardThemeData(
          color: Colors.white,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
            side: const BorderSide(color: Color(0xFFE7EBF0)),
          ),
        ),
      ),
      home: switch (_gate) {
        _GateState.loading => const _LoadingPage(),
        _GateState.setup => _InstallPage(
          api: _api,
          onAuthenticated: _authenticated,
        ),
        _GateState.login => _LoginPage(
          api: _api,
          onAuthenticated: _authenticated,
        ),
        _GateState.dashboard => _DashboardShell(
          api: _api,
          user: _user ?? const {},
          onLogout: _logout,
        ),
        _GateState.unavailable => _UnavailablePage(
          message: _startupError ?? '请确认服务已启动。',
          onRetry: _restore,
        ),
      },
    );
  }
}

enum _GateState { loading, setup, login, dashboard, unavailable }

class _LoadingPage extends StatelessWidget {
  const _LoadingPage();

  @override
  Widget build(BuildContext context) =>
      const Scaffold(body: Center(child: CircularProgressIndicator()));
}

class _InstallPage extends StatefulWidget {
  const _InstallPage({required this.api, required this.onAuthenticated});

  final ApiClient api;
  final ValueChanged<Map<String, dynamic>> onAuthenticated;

  @override
  State<_InstallPage> createState() => _InstallPageState();
}

class _InstallPageState extends State<_InstallPage> {
  final _formKey = GlobalKey<FormState>();
  late final _email = TextEditingController(
    text: _showDevCredentials ? 'admin@biliwind.com' : '',
  );
  late final _password = TextEditingController(
    text: _showDevCredentials ? 'admin@biliwind.com' : '',
  );
  final _confirmPassword = TextEditingController(
    text: _showDevCredentials ? 'admin@biliwind.com' : '',
  );
  final _project = TextEditingController(text: '默认项目');
  bool _showPassword = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    _confirmPassword.dispose();
    _project.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final session = await widget.api.setup(
        email: _email.text.trim(),
        password: _password.text,
        projectName: _project.text.trim(),
      );
      await widget.api.saveSession(session);
      if (mounted) widget.onAuthenticated(session);
    } catch (error) {
      setState(
        () => _error = error is ApiException ? error.message : error.toString(),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => _AuthLayout(
    eyebrow: 'WELCOME TO GEEK MONITOR',
    title: '开始监控你的服务',
    subtitle: '创建管理员账号和第一个项目。系统初始化只需完成一次。',
    child: Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _FormLabel('管理员邮箱'),
          TextFormField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            autofillHints: const [AutofillHints.newUsername],
            decoration: const InputDecoration(
              hintText: 'name@company.com',
              prefixIcon: Icon(Icons.mail_outline),
            ),
            validator: (value) =>
                value == null ||
                    !RegExp(
                      r'^[^\s@]+@[^\s@]+\.[^\s@]+$',
                    ).hasMatch(value.trim())
                ? '请输入有效的邮箱地址'
                : null,
          ),
          const SizedBox(height: 18),
          const _FormLabel('设置密码'),
          TextFormField(
            controller: _password,
            obscureText: !_showPassword,
            autofillHints: const [AutofillHints.newPassword],
            decoration: InputDecoration(
              hintText: '至少 12 个字符',
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                onPressed: () => setState(() => _showPassword = !_showPassword),
                icon: Icon(
                  _showPassword
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                ),
              ),
            ),
            validator: (value) =>
                value == null || value.length < 12 ? '密码至少需要 12 个字符' : null,
          ),
          const SizedBox(height: 18),
          const _FormLabel('再次输入密码'),
          TextFormField(
            controller: _confirmPassword,
            obscureText: !_showPassword,
            decoration: const InputDecoration(
              hintText: '确认管理员密码',
              prefixIcon: Icon(Icons.verified_user_outlined),
            ),
            validator: (value) => value != _password.text ? '两次输入的密码不一致' : null,
            onFieldSubmitted: (_) => _submit(),
          ),
          const SizedBox(height: 18),
          const _FormLabel('默认项目名称'),
          TextFormField(
            controller: _project,
            decoration: const InputDecoration(
              hintText: '例如：我的服务',
              prefixIcon: Icon(Icons.folder_outlined),
            ),
            validator: (value) =>
                value == null || value.trim().isEmpty ? '请输入项目名称' : null,
          ),
          if (_showDevCredentials) ...[
            const SizedBox(height: 14),
            const _DevCredentialHint(),
          ],
          if (_error != null) ...[
            const SizedBox(height: 14),
            _InlineError(_error!),
          ],
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _busy ? null : _submit,
            icon: _busy
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.arrow_forward_rounded),
            label: Text(_busy ? '正在初始化…' : '创建管理员并进入控制台'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              backgroundColor: _blue,
            ),
          ),
          const SizedBox(height: 14),
          const Text(
            '密码经过安全哈希保存。初始化完成后，这个页面将关闭。',
            textAlign: TextAlign.center,
            style: TextStyle(color: _muted, fontSize: 12, height: 1.5),
          ),
        ],
      ),
    ),
  );
}

class _LoginPage extends StatefulWidget {
  const _LoginPage({required this.api, required this.onAuthenticated});

  final ApiClient api;
  final ValueChanged<Map<String, dynamic>> onAuthenticated;

  @override
  State<_LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends State<_LoginPage> {
  final _formKey = GlobalKey<FormState>();
  late final _email = TextEditingController(
    text: _showDevCredentials ? 'admin@biliwind.com' : '',
  );
  late final _password = TextEditingController(
    text: _showDevCredentials ? 'admin@biliwind.com' : '',
  );
  bool _busy = false;
  bool _showPassword = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _fillLastEmail();
  }

  Future<void> _fillLastEmail() async {
    final email = await widget.api.lastEmail();
    if (mounted && email != null && email.isNotEmpty && !_showDevCredentials) {
      _email.text = email;
    }
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final session = await widget.api.login(
        email: _email.text.trim(),
        password: _password.text,
      );
      await widget.api.saveSession(session);
      if (mounted) widget.onAuthenticated(session);
    } catch (error) {
      setState(
        () => _error = error is ApiException ? error.message : error.toString(),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => _AuthLayout(
    eyebrow: 'YOUR OPERATIONS, IN ONE PLACE',
    title: '欢迎回来',
    subtitle: '登录后查看服务状态、项目和最近发生的事件。',
    child: Form(
      key: _formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _FormLabel('管理员邮箱'),
          TextFormField(
            controller: _email,
            keyboardType: TextInputType.emailAddress,
            autofillHints: const [AutofillHints.username],
            decoration: const InputDecoration(
              hintText: 'name@company.com',
              prefixIcon: Icon(Icons.mail_outline),
            ),
            validator: (value) =>
                value == null || value.trim().isEmpty ? '请输入邮箱' : null,
          ),
          const SizedBox(height: 18),
          const _FormLabel('密码'),
          TextFormField(
            controller: _password,
            obscureText: !_showPassword,
            autofillHints: const [AutofillHints.password],
            decoration: InputDecoration(
              hintText: '请输入密码',
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                onPressed: () => setState(() => _showPassword = !_showPassword),
                icon: Icon(
                  _showPassword
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                ),
              ),
            ),
            validator: (value) =>
                value == null || value.isEmpty ? '请输入密码' : null,
            onFieldSubmitted: (_) => _submit(),
          ),
          if (_error != null) ...[
            const SizedBox(height: 14),
            _InlineError(_error!),
          ],
          const SizedBox(height: 24),
          FilledButton.icon(
            onPressed: _busy ? null : _submit,
            icon: _busy
                ? const SizedBox.square(
                    dimension: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(Icons.login_rounded),
            label: Text(_busy ? '正在登录…' : '登录控制台'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(52),
              backgroundColor: _blue,
            ),
          ),
          if (_showDevCredentials) ...[
            const SizedBox(height: 14),
            const _DevCredentialHint(),
          ],
        ],
      ),
    ),
  );
}

class _DevCredentialHint extends StatelessWidget {
  const _DevCredentialHint();

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: const Color(0xFFEAF0FC),
      borderRadius: BorderRadius.circular(10),
    ),
    child: const Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.science_outlined, color: _blue, size: 18),
        SizedBox(width: 9),
        Expanded(
          child: Text(
            '开发模式测试账号：用户名和密码均为 admin@biliwind.com',
            style: TextStyle(color: _blue, fontSize: 12, height: 1.45),
          ),
        ),
      ],
    ),
  );
}

class _AuthLayout extends StatelessWidget {
  const _AuthLayout({
    required this.eyebrow,
    required this.title,
    required this.subtitle,
    required this.child,
  });

  final String eyebrow;
  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: LayoutBuilder(
      builder: (context, constraints) {
        final wide = constraints.maxWidth >= 920;
        return Row(
          children: [
            if (wide) const Expanded(flex: 11, child: _BrandPanel()),
            Expanded(
              flex: wide ? 10 : 1,
              child: Center(
                child: SingleChildScrollView(
                  padding: EdgeInsets.symmetric(
                    horizontal: wide ? 72 : 24,
                    vertical: 36,
                  ),
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 480),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        if (!wide) ...[
                          const _BrandMark(),
                          const SizedBox(height: 44),
                        ],
                        Text(
                          eyebrow,
                          style: const TextStyle(
                            color: _blue,
                            fontSize: 11,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.4,
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          title,
                          style: const TextStyle(
                            color: _ink,
                            fontSize: 30,
                            height: 1.2,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 9),
                        Text(
                          subtitle,
                          style: const TextStyle(
                            color: _muted,
                            fontSize: 14,
                            height: 1.55,
                          ),
                        ),
                        const SizedBox(height: 30),
                        child,
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    ),
  );
}

class _BrandPanel extends StatelessWidget {
  const _BrandPanel();

  @override
  Widget build(BuildContext context) => Container(
    height: double.infinity,
    padding: const EdgeInsets.all(56),
    decoration: const BoxDecoration(
      gradient: LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [Color(0xFF173D76), Color(0xFF102746), Color(0xFF142034)],
      ),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const _BrandMark(inverse: true),
        const Spacer(),
        const Text(
          '看见服务状态。\n更快找到问题。',
          style: TextStyle(
            color: Colors.white,
            fontSize: 37,
            height: 1.3,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 18),
        const SizedBox(
          width: 390,
          child: Text(
            '把主机、网站、数据库和关键任务放进同一个清晰的运维视图。',
            style: TextStyle(
              color: Color(0xFFC5D3E4),
              fontSize: 15,
              height: 1.7,
            ),
          ),
        ),
        const SizedBox(height: 42),
        const Row(
          children: [
            _FeaturePill(icon: Icons.monitor_heart_outlined, label: '持续检查'),
            SizedBox(width: 10),
            _FeaturePill(icon: Icons.notifications_none_rounded, label: '及时发现'),
            SizedBox(width: 10),
            _FeaturePill(icon: Icons.fact_check_outlined, label: '事件跟进'),
          ],
        ),
        const Spacer(),
        const Text(
          'GEEK MONITOR  ·  SELF-HOSTED OBSERVABILITY',
          style: TextStyle(
            color: Color(0xFF8CA3BE),
            fontSize: 10,
            letterSpacing: 1.1,
          ),
        ),
      ],
    ),
  );
}

class _BrandMark extends StatelessWidget {
  const _BrandMark({this.inverse = false});

  final bool inverse;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 38,
        height: 38,
        decoration: BoxDecoration(
          color: inverse
              ? Colors.white.withValues(alpha: 0.12)
              : const Color(0xFFE7EEFA),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Icon(
          Icons.monitor_heart_rounded,
          color: inverse ? Colors.white : _blue,
          size: 22,
        ),
      ),
      const SizedBox(width: 11),
      Text(
        'Geek Monitor',
        style: TextStyle(
          color: inverse ? Colors.white : _ink,
          fontWeight: FontWeight.w700,
          fontSize: 16,
        ),
      ),
    ],
  );
}

class _FeaturePill extends StatelessWidget {
  const _FeaturePill({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
    decoration: BoxDecoration(
      color: Colors.white.withValues(alpha: 0.09),
      borderRadius: BorderRadius.circular(30),
      border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
    ),
    child: Row(
      children: [
        Icon(icon, size: 15, color: const Color(0xFFB9CBE0)),
        const SizedBox(width: 7),
        Text(
          label,
          style: const TextStyle(color: Color(0xFFE0E8F1), fontSize: 11),
        ),
      ],
    ),
  );
}

class _FormLabel extends StatelessWidget {
  const _FormLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 8),
    child: Text(
      text,
      style: const TextStyle(
        fontSize: 13,
        color: _ink,
        fontWeight: FontWeight.w600,
      ),
    ),
  );
}

class _InlineError extends StatelessWidget {
  const _InlineError(this.message);

  final String message;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(12),
    decoration: BoxDecoration(
      color: const Color(0xFFFFF0EE),
      borderRadius: BorderRadius.circular(10),
    ),
    child: Row(
      children: [
        const Icon(Icons.error_outline, color: Color(0xFFB44438), size: 18),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            message,
            style: const TextStyle(color: Color(0xFF9B392F), fontSize: 13),
          ),
        ),
      ],
    ),
  );
}

class _UnavailablePage extends StatelessWidget {
  const _UnavailablePage({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.cloud_off_outlined, size: 44, color: _blue),
              const SizedBox(height: 16),
              const Text(
                '暂时无法连接监控服务',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              Text(
                message,
                textAlign: TextAlign.center,
                style: const TextStyle(color: _muted, height: 1.5),
              ),
              const SizedBox(height: 20),
              FilledButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('重新连接'),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

enum _Section {
  overview,
  resources,
  monitors,
  incidents,
  notifications,
  projects,
  members,
  agents,
  settings,
}

class _DashboardShell extends StatefulWidget {
  const _DashboardShell({
    required this.api,
    required this.user,
    required this.onLogout,
  });

  final ApiClient api;
  final Map<String, dynamic> user;
  final Future<void> Function() onLogout;

  @override
  State<_DashboardShell> createState() => _DashboardShellState();
}

class _DashboardShellState extends State<_DashboardShell> {
  _Section _section = _Section.overview;
  List<Map<String, dynamic>> _projects = [];
  List<Map<String, dynamic>> _resources = [];
  List<Map<String, dynamic>> _monitors = [];
  List<Map<String, dynamic>> _incidents = [];
  List<Map<String, dynamic>> _agents = [];
  bool _loading = true;
  String? _loadError;
  StreamSubscription<StatusStreamEvent>? _statusSubscription;
  Timer? _statusRefreshTimer;

  @override
  void initState() {
    super.initState();
    _reload();
    _statusSubscription = widget.api.statusEvents().listen(
      (_) => _scheduleStatusRefresh(),
      onError: (Object error) {
        if (error is ApiException && error.statusCode == 401) {
          unawaited(widget.onLogout());
        }
      },
    );
  }

  @override
  void dispose() {
    _statusRefreshTimer?.cancel();
    _statusSubscription?.cancel();
    super.dispose();
  }

  void _scheduleStatusRefresh() {
    if (!mounted) return;
    _statusRefreshTimer?.cancel();
    _statusRefreshTimer = Timer(const Duration(milliseconds: 350), () {
      if (mounted) unawaited(_reload());
    });
  }

  Future<void> _reload() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final values = await Future.wait([
        widget.api.projects(),
        widget.api.resources(),
        widget.api.monitors(),
        widget.api.incidents(),
        widget.api.agents(),
      ]);
      setState(() {
        _projects = values[0];
        _resources = values[1];
        _monitors = values[2];
        _incidents = values[3];
        _agents = values[4];
      });
    } catch (error) {
      setState(
        () => _loadError = error is ApiException
            ? error.message
            : error.toString(),
      );
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createProject() async {
    final result = await showDialog<_ProjectDraft>(
      context: context,
      builder: (_) => const _ProjectDialog(),
    );
    if (result == null) return;
    try {
      await widget.api.createProject(
        name: result.name,
        description: result.description,
      );
      await _reload();
      if (mounted) _notice('项目已创建');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _createResource() async {
    if (_projects.isEmpty) {
      _notice('请先创建一个项目', isError: true);
      return;
    }
    final result = await showDialog<_ResourceDraft>(
      context: context,
      builder: (_) => _ResourceDialog(projects: _projects),
    );
    if (result == null) return;
    try {
      await widget.api.createResource(
        projectId: result.projectId,
        name: result.name,
        type: result.type,
        environment: result.environment,
        tags: result.tags,
        ownerEmail: result.ownerEmail,
      );
      await _reload();
      if (mounted) _notice('资源已添加');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _editMonitor([Map<String, dynamic>? current]) async {
    if (_resources.isEmpty) {
      _notice('请先添加一个监控资源', isError: true);
      return;
    }
    final draft = await showDialog<_MonitorDraft>(
      context: context,
      builder: (_) => _MonitorDialog(
        api: widget.api,
        resources: _resources,
        existing: current,
      ),
    );
    if (draft == null) return;
    try {
      if (current == null) {
        final created = await widget.api.createMonitor(draft.toJson());
        await _reload();
        final token = created['heartbeatToken'] as String?;
        if (draft.type == 'HEARTBEAT' && token != null && mounted) {
          await _showHeartbeatToken(
            monitorId: created['id'] as String,
            token: token,
          );
        } else if (mounted) {
          _notice('监控项已创建');
        }
      } else {
        await widget.api.updateMonitor(current['id'] as String, {
          ...draft.toJson(),
          'version': current['version'],
        });
        await _reload();
        if (mounted) _notice('监控项已更新');
      }
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _showHeartbeatToken({
    required String monitorId,
    required String token,
  }) => showDialog<void>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('保存心跳任务凭据'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('此凭据只显示这一次。请将它保存到任务运行环境的密钥配置中。'),
              const SizedBox(height: 12),
              SelectableText(
                token,
                style: const TextStyle(fontFamily: 'monospace'),
              ),
              const SizedBox(height: 16),
              const Text('上报示例（将令牌放入 HEARTBEAT_TOKEN 环境变量）：'),
              const SizedBox(height: 6),
              SelectableText(
                'curl -X POST "\$API_URL/api/v1/heartbeats/$monitorId" \\\n'
                '  -H "Authorization: Bearer \$HEARTBEAT_TOKEN" \\\n'
                '  -H "Content-Type: application/json" \\\n'
                '  -d \'{"runId":"<UUID>","runAt":"<UTC ISO-8601>","status":"SUCCESS","summary":"completed"}\'',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 11),
              ),
              const SizedBox(height: 10),
              const Text(
                '每次真实运行使用新的 UUID；网络重试应沿用同一个 runId。不要在摘要中放入密码或令牌。',
                style: TextStyle(color: _muted, fontSize: 11, height: 1.4),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('关闭'),
        ),
        FilledButton.icon(
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: token));
            if (dialogContext.mounted) Navigator.pop(dialogContext);
            if (mounted) _notice('心跳任务凭据已复制，请安全保存');
          },
          icon: const Icon(Icons.copy_rounded),
          label: const Text('复制凭据并关闭'),
        ),
      ],
    ),
  );

  Future<void> _rotateHeartbeatToken(Map<String, dynamic> monitor) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('轮换心跳任务凭据？'),
        content: const Text('当前凭据会立即失效。请同时更新任务运行环境中的密钥。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('轮换'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      final rotated = await widget.api.rotateHeartbeatToken(
        monitor['id'] as String,
      );
      if (mounted) {
        await _showHeartbeatToken(
          monitorId: rotated['monitorId'] as String,
          token: rotated['token'] as String,
        );
      }
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _deleteMonitor(Map<String, dynamic> monitor) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除监控项？'),
        content: Text('“${monitor['name']}”将停止调度，历史记录会保留。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.deleteMonitor(monitor['id'] as String);
      await _reload();
      if (mounted) _notice('监控项已删除');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _testMonitor(Map<String, dynamic> monitor) async {
    try {
      final result = await widget.api.testMonitor(monitor['id'] as String);
      if (!mounted) return;
      showDialog<void>(
        context: context,
        builder: (context) => _CheckResultDialog(
          title:
              monitor['name'] as String? ??
              '${_monitorTypeName(monitor['type'] as String? ?? 'HTTP')} 检查',
          result: result,
        ),
      );
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _acknowledgeIncident(Map<String, dynamic> incident) async {
    final note = await showDialog<String>(
      context: context,
      builder: (_) => const _IncidentNoteDialog(),
    );
    if (note == null) return;
    try {
      await widget.api.acknowledgeIncident(incident['id'] as String, note);
      await _reload();
      if (mounted) _notice('故障已确认');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _showIncidentEvents(Map<String, dynamic> incident) async {
    try {
      final events = await widget.api.incidentEvents(incident['id'] as String);
      if (!mounted) return;
      showDialog<void>(
        context: context,
        builder: (_) =>
            _IncidentEventsDialog(incident: incident, events: events),
      );
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _deleteResource(Map<String, dynamic> resource) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('移除监控资源？'),
        content: Text('“${resource['name']}”将从资源列表中移除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.deleteResource(resource['id'] as String);
      await _reload();
      if (mounted) _notice('资源已移除');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _toggleResourcePause(Map<String, dynamic> resource) async {
    final paused = resource['paused'] == true;
    try {
      await widget.api.setResourcePaused(
        resource['id'] as String,
        paused: !paused,
      );
      await _reload();
      if (mounted) _notice(paused ? '资源监控已恢复' : '资源监控已暂停');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _showResourceMetrics(Map<String, dynamic> resource) async {
    try {
      final result = await widget.api.resourceMetrics(resource['id'] as String);
      if (!mounted) return;
      showDialog<void>(
        context: context,
        builder: (_) =>
            _MetricsDialog(api: widget.api, resource: resource, result: result),
      );
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _createAgentEnrollment() async {
    if (widget.user['role'] != 'ADMIN') {
      _notice('只有管理员可以签发 Agent 注册令牌', isError: true);
      return;
    }
    final hosts = _resources
        .where((resource) => resource['type'] == 'HOST')
        .toList();
    if (hosts.isEmpty) {
      _notice('请先添加一个主机类型资源', isError: true);
      return;
    }
    final resourceId = await showDialog<String>(
      context: context,
      builder: (_) => _AgentResourceDialog(resources: hosts),
    );
    if (resourceId == null) return;
    try {
      final enrollment = await widget.api.createAgentEnrollment(resourceId);
      if (!mounted) return;
      await _reload();
      if (!mounted) return;
      final token = enrollment['token'] as String;
      final resourceName =
          hosts.firstWhere((item) => item['id'] == resourceId)['name']
              as String? ??
          '主机';
      showDialog<void>(
        context: context,
        builder: (_) => _AgentEnrollmentDialog(
          token: token,
          resourceName: resourceName,
          expiresAt: enrollment['expiresAt'] as String?,
        ),
      );
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  Future<void> _revokeAgent(Map<String, dynamic> agent) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('吊销 Agent 凭据？'),
        content: Text('“${agent['hostname']}”将无法继续拉取配置或提交指标。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('吊销'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.revokeAgent(agent['id'] as String);
      await _reload();
      if (mounted) _notice('Agent 凭据已吊销');
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }

  void _notice(String message, {bool isError = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: isError
            ? const Color(0xFF963F38)
            : const Color(0xFF263D5A),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final wide = constraints.maxWidth >= 940;
      return Scaffold(
        body: Row(
          children: [
            if (wide)
              _SideNavigation(
                section: _section,
                onSelect: (section) => setState(() => _section = section),
                onLogout: widget.onLogout,
              ),
            Expanded(
              child: Column(
                children: [
                  _TopBar(
                    user: widget.user,
                    onRefresh: _reload,
                    onLogout: widget.onLogout,
                  ),
                  Expanded(child: _content()),
                  if (!wide)
                    _BottomNavigation(
                      section: _section,
                      onSelect: (section) => setState(() => _section = section),
                    ),
                ],
              ),
            ),
          ],
        ),
      );
    },
  );

  Widget _content() {
    if (_loading && _projects.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_loadError != null && _projects.isEmpty) {
      return _WorkspaceError(message: _loadError!, onRetry: _reload);
    }
    return switch (_section) {
      _Section.overview => _OverviewPage(
        projects: _projects,
        resources: _resources,
        monitors: _monitors,
        incidents: _incidents,
        onNavigate: (value) => setState(() => _section = value),
        onCreateResource: _createResource,
        onCreateMonitor: () => _editMonitor(),
      ),
      _Section.resources => _ResourcesPage(
        resources: _resources,
        projects: _projects,
        loading: _loading,
        onCreate: _createResource,
        onDelete: _deleteResource,
        onMetrics: _showResourceMetrics,
        onTogglePause: _toggleResourcePause,
      ),
      _Section.monitors => _MonitorsPage(
        monitors: _monitors,
        resources: _resources,
        loading: _loading,
        onCreate: () => _editMonitor(),
        onEdit: _editMonitor,
        onDelete: _deleteMonitor,
        onTest: _testMonitor,
        onHistory: _showMonitorHistory,
        onRotateHeartbeatToken: widget.user['role'] == 'ADMIN'
            ? _rotateHeartbeatToken
            : null,
      ),
      _Section.incidents => _IncidentsPage(
        incidents: _incidents,
        loading: _loading,
        onAcknowledge: _acknowledgeIncident,
        onEvents: _showIncidentEvents,
      ),
      _Section.notifications => _NotificationsPage(
        api: widget.api,
        projects: _projects,
        resources: _resources,
        readOnly: widget.user['role'] == 'READ_ONLY',
        onNotice: _notice,
      ),
      _Section.projects => _ProjectsPage(
        projects: _projects,
        canCreate: widget.user['role'] == 'ADMIN',
        onCreate: _createProject,
      ),
      _Section.members => _ProjectMembersPage(
        api: widget.api,
        projects: _projects,
        userId: widget.user['id'] as String? ?? '',
        canManage: widget.user['role'] == 'ADMIN',
      ),
      _Section.agents => _AgentsPage(
        agents: _agents,
        loading: _loading,
        isAdmin: widget.user['role'] == 'ADMIN',
        onCreateEnrollment: _createAgentEnrollment,
        onRevoke: _revokeAgent,
      ),
      _Section.settings => SystemSettingsPage(
        api: widget.api,
        isAdmin: widget.user['role'] == 'ADMIN',
      ),
    };
  }

  Future<void> _showMonitorHistory(Map<String, dynamic> monitor) async {
    try {
      final monitorId = monitor['id'] as String;
      final data = await Future.wait<Object>([
        widget.api.checkResults(monitorId),
        widget.api.monitorAvailability(monitorId),
      ]);
      if (!mounted) return;
      showDialog<void>(
        context: context,
        builder: (_) => _CheckHistoryDialog(
          api: widget.api,
          monitor: monitor,
          results: data[0] as List<Map<String, dynamic>>,
          availability: data[1] as Map<String, dynamic>,
        ),
      );
    } catch (error) {
      if (mounted) _notice(error.toString(), isError: true);
    }
  }
}

class _SideNavigation extends StatelessWidget {
  const _SideNavigation({
    required this.section,
    required this.onSelect,
    required this.onLogout,
  });

  final _Section section;
  final ValueChanged<_Section> onSelect;
  final Future<void> Function() onLogout;

  @override
  Widget build(BuildContext context) => Container(
    width: 250,
    color: const Color(0xFF19283B),
    padding: const EdgeInsets.fromLTRB(18, 25, 18, 18),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 8),
          child: _BrandMark(inverse: true),
        ),
        const SizedBox(height: 42),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 10),
          child: Text(
            'WORKSPACE',
            style: TextStyle(
              color: Color(0xFF8EA1B7),
              fontSize: 10,
              letterSpacing: 1.2,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
        const SizedBox(height: 12),
        _NavItem(
          icon: Icons.space_dashboard_outlined,
          label: '总览',
          selected: section == _Section.overview,
          onTap: () => onSelect(_Section.overview),
        ),
        _NavItem(
          icon: Icons.dns_outlined,
          label: '监控资源',
          selected: section == _Section.resources,
          onTap: () => onSelect(_Section.resources),
        ),
        _NavItem(
          icon: Icons.monitor_heart_outlined,
          label: '监控配置',
          selected: section == _Section.monitors,
          onTap: () => onSelect(_Section.monitors),
        ),
        _NavItem(
          icon: Icons.warning_amber_rounded,
          label: '故障中心',
          selected: section == _Section.incidents,
          onTap: () => onSelect(_Section.incidents),
        ),
        _NavItem(
          icon: Icons.notifications_outlined,
          label: '通知渠道',
          selected: section == _Section.notifications,
          onTap: () => onSelect(_Section.notifications),
        ),
        _NavItem(
          icon: Icons.folder_outlined,
          label: '项目管理',
          selected: section == _Section.projects,
          onTap: () => onSelect(_Section.projects),
        ),
        _NavItem(
          icon: Icons.group_outlined,
          label: '成员与角色',
          selected: section == _Section.members,
          onTap: () => onSelect(_Section.members),
        ),
        _NavItem(
          icon: Icons.memory_outlined,
          label: 'Agent 节点',
          selected: section == _Section.agents,
          onTap: () => onSelect(_Section.agents),
        ),
        _NavItem(
          icon: Icons.settings_outlined,
          label: '系统设置与审计',
          selected: section == _Section.settings,
          onTap: () => onSelect(_Section.settings),
        ),
        const Spacer(),
        Container(
          padding: const EdgeInsets.all(13),
          decoration: BoxDecoration(
            color: const Color(0xFF23364D),
            borderRadius: BorderRadius.circular(13),
          ),
          child: const Row(
            children: [
              Icon(
                Icons.check_circle_outline,
                size: 18,
                color: Color(0xFF76C49D),
              ),
              SizedBox(width: 9),
              Expanded(
                child: Text(
                  '平台连接正常',
                  style: TextStyle(color: Color(0xFFD4DFEA), fontSize: 12),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        _NavItem(
          icon: Icons.logout_rounded,
          label: '退出登录',
          selected: false,
          onTap: onLogout,
        ),
        const SizedBox(height: 8),
        const Padding(
          padding: EdgeInsets.symmetric(horizontal: 10),
          child: Text(
            'GEEK MONITOR  ·  1.0',
            style: TextStyle(color: Color(0xFF778CA3), fontSize: 10),
          ),
        ),
      ],
    ),
  );
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 5),
    child: Material(
      color: selected ? const Color(0xFF2E4A6B) : Colors.transparent,
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 12),
          child: Row(
            children: [
              Icon(
                icon,
                size: 18,
                color: selected ? Colors.white : const Color(0xFFACBBCB),
              ),
              const SizedBox(width: 11),
              Text(
                label,
                style: TextStyle(
                  color: selected ? Colors.white : const Color(0xFFCCD6E1),
                  fontSize: 13,
                  fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.user,
    required this.onRefresh,
    required this.onLogout,
  });

  final Map<String, dynamic> user;
  final Future<void> Function() onRefresh;
  final Future<void> Function() onLogout;

  @override
  Widget build(BuildContext context) => Container(
    height: 78,
    padding: const EdgeInsets.symmetric(horizontal: 28),
    decoration: const BoxDecoration(
      color: Colors.white,
      border: Border(bottom: BorderSide(color: Color(0xFFE7EBF0))),
    ),
    child: Row(
      children: [
        const Expanded(
          child: Text(
            '运维控制台',
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: _ink,
            ),
          ),
        ),
        IconButton(
          tooltip: '刷新数据',
          onPressed: onRefresh,
          icon: const Icon(Icons.refresh_rounded, color: _muted),
        ),
        const SizedBox(width: 8),
        Container(
          width: 34,
          height: 34,
          decoration: BoxDecoration(
            color: const Color(0xFFE8EEF8),
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(
            Icons.person_outline_rounded,
            color: _blue,
            size: 19,
          ),
        ),
        const SizedBox(width: 10),
        Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              user['email'] as String? ?? '管理员',
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: _ink,
              ),
            ),
            const SizedBox(height: 2),
            Text(switch (user['role']) {
              'ADMIN' => '系统管理员',
              'READ_ONLY' => '只读成员',
              _ => '运维人员',
            }, style: const TextStyle(fontSize: 10, color: _muted)),
          ],
        ),
        if (MediaQuery.sizeOf(context).width < 940) ...[
          const SizedBox(width: 12),
          IconButton(
            tooltip: '退出登录',
            onPressed: onLogout,
            icon: const Icon(Icons.logout_rounded, color: _muted),
          ),
        ],
      ],
    ),
  );
}

class _BottomNavigation extends StatelessWidget {
  const _BottomNavigation({required this.section, required this.onSelect});

  final _Section section;
  final ValueChanged<_Section> onSelect;

  static const _primarySections = [
    _Section.overview,
    _Section.resources,
    _Section.monitors,
    _Section.incidents,
    _Section.notifications,
  ];

  static const _moreSections = [
    (_Section.projects, '项目管理', Icons.folder_outlined),
    (_Section.members, '成员与角色', Icons.group_outlined),
    (_Section.agents, 'Agent 节点', Icons.memory_outlined),
    (_Section.settings, '系统设置与审计', Icons.settings_outlined),
  ];

  void _showMore(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 18, 20, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '更多功能',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
            ),
            for (final item in _moreSections)
              ListTile(
                leading: Icon(item.$3),
                title: Text(item.$2),
                selected: section == item.$1,
                onTap: () {
                  Navigator.pop(sheetContext);
                  onSelect(item.$1);
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final primaryIndex = _primarySections.indexOf(section);
    return NavigationBar(
      selectedIndex: primaryIndex < 0 ? _primarySections.length : primaryIndex,
      labelBehavior: NavigationDestinationLabelBehavior.onlyShowSelected,
      onDestinationSelected: (index) {
        if (index == _primarySections.length) {
          _showMore(context);
        } else {
          onSelect(_primarySections[index]);
        }
      },
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.space_dashboard_outlined),
          label: '总览',
        ),
        NavigationDestination(icon: Icon(Icons.dns_outlined), label: '资源'),
        NavigationDestination(
          icon: Icon(Icons.monitor_heart_outlined),
          label: '检查',
        ),
        NavigationDestination(
          icon: Icon(Icons.warning_amber_rounded),
          label: '故障',
        ),
        NavigationDestination(
          icon: Icon(Icons.notifications_outlined),
          label: '通知',
        ),
        NavigationDestination(
          icon: Icon(Icons.more_horiz_rounded),
          label: '更多',
        ),
      ],
    );
  }
}

class _OverviewPage extends StatelessWidget {
  const _OverviewPage({
    required this.projects,
    required this.resources,
    required this.monitors,
    required this.incidents,
    required this.onNavigate,
    required this.onCreateResource,
    required this.onCreateMonitor,
  });

  final List<Map<String, dynamic>> projects;
  final List<Map<String, dynamic>> resources;
  final List<Map<String, dynamic>> monitors;
  final List<Map<String, dynamic>> incidents;
  final ValueChanged<_Section> onNavigate;
  final VoidCallback onCreateResource;
  final VoidCallback onCreateMonitor;

  @override
  Widget build(BuildContext context) {
    final statuses = <String, int>{};
    for (final resource in resources) {
      final status = resource['healthStatus'] as String? ?? 'UNKNOWN';
      statuses[status] = (statuses[status] ?? 0) + 1;
    }
    final problems = (statuses['DOWN'] ?? 0) + (statuses['DEGRADED'] ?? 0);
    final activeIncidents = incidents
        .where((incident) => incident['status'] != 'RESOLVED')
        .length;
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PageHeading(
            eyebrow: 'OVERVIEW',
            title: '服务运行概览',
            subtitle: '查看项目和监控资源的当前状态。',
            actions: [
              OutlinedButton.icon(
                onPressed: () => onNavigate(_Section.projects),
                icon: const Icon(Icons.folder_open_outlined, size: 18),
                label: const Text('管理项目'),
              ),
              const SizedBox(width: 9),
              OutlinedButton.icon(
                onPressed: onCreateMonitor,
                icon: const Icon(Icons.monitor_heart_outlined, size: 18),
                label: const Text('配置检查'),
              ),
              const SizedBox(width: 9),
              FilledButton.icon(
                onPressed: onCreateResource,
                icon: const Icon(Icons.add_rounded, size: 19),
                label: const Text('添加资源'),
              ),
            ],
          ),
          LayoutBuilder(
            builder: (context, constraints) {
              final count = constraints.maxWidth > 1000
                  ? 4
                  : constraints.maxWidth > 650
                  ? 2
                  : 1;
              final width = (constraints.maxWidth - (count - 1) * 14) / count;
              return Wrap(
                spacing: 14,
                runSpacing: 14,
                children: [
                  SizedBox(
                    width: width,
                    child: _MetricCard(
                      label: '监控资源',
                      value: '${resources.length}',
                      detail: '分布在 ${projects.length} 个项目',
                      icon: Icons.dns_outlined,
                      color: _blue,
                    ),
                  ),
                  SizedBox(
                    width: width,
                    child: _MetricCard(
                      label: '主动检查',
                      value: '${monitors.length}',
                      detail: 'HTTP/TCP/DNS/TLS 检查配置数',
                      icon: Icons.monitor_heart_outlined,
                      color: _blue,
                    ),
                  ),
                  SizedBox(
                    width: width,
                    child: _MetricCard(
                      label: '活跃故障',
                      value: '$activeIncidents',
                      detail: '等待处理或已确认',
                      icon: Icons.warning_amber_rounded,
                      color: activeIncidents > 0
                          ? const Color(0xFFC8743E)
                          : const Color(0xFF2C8B66),
                    ),
                  ),
                  SizedBox(
                    width: width,
                    child: _MetricCard(
                      label: '运行正常',
                      value: '${statuses['UP'] ?? 0}',
                      detail: '最近一次状态为健康',
                      icon: Icons.check_circle_outline,
                      color: const Color(0xFF2C8B66),
                    ),
                  ),
                  SizedBox(
                    width: width,
                    child: _MetricCard(
                      label: '异常资源',
                      value: '$problems',
                      detail: '需要关注的服务',
                      icon: Icons.error_outline_rounded,
                      color: problems > 0 ? const Color(0xFFB44438) : _muted,
                    ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 22),
          LayoutBuilder(
            builder: (context, constraints) {
              if (constraints.maxWidth < 820) {
                return Column(
                  children: [
                    _ResourcePreview(
                      resources: resources,
                      onViewAll: () => onNavigate(_Section.resources),
                    ),
                    const SizedBox(height: 16),
                    _ProjectPreview(
                      projects: projects,
                      onManage: () => onNavigate(_Section.projects),
                    ),
                  ],
                );
              }
              return Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    flex: 7,
                    child: _ResourcePreview(
                      resources: resources,
                      onViewAll: () => onNavigate(_Section.resources),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    flex: 4,
                    child: _ProjectPreview(
                      projects: projects,
                      onManage: () => onNavigate(_Section.projects),
                    ),
                  ),
                ],
              );
            },
          ),
          const SizedBox(height: 18),
          _IncidentPreview(
            incidents: incidents,
            onOpen: () => onNavigate(_Section.incidents),
          ),
          const SizedBox(height: 20),
          const _GettingStartedCard(),
        ],
      ),
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.label,
    required this.value,
    required this.detail,
    required this.icon,
    required this.color,
  });

  final String label;
  final String value;
  final String detail;
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  style: const TextStyle(
                    color: _muted,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(11),
                ),
                child: Icon(icon, color: color, size: 18),
              ),
            ],
          ),
          const SizedBox(height: 17),
          Text(
            value,
            style: const TextStyle(
              color: _ink,
              fontSize: 27,
              fontWeight: FontWeight.w700,
              height: 1,
            ),
          ),
          const SizedBox(height: 8),
          Text(detail, style: const TextStyle(color: _muted, fontSize: 11)),
        ],
      ),
    ),
  );
}

class _ResourcePreview extends StatelessWidget {
  const _ResourcePreview({required this.resources, required this.onViewAll});

  final List<Map<String, dynamic>> resources;
  final VoidCallback onViewAll;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(18, 17, 18, 10),
      child: Column(
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '最近添加的资源',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                ),
              ),
              TextButton(onPressed: onViewAll, child: const Text('查看全部')),
            ],
          ),
          const Divider(height: 18),
          if (resources.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 32),
              child: _EmptyState(
                icon: Icons.dns_outlined,
                title: '还没有监控资源',
                subtitle: '添加网站、主机或数据库开始构建监控视图。',
              ),
            )
          else
            ...resources
                .take(5)
                .map((resource) => _ResourceRow(resource: resource)),
        ],
      ),
    ),
  );
}

class _ResourceRow extends StatelessWidget {
  const _ResourceRow({required this.resource});

  final Map<String, dynamic> resource;

  @override
  Widget build(BuildContext context) {
    final status = resource['healthStatus'] as String? ?? 'PENDING';
    final (color, label) = switch (status) {
      'UP' => (const Color(0xFF2C8B66), '正常'),
      'DOWN' => (const Color(0xFFB44438), '异常'),
      'DEGRADED' => (const Color(0xFFC8743E), '降级'),
      'UNKNOWN' => (const Color(0xFF7B8794), '未知'),
      _ => (const Color(0xFF7C6CAC), '待采集'),
    };
    final type = resource['type'] as String? ?? 'SERVICE';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 9),
      child: Row(
        children: [
          Container(
            width: 36,
            height: 36,
            decoration: BoxDecoration(
              color: const Color(0xFFF0F3F7),
              borderRadius: BorderRadius.circular(11),
            ),
            child: Icon(_resourceIcon(type), size: 18, color: _blue),
          ),
          const SizedBox(width: 11),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  resource['name'] as String? ?? '未命名资源',
                  style: const TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '${resource['projectName'] ?? '项目'}  ·  ${_resourceTypeName(type)}',
                  style: const TextStyle(fontSize: 10, color: _muted),
                ),
              ],
            ),
          ),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(20),
            ),
            child: Row(
              children: [
                Container(
                  width: 6,
                  height: 6,
                  decoration: BoxDecoration(
                    color: color,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: 6),
                Text(
                  label,
                  style: TextStyle(
                    color: color,
                    fontSize: 10,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ProjectPreview extends StatelessWidget {
  const _ProjectPreview({required this.projects, required this.onManage});

  final List<Map<String, dynamic>> projects;
  final VoidCallback onManage;

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(18, 17, 18, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '项目',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                ),
              ),
              IconButton(
                onPressed: onManage,
                tooltip: '项目管理',
                icon: const Icon(
                  Icons.arrow_forward_rounded,
                  color: _muted,
                  size: 18,
                ),
              ),
            ],
          ),
          const Divider(height: 18),
          if (projects.isEmpty)
            const Padding(
              padding: EdgeInsets.all(18),
              child: _EmptyState(
                icon: Icons.folder_outlined,
                title: '暂无项目',
                subtitle: '创建项目来组织监控资源。',
              ),
            )
          else
            ...projects
                .take(5)
                .map(
                  (project) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Row(
                      children: [
                        Container(
                          width: 32,
                          height: 32,
                          decoration: BoxDecoration(
                            color: const Color(0xFFF0F3F7),
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: const Icon(
                            Icons.folder_outlined,
                            size: 17,
                            color: _blue,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            project['name'] as String? ?? '未命名项目',
                            style: const TextStyle(
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                        const Text(
                          '项目',
                          style: TextStyle(fontSize: 10, color: _muted),
                        ),
                      ],
                    ),
                  ),
                ),
        ],
      ),
    ),
  );
}

class _GettingStartedCard extends StatelessWidget {
  const _GettingStartedCard();

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: const Color(0xFFEBF1FA),
      borderRadius: BorderRadius.circular(16),
      border: Border.all(color: const Color(0xFFDCE6F5)),
    ),
    child: Row(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: BorderRadius.circular(12),
          ),
          child: const Icon(Icons.lightbulb_outline_rounded, color: _blue),
        ),
        const SizedBox(width: 13),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '下一步：配置第一个监控项',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: _ink,
                ),
              ),
              SizedBox(height: 4),
              Text(
                '先整理项目和资源；随后可以为网站添加 HTTP 检查。',
                style: TextStyle(fontSize: 11, color: _muted, height: 1.4),
              ),
            ],
          ),
        ),
        const Icon(Icons.arrow_forward_ios_rounded, size: 15, color: _blue),
      ],
    ),
  );
}

class _ResourcesPage extends StatelessWidget {
  const _ResourcesPage({
    required this.resources,
    required this.projects,
    required this.loading,
    required this.onCreate,
    required this.onDelete,
    required this.onMetrics,
    required this.onTogglePause,
  });

  final List<Map<String, dynamic>> resources;
  final List<Map<String, dynamic>> projects;
  final bool loading;
  final VoidCallback onCreate;
  final ValueChanged<Map<String, dynamic>> onDelete;
  final ValueChanged<Map<String, dynamic>> onMetrics;
  final ValueChanged<Map<String, dynamic>> onTogglePause;

  @override
  Widget build(BuildContext context) => _PageScroll(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'MONITORED RESOURCES',
          title: '监控资源',
          subtitle: '按项目管理主机、网站、数据库和基础服务。',
          actions: [
            FilledButton.icon(
              onPressed: onCreate,
              icon: const Icon(Icons.add_rounded),
              label: const Text('添加资源'),
            ),
          ],
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
            child: Column(
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '全部资源',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    Text(
                      '${resources.length} 项',
                      style: const TextStyle(color: _muted, fontSize: 11),
                    ),
                  ],
                ),
                const Divider(height: 20),
                if (loading && resources.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(34),
                    child: CircularProgressIndicator(),
                  )
                else if (resources.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 34),
                    child: _EmptyState(
                      icon: Icons.dns_outlined,
                      title: '还没有监控资源',
                      subtitle: '添加一个网站、主机或数据库资源。',
                    ),
                  )
                else
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: DataTable(
                      columnSpacing: 26,
                      headingRowHeight: 42,
                      dataRowMinHeight: 58,
                      dataRowMaxHeight: 64,
                      columns: const [
                        DataColumn(label: Text('资源名称')),
                        DataColumn(label: Text('类型')),
                        DataColumn(label: Text('项目')),
                        DataColumn(label: Text('环境')),
                        DataColumn(label: Text('状态')),
                        DataColumn(label: Text('')),
                      ],
                      rows: resources
                          .map(
                            (resource) => DataRow(
                              cells: [
                                DataCell(
                                  Text(
                                    resource['name'] as String? ?? '',
                                    style: const TextStyle(
                                      fontWeight: FontWeight.w600,
                                      fontSize: 12,
                                    ),
                                  ),
                                ),
                                DataCell(
                                  _TypeTag(
                                    type:
                                        resource['type'] as String? ??
                                        'SERVICE',
                                  ),
                                ),
                                DataCell(
                                  Text(
                                    resource['projectName'] as String? ?? '',
                                    style: const TextStyle(fontSize: 12),
                                  ),
                                ),
                                DataCell(
                                  Text(
                                    resource['environment'] as String? ?? '',
                                    style: const TextStyle(
                                      fontSize: 12,
                                      color: _muted,
                                    ),
                                  ),
                                ),
                                DataCell(
                                  _StatusTag(
                                    status:
                                        resource['healthStatus'] as String? ??
                                        'PENDING',
                                  ),
                                ),
                                DataCell(
                                  Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      IconButton(
                                        tooltip: resource['paused'] == true
                                            ? '恢复监控'
                                            : '暂停监控',
                                        onPressed: () =>
                                            onTogglePause(resource),
                                        icon: Icon(
                                          resource['paused'] == true
                                              ? Icons
                                                    .play_circle_outline_rounded
                                              : Icons
                                                    .pause_circle_outline_rounded,
                                          color: resource['paused'] == true
                                              ? const Color(0xFFB47B32)
                                              : _muted,
                                          size: 18,
                                        ),
                                      ),
                                      if (const {
                                        'HOST',
                                        'DATABASE',
                                      }.contains(resource['type']))
                                        IconButton(
                                          tooltip:
                                              resource['type'] == 'DATABASE'
                                              ? '查看数据库指标曲线'
                                              : '查看主机指标曲线',
                                          onPressed: () => onMetrics(resource),
                                          icon: const Icon(
                                            Icons.show_chart_rounded,
                                            color: _blue,
                                            size: 18,
                                          ),
                                        ),
                                      IconButton(
                                        tooltip: '移除资源',
                                        onPressed: () => onDelete(resource),
                                        icon: const Icon(
                                          Icons.delete_outline_rounded,
                                          color: _muted,
                                          size: 18,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              ],
                            ),
                          )
                          .toList(),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class _AgentsPage extends StatelessWidget {
  const _AgentsPage({
    required this.agents,
    required this.loading,
    required this.isAdmin,
    required this.onCreateEnrollment,
    required this.onRevoke,
  });

  final List<Map<String, dynamic>> agents;
  final bool loading;
  final bool isAdmin;
  final VoidCallback onCreateEnrollment;
  final ValueChanged<Map<String, dynamic>> onRevoke;

  @override
  Widget build(BuildContext context) => _PageScroll(
    expand: true,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'LINUX AGENTS',
          title: 'Agent 节点',
          subtitle: '注册 Linux Agent，并生成从 GitHub Release 安装的命令。',
          actions: [
            if (isAdmin)
              FilledButton.icon(
                onPressed: onCreateEnrollment,
                icon: const Icon(Icons.add_rounded),
                label: const Text('注册主机 Agent'),
              ),
          ],
        ),
        Card(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 16),
            child: Column(
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        '节点列表',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    Text(
                      '${agents.length} 个',
                      style: const TextStyle(color: _muted, fontSize: 11),
                    ),
                  ],
                ),
                const Divider(height: 20),
                if (loading && agents.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(34),
                    child: CircularProgressIndicator(),
                  )
                else if (agents.isEmpty)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 34),
                    child: _EmptyState(
                      icon: Icons.memory_outlined,
                      title: '还没有 Agent 节点',
                      subtitle: '点击右上角“注册主机 Agent”。签发令牌后会显示可复制的 Linux 安装命令。',
                    ),
                  )
                else
                  LayoutBuilder(
                    builder: (context, constraints) => SizedBox(
                      width: constraints.maxWidth,
                      child: SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: ConstrainedBox(
                          constraints: BoxConstraints(
                            minWidth: constraints.maxWidth,
                          ),
                          child: DataTable(
                            columnSpacing: 22,
                            headingRowHeight: 42,
                            dataRowMinHeight: 58,
                            dataRowMaxHeight: 66,
                            columns: const [
                              DataColumn(label: Text('主机资源')),
                              DataColumn(label: Text('节点')),
                              DataColumn(label: Text('操作系统')),
                              DataColumn(label: Text('版本')),
                              DataColumn(label: Text('状态')),
                              DataColumn(label: Text('最后上报')),
                              DataColumn(label: Text('')),
                            ],
                            rows: agents.map((agent) {
                              final status =
                                  agent['status'] as String? ?? 'OFFLINE';
                              final lastActivity = switch (status) {
                                'WAITING_INSTALL' =>
                                  '令牌有效至 ${_dateTime(agent['expiresAt'] as String?)}',
                                'INSTALL_EXPIRED' => '令牌已过期，可重新签发',
                                _ => _dateTime(agent['lastSeenAt'] as String?),
                              };
                              return DataRow(
                                cells: [
                                  DataCell(
                                    Text(
                                      agent['resourceName'] as String? ?? '主机',
                                      style: const TextStyle(
                                        fontSize: 12,
                                        fontWeight: FontWeight.w600,
                                      ),
                                    ),
                                  ),
                                  DataCell(
                                    Text(
                                      agent['hostname'] as String? ?? '—',
                                      style: const TextStyle(fontSize: 12),
                                    ),
                                  ),
                                  DataCell(
                                    Text(
                                      agent['operatingSystem'] as String? ??
                                          '—',
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: _muted,
                                      ),
                                    ),
                                  ),
                                  DataCell(
                                    Text(
                                      agent['agentVersion'] as String? ?? '—',
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: _muted,
                                      ),
                                    ),
                                  ),
                                  DataCell(_AgentStatusTag(status: status)),
                                  DataCell(
                                    Text(
                                      lastActivity,
                                      style: const TextStyle(
                                        fontSize: 10,
                                        color: _muted,
                                      ),
                                    ),
                                  ),
                                  DataCell(
                                    isAdmin &&
                                            (status == 'ONLINE' ||
                                                status == 'OFFLINE')
                                        ? IconButton(
                                            tooltip: '吊销凭据',
                                            onPressed: () => onRevoke(agent),
                                            icon: const Icon(
                                              Icons.key_off_outlined,
                                              size: 18,
                                              color: _muted,
                                            ),
                                          )
                                        : const SizedBox(width: 32),
                                  ),
                                ],
                              );
                            }).toList(),
                          ),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class _AgentStatusTag extends StatelessWidget {
  const _AgentStatusTag({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      'ONLINE' => const Color(0xFF2C8B66),
      'REVOKED' => const Color(0xFF963F38),
      'INSTALL_EXPIRED' => const Color(0xFF963F38),
      'WAITING_INSTALL' => const Color(0xFFAA7514),
      _ => const Color(0xFF7B8794),
    };
    final label = switch (status) {
      'ONLINE' => '正常',
      'REVOKED' => '已吊销',
      'WAITING_INSTALL' => '等待安装',
      'INSTALL_EXPIRED' => '令牌已过期',
      _ => '离线',
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _AgentResourceDialog extends StatefulWidget {
  const _AgentResourceDialog({required this.resources});
  final List<Map<String, dynamic>> resources;

  @override
  State<_AgentResourceDialog> createState() => _AgentResourceDialogState();
}

class _AgentResourceDialogState extends State<_AgentResourceDialog> {
  late String _resourceId = widget.resources.first['id'] as String;

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('签发 Agent 注册令牌'),
    content: SizedBox(
      width: 440,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '选择要绑定的主机资源。继续后会签发 15 分钟内有效的一次性令牌，并生成可复制的 Linux 安装命令。',
            style: TextStyle(fontSize: 12, height: 1.5),
          ),
          const SizedBox(height: 14),
          DropdownButtonFormField<String>(
            initialValue: _resourceId,
            decoration: const InputDecoration(labelText: '主机资源'),
            items: widget.resources
                .map(
                  (resource) => DropdownMenuItem(
                    value: resource['id'] as String,
                    child: Text(
                      '${resource['name']} · ${resource['projectName']}',
                    ),
                  ),
                )
                .toList(),
            onChanged: (value) {
              if (value != null) setState(() => _resourceId = value);
            },
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () => Navigator.pop(context, _resourceId),
        child: const Text('签发令牌并生成命令'),
      ),
    ],
  );
}

class _AgentEnrollmentDialog extends StatefulWidget {
  const _AgentEnrollmentDialog({
    required this.token,
    required this.resourceName,
    required this.expiresAt,
  });

  final String token;
  final String resourceName;
  final String? expiresAt;

  @override
  State<_AgentEnrollmentDialog> createState() => _AgentEnrollmentDialogState();
}

class _AgentEnrollmentDialogState extends State<_AgentEnrollmentDialog> {
  late final TextEditingController _serverController = TextEditingController(
    text: Uri.base.origin,
  );

  String? get _serverUrl {
    final value = _serverController.text.trim();
    final uri = Uri.tryParse(value);
    final authorityIsSafe =
        uri != null &&
        (RegExp(r'^[A-Za-z0-9.-]+(:[0-9]{1,5})?$').hasMatch(uri.authority) ||
            RegExp(
              r'^\[[0-9A-Fa-f:]+\](:[0-9]{1,5})?$',
            ).hasMatch(uri.authority));
    final pathIsSafe =
        uri != null && RegExp(r'^(/[A-Za-z0-9._/-]*)?$').hasMatch(uri.path);
    if (uri == null ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        !authorityIsSafe ||
        !pathIsSafe ||
        (uri.scheme != 'https' &&
            !(uri.scheme == 'http' &&
                const {'localhost', '127.0.0.1', '::1'}.contains(uri.host)))) {
      return null;
    }
    final path = uri.path.replaceFirst(RegExp(r'/+$'), '');
    final normalized = uri.replace(path: path).toString();
    return normalized.endsWith('/')
        ? normalized.substring(0, normalized.length - 1)
        : normalized;
  }

  String get _installCommand =>
      'curl -fsSL $_agentInstallScriptUrl | sudo bash -s -- --server '
      '${_shellQuote(_serverUrl!)}';

  @override
  void dispose() {
    _serverController.dispose();
    super.dispose();
  }

  Future<void> _copy(String value, String message) async {
    await Clipboard.setData(ClipboardData(text: value));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    final serverUrl = _serverUrl;
    return AlertDialog(
      title: const Text('安装 Linux Agent'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '绑定资源：${widget.resourceName}',
                style: const TextStyle(color: _muted, fontSize: 11),
              ),
              Text(
                '注册令牌 15 分钟后过期：${_dateTime(widget.expiresAt)}',
                style: const TextStyle(color: _muted, fontSize: 11),
              ),
              const SizedBox(height: 14),
              TextField(
                controller: _serverController,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  labelText: '监控中心地址',
                  hintText: 'https://monitor.example.com',
                  helperText: '目标主机必须能访问此地址；生产环境请使用 HTTPS。',
                ),
                keyboardType: TextInputType.url,
              ),
              const SizedBox(height: 14),
              const Text(
                '在目标 Linux 主机执行以下命令。命令不含注册令牌；运行时会在终端隐藏提示输入。',
                style: TextStyle(fontSize: 12, height: 1.5),
              ),
              const SizedBox(height: 8),
              if (serverUrl == null)
                const Text(
                  '请输入 HTTPS 地址；HTTP 仅允许 localhost、127.0.0.1 或 ::1。',
                  style: TextStyle(color: _red, fontSize: 11),
                )
              else
                SelectableText(
                  _installCommand,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    height: 1.5,
                  ),
                ),
              const SizedBox(height: 8),
              const Text(
                '安装脚本从 GitHub 最新 Release 下载 Linux amd64/arm64 二进制并校验 SHA-256，然后创建受限系统账号和 systemd 服务。',
                style: TextStyle(color: _muted, fontSize: 11, height: 1.45),
              ),
              const Divider(height: 24),
              const Text(
                '一次性注册令牌（请安全传到目标主机）',
                style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 6),
              SelectableText(
                widget.token,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton.icon(
          onPressed: serverUrl == null
              ? null
              : () => _copy(_installCommand, '安装命令已复制'),
          icon: const Icon(Icons.terminal_rounded, size: 17),
          label: const Text('复制安装命令'),
        ),
        TextButton.icon(
          onPressed: () => _copy(widget.token, '注册令牌已复制，请安全传递'),
          icon: const Icon(Icons.key_rounded, size: 17),
          label: const Text('复制令牌'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

// The accepted server URL is restricted to characters that are safe inside quotes.
String _shellQuote(String value) =>
    String.fromCharCode(39) + value + String.fromCharCode(39);

class _MetricsDialog extends StatefulWidget {
  const _MetricsDialog({
    required this.api,
    required this.resource,
    required this.result,
  });
  final ApiClient api;
  final Map<String, dynamic> resource;
  final Map<String, dynamic> result;

  @override
  State<_MetricsDialog> createState() => _MetricsDialogState();
}

class _MetricsDialogState extends State<_MetricsDialog> {
  late Map<String, dynamic> _result;
  int _rangeDays = 1;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _result = widget.result;
  }

  Future<void> _loadRange(int days) async {
    setState(() {
      _rangeDays = days;
      _loading = true;
      _error = null;
    });
    try {
      final result = await widget.api.resourceMetrics(
        widget.resource['id'] as String,
        rangeDays: days,
      );
      if (!mounted) return;
      setState(() => _result = result);
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final rawSeries = _result['series'] as List? ?? const [];
    final series = rawSeries
        .map((value) => Map<String, dynamic>.from(value as Map))
        .toList();
    final stepSeconds = _result['stepSeconds'] as int? ?? 60;
    final isDatabase = widget.resource['type'] == 'DATABASE';
    return AlertDialog(
      title: Text(
        '${widget.resource['name']} · ${isDatabase ? '数据库指标' : '主机指标'}',
      ),
      content: SizedBox(
        width: 760,
        height: 540,
        child: Column(
          children: [
            Row(
              children: [
                const Text('时间范围', style: TextStyle(fontSize: 12)),
                const SizedBox(width: 10),
                DropdownButton<int>(
                  value: _rangeDays,
                  items: const [
                    DropdownMenuItem(value: 1, child: Text('最近 24 小时')),
                    DropdownMenuItem(value: 7, child: Text('最近 7 天')),
                    DropdownMenuItem(value: 30, child: Text('最近 30 天')),
                    DropdownMenuItem(value: 180, child: Text('最近 180 天')),
                  ],
                  onChanged: _loading
                      ? null
                      : (days) {
                          if (days != null && days != _rangeDays) {
                            _loadRange(days);
                          }
                        },
                ),
                const Spacer(),
                if (_loading)
                  const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
              ],
            ),
            if (_error != null)
              Align(
                alignment: Alignment.centerLeft,
                child: Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    '读取曲线失败：$_error',
                    style: const TextStyle(color: _red, fontSize: 11),
                  ),
                ),
              ),
            Expanded(
              child: series.isEmpty
                  ? _EmptyState(
                      icon: Icons.show_chart_rounded,
                      title: '当前时间范围没有指标',
                      subtitle: isDatabase
                          ? '数据库检查采集到可用连接统计后，曲线会显示在这里。账号没有统计权限时不会生成对应指标。'
                          : 'Agent 开始上报后，主机趋势会显示在这里。',
                    )
                  : ListView.separated(
                      itemCount: series.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 10),
                      itemBuilder: (context, index) {
                        final item = series[index];
                        final labels = Map<String, dynamic>.from(
                          item['labels'] as Map? ?? const {},
                        );
                        final points = (item['points'] as List? ?? const [])
                            .map(
                              (point) =>
                                  Map<String, dynamic>.from(point as Map),
                            )
                            .toList();
                        return _MetricChartCard(
                          metricName: item['metricName'] as String? ?? '',
                          labels: labels,
                          points: points,
                          stepSeconds: stepSeconds,
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _MetricChartCard extends StatelessWidget {
  const _MetricChartCard({
    required this.metricName,
    required this.labels,
    required this.points,
    required this.stepSeconds,
  });
  final String metricName;
  final Map<String, dynamic> labels;
  final List<Map<String, dynamic>> points;
  final int stepSeconds;

  @override
  Widget build(BuildContext context) {
    final label = labels.entries
        .map((entry) => '${entry.key}: ${entry.value}')
        .join(' · ');
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 11, 14, 9),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    _metricName(metricName),
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (label.isNotEmpty)
                  Text(
                    label,
                    style: const TextStyle(fontSize: 10, color: _muted),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 140,
              child: CustomPaint(
                painter: _MetricChartPainter(
                  points: points,
                  stepSeconds: stepSeconds,
                ),
                child: const SizedBox.expand(),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MetricChartPainter extends CustomPainter {
  _MetricChartPainter({required this.points, required this.stepSeconds});
  final List<Map<String, dynamic>> points;
  final int stepSeconds;

  @override
  void paint(Canvas canvas, Size size) {
    const left = 8.0;
    const right = 8.0;
    const top = 8.0;
    const bottom = 8.0;
    final chart = Rect.fromLTRB(
      left,
      top,
      size.width - right,
      size.height - bottom,
    );
    final gridPaint = Paint()
      ..color = const Color(0xFFE8EDF2)
      ..strokeWidth = 1;
    for (var index = 0; index <= 3; index++) {
      final y = chart.top + chart.height * index / 3;
      canvas.drawLine(Offset(chart.left, y), Offset(chart.right, y), gridPaint);
    }
    final parsed = <(DateTime, double)>[];
    for (final point in points) {
      final time = DateTime.tryParse(point['bucketAt'] as String? ?? '');
      final value = (point['average'] as num?)?.toDouble();
      if (time != null && value != null && value.isFinite) {
        parsed.add((time, value));
      }
    }
    if (parsed.isEmpty) {
      return;
    }
    final start = parsed.first.$1;
    final end = parsed.last.$1;
    var minValue = parsed
        .map((value) => value.$2)
        .reduce((a, b) => a < b ? a : b);
    var maxValue = parsed
        .map((value) => value.$2)
        .reduce((a, b) => a > b ? a : b);
    if (minValue == maxValue) {
      minValue -= 1;
      maxValue += 1;
    }
    final span = maxValue - minValue;
    final duration = end.difference(start).inMilliseconds;
    Offset position((DateTime, double) item) {
      final x = duration <= 0
          ? chart.center.dx
          : chart.left +
                chart.width *
                    item.$1.difference(start).inMilliseconds /
                    duration;
      final y = chart.bottom - chart.height * (item.$2 - minValue) / span;
      return Offset(x, y);
    }

    final linePaint = Paint()
      ..color = _blue
      ..strokeWidth = 2.2
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final pointPaint = Paint()
      ..color = _blue
      ..style = PaintingStyle.fill;
    var path = Path();
    var priorTime = parsed.first.$1;
    var hasSegment = false;
    for (var index = 0; index < parsed.length; index++) {
      final item = parsed[index];
      final current = position(item);
      final gap = item.$1.difference(priorTime).inSeconds;
      if (index == 0 || gap > stepSeconds * 1.5) {
        if (hasSegment) canvas.drawPath(path, linePaint);
        path = Path()..moveTo(current.dx, current.dy);
        hasSegment = false;
      } else {
        path.lineTo(current.dx, current.dy);
        hasSegment = true;
      }
      canvas.drawCircle(current, 2.4, pointPaint);
      priorTime = item.$1;
    }
    if (hasSegment) canvas.drawPath(path, linePaint);
  }

  @override
  bool shouldRepaint(covariant _MetricChartPainter oldDelegate) =>
      oldDelegate.points != points || oldDelegate.stepSeconds != stepSeconds;
}

String _metricName(String value) => switch (value) {
  'cpu_usage_percent' => 'CPU 使用率（%）',
  'load_1m' => '系统负载（1 分钟）',
  'memory_used_percent' => '内存使用率（%）',
  'memory_available_bytes' => '可用内存（字节）',
  'swap_used_percent' => 'Swap 使用率（%）',
  'disk_used_percent' => '磁盘使用率（%）',
  'disk_inode_used_percent' => 'inode 使用率（%）',
  'network_receive_bytes_per_second' => '网络接收（字节/秒）',
  'network_transmit_bytes_per_second' => '网络发送（字节/秒）',
  'database_connections' => '数据库当前连接数（个）',
  'database_connection_limit' => '数据库连接上限（个）',
  'database_connections_percent' => '数据库连接使用率（%）',
  'redis_memory_used_bytes' => 'Redis 已使用内存（字节）',
  'redis_memory_limit_bytes' => 'Redis 内存上限（字节）',
  'redis_memory_used_percent' => 'Redis 内存使用率（%）',
  'redis_evicted_keys_total' => 'Redis 已淘汰键累计数（个）',
  _ => value,
};

class _ProjectMembersPage extends StatefulWidget {
  const _ProjectMembersPage({
    required this.api,
    required this.projects,
    required this.userId,
    required this.canManage,
  });

  final ApiClient api;
  final List<Map<String, dynamic>> projects;
  final String userId;
  final bool canManage;

  @override
  State<_ProjectMembersPage> createState() => _ProjectMembersPageState();
}

class _ProjectMembersPageState extends State<_ProjectMembersPage> {
  String? _projectId;
  List<Map<String, dynamic>> _members = [];
  bool _loading = false;

  List<Map<String, dynamic>> get _adminProjects => widget.projects
      .where((project) => project['membershipRole'] == 'ADMIN')
      .toList();

  @override
  void initState() {
    super.initState();
    final projects = _adminProjects;
    if (projects.isNotEmpty) {
      _projectId = projects.first['id'] as String;
      _loadMembers();
    }
  }

  Future<void> _loadMembers() async {
    final projectId = _projectId;
    if (projectId == null || !widget.canManage) return;
    setState(() => _loading = true);
    try {
      final members = await widget.api.projectMembers(projectId);
      if (mounted && projectId == _projectId) {
        setState(() => _members = members);
      }
    } catch (error) {
      if (mounted) _showNotice(error.toString(), isError: true);
    } finally {
      if (mounted && projectId == _projectId) {
        setState(() => _loading = false);
      }
    }
  }

  Future<void> _addMember() async {
    final projectId = _projectId;
    if (projectId == null) return;
    final draft = await showDialog<_ProjectMemberDraft>(
      context: context,
      builder: (_) => const _ProjectMemberDialog(),
    );
    if (draft == null) return;
    try {
      await widget.api.addProjectMember(
        projectId: projectId,
        email: draft.email,
        password: draft.password,
        role: draft.role,
      );
      await _loadMembers();
      if (mounted) _showNotice('成员已添加');
    } catch (error) {
      if (mounted) _showNotice(error.toString(), isError: true);
    }
  }

  Future<void> _changeRole(Map<String, dynamic> member, String role) async {
    final projectId = _projectId;
    if (projectId == null || role == member['role']) return;
    try {
      await widget.api.updateProjectMemberRole(
        projectId: projectId,
        memberId: member['id'] as String,
        role: role,
      );
      await _loadMembers();
      if (mounted) _showNotice('账号角色已更新');
    } catch (error) {
      if (mounted) _showNotice(error.toString(), isError: true);
      await _loadMembers();
    }
  }

  Future<void> _removeMember(Map<String, dynamic> member) async {
    final projectId = _projectId;
    if (projectId == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('移除项目成员？'),
        content: Text('移除 ${member['email']} 后，该账号将无法访问此项目。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.removeProjectMember(
        projectId: projectId,
        memberId: member['id'] as String,
      );
      await _loadMembers();
      if (mounted) _showNotice('成员已从项目移除');
    } catch (error) {
      if (mounted) _showNotice(error.toString(), isError: true);
    }
  }

  void _showNotice(String message, {bool isError = false}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          backgroundColor: isError ? _red : const Color(0xFF263B52),
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    final projects = _adminProjects;
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PageHeading(
            eyebrow: 'PROJECT ACCESS',
            title: '成员与角色',
            subtitle: '为项目授权成员，并管理其运维权限。',
            actions: [
              if (widget.canManage && projects.isNotEmpty)
                FilledButton.icon(
                  onPressed: _addMember,
                  icon: const Icon(Icons.person_add_alt_1_rounded),
                  label: const Text('添加成员'),
                ),
            ],
          ),
          if (!widget.canManage || projects.isEmpty)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(28),
                child: _EmptyState(
                  icon: Icons.group_outlined,
                  title: '没有可管理的项目',
                  subtitle: '只有项目管理员可以查看和修改成员权限。',
                ),
              ),
            )
          else ...[
            Card(
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    DropdownButtonFormField<String>(
                      initialValue: _projectId,
                      decoration: const InputDecoration(labelText: '项目'),
                      items: projects
                          .map(
                            (project) => DropdownMenuItem(
                              value: project['id'] as String,
                              child: Text(project['name'] as String? ?? '项目'),
                            ),
                          )
                          .toList(),
                      onChanged: (value) {
                        if (value == null) return;
                        setState(() {
                          _projectId = value;
                          _members = [];
                        });
                        _loadMembers();
                      },
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      '账号角色会同步到该账号加入的所有项目；项目成员关系决定其可访问范围。',
                      style: TextStyle(color: _muted, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 14),
            Card(
              child: _loading
                  ? const Padding(
                      padding: EdgeInsets.all(28),
                      child: Center(child: CircularProgressIndicator()),
                    )
                  : _members.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.all(24),
                      child: _EmptyState(
                        icon: Icons.group_outlined,
                        title: '项目还没有其他成员',
                        subtitle: '添加成员后，他们可使用自己的账号登录。',
                      ),
                    )
                  : Column(
                      children: _members.map((member) {
                        final memberId = member['id'] as String;
                        final isSelf = memberId == widget.userId;
                        final createdAt = member['createdAt'] as String? ?? '';
                        return Column(
                          children: [
                            ListTile(
                              leading: CircleAvatar(
                                backgroundColor: const Color(0xFFEAF0FA),
                                child: Icon(
                                  Icons.person_outline_rounded,
                                  color: _blue,
                                ),
                              ),
                              title: Text(member['email'] as String? ?? ''),
                              subtitle: Text(
                                createdAt.isEmpty
                                    ? '账号创建时间未知'
                                    : '账号创建于 ${createdAt.replaceFirst('T', ' ').split('.').first}',
                              ),
                              trailing: Wrap(
                                crossAxisAlignment: WrapCrossAlignment.center,
                                spacing: 4,
                                children: [
                                  DropdownButton<String>(
                                    value:
                                        member['role'] as String? ?? 'OPERATOR',
                                    items: const [
                                      DropdownMenuItem(
                                        value: 'ADMIN',
                                        child: Text('管理员'),
                                      ),
                                      DropdownMenuItem(
                                        value: 'OPERATOR',
                                        child: Text('运维人员'),
                                      ),
                                      DropdownMenuItem(
                                        value: 'READ_ONLY',
                                        child: Text('只读成员'),
                                      ),
                                    ],
                                    onChanged: isSelf || !widget.canManage
                                        ? null
                                        : (value) {
                                            if (value != null) {
                                              _changeRole(member, value);
                                            }
                                          },
                                  ),
                                  IconButton(
                                    tooltip: isSelf ? '当前账号' : '从项目移除',
                                    onPressed: isSelf || !widget.canManage
                                        ? null
                                        : () => _removeMember(member),
                                    icon: Icon(
                                      isSelf
                                          ? Icons.verified_user_outlined
                                          : Icons.person_remove_alt_1_rounded,
                                      color: isSelf ? _muted : _red,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const Divider(height: 1),
                          ],
                        );
                      }).toList(),
                    ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ProjectMemberDraft {
  const _ProjectMemberDraft(this.email, this.password, this.role);
  final String email;
  final String password;
  final String role;
}

class _ProjectMemberDialog extends StatefulWidget {
  const _ProjectMemberDialog();

  @override
  State<_ProjectMemberDialog> createState() => _ProjectMemberDialogState();
}

class _ProjectMemberDialogState extends State<_ProjectMemberDialog> {
  final _formKey = GlobalKey<FormState>();
  final _email = TextEditingController();
  final _password = TextEditingController();
  String _role = 'OPERATOR';

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('添加项目成员'),
    content: SizedBox(
      width: 460,
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextFormField(
              controller: _email,
              decoration: const InputDecoration(labelText: '邮箱'),
              keyboardType: TextInputType.emailAddress,
              validator: (value) {
                final email = value?.trim() ?? '';
                return email.contains('@') && email.contains('.')
                    ? null
                    : '请输入有效邮箱';
              },
            ),
            TextFormField(
              controller: _password,
              decoration: const InputDecoration(
                labelText: '初始密码',
                hintText: '新账号必填，已有账号留空',
              ),
              obscureText: true,
              validator: (value) {
                final password = value ?? '';
                return password.isEmpty || password.length >= 12
                    ? null
                    : '密码至少需要 12 个字符';
              },
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: _role,
              decoration: const InputDecoration(labelText: '账号角色'),
              items: const [
                DropdownMenuItem(value: 'ADMIN', child: Text('管理员')),
                DropdownMenuItem(value: 'OPERATOR', child: Text('运维人员')),
                DropdownMenuItem(value: 'READ_ONLY', child: Text('只读成员')),
              ],
              onChanged: (value) {
                if (value != null) setState(() => _role = value);
              },
            ),
            const SizedBox(height: 10),
            const Text(
              '角色应用于该账号的所有项目。已有账号请选择其当前角色；新账号首次登录后可正常修改密码。',
              style: TextStyle(color: _muted, fontSize: 12, height: 1.45),
            ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () {
          if (!_formKey.currentState!.validate()) return;
          Navigator.pop(
            context,
            _ProjectMemberDraft(_email.text.trim(), _password.text, _role),
          );
        },
        child: const Text('添加'),
      ),
    ],
  );
}

class SystemSettingsPage extends StatefulWidget {
  const SystemSettingsPage({super.key, required this.api, required this.isAdmin});

  final ApiClient api;
  final bool isAdmin;

  @override
  State<SystemSettingsPage> createState() => _SystemSettingsPageState();
}

class _SystemSettingsPageState extends State<SystemSettingsPage> {
  Map<String, dynamic>? _status;
  List<Map<String, dynamic>> _auditEntries = [];
  Map<String, dynamic>? _nextCursor;
  String? _error;
  String? _moreError;
  bool _loading = false;
  bool _loadingMore = false;

  @override
  void initState() {
    super.initState();
    if (widget.isAdmin) _reload();
  }

  Future<void> _reload() async {
    setState(() {
      _loading = true;
      _error = null;
      _moreError = null;
    });
    try {
      final values = await Future.wait<Object>([
        widget.api.systemStatus(),
        widget.api.auditLog(),
      ]);
      final page = Map<String, dynamic>.from(values[1] as Map);
      if (!mounted) return;
      setState(() {
        _status = Map<String, dynamic>.from(values[0] as Map);
        _auditEntries = List<Map<String, dynamic>>.from(
          (page['items'] as List).map(
            (item) => Map<String, dynamic>.from(item as Map),
          ),
        );
        _nextCursor = page['next'] is Map
            ? Map<String, dynamic>.from(page['next'] as Map)
            : null;
      });
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadMore() async {
    final cursor = _nextCursor;
    if (cursor == null || _loadingMore) return;
    setState(() {
      _loadingMore = true;
      _moreError = null;
    });
    try {
      final page = await widget.api.auditLog(
        beforeCreatedAt: cursor['createdAt'] as String,
        beforeId: cursor['id'] as String,
      );
      final entries = List<Map<String, dynamic>>.from(
        (page['items'] as List).map(
          (item) => Map<String, dynamic>.from(item as Map),
        ),
      );
      if (!mounted) return;
      setState(() {
        _auditEntries = [..._auditEntries, ...entries];
        _nextCursor = page['next'] is Map
            ? Map<String, dynamic>.from(page['next'] as Map)
            : null;
      });
    } catch (error) {
      if (mounted) setState(() => _moreError = error.toString());
    } finally {
      if (mounted) setState(() => _loadingMore = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _PageHeading(
            eyebrow: 'SYSTEM',
            title: '系统设置与审计',
            subtitle: '查看数据库状态、数据保留策略、最近备份和管理操作记录。',
            actions: [
              if (widget.isAdmin)
                OutlinedButton.icon(
                  onPressed: _loading ? null : _reload,
                  icon: const Icon(Icons.refresh_rounded),
                  label: const Text('刷新状态'),
                ),
            ],
          ),
          if (!widget.isAdmin)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(28),
                child: _EmptyState(
                  icon: Icons.lock_outline_rounded,
                  title: '需要系统管理员权限',
                  subtitle: '系统状态和审计记录仅对管理员开放。',
                ),
              ),
            )
          else if (_loading && _status == null)
            const Card(
              child: Padding(
                padding: EdgeInsets.all(28),
                child: Center(child: CircularProgressIndicator()),
              ),
            )
          else if (_error != null && _status == null)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: _EmptyState(
                  icon: Icons.cloud_off_outlined,
                  title: '无法读取系统状态',
                  subtitle: _error!,
                ),
              ),
            )
          else if (_status != null) ...[
            if (_error != null) ...[
              Card(
                color: _amber.withValues(alpha: 0.08),
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.warning_amber_rounded, color: _amber),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          '最新状态刷新失败，以下内容是上次成功读取的快照。${_error!}',
                          style: const TextStyle(color: _amber, fontSize: 12),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
            ],
            _systemOverviewCard(_status!),
            const SizedBox(height: 14),
            _retentionCard(_status!['retentionPolicies'] as List? ?? const []),
            const SizedBox(height: 14),
            _auditCard(),
          ],
        ],
      ),
    );
  }

  Widget _systemOverviewCard(Map<String, dynamic> status) {
    final databaseBytes = status['databaseSizeBytes'] as num? ?? 0;
    final version = status['schemaVersion'] as String? ?? '未知';
    final backup = Map<String, dynamic>.from(status['backup'] as Map);
    final platform = status['platformHealth'] is Map
        ? Map<String, dynamic>.from(status['platformHealth'] as Map)
        : <String, dynamic>{};
    final retention = platform['retention'] is Map
        ? Map<String, dynamic>.from(platform['retention'] as Map)
        : <String, dynamic>{};
    final platformState = platform['status'] as String? ?? 'UNKNOWN';
    final (platformLabel, platformColor) = switch (platformState) {
      'HEALTHY' => ('平台采集正常', _green),
      'DEGRADED' => ('平台存在异常', _red),
      _ => ('平台状态未知', _amber),
    };
    final attempts = platform['notificationAttempts24h'] as num? ?? 0;
    final delivered = platform['notificationDelivered24h'] as num? ?? 0;
    final notificationRate = platform['notificationSuccessRate24h'] as num?;
    final retentionState = retention['status'] as String? ?? 'NOT_RUN';
    final retentionLabel = switch (retentionState) {
      'CURRENT' => '最近一次成功',
      'RUNNING' => '正在运行',
      'FAILED' => '连续失败 ${retention['consecutiveFailures'] ?? 1} 次',
      _ => '进程启动后尚未成功运行',
    };
    final retentionDetails = [
      retentionLabel,
      if (retention['lastSuccessAt'] is String)
        _formatSystemTime(retention['lastSuccessAt'] as String),
      if (retention['lastFailureType'] is String && retentionState == 'FAILED')
        retention['lastFailureType'] as String,
    ].join(' · ');
    final backupState = backup['status'] as String? ?? 'NOT_RECORDED';
    final (backupLabel, backupColor) = switch (backupState) {
      'CURRENT' => ('24 小时内有备份记录', _green),
      'OVERDUE' => ('超过 24 小时未记录备份', _red),
      _ => ('尚无备份记录', _amber),
    };
    final backupDetails = switch (backupState) {
      'CURRENT' || 'OVERDUE' => [
        backup['filename'] as String? ?? '',
        _formatBytes(backup['sizeBytes'] as num? ?? 0),
        _formatSystemTime(backup['completedAt'] as String? ?? ''),
      ].where((part) => part.isNotEmpty).join(' · '),
      _ => '运行 scripts/backup-postgres.sh 并完成归档校验后会更新这里。',
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.storage_outlined, color: _blue),
                const SizedBox(width: 10),
                const Expanded(
                  child: Text(
                    '平台状态',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 10,
                    vertical: 5,
                  ),
                  decoration: BoxDecoration(
                    color: platformColor.withValues(alpha: 0.12),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    platformLabel,
                    style: TextStyle(
                      color: platformColor,
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 28,
              runSpacing: 12,
              children: [
                _SystemValue(
                  label: '数据库大小',
                  value: _formatBytes(databaseBytes),
                ),
                _SystemValue(label: '数据库迁移版本', value: 'V$version'),
                _SystemValue(
                  label: '状态更新时间',
                  value: _formatSystemTime(
                    status['generatedAt'] as String? ?? '',
                  ),
                ),
                _SystemValue(
                  label: '到期检查积压',
                  value: '${platform['dueChecks'] ?? '—'}',
                ),
                _SystemValue(
                  label: '最久调度延迟',
                  value: '${platform['oldestScheduleDelaySeconds'] ?? '—'} 秒',
                ),
                _SystemValue(
                  label: '过期检查结果',
                  value:
                      '${platform['staleChecks'] ?? '—'} / ${platform['enabledChecks'] ?? '—'}',
                ),
                _SystemValue(
                  label: '运行中检查',
                  value: '${platform['runningChecks'] ?? '—'}',
                ),
                _SystemValue(
                  label: '近 1 小时规则处理 P95',
                  value: platform['recentResultP95LatencyMs'] is num
                      ? '${(platform['recentResultP95LatencyMs'] as num).toStringAsFixed(0)} ms'
                      : '暂无样本',
                ),
                _SystemValue(
                  label: 'Agent 批次拒绝（进程启动后）',
                  value: '${platform['rejectedAgentBatchesSinceStart'] ?? '—'}',
                ),
                _SystemValue(
                  label: '通知 24 小时送达率',
                  value: notificationRate == null
                      ? '暂无投递（$delivered / $attempts）'
                      : '${notificationRate.toStringAsFixed(1)}%（$delivered / $attempts）',
                ),
                _SystemValue(
                  label: '数据清理任务',
                  value: retentionDetails,
                ),
              ],
            ),
            const Divider(height: 30),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.backup_outlined, color: backupColor),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        backupLabel,
                        style: TextStyle(
                          color: backupColor,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        backupDetails,
                        style: const TextStyle(color: _muted, fontSize: 12),
                      ),
                      if (backup['sha256'] is String) ...[
                        const SizedBox(height: 3),
                        SelectableText(
                          'SHA-256 ${backup['sha256']}',
                          style: const TextStyle(
                            color: _muted,
                            fontSize: 11,
                            fontFamily: 'monospace',
                          ),
                        ),
                      ],
                      const SizedBox(height: 5),
                      const Text(
                        '状态来自本机备份脚本记录，不代表异机副本或恢复演练已验证。',
                        style: TextStyle(color: _muted, fontSize: 11),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _retentionCard(List policies) => Card(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(18, 17, 18, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '数据保留策略',
            style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 8),
          for (final item in policies)
            ListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text(item['label'] as String? ?? '数据'),
              trailing: Text(
                '${item['retentionDays'] ?? '—'} 天',
                style: const TextStyle(
                  color: _blue,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
        ],
      ),
    ),
  );

  Widget _auditCard() => Card(
    child: Padding(
      padding: const EdgeInsets.fromLTRB(18, 17, 18, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '操作审计',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
              Text(
                '${_auditEntries.length} 条',
                style: const TextStyle(color: _muted, fontSize: 12),
              ),
            ],
          ),
          const SizedBox(height: 8),
          if (_auditEntries.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 18),
              child: _EmptyState(
                icon: Icons.receipt_long_outlined,
                title: '暂无操作记录',
                subtitle: '项目成员、通知、Agent 和故障确认等管理操作会显示在这里。',
              ),
            )
          else
            for (final entry in _auditEntries) ...[
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: const Icon(Icons.receipt_long_outlined, color: _blue),
                title: Text(
                  _auditActionLabel(entry['action'] as String? ?? ''),
                ),
                subtitle: Text(
                  [
                    entry['actorEmail'] as String? ?? '系统',
                    '${entry['subjectType'] ?? '对象'} ${entry['subjectId'] ?? ''}'
                        .trim(),
                    _auditSummary(entry['summary']),
                    _formatSystemTime(entry['createdAt'] as String? ?? ''),
                  ].where((value) => value.isNotEmpty).join(' · '),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const Divider(height: 1),
            ],
          if (_moreError != null)
            Padding(
              padding: const EdgeInsets.only(top: 10),
              child: Text(
                _moreError!,
                style: const TextStyle(color: _red, fontSize: 12),
              ),
            ),
          if (_nextCursor != null)
            Align(
              alignment: Alignment.center,
              child: TextButton.icon(
                onPressed: _loadingMore ? null : _loadMore,
                icon: _loadingMore
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.expand_more_rounded),
                label: const Text('加载更早记录'),
              ),
            ),
        ],
      ),
    ),
  );
}

class _SystemValue extends StatelessWidget {
  const _SystemValue({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: const TextStyle(color: _muted, fontSize: 11)),
      const SizedBox(height: 3),
      Text(
        value,
        style: const TextStyle(color: _ink, fontWeight: FontWeight.w600),
      ),
    ],
  );
}

String _formatBytes(num value) {
  if (value < 1024) return '${value.toInt()} B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  var scaled = value / 1024;
  var unit = 0;
  while (scaled >= 1024 && unit < units.length - 1) {
    scaled /= 1024;
    unit++;
  }
  return '${scaled.toStringAsFixed(1)} ${units[unit]}';
}

String _formatSystemTime(String value) => value
    .replaceFirst('T', ' ')
    .replaceFirst(RegExp(r'\.\d+'), '')
    .replaceFirst('Z', ' UTC');

String _auditSummary(dynamic value) {
  if (value is! Map || value.isEmpty) return '';
  return value.entries.map((entry) => '${entry.key}: ${entry.value}').join('，');
}

String _auditActionLabel(String action) => switch (action) {
  'project.member_added' => '添加项目成员',
  'project.member_role_updated' => '更新成员角色',
  'project.member_removed' => '移除项目成员',
  'incident.acknowledged' => '确认故障',
  'notification_channel.created' => '创建通知渠道',
  'notification_channel.updated' => '更新通知渠道',
  'notification_channel.deleted' => '删除通知渠道',
  'notification_channel.test_requested' => '发送测试通知',
  'agent.enrollment.created' => '签发 Agent 注册令牌',
  'agent.registered' => '注册 Agent',
  'agent.revoked' => '吊销 Agent 凭据',
  'heartbeat.token.rotated' => '轮换心跳任务令牌',
  'installation.completed' => '完成系统初始化',
  _ => action,
};

class _ProjectsPage extends StatelessWidget {
  const _ProjectsPage({
    required this.projects,
    required this.canCreate,
    required this.onCreate,
  });

  final List<Map<String, dynamic>> projects;
  final bool canCreate;
  final VoidCallback onCreate;

  @override
  Widget build(BuildContext context) => _PageScroll(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'PROJECTS',
          title: '项目管理',
          subtitle: '用项目组织环境、资源和团队权限。',
          actions: canCreate
              ? [
                  FilledButton.icon(
                    onPressed: onCreate,
                    icon: const Icon(Icons.add_rounded),
                    label: const Text('创建项目'),
                  ),
                ]
              : const [],
        ),
        if (projects.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(34),
              child: _EmptyState(
                icon: Icons.folder_outlined,
                title: '还没有项目',
                subtitle: '创建第一个项目后即可添加监控资源。',
              ),
            ),
          )
        else
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth > 1050
                  ? 3
                  : constraints.maxWidth > 650
                  ? 2
                  : 1;
              final width =
                  (constraints.maxWidth - (columns - 1) * 14) / columns;
              return Wrap(
                spacing: 14,
                runSpacing: 14,
                children: projects
                    .map(
                      (project) => SizedBox(
                        width: width,
                        child: Card(
                          child: Padding(
                            padding: const EdgeInsets.all(18),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Container(
                                      width: 40,
                                      height: 40,
                                      decoration: BoxDecoration(
                                        color: const Color(0xFFEAF0FA),
                                        borderRadius: BorderRadius.circular(12),
                                      ),
                                      child: const Icon(
                                        Icons.folder_open_outlined,
                                        color: _blue,
                                      ),
                                    ),
                                    const Spacer(),
                                    const Icon(
                                      Icons.more_horiz_rounded,
                                      color: _muted,
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 18),
                                Text(
                                  project['name'] as String? ?? '未命名项目',
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w700,
                                    fontSize: 15,
                                  ),
                                ),
                                const SizedBox(height: 7),
                                Text(
                                  (project['description'] as String? ?? '')
                                          .isEmpty
                                      ? '暂无项目说明'
                                      : project['description'] as String,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(
                                    color: _muted,
                                    fontSize: 12,
                                  ),
                                ),
                                const SizedBox(height: 17),
                                Row(
                                  children: [
                                    const Icon(
                                      Icons.dns_outlined,
                                      size: 16,
                                      color: _muted,
                                    ),
                                    const SizedBox(width: 6),
                                    const Text(
                                      '项目空间',
                                      style: TextStyle(
                                        color: _muted,
                                        fontSize: 11,
                                      ),
                                    ),
                                    const Spacer(),
                                    Text(
                                      _shortDate(
                                        project['createdAt'] as String?,
                                      ),
                                      style: const TextStyle(
                                        color: _muted,
                                        fontSize: 10,
                                      ),
                                    ),
                                  ],
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    )
                    .toList(),
              );
            },
          ),
      ],
    ),
  );
}

class _NotificationsPage extends StatefulWidget {
  const _NotificationsPage({
    required this.api,
    required this.projects,
    required this.resources,
    required this.readOnly,
    required this.onNotice,
  });

  final ApiClient api;
  final List<Map<String, dynamic>> projects;
  final List<Map<String, dynamic>> resources;
  final bool readOnly;
  final ValueChanged<String> onNotice;

  @override
  State<_NotificationsPage> createState() => _NotificationsPageState();
}

class _NotificationsPageState extends State<_NotificationsPage> {
  List<Map<String, dynamic>> _channels = [];
  List<Map<String, dynamic>> _deliveries = [];
  List<Map<String, dynamic>> _maintenanceWindows = [];
  List<Map<String, dynamic>> _notificationSilences = [];
  bool _loading = true;
  String? _testingId;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final results = await Future.wait([
        widget.api.notificationChannels(),
        widget.api.notificationDeliveries(),
        widget.api.maintenanceWindows(),
        widget.api.notificationSilences(),
      ]);
      if (!mounted) return;
      setState(() {
        _channels = results[0];
        _deliveries = results[1];
        _maintenanceWindows = results[2];
        _notificationSilences = results[3];
      });
    } catch (error) {
      if (mounted) widget.onNotice('加载通知记录失败：$error');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createMaintenanceWindow() async {
    if (widget.projects.isEmpty) {
      widget.onNotice('请先创建项目');
      return;
    }
    final draft = await showDialog<_MaintenanceWindowDraft>(
      context: context,
      builder: (_) => _MaintenanceWindowDialog(
        projects: widget.projects,
        resources: widget.resources,
      ),
    );
    if (draft == null) return;
    try {
      await widget.api.createMaintenanceWindow(draft.toJson());
      await _load();
      if (mounted) widget.onNotice('维护窗口已创建');
    } catch (error) {
      if (mounted) widget.onNotice('创建维护窗口失败：$error');
    }
  }

  Future<void> _cancelMaintenanceWindow(Map<String, dynamic> window) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('取消维护窗口？'),
        content: Text('“${window['name']}”取消后会立即恢复该范围内的通知。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('保留'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('取消窗口'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.cancelMaintenanceWindow(window['id'] as String);
      await _load();
      if (mounted) widget.onNotice('维护窗口已取消');
    } catch (error) {
      if (mounted) widget.onNotice('取消维护窗口失败：$error');
    }
  }

  Future<void> _createNotificationSilence() async {
    if (widget.projects.isEmpty) {
      widget.onNotice('请先创建项目');
      return;
    }
    final draft = await showDialog<_MaintenanceWindowDraft>(
      context: context,
      builder: (_) => _MaintenanceWindowDialog(
        projects: widget.projects,
        resources: widget.resources,
        title: '安排通知静默',
        isSilence: true,
      ),
    );
    if (draft == null) return;
    try {
      await widget.api.createNotificationSilence(draft.toJson());
      await _load();
      if (mounted) widget.onNotice('通知静默已创建');
    } catch (error) {
      if (mounted) widget.onNotice('创建通知静默失败：$error');
    }
  }

  Future<void> _cancelNotificationSilence(Map<String, dynamic> silence) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('取消通知静默？'),
        content: Text('“${silence['name']}”取消后，排队中的通知会继续投递。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('保留'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('取消静默'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.cancelNotificationSilence(silence['id'] as String);
      await _load();
      if (mounted) widget.onNotice('通知静默已取消');
    } catch (error) {
      if (mounted) widget.onNotice('取消通知静默失败：$error');
    }
  }

  Future<void> _createChannel() async {
    if (widget.projects.isEmpty) {
      widget.onNotice('请先创建项目');
      return;
    }
    final draft = await showDialog<_NotificationChannelDraft>(
      context: context,
      builder: (_) => _NotificationChannelDialog(projects: widget.projects),
    );
    if (draft == null) return;
    try {
      await widget.api.createNotificationChannel(draft.toJson());
      await _load();
      if (mounted) widget.onNotice('通知渠道已创建');
    } catch (error) {
      if (mounted) widget.onNotice('创建通知渠道失败：$error');
    }
  }

  Future<void> _testChannel(Map<String, dynamic> channel) async {
    final id = channel['id'] as String;
    setState(() => _testingId = id);
    try {
      final result = await widget.api.testNotificationChannel(id);
      final outboxId = result['outboxId'] as String;
      Map<String, dynamic>? delivery;
      for (var attempt = 0; attempt < 10; attempt++) {
        await Future<void>.delayed(const Duration(seconds: 1));
        final rows = await widget.api.notificationDeliveries(channelId: id);
        delivery = rows.cast<Map<String, dynamic>?>().firstWhere(
          (row) => row?['outboxId'] == outboxId,
          orElse: () => null,
        );
        if (delivery != null) break;
      }
      await _load();
      if (!mounted) return;
      if (delivery == null) {
        widget.onNotice('测试通知已排队，投递记录稍后刷新查看');
      } else if (delivery['status'] == 'DELIVERED') {
        widget.onNotice('测试通知投递成功');
      } else {
        widget.onNotice(
          '测试通知未送达：${delivery['errorSummary'] ?? delivery['status']}',
        );
      }
    } catch (error) {
      if (mounted) widget.onNotice('发送测试通知失败：$error');
    } finally {
      if (mounted) setState(() => _testingId = null);
    }
  }

  Future<void> _deleteChannel(Map<String, dynamic> channel) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除通知渠道？'),
        content: Text('“${channel['name']}”停止接收后续故障通知，历史投递记录会保留。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.deleteNotificationChannel(channel['id'] as String);
      await _load();
      if (mounted) widget.onNotice('通知渠道已删除');
    } catch (error) {
      if (mounted) widget.onNotice('删除通知渠道失败：$error');
    }
  }

  @override
  Widget build(BuildContext context) => _PageScroll(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'ALERT DELIVERY',
          title: '告警与通知',
          subtitle: '维护窗口保留检查结果并抑制通知；故障和恢复事件通过项目渠道发送。',
          actions: [
            OutlinedButton.icon(
              onPressed: _loading ? null : _load,
              icon: const Icon(Icons.refresh_rounded, size: 18),
              label: const Text('刷新'),
            ),
            if (!widget.readOnly)
              FilledButton.icon(
                onPressed: _createChannel,
                icon: const Icon(Icons.add_rounded, size: 18),
                label: const Text('添加渠道'),
              ),
          ],
        ),
        Row(
          children: [
            const Expanded(
              child: Text(
                '维护窗口',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '${_maintenanceWindows.where((window) => window['status'] == 'ACTIVE' || window['status'] == 'SCHEDULED').length} 个有效窗口',
              style: const TextStyle(color: _muted, fontSize: 11),
            ),
            if (!widget.readOnly) ...[
              const SizedBox(width: 10),
              FilledButton.tonalIcon(
                onPressed: _createMaintenanceWindow,
                icon: const Icon(Icons.schedule_rounded, size: 17),
                label: const Text('安排维护'),
              ),
            ],
          ],
        ),
        const SizedBox(height: 10),
        if (_maintenanceWindows.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: _EmptyState(
                icon: Icons.event_busy_outlined,
                title: '暂无维护窗口',
                subtitle: '维护期间继续保存检查结果，并抑制该项目或资源的故障通知。',
              ),
            ),
          )
        else
          ..._maintenanceWindows.map(
            (window) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Card(
                child: ListTile(
                  dense: true,
                  leading: Icon(
                    window['status'] == 'ACTIVE'
                        ? Icons.pause_circle_outline_rounded
                        : Icons.event_available_outlined,
                    color: window['status'] == 'ACTIVE' ? _blue : _muted,
                  ),
                  title: Text(
                    window['name'] as String? ?? '维护窗口',
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  subtitle: Text(
                    '${window['projectName']} · ${window['resourceName'] ?? '全部资源'} · ${_dateTime(window['startsAt'] as String?)} 至 ${_dateTime(window['endsAt'] as String?)}${(window['description'] as String? ?? '').isEmpty ? '' : ' · ${window['description']}'}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 10, color: _muted),
                  ),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _MaintenanceWindowStatus(
                        status: window['status'] as String? ?? 'ENDED',
                      ),
                      if (!widget.readOnly &&
                          (window['status'] == 'ACTIVE' ||
                              window['status'] == 'SCHEDULED'))
                        IconButton(
                          tooltip: '取消维护窗口',
                          onPressed: () => _cancelMaintenanceWindow(window),
                          icon: const Icon(Icons.close_rounded, size: 18),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        const SizedBox(height: 20),
        Row(
          children: [
            const Expanded(
              child: Text(
                '通知静默',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '${_notificationSilences.where((silence) => silence['status'] == 'ACTIVE' || silence['status'] == 'SCHEDULED').length} 个有效静默',
              style: const TextStyle(color: _muted, fontSize: 11),
            ),
            if (!widget.readOnly) ...[
              const SizedBox(width: 10),
              FilledButton.tonalIcon(
                onPressed: _createNotificationSilence,
                icon: const Icon(Icons.notifications_paused_outlined, size: 17),
                label: const Text('安排静默'),
              ),
            ],
          ],
        ),
        const SizedBox(height: 10),
        if (_notificationSilences.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: _EmptyState(
                icon: Icons.notifications_paused_outlined,
                title: '暂无通知静默',
                subtitle: '静默期间故障状态和事件照常记录，通知会在静默结束后继续投递。',
              ),
            ),
          )
        else
          ..._notificationSilences.map(
            (silence) => Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Card(
                child: ListTile(
                  dense: true,
                  leading: const Icon(
                    Icons.notifications_paused_outlined,
                    color: _muted,
                  ),
                  title: Text(
                    silence['name'] as String? ?? '通知静默',
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  subtitle: Text(
                    '${silence['projectName']} · ${silence['resourceName'] ?? '全部资源'} · ${_dateTime(silence['startsAt'] as String?)} 至 ${_dateTime(silence['endsAt'] as String?)}${(silence['description'] as String? ?? '').isEmpty ? '' : ' · ${silence['description']}'}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 10, color: _muted),
                  ),
                  trailing: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _MaintenanceWindowStatus(
                        status: silence['status'] as String? ?? 'ENDED',
                      ),
                      if (!widget.readOnly &&
                          (silence['status'] == 'ACTIVE' ||
                              silence['status'] == 'SCHEDULED'))
                        IconButton(
                          tooltip: '取消通知静默',
                          onPressed: () => _cancelNotificationSilence(silence),
                          icon: const Icon(Icons.close_rounded, size: 18),
                        ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        const SizedBox(height: 20),
        Row(
          children: [
            const Expanded(
              child: Text(
                '通知渠道',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '${_channels.length} 个渠道',
              style: const TextStyle(color: _muted, fontSize: 11),
            ),
          ],
        ),
        const SizedBox(height: 10),
        if (_loading && _channels.isEmpty)
          const Center(
            child: Padding(
              padding: EdgeInsets.all(36),
              child: CircularProgressIndicator(),
            ),
          )
        else if (_channels.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(32),
              child: _EmptyState(
                icon: Icons.notifications_none_rounded,
                title: '还没有通知渠道',
                subtitle: '添加邮件或 Webhook 渠道后，项目中的故障和恢复事件会进入持久化发送队列。',
              ),
            ),
          )
        else
          ..._channels.map(
            (channel) => Padding(
              padding: const EdgeInsets.only(bottom: 11),
              child: Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Row(
                    children: [
                      Container(
                        width: 42,
                        height: 42,
                        decoration: BoxDecoration(
                          color: const Color(0xFFEAF0FA),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Icon(
                          channel['type'] == 'EMAIL'
                              ? Icons.mail_outline_rounded
                              : Icons.webhook_rounded,
                          color: _blue,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              channel['name'] as String? ?? '通知渠道',
                              style: const TextStyle(
                                fontWeight: FontWeight.w700,
                                fontSize: 13,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              '${channel['projectName']}  ·  ${channel['type'] == 'EMAIL' ? '邮件' : 'Webhook'}  ·  ${channel['destinationSummary']}  ·  每分钟最多发送 ${channel['rateLimitPerMinute'] ?? 60} 批',
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                              style: const TextStyle(
                                color: _muted,
                                fontSize: 11,
                              ),
                            ),
                          ],
                        ),
                      ),
                      if (!widget.readOnly) ...[
                        IconButton(
                          tooltip: '发送测试通知',
                          onPressed: _testingId == channel['id']
                              ? null
                              : () => _testChannel(channel),
                          icon: _testingId == channel['id']
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.send_outlined, size: 19),
                        ),
                        IconButton(
                          tooltip: '删除渠道',
                          onPressed: () => _deleteChannel(channel),
                          icon: const Icon(
                            Icons.delete_outline_rounded,
                            size: 19,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ),
            ),
          ),
        const SizedBox(height: 20),
        Row(
          children: [
            const Expanded(
              child: Text(
                '最近投递',
                style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
              ),
            ),
            Text(
              '${_deliveries.length} 条记录',
              style: const TextStyle(color: _muted, fontSize: 11),
            ),
          ],
        ),
        const SizedBox(height: 10),
        if (_deliveries.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: _EmptyState(
                icon: Icons.outbox_outlined,
                title: '暂无投递记录',
                subtitle: '故障、恢复和测试通知的每次发送结果都会显示在这里。',
              ),
            ),
          )
        else
          Card(
            child: Column(
              children: _deliveries
                  .map(
                    (delivery) => ListTile(
                      dense: true,
                      leading: _DeliveryStatus(
                        status: delivery['status'] as String? ?? 'RETRYING',
                      ),
                      title: Text(
                        '${delivery['eventType']}  ·  ${delivery['channelName']}',
                        style: const TextStyle(
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      subtitle: Text(
                        '${delivery['errorSummary'] ?? '投递成功'}${(delivery['groupCount'] as int? ?? 1) > 1 ? '  ·  合并 ${(delivery['groupCount'] as int)} 条' : ''}${delivery['httpStatus'] == null ? '' : '  ·  HTTP ${delivery['httpStatus']}'}  ·  第 ${delivery['attemptNumber']} 次',
                        style: const TextStyle(fontSize: 10, color: _muted),
                      ),
                      trailing: Text(
                        _dateTime(delivery['finishedAt'] as String?),
                        style: const TextStyle(fontSize: 10, color: _muted),
                      ),
                    ),
                  )
                  .toList(),
            ),
          ),
      ],
    ),
  );
}

class _DeliveryStatus extends StatelessWidget {
  const _DeliveryStatus({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final (color, label) = switch (status) {
      'DELIVERED' => (const Color(0xFF2C8B66), '成功'),
      'FAILED' => (const Color(0xFFB44438), '失败'),
      'CANCELLED' => (const Color(0xFF7B8794), '取消'),
      _ => (const Color(0xFFC8743E), '重试'),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(label, style: TextStyle(color: color, fontSize: 10)),
    );
  }
}

class _MaintenanceWindowStatus extends StatelessWidget {
  const _MaintenanceWindowStatus({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final (color, label) = switch (status) {
      'ACTIVE' => (_blue, '生效中'),
      'SCHEDULED' => (const Color(0xFF2C8B66), '待开始'),
      'CANCELLED' => (_muted, '已取消'),
      _ => (_muted, '已结束'),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(label, style: TextStyle(color: color, fontSize: 10)),
    );
  }
}

class _MaintenanceWindowDraft {
  const _MaintenanceWindowDraft({
    required this.projectId,
    required this.resourceId,
    required this.name,
    required this.description,
    required this.startsAt,
    required this.endsAt,
  });

  final String projectId;
  final String? resourceId;
  final String name;
  final String description;
  final DateTime startsAt;
  final DateTime endsAt;

  Map<String, dynamic> toJson() => {
    'projectId': projectId,
    'resourceId': resourceId,
    'name': name,
    'description': description,
    'startsAt': startsAt.toUtc().toIso8601String(),
    'endsAt': endsAt.toUtc().toIso8601String(),
  };
}

class _MaintenanceWindowDialog extends StatefulWidget {
  const _MaintenanceWindowDialog({
    required this.projects,
    required this.resources,
    this.title = '安排维护窗口',
    this.isSilence = false,
  });

  final List<Map<String, dynamic>> projects;
  final List<Map<String, dynamic>> resources;
  final String title;
  final bool isSilence;

  @override
  State<_MaintenanceWindowDialog> createState() =>
      _MaintenanceWindowDialogState();
}

class _MaintenanceWindowDialogState extends State<_MaintenanceWindowDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _description = TextEditingController();
  late String _projectId = widget.projects.first['id'] as String;
  String _resourceSelection = '';
  late DateTime _startsAt = DateTime.now().add(const Duration(minutes: 5));
  late DateTime _endsAt = DateTime.now().add(const Duration(hours: 1));
  String? _timeError;

  List<Map<String, dynamic>> get _projectResources => widget.resources
      .where((resource) => resource['projectId'] == _projectId)
      .toList();

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<DateTime?> _pickDateTime(DateTime initial) async {
    final today = DateTime.now();
    final date = await showDatePicker(
      context: context,
      initialDate: initial.isBefore(today) ? today : initial,
      firstDate: DateTime(today.year, today.month, today.day),
      lastDate: DateTime(today.year + 5, today.month, today.day),
    );
    if (date == null || !mounted) return null;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(initial),
    );
    if (time == null) return null;
    return DateTime(date.year, date.month, date.day, time.hour, time.minute);
  }

  String _formatDateTime(BuildContext context, DateTime value) =>
      '${MaterialLocalizations.of(context).formatMediumDate(value)} ${MaterialLocalizations.of(context).formatTimeOfDay(TimeOfDay.fromDateTime(value))}';

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: SizedBox(
      width: 490,
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _projectId,
                decoration: const InputDecoration(labelText: '所属项目'),
                items: widget.projects
                    .map(
                      (project) => DropdownMenuItem(
                        value: project['id'] as String,
                        child: Text(project['name'] as String),
                      ),
                    )
                    .toList(),
                onChanged: (value) => setState(() {
                  _projectId = value!;
                  _resourceSelection = '';
                }),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                key: ValueKey(_projectId),
                initialValue: _resourceSelection,
                decoration: InputDecoration(
                  labelText: widget.isSilence ? '静默范围' : '维护范围',
                ),
                items: [
                  const DropdownMenuItem(value: '', child: Text('项目内全部资源')),
                  ..._projectResources.map(
                    (resource) => DropdownMenuItem(
                      value: resource['id'] as String,
                      child: Text(resource['name'] as String),
                    ),
                  ),
                ],
                onChanged: (value) =>
                    setState(() => _resourceSelection = value ?? ''),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _name,
                maxLength: 160,
                decoration: const InputDecoration(labelText: '维护名称'),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? '请输入维护名称' : null,
              ),
              TextFormField(
                controller: _description,
                maxLength: 1000,
                maxLines: 2,
                decoration: const InputDecoration(labelText: '说明（可选）'),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  final value = await _pickDateTime(_startsAt);
                  if (value != null) {
                    setState(() {
                      _startsAt = value;
                      _timeError = null;
                    });
                  }
                },
                icon: const Icon(Icons.play_arrow_rounded, size: 18),
                label: Text('开始：${_formatDateTime(context, _startsAt)}'),
              ),
              const SizedBox(height: 8),
              OutlinedButton.icon(
                onPressed: () async {
                  final value = await _pickDateTime(_endsAt);
                  if (value != null) {
                    setState(() {
                      _endsAt = value;
                      _timeError = null;
                    });
                  }
                },
                icon: const Icon(Icons.stop_rounded, size: 18),
                label: Text('结束：${_formatDateTime(context, _endsAt)}'),
              ),
              if (_timeError != null) ...[
                const SizedBox(height: 8),
                Text(
                  _timeError!,
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.error,
                    fontSize: 12,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () {
          if (!_formKey.currentState!.validate()) return;
          if (_startsAt.isBefore(
                DateTime.now().subtract(const Duration(minutes: 5)),
              ) ||
              !_endsAt.isAfter(_startsAt) ||
              !_endsAt.isAfter(DateTime.now())) {
            setState(() => _timeError = '请设置有效的开始和结束时间');
            return;
          }
          Navigator.pop(
            context,
            _MaintenanceWindowDraft(
              projectId: _projectId,
              resourceId: _resourceSelection.isEmpty
                  ? null
                  : _resourceSelection,
              name: _name.text.trim(),
              description: _description.text.trim(),
              startsAt: _startsAt,
              endsAt: _endsAt,
            ),
          );
        },
        child: Text(widget.isSilence ? '创建通知静默' : '创建维护窗口'),
      ),
    ],
  );
}

class _NotificationChannelDraft {
  const _NotificationChannelDraft({
    required this.projectId,
    required this.name,
    required this.type,
    required this.emailRecipients,
    required this.webhookUrl,
    required this.webhookBearerToken,
    required this.rateLimitPerMinute,
  });

  final String projectId;
  final String name;
  final String type;
  final List<String> emailRecipients;
  final String webhookUrl;
  final String webhookBearerToken;
  final int rateLimitPerMinute;

  Map<String, dynamic> toJson() => {
    'projectId': projectId,
    'name': name,
    'type': type,
    'emailRecipients': type == 'EMAIL' ? emailRecipients : null,
    'webhookUrl': type == 'WEBHOOK' ? webhookUrl : null,
    'webhookBearerToken': type == 'WEBHOOK' ? webhookBearerToken : null,
    'rateLimitPerMinute': rateLimitPerMinute,
  };
}

class _NotificationChannelDialog extends StatefulWidget {
  const _NotificationChannelDialog({required this.projects});
  final List<Map<String, dynamic>> projects;

  @override
  State<_NotificationChannelDialog> createState() =>
      _NotificationChannelDialogState();
}

class _NotificationChannelDialogState
    extends State<_NotificationChannelDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _projectId = widget.projects.first['id'] as String;
  String _type = 'EMAIL';
  final _name = TextEditingController();
  final _recipients = TextEditingController();
  final _url = TextEditingController();
  final _token = TextEditingController();
  final _rateLimit = TextEditingController(text: '60');

  @override
  void dispose() {
    _name.dispose();
    _recipients.dispose();
    _url.dispose();
    _token.dispose();
    _rateLimit.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('添加通知渠道'),
    content: SizedBox(
      width: 490,
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _projectId,
                decoration: const InputDecoration(labelText: '所属项目'),
                items: widget.projects
                    .map(
                      (project) => DropdownMenuItem(
                        value: project['id'] as String,
                        child: Text(project['name'] as String),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value != null) setState(() => _projectId = value);
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _rateLimit,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: '每分钟发送上限',
                  helperText: '同类事件会先聚合 15 秒；范围 1–600 批/分钟。',
                ),
                validator: (value) {
                  final limit = int.tryParse((value ?? '').trim());
                  return limit == null || limit < 1 || limit > 600
                      ? '请输入 1 到 600 之间的整数'
                      : null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _name,
                autofocus: true,
                maxLength: 120,
                decoration: const InputDecoration(labelText: '渠道名称'),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? '请输入渠道名称' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: '渠道类型'),
                items: const [
                  DropdownMenuItem(value: 'EMAIL', child: Text('邮件')),
                  DropdownMenuItem(value: 'WEBHOOK', child: Text('通用 Webhook')),
                ],
                onChanged: (value) {
                  if (value != null) setState(() => _type = value);
                },
              ),
              const SizedBox(height: 12),
              if (_type == 'EMAIL')
                TextFormField(
                  controller: _recipients,
                  keyboardType: TextInputType.emailAddress,
                  maxLines: 2,
                  decoration: const InputDecoration(
                    labelText: '收件人邮箱',
                    hintText: 'ops@example.com, owner@example.com',
                    helperText: '多个邮箱用逗号分隔，最多 20 个。',
                  ),
                  validator: (value) {
                    final values = (value ?? '')
                        .split(',')
                        .map((item) => item.trim())
                        .where((item) => item.isNotEmpty);
                    return values.isEmpty ||
                            values.any((item) => !item.contains('@'))
                        ? '请输入有效邮箱地址'
                        : null;
                  },
                )
              else ...[
                TextFormField(
                  controller: _url,
                  keyboardType: TextInputType.url,
                  decoration: const InputDecoration(
                    labelText: 'Webhook URL',
                    hintText: 'https://hooks.example.com/…',
                    helperText: '生产环境要求 HTTPS；重定向不会跟随。',
                  ),
                  validator: (value) {
                    final uri = Uri.tryParse((value ?? '').trim());
                    return uri == null ||
                            !{'https', 'http'}.contains(uri.scheme) ||
                            uri.host.isEmpty
                        ? '请输入有效的 HTTP(S) 地址'
                        : null;
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _token,
                  obscureText: true,
                  decoration: const InputDecoration(
                    labelText: 'Bearer Token（可选）',
                    helperText: '保存后加密，之后不会再次显示。',
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () {
          if (!_formKey.currentState!.validate()) return;
          Navigator.pop(
            context,
            _NotificationChannelDraft(
              projectId: _projectId,
              name: _name.text.trim(),
              type: _type,
              emailRecipients: _recipients.text
                  .split(',')
                  .map((value) => value.trim())
                  .where((value) => value.isNotEmpty)
                  .toList(),
              webhookUrl: _url.text.trim(),
              webhookBearerToken: _token.text,
              rateLimitPerMinute: int.parse(_rateLimit.text.trim()),
            ),
          );
        },
        child: const Text('保存'),
      ),
    ],
  );
}

class _MonitorsPage extends StatelessWidget {
  const _MonitorsPage({
    required this.monitors,
    required this.resources,
    required this.loading,
    required this.onCreate,
    required this.onEdit,
    required this.onDelete,
    required this.onTest,
    required this.onHistory,
    this.onRotateHeartbeatToken,
  });

  final List<Map<String, dynamic>> monitors;
  final List<Map<String, dynamic>> resources;
  final bool loading;
  final VoidCallback onCreate;
  final ValueChanged<Map<String, dynamic>> onEdit;
  final ValueChanged<Map<String, dynamic>> onDelete;
  final ValueChanged<Map<String, dynamic>> onTest;
  final ValueChanged<Map<String, dynamic>> onHistory;
  final ValueChanged<Map<String, dynamic>>? onRotateHeartbeatToken;

  @override
  Widget build(BuildContext context) => _PageScroll(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'MONITOR CONFIGURATION',
          title: '监控配置',
          subtitle: '配置 HTTP、TCP、DNS、TLS 检查、主机指标阈值和定时任务心跳。',
          actions: [
            FilledButton.icon(
              onPressed: onCreate,
              icon: const Icon(Icons.add_rounded),
              label: const Text('添加检查'),
            ),
          ],
        ),
        if (loading && monitors.isEmpty)
          const Center(
            child: Padding(
              padding: EdgeInsets.all(40),
              child: CircularProgressIndicator(),
            ),
          )
        else if (monitors.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(34),
              child: _EmptyState(
                icon: Icons.monitor_heart_outlined,
                title: '还没有检查项',
                subtitle: '为网站、主机、服务或定时任务添加对应检查。',
              ),
            ),
          )
        else
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth > 1100 ? 2 : 1;
              final width =
                  (constraints.maxWidth - (columns - 1) * 14) / columns;
              return Wrap(
                spacing: 14,
                runSpacing: 14,
                children: monitors.map((monitor) {
                  final status = monitor['state'] as String? ?? 'PENDING';
                  final type = monitor['type'] as String? ?? 'HTTP';
                  final host = monitor['targetHost'] as String? ?? '';
                  final displayHost =
                      host.contains(':') && !host.startsWith('[')
                      ? '[$host]'
                      : host;
                  final target = type == 'METRIC_THRESHOLD'
                      ? '${monitor['metricName']} ${_metricOperatorSymbol(monitor['metricOperator'] as String?)} ${monitor['metricThreshold']} / ${monitor['metricWindowSeconds']} 秒窗口'
                      : type == 'HEARTBEAT'
                      ? '任务上报 · 宽限 ${monitor['heartbeatGraceSeconds'] ?? 60} 秒'
                      : const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(type)
                      ? '$displayHost:${monitor['port']} · ${type == 'REDIS' ? 'DB ${monitor['redisDatabase'] ?? 0}' : monitor['databaseName'] ?? ''}'
                      : type == 'HTTP'
                      ? monitor['targetUrl'] as String? ?? ''
                      : type == 'DNS'
                      ? '$displayHost · ${monitor['recordType']}'
                      : '$displayHost:${monitor['port']}';
                  final resourceName = monitor['resourceName'] as String? ?? '';
                  return SizedBox(
                    width: width,
                    child: Card(
                      child: Padding(
                        padding: const EdgeInsets.all(18),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Container(
                                  width: 38,
                                  height: 38,
                                  decoration: BoxDecoration(
                                    color: const Color(0xFFEAF0FA),
                                    borderRadius: BorderRadius.circular(12),
                                  ),
                                  child: Icon(
                                    _monitorIcon(type),
                                    color: _blue,
                                    size: 20,
                                  ),
                                ),
                                const SizedBox(width: 11),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        monitor['name'] as String? ??
                                            '${_monitorTypeName(type)} 检查',
                                        style: const TextStyle(
                                          fontWeight: FontWeight.w700,
                                          fontSize: 14,
                                        ),
                                      ),
                                      const SizedBox(height: 4),
                                      Text(
                                        '$resourceName  ·  ${_monitorTypeName(type)}  ·  每 ${monitor['intervalSeconds']} 秒',
                                        style: const TextStyle(
                                          color: _muted,
                                          fontSize: 10,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                _StatusTag(status: status),
                              ],
                            ),
                            const SizedBox(height: 16),
                            Container(
                              width: double.infinity,
                              padding: const EdgeInsets.symmetric(
                                horizontal: 11,
                                vertical: 10,
                              ),
                              decoration: BoxDecoration(
                                color: const Color(0xFFF6F8FA),
                                borderRadius: BorderRadius.circular(10),
                              ),
                              child: Text(
                                target,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: Color(0xFF3E5268),
                                  fontSize: 11,
                                  fontFamily: 'monospace',
                                ),
                              ),
                            ),
                            const SizedBox(height: 14),
                            Wrap(
                              spacing: 8,
                              runSpacing: 7,
                              children: [
                                _InfoPill(
                                  icon: Icons.error_outline_rounded,
                                  text:
                                      '连续 ${monitor['failureThreshold']} 次失败触发',
                                ),
                                _InfoPill(
                                  icon: Icons.restart_alt_rounded,
                                  text: '${monitor['recoveryThreshold']} 次成功恢复',
                                ),
                                if (type != 'HEARTBEAT' &&
                                    type != 'METRIC_THRESHOLD')
                                  _InfoPill(
                                    icon: Icons.schedule_outlined,
                                    text: '超时 ${monitor['timeoutSeconds']} 秒',
                                  ),
                                if (monitor['critical'] == true)
                                  const _InfoPill(
                                    icon: Icons.priority_high_rounded,
                                    text: '关键检查',
                                  ),
                              ],
                            ),
                            const SizedBox(height: 13),
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    '最近结果：${_dateTime(monitor['lastResultAt'] as String?)}',
                                    style: const TextStyle(
                                      color: _muted,
                                      fontSize: 10,
                                    ),
                                  ),
                                ),
                                IconButton(
                                  tooltip: '立即试运行',
                                  onPressed: () => onTest(monitor),
                                  icon: const Icon(
                                    Icons.play_circle_outline_rounded,
                                    color: _blue,
                                    size: 19,
                                  ),
                                ),
                                IconButton(
                                  tooltip: '查看历史',
                                  onPressed: () => onHistory(monitor),
                                  icon: const Icon(
                                    Icons.history_rounded,
                                    color: _muted,
                                    size: 19,
                                  ),
                                ),
                                if (type == 'HEARTBEAT' &&
                                    onRotateHeartbeatToken != null)
                                  IconButton(
                                    tooltip: '轮换任务凭据',
                                    onPressed: () =>
                                        onRotateHeartbeatToken!(monitor),
                                    icon: const Icon(
                                      Icons.key_outlined,
                                      color: _muted,
                                      size: 18,
                                    ),
                                  ),
                                IconButton(
                                  tooltip: '编辑',
                                  onPressed: () => onEdit(monitor),
                                  icon: const Icon(
                                    Icons.edit_outlined,
                                    color: _muted,
                                    size: 18,
                                  ),
                                ),
                                IconButton(
                                  tooltip: '删除',
                                  onPressed: () => onDelete(monitor),
                                  icon: const Icon(
                                    Icons.delete_outline_rounded,
                                    color: _muted,
                                    size: 18,
                                  ),
                                ),
                              ],
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                }).toList(),
              );
            },
          ),
      ],
    ),
  );
}

class _InfoPill extends StatelessWidget {
  const _InfoPill({required this.icon, required this.text});
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 6),
    decoration: BoxDecoration(
      color: const Color(0xFFF3F5F8),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 13, color: _muted),
        const SizedBox(width: 5),
        Text(text, style: const TextStyle(color: _muted, fontSize: 10)),
      ],
    ),
  );
}

class _MonitorDraft {
  const _MonitorDraft({
    required this.type,
    required this.resourceId,
    required this.name,
    required this.target,
    required this.method,
    required this.allowPostNoSideEffects,
    required this.credentialId,
    required this.port,
    required this.recordType,
    required this.resolver,
    required this.expectedRecordValue,
    required this.minimumValidityDays,
    required this.intervalSeconds,
    required this.timeoutSeconds,
    required this.heartbeatGraceSeconds,
    required this.metricName,
    required this.metricOperator,
    required this.metricThreshold,
    required this.metricRecoveryValue,
    required this.metricWindowSeconds,
    required this.metricSeverity,
    required this.databaseName,
    required this.databaseUsername,
    required this.databasePassword,
    required this.databaseTls,
    required this.redisDatabase,
    required this.failureThreshold,
    required this.recoveryThreshold,
    required this.reminderIntervalMinutes,
    required this.expectedStatusCodes,
    required this.textContains,
    required this.jsonPath,
    required this.jsonExpectedValue,
    required this.requestHeaders,
    required this.critical,
    required this.enabled,
  });

  final String type;
  final String resourceId;
  final String name;
  final String target;
  final String method;
  final bool allowPostNoSideEffects;
  final String credentialId;
  final int? port;
  final String? recordType;
  final String? resolver;
  final String? expectedRecordValue;
  final int? minimumValidityDays;
  final int intervalSeconds;
  final int timeoutSeconds;
  final int heartbeatGraceSeconds;
  final String? metricName;
  final String? metricOperator;
  final double? metricThreshold;
  final double? metricRecoveryValue;
  final int? metricWindowSeconds;
  final String? metricSeverity;
  final String? databaseName;
  final String? databaseUsername;
  final String? databasePassword;
  final bool databaseTls;
  final int? redisDatabase;
  final int failureThreshold;
  final int recoveryThreshold;
  final int reminderIntervalMinutes;
  final List<int> expectedStatusCodes;
  final String? textContains;
  final String? jsonPath;
  final String? jsonExpectedValue;
  final Map<String, String> requestHeaders;
  final bool critical;
  final bool enabled;

  Map<String, dynamic> toJson() => {
    'resourceId': resourceId,
    'name': name,
    'type': type,
    'intervalSeconds': intervalSeconds,
    'timeoutSeconds': timeoutSeconds,
    if (type == 'HEARTBEAT') 'heartbeatGraceSeconds': heartbeatGraceSeconds,
    if (type == 'METRIC_THRESHOLD') ...{
      'metricName': metricName,
      'metricOperator': metricOperator,
      'metricThreshold': metricThreshold,
      'metricRecoveryValue': metricRecoveryValue,
      'metricWindowSeconds': metricWindowSeconds,
      'metricSeverity': metricSeverity,
    },
    if (const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(type)) ...{
      'targetHost': target,
      'port': port,
      'databaseName': databaseName,
      'databaseUsername': databaseUsername,
      if (databasePassword?.isNotEmpty == true)
        'databasePassword': databasePassword,
      'databaseTls': databaseTls,
      if (type == 'REDIS') 'redisDatabase': redisDatabase,
    },
    'failureThreshold': failureThreshold,
    'recoveryThreshold': recoveryThreshold,
    'reminderIntervalMinutes': reminderIntervalMinutes,
    'critical': critical,
    'enabled': enabled,
    if (type == 'HTTP') ...{
      'targetUrl': target,
      'method': method,
      'allowPostNoSideEffects': method == 'POST' && allowPostNoSideEffects,
      'credentialId': credentialId,
      'expectedStatusCodes': expectedStatusCodes,
      'textContains': textContains,
      'jsonPath': jsonPath,
      'jsonExpectedValue': jsonExpectedValue,
      'requestHeaders': requestHeaders,
    },
    if (type == 'TCP') ...{'targetHost': target, 'port': port},
    if (type == 'DNS') ...{
      'targetHost': target,
      'recordType': recordType,
      'resolver': resolver,
      'expectedRecordValue': expectedRecordValue,
    },
    if (type == 'TLS') ...{
      'targetHost': target,
      'port': port,
      'minimumValidityDays': minimumValidityDays,
    },
  };
}

class _ProjectCredentialDraft {
  const _ProjectCredentialDraft({
    required this.name,
    required this.headers,
    required this.allowedOrigins,
  });

  final String name;
  final Map<String, String> headers;
  final List<String> allowedOrigins;
}

class _ProjectCredentialDialog extends StatefulWidget {
  const _ProjectCredentialDialog({this.credential});

  final Map<String, dynamic>? credential;

  @override
  State<_ProjectCredentialDialog> createState() =>
      _ProjectCredentialDialogState();
}

class _ProjectCredentialDialogState extends State<_ProjectCredentialDialog> {
  final _formKey = GlobalKey<FormState>();
  late final _name = TextEditingController(
    text: widget.credential?['name'] as String? ?? '',
  );
  late final _headers = TextEditingController(
    text: ((widget.credential?['headerNames'] as List?) ?? const [])
        .map((name) => '$name: ')
        .join('\n'),
  );
  late final _allowedOrigins = TextEditingController(
    text: ((widget.credential?['allowedOrigins'] as List?) ?? const []).join(
      '\n',
    ),
  );

  @override
  void dispose() {
    _name.dispose();
    _headers.dispose();
    _allowedOrigins.dispose();
    super.dispose();
  }

  Map<String, String>? _parseHeaders() {
    final parsed = <String, String>{};
    final seen = <String>{};
    for (final line in _headers.text.split('\n')) {
      if (line.trim().isEmpty) continue;
      final separator = line.indexOf(':');
      if (separator <= 0) return null;
      final name = line.substring(0, separator).trim();
      final value = line.substring(separator + 1).trim();
      final normalized = name.toLowerCase();
      if (name.length > 128 ||
          !RegExp(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").hasMatch(name) ||
          const {
            'host',
            'content-length',
            'transfer-encoding',
            'connection',
            'keep-alive',
            'proxy-authenticate',
            'proxy-authorization',
            'proxy-connection',
            'te',
            'trailer',
            'upgrade',
          }.contains(normalized) ||
          normalized.startsWith('proxy-') ||
          value.length > 4096 ||
          value.codeUnits.any(
            (code) => code < 32 || (code >= 127 && code <= 159),
          ) ||
          !seen.add(normalized)) {
        return null;
      }
      parsed[normalized] = value;
    }
    if (parsed.length > 32 ||
        parsed.entries.fold<int>(
              0,
              (total, entry) => total + entry.key.length + entry.value.length,
            ) >
            16384) {
      return null;
    }
    if (widget.credential == null &&
        (parsed.isEmpty || parsed.values.every((value) => value.isEmpty))) {
      return null;
    }
    return parsed;
  }

  List<String>? _parseAllowedOrigins() {
    final origins = <String>{};
    for (final line in _allowedOrigins.text.split('\n')) {
      final value = line.trim();
      if (value.isEmpty) continue;
      final uri = Uri.tryParse(value);
      if (uri == null ||
          !uri.hasAuthority ||
          !{'http', 'https'}.contains(uri.scheme.toLowerCase()) ||
          uri.host.isEmpty ||
          uri.userInfo.isNotEmpty ||
          !{'', '/'}.contains(uri.path) ||
          uri.hasQuery ||
          uri.hasFragment) {
        return null;
      }
      final port = uri.hasPort ? uri.port : null;
      if (port != null && (port < 1 || port > 65535)) return null;
      final scheme = uri.scheme.toLowerCase();
      final defaultPort =
          (scheme == 'http' && port == 80) ||
          (scheme == 'https' && port == 443);
      final host = uri.host.toLowerCase();
      final formattedHost = host.contains(':') && !host.startsWith('[')
          ? '[$host]'
          : host;
      origins.add(
        '$scheme://$formattedHost${port == null || defaultPort ? '' : ':$port'}',
      );
    }
    if (origins.isEmpty || origins.length > 32) return null;
    return origins.toList();
  }

  void _save() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.pop(
      context,
      _ProjectCredentialDraft(
        name: _name.text.trim(),
        headers: _parseHeaders()!,
        allowedOrigins: _parseAllowedOrigins()!,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.credential == null ? '新建项目共享凭据' : '编辑项目共享凭据'),
    content: SizedBox(
      width: 520,
      child: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _name,
                maxLength: 100,
                decoration: const InputDecoration(labelText: '凭据名称'),
                validator: (value) => value == null || value.trim().isEmpty
                    ? '请输入凭据名称'
                    : value.trim().length > 100
                    ? '名称不能超过 100 个字符'
                    : null,
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _headers,
                maxLength: 16384,
                minLines: 4,
                maxLines: 8,
                decoration: InputDecoration(
                  labelText: 'HTTP 请求头',
                  hintText: 'Authorization: Bearer …\nX-API-Key: …',
                  helperText: widget.credential == null
                      ? '每行填写 Name: value。仅保存 AES-GCM 密文；创建后不会再次显示明文。'
                      : '已保存的值不回显。留空保留原值，删除整行移除该请求头。',
                  helperMaxLines: 3,
                  alignLabelWithHint: true,
                ),
                validator: (_) =>
                    _parseHeaders() == null ? '请求头格式无效、为空、重复或超过限制' : null,
              ),
              const SizedBox(height: 8),
              TextFormField(
                controller: _allowedOrigins,
                maxLength: 4096,
                minLines: 2,
                maxLines: 5,
                decoration: const InputDecoration(
                  labelText: '授权源站范围',
                  hintText:
                      'https://api.example.com\nhttps://login.example.com:8443',
                  helperText: '每行一个精确源站（协议、主机和端口）。请求头只会发送到这些源站。',
                  helperMaxLines: 2,
                  alignLabelWithHint: true,
                ),
                validator: (_) => _parseAllowedOrigins() == null
                    ? '至少填写一个有效源站，最多 32 个；不要填写路径、查询参数或片段'
                    : null,
              ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(onPressed: _save, child: const Text('保存凭据')),
    ],
  );
}

class _MonitorDialog extends StatefulWidget {
  const _MonitorDialog({
    required this.api,
    required this.resources,
    this.existing,
  });
  final ApiClient api;
  final List<Map<String, dynamic>> resources;
  final Map<String, dynamic>? existing;

  @override
  State<_MonitorDialog> createState() => _MonitorDialogState();
}

class _MonitorDialogState extends State<_MonitorDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _type = widget.existing?['type'] as String? ?? 'HTTP';
  late String _resourceId =
      widget.existing?['resourceId'] as String? ??
      (widget.resources.firstWhere(
            (resource) => resource['type'] != 'HEARTBEAT',
            orElse: () => widget.resources.first,
          )['id']
          as String);
  late String _method = widget.existing?['method'] as String? ?? 'GET';
  late bool _allowPostNoSideEffects =
      widget.existing?['allowPostNoSideEffects'] as bool? ?? false;
  late String _credentialId = widget.existing?['credentialId'] as String? ?? '';
  List<Map<String, dynamic>> _projectCredentials = [];
  bool _credentialsLoading = false;
  String? _credentialsError;
  late String _recordType = widget.existing?['recordType'] as String? ?? 'A';
  late String _metricName =
      widget.existing?['metricName'] as String? ?? 'cpu_usage_percent';
  late String _metricOperator =
      widget.existing?['metricOperator'] as String? ?? 'GT';
  late String _metricSeverity =
      widget.existing?['metricSeverity'] as String? ?? 'WARNING';
  late final _name = TextEditingController(
    text: widget.existing?['name'] as String? ?? '',
  );
  late final _target = TextEditingController(
    text: _type == 'HTTP'
        ? (widget.existing?['targetUrl'] as String? ?? '')
        : (widget.existing?['targetHost'] as String? ?? ''),
  );
  late final _port = TextEditingController(
    text: '${widget.existing?['port'] ?? _defaultMonitorPort(_type)}',
  );
  late final _databaseName = TextEditingController(
    text: widget.existing?['databaseName'] as String? ?? '',
  );
  late final _databaseUsername = TextEditingController(
    text: widget.existing?['databaseUsername'] as String? ?? '',
  );
  late final _databasePassword = TextEditingController();
  late final _redisDatabase = TextEditingController(
    text: '${widget.existing?['redisDatabase'] ?? 0}',
  );
  late final _resolver = TextEditingController(
    text: widget.existing?['resolver'] as String? ?? '',
  );
  late final _expectedRecordValue = TextEditingController(
    text: widget.existing?['expectedRecordValue'] as String? ?? '',
  );
  late final _minimumValidityDays = TextEditingController(
    text: '${widget.existing?['minimumValidityDays'] ?? 14}',
  );
  late final _interval = TextEditingController(
    text:
        '${widget.existing?['intervalSeconds'] ?? (_type == 'TLS' ? 21600 : 60)}',
  );
  late final _timeout = TextEditingController(
    text:
        '${widget.existing?['timeoutSeconds'] ?? (_type == 'DNS'
                ? 5
                : _type == 'HEARTBEAT' || _type == 'METRIC_THRESHOLD'
                ? 1
                : 10)}',
  );
  late final _heartbeatGrace = TextEditingController(
    text: '${widget.existing?['heartbeatGraceSeconds'] ?? 60}',
  );
  late final _metricThreshold = TextEditingController(
    text: '${widget.existing?['metricThreshold'] ?? 90}',
  );
  late final _metricRecoveryValue = TextEditingController(
    text: '${widget.existing?['metricRecoveryValue'] ?? 80}',
  );
  late final _metricWindow = TextEditingController(
    text: '${widget.existing?['metricWindowSeconds'] ?? 300}',
  );
  late final _failure = TextEditingController(
    text: '${widget.existing?['failureThreshold'] ?? 3}',
  );
  late final _recovery = TextEditingController(
    text: '${widget.existing?['recoveryThreshold'] ?? 2}',
  );
  late final _reminderInterval = TextEditingController(
    text: '${widget.existing?['reminderIntervalMinutes'] ?? 30}',
  );
  late final _statuses = TextEditingController(
    text: _statusText(widget.existing?['expectedStatusCodes'] as List?),
  );
  late final _contains = TextEditingController(
    text: widget.existing?['textContains'] as String? ?? '',
  );
  late final _jsonPath = TextEditingController(
    text: widget.existing?['jsonPath'] as String? ?? '',
  );
  late final _jsonExpectedValue = TextEditingController(
    text: widget.existing?['jsonExpectedValue'] as String? ?? '',
  );
  late final _requestHeaders = TextEditingController(
    text: (widget.existing?['requestHeaders'] as Map? ?? const {}).keys
        .map((name) => '$name: ')
        .join('\n'),
  );
  late bool _critical = widget.existing?['critical'] as bool? ?? true;
  late bool _enabled = widget.existing?['enabled'] as bool? ?? true;
  late bool _databaseTls =
      (widget.existing?['databaseTls'] as bool?) ?? (_type == 'MYSQL');

  @override
  void initState() {
    super.initState();
    _reloadProjectCredentials();
  }

  String? _projectIdForResource([String? resourceId]) {
    final selected = widget.resources.firstWhere(
      (resource) => resource['id'] == (resourceId ?? _resourceId),
      orElse: () => const <String, dynamic>{},
    );
    return selected['projectId'] as String?;
  }

  Future<void> _reloadProjectCredentials() async {
    final projectId = _projectIdForResource();
    if (projectId == null) {
      setState(() {
        _projectCredentials = [];
        _credentialsError = '无法确定资源所属项目';
        _credentialsLoading = false;
      });
      return;
    }
    setState(() {
      _credentialsLoading = true;
      _credentialsError = null;
    });
    try {
      final values = await widget.api.projectCredentials(projectId);
      if (!mounted) return;
      setState(() {
        _projectCredentials = values;
        _credentialsLoading = false;
        if (_credentialId.isNotEmpty &&
            !values.any((credential) => credential['id'] == _credentialId)) {
          _credentialId = '';
        }
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _credentialsError = error.toString();
        _credentialsLoading = false;
      });
    }
  }

  Future<void> _createProjectCredential() async {
    final projectId = _projectIdForResource();
    if (projectId == null) return;
    final draft = await showDialog<_ProjectCredentialDraft>(
      context: context,
      builder: (_) => const _ProjectCredentialDialog(),
    );
    if (draft == null) return;
    try {
      final credential = await widget.api.createProjectCredential(
        projectId: projectId,
        name: draft.name,
        headers: draft.headers,
        allowedOrigins: draft.allowedOrigins,
      );
      await _reloadProjectCredentials();
      if (!mounted) return;
      setState(() => _credentialId = credential['id'] as String);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('项目共享凭据已创建并选中')));
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  Future<void> _editProjectCredential() async {
    final projectId = _projectIdForResource();
    final credential = _projectCredentials
        .cast<Map<String, dynamic>?>()
        .firstWhere(
          (value) => value?['id'] == _credentialId,
          orElse: () => null,
        );
    if (projectId == null || credential == null) return;
    final draft = await showDialog<_ProjectCredentialDraft>(
      context: context,
      builder: (_) => _ProjectCredentialDialog(credential: credential),
    );
    if (draft == null) return;
    try {
      await widget.api.updateProjectCredential(
        projectId: projectId,
        id: _credentialId,
        name: draft.name,
        headers: draft.headers,
        allowedOrigins: draft.allowedOrigins,
        version: credential['version'] as int,
      );
      await _reloadProjectCredentials();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('项目共享凭据已更新')));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  Future<void> _deleteProjectCredential() async {
    final projectId = _projectIdForResource();
    final credential = _projectCredentials
        .cast<Map<String, dynamic>?>()
        .firstWhere(
          (value) => value?['id'] == _credentialId,
          orElse: () => null,
        );
    if (projectId == null || credential == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除项目共享凭据？'),
        content: Text('“${credential['name']}”将被删除。仍被监控项引用时，服务端会拒绝删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await widget.api.deleteProjectCredential(
        projectId: projectId,
        id: _credentialId,
      );
      if (!mounted) return;
      setState(() => _credentialId = '');
      await _reloadProjectCredentials();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('项目共享凭据已删除')));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    }
  }

  String _statusText(List? codes) {
    if (codes == null || codes.isEmpty) return '200-299';
    final values = codes.cast<int>().toList()..sort();
    if (values.length == 100 && values.first == 200 && values.last == 299) {
      return '200-299';
    }
    return values.join(', ');
  }

  @override
  void dispose() {
    _name.dispose();
    _target.dispose();
    _port.dispose();
    _databaseName.dispose();
    _databaseUsername.dispose();
    _databasePassword.dispose();
    _redisDatabase.dispose();
    _resolver.dispose();
    _expectedRecordValue.dispose();
    _minimumValidityDays.dispose();
    _interval.dispose();
    _timeout.dispose();
    _heartbeatGrace.dispose();
    _metricThreshold.dispose();
    _metricRecoveryValue.dispose();
    _metricWindow.dispose();
    _failure.dispose();
    _recovery.dispose();
    _reminderInterval.dispose();
    _statuses.dispose();
    _contains.dispose();
    _jsonPath.dispose();
    _jsonExpectedValue.dispose();
    _requestHeaders.dispose();
    super.dispose();
  }

  List<int>? _parseStatuses() {
    final parsed = <int>{};
    for (final part in _statuses.text.split(',')) {
      final segment = part.trim();
      if (segment.isEmpty) continue;
      if (segment.contains('-')) {
        final range = segment.split('-');
        if (range.length != 2) return null;
        final start = int.tryParse(range[0].trim());
        final end = int.tryParse(range[1].trim());
        if (start == null ||
            end == null ||
            start > end ||
            start < 100 ||
            end > 599 ||
            end - start > 99) {
          return null;
        }
        parsed.addAll(
          Iterable<int>.generate(end - start + 1, (index) => start + index),
        );
      } else {
        final code = int.tryParse(segment);
        if (code == null || code < 100 || code > 599) return null;
        parsed.add(code);
      }
    }
    return parsed.isEmpty ? null : (parsed.toList()..sort());
  }

  void _changeType(String type) {
    final previousType = _type;
    final previousIntervalDefault = previousType == 'TLS' ? 21600 : 60;
    final nextIntervalDefault = type == 'TLS' ? 21600 : 60;
    final previousTimeoutDefault = previousType == 'DNS'
        ? 5
        : previousType == 'HEARTBEAT' || previousType == 'METRIC_THRESHOLD'
        ? 1
        : 10;
    final nextTimeoutDefault = type == 'DNS'
        ? 5
        : type == 'HEARTBEAT' || type == 'METRIC_THRESHOLD'
        ? 1
        : 10;
    final previousPortDefault = _defaultMonitorPort(previousType);
    final nextPortDefault = _defaultMonitorPort(type);
    if (_interval.text == '$previousIntervalDefault') {
      _interval.text = '$nextIntervalDefault';
    }
    if (_timeout.text == '$previousTimeoutDefault') {
      _timeout.text = '$nextTimeoutDefault';
    }
    if (_port.text == '$previousPortDefault') _port.text = '$nextPortDefault';
    final compatibleResource = widget.resources.firstWhere((resource) {
      final resourceType = resource['type'] as String? ?? '';
      return switch (type) {
        'HEARTBEAT' => resourceType == 'HEARTBEAT',
        'METRIC_THRESHOLD' => resourceType == _metricResourceType(_metricName),
        'POSTGRESQL' || 'MYSQL' || 'REDIS' => resourceType == 'DATABASE',
        _ => resourceType != 'HEARTBEAT',
      };
    }, orElse: () => const <String, dynamic>{});
    setState(() {
      _type = type;
      if (type == 'MYSQL' &&
          !const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(previousType)) {
        _databaseTls = true;
      }
      if (compatibleResource.isNotEmpty) {
        _resourceId = compatibleResource['id'] as String;
      }
    });
    _reloadProjectCredentials();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(
      '${widget.existing == null ? '配置' : '编辑'}${_monitorTypeName(_type)}检查',
    ),
    content: SizedBox(
      width: 560,
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _resourceId,
                decoration: const InputDecoration(labelText: '监控资源'),
                items: widget.resources
                    .map(
                      (resource) => DropdownMenuItem(
                        value: resource['id'] as String,
                        child: Text(
                          '${resource['name']} · ${_resourceTypeName(resource['type'] as String? ?? '')}',
                        ),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value == null) return;
                  setState(() => _resourceId = value);
                  _reloadProjectCredentials();
                },
              ),
              const SizedBox(height: 11),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: '检查类型'),
                items: widget.existing?['type'] == 'HEARTBEAT'
                    ? const [
                        DropdownMenuItem(
                          value: 'HEARTBEAT',
                          child: Text('心跳任务'),
                        ),
                      ]
                    : const {
                        'POSTGRESQL',
                        'MYSQL',
                        'REDIS',
                      }.contains(widget.existing?['type'])
                    ? [
                        DropdownMenuItem(
                          value: widget.existing!['type'] as String,
                          child: Text(
                            _monitorTypeName(
                              widget.existing!['type'] as String,
                            ),
                          ),
                        ),
                      ]
                    : [
                        const DropdownMenuItem(
                          value: 'HTTP',
                          child: Text('HTTP/HTTPS'),
                        ),
                        const DropdownMenuItem(
                          value: 'TCP',
                          child: Text('TCP 端口'),
                        ),
                        const DropdownMenuItem(
                          value: 'DNS',
                          child: Text('DNS 记录'),
                        ),
                        const DropdownMenuItem(
                          value: 'TLS',
                          child: Text('TLS 证书'),
                        ),
                        const DropdownMenuItem(
                          value: 'METRIC_THRESHOLD',
                          child: Text('主机指标阈值'),
                        ),
                        const DropdownMenuItem(
                          value: 'POSTGRESQL',
                          child: Text('PostgreSQL'),
                        ),
                        const DropdownMenuItem(
                          value: 'MYSQL',
                          child: Text('MySQL'),
                        ),
                        const DropdownMenuItem(
                          value: 'REDIS',
                          child: Text('Redis'),
                        ),
                        if (widget.existing == null)
                          const DropdownMenuItem(
                            value: 'HEARTBEAT',
                            child: Text('心跳任务'),
                          ),
                      ],
                onChanged: (value) {
                  if (value == null) return;
                  _changeType(value);
                },
              ),
              const SizedBox(height: 11),
              TextFormField(
                controller: _name,
                autofocus: true,
                maxLength: 160,
                decoration: const InputDecoration(
                  labelText: '检查名称',
                  hintText: '例如：官网首页',
                ),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? '请输入检查名称' : null,
              ),
              const SizedBox(height: 11),
              if (_type == 'HTTP') ...[
                TextFormField(
                  controller: _target,
                  keyboardType: TextInputType.url,
                  decoration: const InputDecoration(
                    labelText: 'HTTP/HTTPS 地址',
                    hintText: 'https://example.com/health',
                  ),
                  validator: (value) {
                    final uri = Uri.tryParse(value?.trim() ?? '');
                    return uri == null ||
                            !uri.hasAuthority ||
                            !{'http', 'https'}.contains(uri.scheme) ||
                            uri.userInfo.isNotEmpty
                        ? '请输入有效的 HTTP 或 HTTPS 地址'
                        : null;
                  },
                ),
                const SizedBox(height: 11),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _method,
                        decoration: const InputDecoration(labelText: '请求方法'),
                        items: const [
                          DropdownMenuItem(value: 'GET', child: Text('GET')),
                          DropdownMenuItem(value: 'HEAD', child: Text('HEAD')),
                          DropdownMenuItem(value: 'POST', child: Text('POST')),
                        ],
                        onChanged: (value) {
                          if (value != null) setState(() => _method = value);
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _statuses,
                        decoration: const InputDecoration(
                          labelText: '成功状态码',
                          hintText: '200-299',
                        ),
                        validator: (_) => _parseStatuses() == null
                            ? '使用逗号或范围填写，如 200-204, 301'
                            : null,
                      ),
                    ),
                  ],
                ),
                if (_method == 'POST') ...[
                  const SizedBox(height: 6),
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _allowPostNoSideEffects,
                    onChanged: (value) => setState(
                      () => _allowPostNoSideEffects = value ?? false,
                    ),
                    title: const Text(
                      '我确认此 URL 是无副作用的检查端点',
                      style: TextStyle(fontSize: 12),
                    ),
                    subtitle: const Text(
                      '请求不会携带正文，但 POST 仍可能触发服务器写操作。未确认时不能保存。',
                      style: TextStyle(fontSize: 10),
                    ),
                    controlAffinity: ListTileControlAffinity.leading,
                  ),
                ],
                const SizedBox(height: 11),
                TextFormField(
                  controller: _contains,
                  maxLength: 500,
                  decoration: const InputDecoration(
                    labelText: '响应内容必须包含（可选）',
                    hintText: '例：service is healthy',
                  ),
                ),
                const SizedBox(height: 11),
                TextFormField(
                  controller: _jsonPath,
                  maxLength: 256,
                  decoration: const InputDecoration(
                    labelText: 'JSON 路径（JSON Pointer，可选）',
                    hintText: '/status/ready',
                  ),
                ),
                const SizedBox(height: 11),
                TextFormField(
                  controller: _jsonExpectedValue,
                  maxLength: 500,
                  minLines: 1,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'JSON 期望值（JSON 字面量）',
                    hintText: 'true、200 或 "ok"',
                    helperText: '路径和期望值需同时填写；与正文包含同时设置时，两项都必须匹配。',
                  ),
                  validator: (_) => _validateJsonAssertion(),
                ),
                const SizedBox(height: 11),
                TextFormField(
                  controller: _requestHeaders,
                  maxLength: 16384,
                  minLines: 3,
                  maxLines: 6,
                  decoration: const InputDecoration(
                    labelText: '自定义请求头（可选）',
                    hintText: 'Accept-Language: zh-CN\nAuthorization: Bearer …',
                    helperText:
                        '每行填写 Name: value。值会加密保存且读取时隐藏；编辑时留空保留原值，删除整行可移除。',
                    alignLabelWithHint: true,
                  ),
                  validator: (_) =>
                      _parseRequestHeaders() == null ? '请求头格式无效、重复或超过限制' : null,
                ),
                const SizedBox(height: 11),
                DropdownButtonFormField<String>(
                  initialValue: _credentialId,
                  decoration: InputDecoration(
                    labelText: '项目共享 HTTP 凭据',
                    helperText:
                        _credentialsError ?? '凭据仅在此项目内可用；同名自定义请求头会覆盖共享值。',
                    helperMaxLines: 2,
                  ),
                  items: [
                    const DropdownMenuItem(value: '', child: Text('不使用共享凭据')),
                    if (_credentialId.isNotEmpty &&
                        !_projectCredentials.any(
                          (credential) => credential['id'] == _credentialId,
                        ))
                      DropdownMenuItem(
                        value: _credentialId,
                        child: Text(_credentialsLoading ? '正在加载凭据…' : '凭据不可用'),
                      ),
                    ..._projectCredentials.map(
                      (credential) => DropdownMenuItem(
                        value: credential['id'] as String,
                        child: Text(
                          '${credential['name']} · ${(credential['headerNames'] as List? ?? const []).join(', ')}',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ),
                  ],
                  onChanged: (value) =>
                      setState(() => _credentialId = value ?? ''),
                ),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Wrap(
                    spacing: 4,
                    children: [
                      TextButton.icon(
                        onPressed: _credentialsLoading
                            ? null
                            : _createProjectCredential,
                        icon: const Icon(Icons.key_outlined, size: 16),
                        label: const Text('新建共享凭据'),
                      ),
                      if (_credentialId.isNotEmpty &&
                          _projectCredentials.any(
                            (credential) => credential['id'] == _credentialId,
                          )) ...[
                        TextButton.icon(
                          onPressed: _credentialsLoading
                              ? null
                              : _editProjectCredential,
                          icon: const Icon(Icons.edit_outlined, size: 16),
                          label: const Text('编辑凭据'),
                        ),
                        TextButton.icon(
                          onPressed: _credentialsLoading
                              ? null
                              : _deleteProjectCredential,
                          icon: const Icon(Icons.delete_outline, size: 16),
                          label: const Text('删除凭据'),
                        ),
                      ],
                    ],
                  ),
                ),
              ] else if (_type != 'HEARTBEAT') ...[
                TextFormField(
                  controller: _target,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(
                    labelText: _type == 'DNS'
                        ? '查询域名'
                        : const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type)
                        ? '数据库主机名或 IP'
                        : '目标主机名',
                    hintText: _type == 'DNS'
                        ? (_recordType == 'PTR'
                              ? '域名或 IP 地址'
                              : 'status.example.com')
                        : const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type)
                        ? 'db.example.com'
                        : 'example.com',
                  ),
                  validator: (value) => !_validMonitorHost(value ?? '')
                      ? '请输入有效主机名或 IP 地址'
                      : null,
                ),
                const SizedBox(height: 11),
                if (_type == 'TCP' ||
                    _type == 'TLS' ||
                    const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type))
                  _IntField(
                    controller: _port,
                    label: '端口',
                    minimum: 1,
                    maximum: 65535,
                  ),
                if (const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type)) ...[
                  if (_type != 'REDIS') ...[
                    const SizedBox(height: 11),
                    TextFormField(
                      controller: _databaseName,
                      maxLength: 63,
                      decoration: const InputDecoration(
                        labelText: '数据库名称',
                        hintText: 'app_db',
                      ),
                      validator: (value) =>
                          _type != 'REDIS' &&
                              !RegExp(
                                r'^[A-Za-z0-9_.-]{1,63}$',
                              ).hasMatch(value?.trim() ?? '')
                          ? '请填写有效数据库名（字母、数字、点、下划线或短横线）'
                          : null,
                    ),
                  ],
                  const SizedBox(height: 11),
                  TextFormField(
                    controller: _databaseUsername,
                    maxLength: 128,
                    decoration: InputDecoration(
                      labelText: _type == 'REDIS'
                          ? 'Redis ACL 用户（可选）'
                          : '只读监控用户名',
                    ),
                    validator: (value) =>
                        _type != 'REDIS' &&
                            (value == null || value.trim().isEmpty)
                        ? '请输入数据库用户名'
                        : null,
                  ),
                  const SizedBox(height: 11),
                  TextFormField(
                    controller: _databasePassword,
                    obscureText: true,
                    maxLength: 1024,
                    decoration: InputDecoration(
                      labelText: _type == 'REDIS' ? '密码（可选）' : '监控密码',
                      hintText:
                          widget.existing?['databasePasswordConfigured'] == true
                          ? '留空则保留已保存的密码'
                          : null,
                    ),
                    validator: (value) =>
                        _type != 'REDIS' &&
                            widget.existing?['databasePasswordConfigured'] !=
                                true &&
                            (value == null || value.isEmpty)
                        ? '请输入只读监控密码'
                        : null,
                  ),
                  if (_type == 'REDIS') ...[
                    const SizedBox(height: 11),
                    _IntField(
                      controller: _redisDatabase,
                      label: 'Redis 数据库编号',
                      minimum: 0,
                      maximum: 15,
                    ),
                  ],
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _databaseTls,
                    onChanged: (value) => setState(() => _databaseTls = value),
                    title: const Text(
                      '启用数据库 TLS',
                      style: TextStyle(fontSize: 13),
                    ),
                    subtitle: const Text(
                      '需要目标数据库配置受信任的证书',
                      style: TextStyle(fontSize: 10),
                    ),
                  ),
                ],
                if (_type == 'DNS') ...[
                  DropdownButtonFormField<String>(
                    initialValue: _recordType,
                    decoration: const InputDecoration(labelText: '记录类型'),
                    items: const [
                      DropdownMenuItem(value: 'A', child: Text('A')),
                      DropdownMenuItem(value: 'AAAA', child: Text('AAAA')),
                      DropdownMenuItem(value: 'CNAME', child: Text('CNAME')),
                      DropdownMenuItem(value: 'MX', child: Text('MX')),
                      DropdownMenuItem(value: 'TXT', child: Text('TXT')),
                      DropdownMenuItem(value: 'NS', child: Text('NS')),
                      DropdownMenuItem(value: 'SRV', child: Text('SRV')),
                      DropdownMenuItem(value: 'PTR', child: Text('PTR 反向解析')),
                    ],
                    onChanged: (value) {
                      if (value != null) setState(() => _recordType = value);
                    },
                  ),
                  const SizedBox(height: 11),
                  TextFormField(
                    controller: _resolver,
                    decoration: const InputDecoration(
                      labelText: 'DNS 解析器 IP（可选）',
                      hintText: '留空使用系统解析器',
                    ),
                    validator: (value) =>
                        value != null &&
                            value.trim().isNotEmpty &&
                            !_validMonitorHost(value, ipOnly: true)
                        ? '请填写解析器 IP 地址'
                        : null,
                  ),
                  const SizedBox(height: 11),
                  TextFormField(
                    controller: _expectedRecordValue,
                    decoration: const InputDecoration(
                      labelText: '期望记录值（可选）',
                      hintText: '例如：203.0.113.10',
                    ),
                  ),
                ],
                if (_type == 'TLS') ...[
                  const SizedBox(height: 11),
                  _IntField(
                    controller: _minimumValidityDays,
                    label: '最小证书剩余天数',
                    minimum: 0,
                    maximum: 3650,
                  ),
                  const SizedBox(height: 5),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      '证书到期会在剩余 30、14、7 天产生逐级提醒；此阈值决定监控状态何时标记为失败。',
                      style: TextStyle(color: _muted, fontSize: 10),
                    ),
                  ),
                ],
              ],
              if (_type == 'METRIC_THRESHOLD') ...[
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF3F6FB),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    _isDatabaseMetric(_metricName)
                        ? '使用数据库检查采集的连接数、上限或使用率。窗口至少覆盖 80% 的样本且相邻采样间隔不超过两倍周期；权限不足时指标显示未知，不会把数据库判为宕机。'
                        : '仅使用可信 Agent 样本。触发窗口至少覆盖 80% 的采样点，且任意相邻采样间隔不能超过两倍采样周期；数据不足时状态为未知。',
                    style: const TextStyle(
                      color: _muted,
                      fontSize: 11,
                      height: 1.45,
                    ),
                  ),
                ),
                const SizedBox(height: 11),
                DropdownButtonFormField<String>(
                  initialValue: _metricName,
                  decoration: const InputDecoration(labelText: '监控指标'),
                  items: _thresholdMetricNames
                      .map(
                        (metric) => DropdownMenuItem(
                          value: metric,
                          child: Text(_metricDisplayName(metric)),
                        ),
                      )
                      .toList(),
                  onChanged: (value) {
                    if (value == null) return;
                    final compatibleResource = widget.resources.firstWhere(
                      (resource) =>
                          resource['type'] == _metricResourceType(value),
                      orElse: () => const <String, dynamic>{},
                    );
                    setState(() {
                      _metricName = value;
                      if (compatibleResource.isNotEmpty) {
                        _resourceId = compatibleResource['id'] as String;
                      }
                    });
                  },
                ),
                const SizedBox(height: 11),
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _metricOperator,
                        decoration: const InputDecoration(labelText: '触发条件'),
                        items: const [
                          DropdownMenuItem(value: 'GT', child: Text('大于 >')),
                          DropdownMenuItem(value: 'GTE', child: Text('大于等于 ≥')),
                          DropdownMenuItem(value: 'LT', child: Text('小于 <')),
                          DropdownMenuItem(value: 'LTE', child: Text('小于等于 ≤')),
                        ],
                        onChanged: (value) {
                          if (value != null) {
                            setState(() => _metricOperator = value);
                          }
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _metricSeverity,
                        decoration: const InputDecoration(labelText: '事件级别'),
                        items: const [
                          DropdownMenuItem(value: 'WARNING', child: Text('警告')),
                          DropdownMenuItem(value: 'HIGH', child: Text('高')),
                          DropdownMenuItem(
                            value: 'CRITICAL',
                            child: Text('严重'),
                          ),
                        ],
                        onChanged: (value) {
                          if (value != null) {
                            setState(() => _metricSeverity = value);
                          }
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 11),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _metricThreshold,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: InputDecoration(
                          labelText: '触发值 ${_metricUnit(_metricName)}',
                        ),
                        validator: (value) => _validateMetricValue(value),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _metricRecoveryValue,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: InputDecoration(
                          labelText: '恢复值 ${_metricUnit(_metricName)}',
                        ),
                        validator: (value) => _validateMetricValue(value),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 11),
                _IntField(
                  controller: _metricWindow,
                  label: '持续窗口（秒）',
                  minimum: _isDatabaseMetric(_metricName) ? 240 : 120,
                  maximum: 86400,
                ),
                const SizedBox(height: 5),
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    '恢复值需位于触发阈值的安全侧；触发和恢复还分别受连续失败/成功次数控制。',
                    style: TextStyle(color: _muted, fontSize: 10, height: 1.4),
                  ),
                ),
              ],
              if (_type == 'HEARTBEAT') ...[
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF3F6FB),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Text(
                    '创建后会签发一次性任务凭据。任务脚本通过心跳 API 上报 SUCCESS 或 FAILURE；超过检查间隔与宽限期仍未上报时会触发故障。',
                    style: TextStyle(color: _muted, fontSize: 11, height: 1.45),
                  ),
                ),
                const SizedBox(height: 11),
                _IntField(
                  controller: _heartbeatGrace,
                  label: '缺报宽限期（秒）',
                  minimum: 0,
                  maximum: 86400,
                ),
              ],
              const SizedBox(height: 11),
              Row(
                children: [
                  Expanded(
                    child: _IntField(
                      controller: _interval,
                      label: '检查间隔（秒）',
                      minimum: 10,
                      maximum: 86400,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _IntField(
                      controller: _timeout,
                      label: '超时（秒）',
                      minimum: 1,
                      maximum: 30,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 11),
              Row(
                children: [
                  Expanded(
                    child: _IntField(
                      controller: _failure,
                      label: '连续失败次数',
                      minimum: 1,
                      maximum: 20,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _IntField(
                      controller: _recovery,
                      label: '连续成功恢复',
                      minimum: 1,
                      maximum: 20,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 11),
              _IntField(
                controller: _reminderInterval,
                label: '持续故障提醒间隔（分钟）',
                minimum: 0,
                maximum: 1440,
              ),
              const Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  '故障持续时按此间隔重复提醒；设为 0 可关闭。级别升级会立即提醒并重新开始计时。',
                  style: TextStyle(color: _muted, fontSize: 10, height: 1.4),
                ),
              ),
              const SizedBox(height: 8),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _critical,
                onChanged: (value) => setState(() => _critical = value),
                title: const Text('关键检查', style: TextStyle(fontSize: 13)),
                subtitle: const Text(
                  '参与资源整体健康状态计算',
                  style: TextStyle(fontSize: 11),
                ),
              ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                value: _enabled,
                onChanged: (value) => setState(() => _enabled = value),
                title: const Text('立即启用', style: TextStyle(fontSize: 13)),
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  _type == 'HEARTBEAT'
                      ? '任务计划间隔与宽限期决定何时判定缺报；具体执行频率由你的任务调度器控制。'
                      : _type == 'HTTP'
                      ? '检查间隔必须大于超时时间；未设置自定义断言时，默认接受 200–299。'
                      : '检查间隔必须大于超时时间；目标网络访问仍遵循服务端允许网段策略。',
                  style: TextStyle(color: _muted, fontSize: 10, height: 1.4),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: _submit,
        child: Text(widget.existing == null ? '创建检查' : '保存'),
      ),
    ],
  );

  String? _validateMetricValue(String? value) {
    final number = double.tryParse(value?.trim() ?? '');
    if (number == null || !number.isFinite || number < 0) {
      return '请输入 0 或更大的有限数值';
    }
    if (number > 1000000000000000) {
      return '数值不能超过 1,000,000,000,000,000';
    }
    if (_metricName.endsWith('_percent') && number > 100) {
      return '百分比不能超过 100';
    }
    return null;
  }

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    if (_type == 'HTTP' && _method == 'POST' && !_allowPostNoSideEffects) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('使用 POST 前请确认目标端点无副作用')));
      return;
    }
    final selectedResource = widget.resources.firstWhere(
      (resource) => resource['id'] == _resourceId,
      orElse: () => const <String, dynamic>{},
    );
    if ((_type == 'HEARTBEAT') != (selectedResource['type'] == 'HEARTBEAT')) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('心跳任务必须绑定心跳类型资源')));
      return;
    }
    if (_type == 'METRIC_THRESHOLD' &&
        selectedResource['type'] != _metricResourceType(_metricName)) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('指标阈值规则必须绑定与指标类型匹配的 HOST 或 DATABASE 资源')),
      );
      return;
    }
    if (const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type) &&
        selectedResource['type'] != 'DATABASE') {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('数据库检查必须绑定 DATABASE 资源')));
      return;
    }
    if (_type == 'REDIS' &&
        _databaseUsername.text.trim().isNotEmpty &&
        _databasePassword.text.isEmpty &&
        widget.existing?['databasePasswordConfigured'] != true) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('使用 Redis ACL 用户时必须配置密码')));
      return;
    }
    final interval = int.parse(_interval.text);
    final timeout = int.parse(_timeout.text);
    if (interval <= timeout) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('检查间隔必须大于单次超时时间')));
      return;
    }
    if (_type == 'METRIC_THRESHOLD') {
      final trigger = double.parse(_metricThreshold.text);
      final recovery = double.parse(_metricRecoveryValue.text);
      final validRecovery = switch (_metricOperator) {
        'GT' || 'GTE' => recovery < trigger,
        _ => recovery > trigger,
      };
      if (!validRecovery) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('恢复值必须位于触发阈值的安全侧')));
        return;
      }
    }
    Navigator.pop(
      context,
      _MonitorDraft(
        type: _type,
        resourceId: _resourceId,
        name: _name.text.trim(),
        target: _target.text.trim(),
        method: _method,
        allowPostNoSideEffects: _allowPostNoSideEffects,
        credentialId: _credentialId,
        port:
            _type == 'TCP' ||
                _type == 'TLS' ||
                const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type)
            ? int.parse(_port.text)
            : null,
        recordType: _type == 'DNS' ? _recordType : null,
        resolver: _resolver.text.trim().isEmpty ? null : _resolver.text.trim(),
        expectedRecordValue: _expectedRecordValue.text.trim().isEmpty
            ? null
            : _expectedRecordValue.text.trim(),
        minimumValidityDays: _type == 'TLS'
            ? int.parse(_minimumValidityDays.text)
            : null,
        intervalSeconds: interval,
        timeoutSeconds: timeout,
        heartbeatGraceSeconds: _type == 'HEARTBEAT'
            ? int.parse(_heartbeatGrace.text)
            : 60,
        metricName: _type == 'METRIC_THRESHOLD' ? _metricName : null,
        metricOperator: _type == 'METRIC_THRESHOLD' ? _metricOperator : null,
        metricThreshold: _type == 'METRIC_THRESHOLD'
            ? double.parse(_metricThreshold.text)
            : null,
        metricRecoveryValue: _type == 'METRIC_THRESHOLD'
            ? double.parse(_metricRecoveryValue.text)
            : null,
        metricWindowSeconds: _type == 'METRIC_THRESHOLD'
            ? int.parse(_metricWindow.text)
            : null,
        metricSeverity: _type == 'METRIC_THRESHOLD' ? _metricSeverity : null,
        databaseName: _type == 'POSTGRESQL' || _type == 'MYSQL'
            ? _databaseName.text.trim()
            : null,
        databaseUsername: const {'POSTGRESQL', 'MYSQL', 'REDIS'}.contains(_type)
            ? _databaseUsername.text.trim()
            : null,
        databasePassword:
            _type == 'POSTGRESQL' || _type == 'MYSQL' || _type == 'REDIS'
            ? _databasePassword.text
            : null,
        databaseTls: _databaseTls,
        redisDatabase: _type == 'REDIS' ? int.parse(_redisDatabase.text) : null,
        failureThreshold: int.parse(_failure.text),
        recoveryThreshold: int.parse(_recovery.text),
        reminderIntervalMinutes: int.parse(_reminderInterval.text),
        expectedStatusCodes: _parseStatuses()!,
        textContains: _contains.text.trim().isEmpty
            ? null
            : _contains.text.trim(),
        jsonPath: _jsonPath.text.trim().isEmpty ? null : _jsonPath.text.trim(),
        jsonExpectedValue: _jsonExpectedValue.text.trim().isEmpty
            ? null
            : _jsonExpectedValue.text.trim(),
        requestHeaders: _parseRequestHeaders()!,
        critical: _critical,
        enabled: _enabled,
      ),
    );
  }

  String? _validateJsonAssertion() {
    final path = _jsonPath.text.trim();
    final expected = _jsonExpectedValue.text.trim();
    if (path.isEmpty && expected.isEmpty) return null;
    if (path.isEmpty || expected.isEmpty) return 'JSON 路径和期望值必须同时填写';
    if (!path.startsWith('/') ||
        path.length > 256 ||
        RegExp(r'~(?![01])').hasMatch(path)) {
      return 'JSON Pointer 必须以 / 开头、正确转义 ~，且不超过 256 个字符';
    }
    if (expected.length > 500) return '期望值不能超过 500 个字符';
    try {
      jsonDecode(expected);
    } on FormatException {
      return '期望值必须是有效的 JSON 字面量，例如 true、200 或 "ok"';
    }
    return null;
  }

  Map<String, String>? _parseRequestHeaders() {
    final headers = <String, String>{};
    final seenNames = <String>{};
    for (final line in _requestHeaders.text.split('\n')) {
      if (line.trim().isEmpty) continue;
      final separator = line.indexOf(':');
      if (separator <= 0) return null;
      final name = line.substring(0, separator).trim();
      final value = line.substring(separator + 1).trim();
      final normalizedName = name.toLowerCase();
      if (name.length > 128 ||
          !RegExp(r"^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").hasMatch(name) ||
          const {
            'host',
            'content-length',
            'transfer-encoding',
            'connection',
            'keep-alive',
            'proxy-authenticate',
            'proxy-authorization',
            'proxy-connection',
            'te',
            'trailer',
            'upgrade',
          }.contains(normalizedName) ||
          normalizedName.startsWith('proxy-') ||
          value.length > 4096 ||
          value.codeUnits.any(
            (code) => code < 32 || (code >= 127 && code <= 159),
          ) ||
          !seenNames.add(normalizedName)) {
        return null;
      }
      headers[normalizedName] = value;
    }
    return headers;
  }
}

class _IntField extends StatelessWidget {
  const _IntField({
    required this.controller,
    required this.label,
    required this.minimum,
    required this.maximum,
  });
  final TextEditingController controller;
  final String label;
  final int minimum;
  final int maximum;

  @override
  Widget build(BuildContext context) => TextFormField(
    controller: controller,
    keyboardType: TextInputType.number,
    decoration: InputDecoration(labelText: label),
    validator: (value) {
      final number = int.tryParse(value ?? '');
      return number == null || number < minimum || number > maximum
          ? '$minimum–$maximum 之间的整数'
          : null;
    },
  );
}

class _CheckResultDialog extends StatelessWidget {
  const _CheckResultDialog({required this.title, required this.result});
  final String title;
  final Map<String, dynamic> result;

  @override
  Widget build(BuildContext context) {
    final success = result['status'] == 'UP';
    final unknown = result['status'] == 'UNKNOWN';
    final color = success
        ? const Color(0xFF2C8B66)
        : unknown
        ? const Color(0xFF7B8794)
        : const Color(0xFFB44438);
    return AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: 430,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  success
                      ? Icons.check_circle_outline
                      : unknown
                      ? Icons.help_outline_rounded
                      : Icons.error_outline,
                  color: color,
                ),
                const SizedBox(width: 9),
                Text(
                  success
                      ? '检查通过'
                      : unknown
                      ? '结果未知'
                      : '检查失败',
                  style: TextStyle(color: color, fontWeight: FontWeight.w700),
                ),
              ],
            ),
            const SizedBox(height: 18),
            _DialogInfoRow('响应时间', '${result['durationMs']} ms'),
            if (result['statusCode'] != null)
              _DialogInfoRow('HTTP 状态码', '${result['statusCode']}'),
            if (result['errorCode'] != null)
              _DialogInfoRow('错误类别', '${result['errorCode']}'),
            if (result['summary'] != null)
              _DialogInfoRow('说明', '${result['summary']}'),
            _DialogInfoRow('检查时间', _dateTime(result['checkedAt'] as String?)),
          ],
        ),
      ),
      actions: [
        FilledButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('完成'),
        ),
      ],
    );
  }
}

class _CheckHistoryDialog extends StatefulWidget {
  const _CheckHistoryDialog({
    required this.api,
    required this.monitor,
    required this.results,
    required this.availability,
  });

  final ApiClient api;
  final Map<String, dynamic> monitor;
  final List<Map<String, dynamic>> results;

  final Map<String, dynamic> availability;

  @override
  State<_CheckHistoryDialog> createState() => _CheckHistoryDialogState();
}

class _CheckHistoryDialogState extends State<_CheckHistoryDialog> {
  late Map<String, dynamic> _availability = widget.availability;
  int _rangeDays = 1;
  bool _loadingAvailability = false;

  Future<void> _loadAvailability(int days) async {
    setState(() {
      _rangeDays = days;
      _loadingAvailability = true;
    });
    try {
      final value = await widget.api.monitorAvailability(
        widget.monitor['id'] as String,
        rangeDays: days,
      );
      if (mounted) setState(() => _availability = value);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(error.toString())));
      }
    } finally {
      if (mounted) setState(() => _loadingAvailability = false);
    }
  }

  String _percentage(String key) {
    if (_availability['ready'] != true) return '汇总中';
    final value = _availability[key];
    return value is num ? '${value.toStringAsFixed(2)}%' : '无数据';
  }

  String _duration(String key) {
    if (_availability['ready'] != true) return '汇总中';
    final seconds = _availability[key];
    if (seconds is! num) return '—';
    final duration = Duration(seconds: seconds.toInt());
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);
    return hours > 0 ? '$hours小时$minutes分' : '$minutes分';
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text('${widget.monitor['name']} · 检查历史'),
    content: SizedBox(
      width: 650,
      height: 590,
      child: Column(
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  '可用率统计',
                  style: TextStyle(fontWeight: FontWeight.w700, fontSize: 13),
                ),
              ),
              DropdownButton<int>(
                value: _rangeDays,
                onChanged: _loadingAvailability
                    ? null
                    : (value) {
                        if (value != null) _loadAvailability(value);
                      },
                items: const [
                  DropdownMenuItem(value: 1, child: Text('24 小时')),
                  DropdownMenuItem(value: 7, child: Text('7 天')),
                  DropdownMenuItem(value: 30, child: Text('30 天')),
                  DropdownMenuItem(value: 180, child: Text('180 天')),
                ],
              ),
            ],
          ),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(13),
            decoration: BoxDecoration(
              color: const Color(0xFFF4F7FA),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: const Color(0xFFE5EAF0)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: _AvailabilityValue(
                        label: '可用率',
                        value: _percentage('availabilityPercent'),
                        color: const Color(0xFF2C8B66),
                      ),
                    ),
                    Expanded(
                      child: _AvailabilityValue(
                        label: '数据覆盖率',
                        value: _percentage('coveragePercent'),
                        color: _blue,
                      ),
                    ),
                    if (_loadingAvailability)
                      const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                  ],
                ),
                const SizedBox(height: 11),
                SizedBox(
                  height: 16,
                  width: double.infinity,
                  child: CustomPaint(
                    painter: _AvailabilityTimelinePainter(
                      points:
                          (_availability['points'] as List?)
                              ?.whereType<Map>()
                              .map((point) => Map<String, dynamic>.from(point))
                              .toList() ??
                          const [],
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 14,
                  runSpacing: 5,
                  children: [
                    _AvailabilityLegend('可用', const Color(0xFF52A780)),
                    _AvailabilityLegend('DOWN', const Color(0xFFC6534A)),
                    _AvailabilityLegend('UNKNOWN', const Color(0xFFAAB4C0)),
                    _AvailabilityLegend('维护/暂停', const Color(0xFFE1AC52)),
                  ],
                ),
                const SizedBox(height: 8),
                Text(
                  '可用 ${_duration('upSeconds')}（降级 ${_duration('degradedSeconds')}） · '
                  'DOWN ${_duration('downSeconds')} · UNKNOWN ${_duration('unknownSeconds')} · '
                  '维护/暂停 ${_duration('excludedSeconds')}',
                  style: const TextStyle(color: _muted, fontSize: 10),
                ),
                const SizedBox(height: 3),
                Text(
                  _availability['ready'] == true
                      ? '按 UTC 整点小时统计；降级计入可用时长。'
                      : '历史小时数据正在补齐，完成前的百分比暂不显示。',
                  style: const TextStyle(color: _muted, fontSize: 10),
                ),
              ],
            ),
          ),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 10),
            child: Divider(height: 1),
          ),
          const Align(
            alignment: Alignment.centerLeft,
            child: Text(
              '最近检查记录',
              style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12),
            ),
          ),
          const SizedBox(height: 6),
          Expanded(
            child: widget.results.isEmpty
                ? const _EmptyState(
                    icon: Icons.history_rounded,
                    title: '还没有检查结果',
                    subtitle: '首次运行后，最近执行记录会显示在这里。',
                  )
                : ListView.separated(
                    itemCount: widget.results.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final result = widget.results[index];
                      final success = result['status'] == 'UP';
                      final unknown = result['status'] == 'UNKNOWN';
                      final color = success
                          ? const Color(0xFF2C8B66)
                          : result['status'] == 'RUNNING'
                          ? const Color(0xFF7C6CAC)
                          : unknown
                          ? const Color(0xFF7B8794)
                          : const Color(0xFFB44438);
                      return Padding(
                        padding: const EdgeInsets.symmetric(vertical: 9),
                        child: Row(
                          children: [
                            Icon(
                              success
                                  ? Icons.check_circle_outline
                                  : unknown
                                  ? Icons.help_outline_rounded
                                  : Icons.error_outline,
                              color: color,
                              size: 17,
                            ),
                            const SizedBox(width: 9),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    '${result['status']}  ·  ${_dateTime(result['scheduledAt'] as String?)}',
                                    style: const TextStyle(
                                      fontSize: 11,
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                  const SizedBox(height: 3),
                                  Text(
                                    (result['summary'] as String?) ?? '检查完成',
                                    style: const TextStyle(
                                      color: _muted,
                                      fontSize: 10,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            Text(
                              '${result['durationMs'] ?? '—'} ms',
                              style: const TextStyle(
                                color: _muted,
                                fontSize: 10,
                              ),
                            ),
                          ],
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('关闭'),
      ),
    ],
  );
}

class _AvailabilityValue extends StatelessWidget {
  const _AvailabilityValue({
    required this.label,
    required this.value,
    required this.color,
  });

  final String label;
  final String value;
  final Color color;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: const TextStyle(color: _muted, fontSize: 10)),
      const SizedBox(height: 3),
      Text(
        value,
        style: TextStyle(
          color: color,
          fontSize: 19,
          fontWeight: FontWeight.w700,
        ),
      ),
    ],
  );
}

class _AvailabilityLegend extends StatelessWidget {
  const _AvailabilityLegend(this.label, this.color);

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 8,
        height: 8,
        decoration: BoxDecoration(
          color: color,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
      const SizedBox(width: 5),
      Text(label, style: const TextStyle(color: _muted, fontSize: 9)),
    ],
  );
}

class _AvailabilityTimelinePainter extends CustomPainter {
  _AvailabilityTimelinePainter({required this.points});

  final List<Map<String, dynamic>> points;

  @override
  void paint(Canvas canvas, Size size) {
    if (points.isEmpty) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(5)),
        Paint()..color = const Color(0xFFE0E5EB),
      );
      return;
    }
    final colors = <String, Color>{
      'upSeconds': const Color(0xFF52A780),
      'downSeconds': const Color(0xFFC6534A),
      'unknownSeconds': const Color(0xFFAAB4C0),
      'excludedSeconds': const Color(0xFFE1AC52),
    };
    final keys = colors.keys.toList();
    final paint = Paint();
    for (var index = 0; index < points.length; index++) {
      final point = points[index];
      final scheduled = (point['scheduledSeconds'] as num?)?.toDouble() ?? 0;
      if (scheduled <= 0) continue;
      var left = size.width * index / points.length;
      final right = size.width * (index + 1) / points.length;
      for (final key in keys) {
        final seconds = (point[key] as num?)?.toDouble() ?? 0;
        if (seconds <= 0) continue;
        final next =
            (left +
                    (right - size.width * index / points.length) *
                        seconds /
                        scheduled)
                .clamp(left, right);
        paint.color = colors[key]!;
        canvas.drawRect(Rect.fromLTRB(left, 0, next, size.height), paint);
        left = next;
      }
    }
  }

  @override
  bool shouldRepaint(covariant _AvailabilityTimelinePainter oldDelegate) =>
      oldDelegate.points != points;
}

class _DialogInfoRow extends StatelessWidget {
  const _DialogInfoRow(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 95,
          child: Text(
            label,
            style: const TextStyle(color: _muted, fontSize: 12),
          ),
        ),
        Expanded(
          child: Text(value, style: const TextStyle(fontSize: 12, height: 1.4)),
        ),
      ],
    ),
  );
}

class _IncidentsPage extends StatefulWidget {
  const _IncidentsPage({
    required this.incidents,
    required this.loading,
    required this.onAcknowledge,
    required this.onEvents,
  });
  final List<Map<String, dynamic>> incidents;
  final bool loading;
  final ValueChanged<Map<String, dynamic>> onAcknowledge;
  final ValueChanged<Map<String, dynamic>> onEvents;

  @override
  State<_IncidentsPage> createState() => _IncidentsPageState();
}

class _IncidentsPageState extends State<_IncidentsPage> {
  String _filter = 'ACTIVE';

  @override
  Widget build(BuildContext context) {
    final visible = widget.incidents.where((incident) {
      if (_filter == 'ACTIVE') return incident['status'] != 'RESOLVED';
      if (_filter == 'RESOLVED') return incident['status'] == 'RESOLVED';
      return true;
    }).toList();
    final children = <Widget>[
      _PageHeading(
        eyebrow: 'INCIDENT CENTER',
        title: '故障中心',
        subtitle: '查看故障原因、确认处理并追踪恢复过程。',
      ),
      Wrap(
        spacing: 8,
        children: [
          _IncidentFilter(
            label: '活跃',
            selected: _filter == 'ACTIVE',
            onTap: () => setState(() => _filter = 'ACTIVE'),
          ),
          _IncidentFilter(
            label: '全部',
            selected: _filter == 'ALL',
            onTap: () => setState(() => _filter = 'ALL'),
          ),
          _IncidentFilter(
            label: '已恢复',
            selected: _filter == 'RESOLVED',
            onTap: () => setState(() => _filter = 'RESOLVED'),
          ),
        ],
      ),
      const SizedBox(height: 16),
    ];
    if (widget.loading && widget.incidents.isEmpty) {
      children.add(
        const Center(
          child: Padding(
            padding: EdgeInsets.all(40),
            child: CircularProgressIndicator(),
          ),
        ),
      );
    } else if (visible.isEmpty) {
      children.add(
        const Card(
          child: Padding(
            padding: EdgeInsets.all(36),
            child: _EmptyState(
              icon: Icons.check_circle_outline,
              title: '没有符合条件的故障',
              subtitle: '检查项达到失败阈值后，故障会显示在这里。',
            ),
          ),
        ),
      );
    } else {
      for (final incident in visible) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          incident['status'] == 'RESOLVED'
                              ? Icons.check_circle_outline
                              : Icons.warning_amber_rounded,
                          color: incident['status'] == 'RESOLVED'
                              ? const Color(0xFF2C8B66)
                              : const Color(0xFFB44438),
                          size: 20,
                        ),
                        const SizedBox(width: 9),
                        Expanded(
                          child: Text(
                            incident['title'] as String? ?? '监控检查异常',
                            style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                        ),
                        _IncidentStatus(
                          status: incident['status'] as String? ?? 'OPEN',
                        ),
                      ],
                    ),
                    const SizedBox(height: 11),
                    Text(
                      '${_incidentCategoryName(incident['category'] as String?)} · ${_incidentSeverityName(incident['severity'] as String?)}  ·  ${incident['resourceName'] ?? '资源'}  ·  开始于 ${_dateTime(incident['openedAt'] as String?)}  ·  ${incident['occurrenceCount']} 次失败',
                      style: const TextStyle(color: _muted, fontSize: 11),
                    ),
                    if (incident['acknowledgedBy'] != null) ...[
                      const SizedBox(height: 8),
                      Text(
                        '由 ${incident['acknowledgedBy']} 于 ${_dateTime(incident['acknowledgedAt'] as String?)} 确认',
                        style: const TextStyle(color: _muted, fontSize: 10),
                      ),
                    ],
                    if (incident['acknowledgementNote'] != null &&
                        (incident['acknowledgementNote'] as String)
                            .isNotEmpty) ...[
                      const SizedBox(height: 7),
                      Text(
                        '处理备注：${incident['acknowledgementNote']}',
                        style: const TextStyle(color: _ink, fontSize: 11),
                      ),
                    ],
                    const Divider(height: 22),
                    Row(
                      children: [
                        TextButton.icon(
                          onPressed: () => widget.onEvents(incident),
                          icon: const Icon(Icons.subject_rounded, size: 17),
                          label: const Text('事件记录'),
                        ),
                        const Spacer(),
                        if (incident['status'] == 'OPEN')
                          FilledButton.icon(
                            onPressed: () => widget.onAcknowledge(incident),
                            icon: const Icon(Icons.done_rounded, size: 17),
                            label: const Text('确认处理'),
                          ),
                        if (incident['status'] == 'RESOLVED')
                          Text(
                            '恢复于 ${_dateTime(incident['resolvedAt'] as String?)}',
                            style: const TextStyle(
                              color: Color(0xFF2C8B66),
                              fontSize: 10,
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      }
    }
    return _PageScroll(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    );
  }
}

class _IncidentFilter extends StatelessWidget {
  const _IncidentFilter({
    required this.label,
    required this.selected,
    required this.onTap,
  });
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => FilterChip(
    label: Text(label),
    selected: selected,
    onSelected: (_) => onTap(),
    showCheckmark: false,
  );
}

class _IncidentStatus extends StatelessWidget {
  const _IncidentStatus({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status) {
      'ACKNOWLEDGED' => ('处理中', const Color(0xFF315CA8)),
      'RESOLVED' => ('已恢复', const Color(0xFF2C8B66)),
      _ => ('待确认', const Color(0xFFB44438)),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _IncidentPreview extends StatelessWidget {
  const _IncidentPreview({required this.incidents, required this.onOpen});
  final List<Map<String, dynamic>> incidents;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final active = incidents
        .where((incident) => incident['status'] != 'RESOLVED')
        .take(3)
        .toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 15, 18, 11),
        child: Column(
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    '活跃故障',
                    style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
                  ),
                ),
                TextButton(onPressed: onOpen, child: const Text('打开故障中心')),
              ],
            ),
            const Divider(height: 15),
            if (active.isEmpty)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: Row(
                  children: [
                    Icon(
                      Icons.check_circle_outline,
                      color: Color(0xFF2C8B66),
                      size: 18,
                    ),
                    SizedBox(width: 9),
                    Text(
                      '当前没有活跃故障',
                      style: TextStyle(color: _muted, fontSize: 11),
                    ),
                  ],
                ),
              )
            else
              ...active.map(
                (incident) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Row(
                    children: [
                      const Icon(
                        Icons.warning_amber_rounded,
                        color: Color(0xFFB44438),
                        size: 17,
                      ),
                      const SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          incident['title'] as String? ?? '监控检查异常',
                          style: const TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      Text(
                        '${incident['status'] == 'ACKNOWLEDGED' ? '处理中' : '待确认'}  ·  ${_dateTime(incident['openedAt'] as String?)}',
                        style: const TextStyle(color: _muted, fontSize: 10),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _IncidentNoteDialog extends StatefulWidget {
  const _IncidentNoteDialog();
  @override
  State<_IncidentNoteDialog> createState() => _IncidentNoteDialogState();
}

class _IncidentNoteDialogState extends State<_IncidentNoteDialog> {
  final _note = TextEditingController();
  @override
  void dispose() {
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('确认处理故障'),
    content: SizedBox(
      width: 420,
      child: TextField(
        controller: _note,
        maxLength: 2000,
        maxLines: 4,
        decoration: const InputDecoration(
          labelText: '处理备注（可选）',
          hintText: '记录排查进展或负责人',
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () => Navigator.pop(context, _note.text.trim()),
        child: const Text('确认'),
      ),
    ],
  );
}

class _IncidentEventsDialog extends StatelessWidget {
  const _IncidentEventsDialog({required this.incident, required this.events});
  final Map<String, dynamic> incident;
  final List<Map<String, dynamic>> events;
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text('${incident['resourceName']} · 事件记录'),
    content: SizedBox(
      width: 480,
      height: 380,
      child: events.isEmpty
          ? const _EmptyState(
              icon: Icons.subject_rounded,
              title: '暂无记录',
              subtitle: '',
            )
          : ListView.separated(
              itemCount: events.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final event = events[index];
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.circle, size: 8, color: _blue),
                  title: Text(
                    _eventName(event['eventType'] as String? ?? ''),
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  subtitle: Text(
                    [
                      if (event['actorEmail'] != null) event['actorEmail'],
                      if (event['note'] != null &&
                          (event['note'] as String).isNotEmpty)
                        event['note'],
                      _dateTime(event['createdAt'] as String?),
                    ].join(' · '),
                    style: const TextStyle(fontSize: 10, color: _muted),
                  ),
                );
              },
            ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('关闭'),
      ),
    ],
  );
}

String _eventName(String type) => switch (type) {
  'OPENED' => '故障触发',
  'ACKNOWLEDGED' => '负责人确认',
  'RESOLVED' => '监控恢复',
  'ESCALATED' => '提醒级别变化',
  'REMINDER' => '持续故障提醒',
  'NOTE' => '状态更新',
  _ => type,
};

String _incidentCategoryName(String? category) => switch (category) {
  'TLS_CERTIFICATE' => 'TLS 证书',
  'PERFORMANCE' => '性能阈值',
  'COLLECTION' => 'Agent 采集',
  _ => '可用性',
};

String _incidentSeverityName(String? severity) => switch (severity) {
  'CRITICAL' => '严重',
  'HIGH' => '高',
  'WARNING' => '警告',
  _ => severity ?? '警告',
};

class _ProjectDraft {
  const _ProjectDraft(this.name, this.description);
  final String name;
  final String description;
}

class _ProjectDialog extends StatefulWidget {
  const _ProjectDialog();

  @override
  State<_ProjectDialog> createState() => _ProjectDialogState();
}

class _ProjectDialogState extends State<_ProjectDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _description = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('创建项目'),
    content: SizedBox(
      width: 430,
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextFormField(
              controller: _name,
              autofocus: true,
              maxLength: 120,
              decoration: const InputDecoration(
                labelText: '项目名称',
                hintText: '例如：核心服务',
              ),
              validator: (value) =>
                  value == null || value.trim().isEmpty ? '请输入项目名称' : null,
            ),
            const SizedBox(height: 12),
            TextField(
              controller: _description,
              maxLength: 1000,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: '项目说明（可选）',
                alignLabelWithHint: true,
              ),
            ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(
              context,
              _ProjectDraft(_name.text.trim(), _description.text.trim()),
            );
          }
        },
        child: const Text('创建'),
      ),
    ],
  );
}

class _ResourceDraft {
  const _ResourceDraft({
    required this.projectId,
    required this.name,
    required this.type,
    required this.environment,
    required this.tags,
    this.ownerEmail,
  });
  final String projectId;
  final String name;
  final String type;
  final String environment;
  final List<String> tags;
  final String? ownerEmail;
}

class _ResourceDialog extends StatefulWidget {
  const _ResourceDialog({required this.projects});

  final List<Map<String, dynamic>> projects;

  @override
  State<_ResourceDialog> createState() => _ResourceDialogState();
}

class _ResourceDialogState extends State<_ResourceDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _projectId = widget.projects.first['id'] as String;
  String _type = 'WEBSITE';
  final _name = TextEditingController();
  final _environment = TextEditingController(text: 'production');
  final _tags = TextEditingController();
  final _ownerEmail = TextEditingController();

  @override
  void dispose() {
    _name.dispose();
    _environment.dispose();
    _tags.dispose();
    _ownerEmail.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('添加监控资源'),
    content: SizedBox(
      width: 470,
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _projectId,
                decoration: const InputDecoration(labelText: '所属项目'),
                items: widget.projects
                    .map(
                      (project) => DropdownMenuItem(
                        value: project['id'] as String,
                        child: Text(project['name'] as String),
                      ),
                    )
                    .toList(),
                onChanged: (value) => setState(() => _projectId = value!),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _name,
                autofocus: true,
                maxLength: 160,
                decoration: const InputDecoration(
                  labelText: '资源名称',
                  hintText: '例如：官网',
                ),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? '请输入资源名称' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: '资源类型'),
                items: const [
                  DropdownMenuItem(value: 'WEBSITE', child: Text('网站')),
                  DropdownMenuItem(value: 'HOST', child: Text('主机')),
                  DropdownMenuItem(value: 'DATABASE', child: Text('数据库')),
                  DropdownMenuItem(value: 'SERVICE', child: Text('基础服务')),
                  DropdownMenuItem(value: 'HEARTBEAT', child: Text('心跳任务')),
                ],
                onChanged: (value) => setState(() => _type = value!),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _environment,
                maxLength: 80,
                decoration: const InputDecoration(
                  labelText: '环境',
                  hintText: 'production',
                ),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? '请输入环境名称' : null,
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _tags,
                decoration: const InputDecoration(
                  labelText: '标签（可选）',
                  hintText: '用逗号分隔，例如：核心, 外网',
                ),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _ownerEmail,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(labelText: '负责人邮箱（可选）'),
              ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: () {
          if (_formKey.currentState!.validate()) {
            Navigator.pop(
              context,
              _ResourceDraft(
                projectId: _projectId,
                name: _name.text.trim(),
                type: _type,
                environment: _environment.text.trim(),
                tags: _tags.text
                    .split(',')
                    .map((tag) => tag.trim())
                    .where((tag) => tag.isNotEmpty)
                    .take(20)
                    .toList(),
                ownerEmail: _ownerEmail.text.trim().isEmpty
                    ? null
                    : _ownerEmail.text.trim(),
              ),
            );
          }
        },
        child: const Text('添加资源'),
      ),
    ],
  );
}

class _TypeTag extends StatelessWidget {
  const _TypeTag({required this.type});
  final String type;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
    decoration: BoxDecoration(
      color: const Color(0xFFF0F3F7),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      _resourceTypeName(type),
      style: const TextStyle(color: _muted, fontSize: 10),
    ),
  );
}

class _StatusTag extends StatelessWidget {
  const _StatusTag({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final (color, label) = switch (status) {
      'UP' => (const Color(0xFF2C8B66), '正常'),
      'DOWN' => (const Color(0xFFB44438), '异常'),
      'DEGRADED' => (const Color(0xFFC8743E), '降级'),
      'UNKNOWN' => (const Color(0xFF7B8794), '未知'),
      _ => (const Color(0xFF7C6CAC), '待采集'),
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 7,
          height: 7,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 7),
        Text(
          label,
          style: TextStyle(
            color: color,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
      ],
    );
  }
}

class _PageScroll extends StatelessWidget {
  const _PageScroll({required this.child, this.expand = false});
  final Widget child;
  final bool expand;

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: const EdgeInsets.fromLTRB(28, 27, 28, 36),
    child: expand
        ? SizedBox(width: double.infinity, child: child)
        : Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1400),
              child: child,
            ),
          ),
  );
}

class _PageHeading extends StatelessWidget {
  const _PageHeading({
    required this.eyebrow,
    required this.title,
    required this.subtitle,
    this.actions = const [],
  });
  final String eyebrow;
  final String title;
  final String subtitle;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 22),
    child: LayoutBuilder(
      builder: (context, constraints) {
        final heading = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              eyebrow,
              style: const TextStyle(
                color: _blue,
                fontSize: 10,
                letterSpacing: 1.3,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 7),
            Text(
              title,
              style: const TextStyle(
                color: _ink,
                fontSize: 25,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 6),
            Text(subtitle, style: const TextStyle(color: _muted, fontSize: 12)),
          ],
        );
        if (constraints.maxWidth <= 540) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              heading,
              if (actions.isNotEmpty) ...[
                const SizedBox(height: 13),
                Wrap(spacing: 9, runSpacing: 8, children: actions),
              ],
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(child: heading),
            ...actions,
          ],
        );
      },
    ),
  );
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({
    required this.icon,
    required this.title,
    required this.subtitle,
  });
  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    children: [
      Container(
        width: 42,
        height: 42,
        decoration: BoxDecoration(
          color: const Color(0xFFF1F4F8),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(icon, color: _muted, size: 20),
      ),
      const SizedBox(height: 11),
      Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 12),
      ),
      const SizedBox(height: 5),
      Text(
        subtitle,
        textAlign: TextAlign.center,
        style: const TextStyle(color: _muted, fontSize: 10, height: 1.5),
      ),
    ],
  );
}

class _WorkspaceError extends StatelessWidget {
  const _WorkspaceError({required this.message, required this.onRetry});
  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 440),
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off_outlined, color: _blue, size: 38),
            const SizedBox(height: 12),
            const Text(
              '无法读取工作区',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 8),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: _muted),
            ),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('重试'),
            ),
          ],
        ),
      ),
    ),
  );
}

IconData _resourceIcon(String type) => switch (type) {
  'WEBSITE' => Icons.language_rounded,
  'HOST' => Icons.dns_outlined,
  'DATABASE' => Icons.storage_outlined,
  'HEARTBEAT' => Icons.favorite_border_rounded,
  _ => Icons.hub_outlined,
};

IconData _monitorIcon(String type) => switch (type) {
  'TCP' => Icons.cable_rounded,
  'DNS' => Icons.dns_outlined,
  'TLS' => Icons.verified_user_outlined,
  'HEARTBEAT' => Icons.favorite_border_rounded,
  'METRIC_THRESHOLD' => Icons.speed_rounded,
  'POSTGRESQL' || 'MYSQL' || 'REDIS' => Icons.storage_outlined,
  _ => Icons.language_rounded,
};

String _monitorTypeName(String type) => switch (type) {
  'TCP' => 'TCP',
  'DNS' => 'DNS',
  'TLS' => 'TLS',
  'HEARTBEAT' => '心跳任务',
  'METRIC_THRESHOLD' => '指标阈值',
  'POSTGRESQL' => 'PostgreSQL',
  'MYSQL' => 'MySQL',
  'REDIS' => 'Redis',
  _ => 'HTTP',
};

int _defaultMonitorPort(String type) => switch (type) {
  'TLS' => 443,
  'POSTGRESQL' => 5432,
  'MYSQL' => 3306,
  'REDIS' => 6379,
  _ => 80,
};

const _thresholdMetricNames = [
  'cpu_usage_percent',
  'load_1m',
  'memory_used_percent',
  'memory_available_bytes',
  'swap_used_percent',
  'disk_used_percent',
  'disk_inode_used_percent',
  'network_receive_bytes_per_second',
  'network_transmit_bytes_per_second',
  'database_connections',
  'database_connection_limit',
  'database_connections_percent',
  'redis_memory_used_bytes',
  'redis_memory_limit_bytes',
  'redis_memory_used_percent',
  'redis_evicted_keys_total',
];

String _metricDisplayName(String name) => switch (name) {
  'cpu_usage_percent' => 'CPU 使用率',
  'load_1m' => '1 分钟 Load',
  'memory_used_percent' => '内存使用率',
  'memory_available_bytes' => '可用内存',
  'swap_used_percent' => 'Swap 使用率',
  'disk_used_percent' => '磁盘使用率（按挂载点）',
  'disk_inode_used_percent' => '磁盘 inode 使用率（按挂载点）',
  'network_receive_bytes_per_second' => '网络接收速率（按接口）',
  'network_transmit_bytes_per_second' => '网络发送速率（按接口）',
  'database_connections' => '数据库当前连接数',
  'database_connection_limit' => '数据库连接上限',
  'database_connections_percent' => '数据库连接使用率',
  'redis_memory_used_bytes' => 'Redis 已使用内存',
  'redis_memory_limit_bytes' => 'Redis 内存上限',
  'redis_memory_used_percent' => 'Redis 内存使用率',
  'redis_evicted_keys_total' => 'Redis 已淘汰键累计数',
  _ => name,
};

bool _isDatabaseMetric(String name) =>
    name.startsWith('database_') || name.startsWith('redis_');

String _metricResourceType(String name) =>
    _isDatabaseMetric(name) ? 'DATABASE' : 'HOST';

String _metricUnit(String name) => name.endsWith('_percent')
    ? '(%)'
    : name.endsWith('_bytes_per_second')
    ? '(bytes/s)'
    : name.endsWith('_bytes')
    ? '(bytes)'
    : name.endsWith('_total')
    ? '(count)'
    : '';

String _metricOperatorSymbol(String? operator) => switch (operator) {
  'GT' => '>',
  'GTE' => '≥',
  'LT' => '<',
  'LTE' => '≤',
  _ => '>',
};

bool _validMonitorHost(String value, {bool ipOnly = false}) {
  final raw = value.trim();
  if (raw.isEmpty ||
      raw.contains('/') ||
      raw.contains('@') ||
      raw.contains(RegExp(r'\s'))) {
    return false;
  }
  final unwrapped = raw.startsWith('[') && raw.endsWith(']')
      ? raw.substring(1, raw.length - 1)
      : raw;
  final ipv4Parts = unwrapped.split('.');
  final isIpv4 =
      ipv4Parts.length == 4 &&
      ipv4Parts.every((part) {
        final value = int.tryParse(part);
        return value != null && value >= 0 && value <= 255;
      });
  final isIpv6 =
      unwrapped.contains(':') &&
      RegExp(r'^[0-9a-fA-F:.]+$').hasMatch(unwrapped) &&
      (Uri.tryParse('https://[$unwrapped]')?.host.isNotEmpty ?? false);
  if (ipOnly) return isIpv4 || isIpv6;
  final hostForUri = unwrapped.contains(':') ? '[$unwrapped]' : raw;
  final uri = Uri.tryParse('https://$hostForUri');
  return uri != null &&
      uri.hasAuthority &&
      uri.host.isNotEmpty &&
      !uri.hasPort &&
      uri.userInfo.isEmpty &&
      uri.path.isEmpty &&
      uri.query.isEmpty &&
      uri.fragment.isEmpty;
}

String _resourceTypeName(String type) => switch (type) {
  'WEBSITE' => '网站',
  'HOST' => '主机',
  'DATABASE' => '数据库',
  'HEARTBEAT' => '心跳任务',
  _ => '基础服务',
};

String _shortDate(String? value) {
  if (value == null) return '';
  final parsed = DateTime.tryParse(value)?.toLocal();
  if (parsed == null) return '';
  return '${parsed.year}-${parsed.month.toString().padLeft(2, '0')}-${parsed.day.toString().padLeft(2, '0')}';
}

String _dateTime(String? value) {
  if (value == null) return '尚无记录';
  final parsed = DateTime.tryParse(value)?.toLocal();
  if (parsed == null) return '时间未知';
  final date = _shortDate(parsed.toIso8601String());
  final hour = parsed.hour.toString().padLeft(2, '0');
  final minute = parsed.minute.toString().padLeft(2, '0');
  final second = parsed.second.toString().padLeft(2, '0');
  return '$date $hour:$minute:$second';
}
