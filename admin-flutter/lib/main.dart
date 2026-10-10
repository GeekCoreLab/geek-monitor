import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'api_client.dart';

void main() {
  runApp(const GeekMonitorApp());
}

const _ink = Color(0xFF182433);
const _muted = Color(0xFF6F7C8C);
const _blue = Color(0xFF315CA8);
const _canvas = Color(0xFFF4F6F9);
const _showDevCredentials =
    kDebugMode || bool.fromEnvironment('GEEK_MONITOR_DEV_CREDENTIALS');

class GeekMonitorApp extends StatefulWidget {
  const GeekMonitorApp({super.key});

  @override
  State<GeekMonitorApp> createState() => _GeekMonitorAppState();
}

class _GeekMonitorAppState extends State<GeekMonitorApp> {
  final _api = ApiClient();
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
    await _api.logout();
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
  bool _loading = true;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _reload();
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
      ]);
      setState(() {
        _projects = values[0];
        _resources = values[1];
        _monitors = values[2];
        _incidents = values[3];
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
      builder: (_) => _MonitorDialog(resources: _resources, existing: current),
    );
    if (draft == null) return;
    try {
      if (current == null) {
        await widget.api.createMonitor(draft.toJson());
      } else {
        await widget.api.updateMonitor(current['id'] as String, {
          ...draft.toJson(),
          'version': current['version'],
        });
      }
      await _reload();
      if (mounted) _notice(current == null ? '监控项已创建' : '监控项已更新');
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
        onCreate: _createProject,
      ),
    };
  }

  Future<void> _showMonitorHistory(Map<String, dynamic> monitor) async {
    try {
      final results = await widget.api.checkResults(monitor['id'] as String);
      if (!mounted) return;
      showDialog<void>(
        context: context,
        builder: (_) => _CheckHistoryDialog(monitor: monitor, results: results),
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
            Text(
              user['role'] == 'ADMIN' ? '系统管理员' : '成员',
              style: const TextStyle(fontSize: 10, color: _muted),
            ),
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

  @override
  Widget build(BuildContext context) => NavigationBar(
    selectedIndex: section.index,
    onDestinationSelected: (index) => onSelect(_Section.values[index]),
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
      NavigationDestination(icon: Icon(Icons.folder_outlined), label: '项目'),
    ],
  );
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
  });

  final List<Map<String, dynamic>> resources;
  final List<Map<String, dynamic>> projects;
  final bool loading;
  final VoidCallback onCreate;
  final ValueChanged<Map<String, dynamic>> onDelete;

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
                                  IconButton(
                                    tooltip: '移除资源',
                                    onPressed: () => onDelete(resource),
                                    icon: const Icon(
                                      Icons.delete_outline_rounded,
                                      color: _muted,
                                      size: 18,
                                    ),
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

class _ProjectsPage extends StatelessWidget {
  const _ProjectsPage({required this.projects, required this.onCreate});

  final List<Map<String, dynamic>> projects;
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
          actions: [
            FilledButton.icon(
              onPressed: onCreate,
              icon: const Icon(Icons.add_rounded),
              label: const Text('创建项目'),
            ),
          ],
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
      ]);
      if (!mounted) return;
      setState(() {
        _channels = results[0];
        _deliveries = results[1];
        _maintenanceWindows = results[2];
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
                              '${channel['projectName']}  ·  ${channel['type'] == 'EMAIL' ? '邮件' : 'Webhook'}  ·  ${channel['destinationSummary']}',
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
                        '${delivery['errorSummary'] ?? '投递成功'}${delivery['httpStatus'] == null ? '' : '  ·  HTTP ${delivery['httpStatus']}'}  ·  第 ${delivery['attemptNumber']} 次',
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
  });

  final List<Map<String, dynamic>> projects;
  final List<Map<String, dynamic>> resources;

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
    title: const Text('安排维护窗口'),
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
                decoration: const InputDecoration(labelText: '维护范围'),
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
        child: const Text('创建维护窗口'),
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
  });

  final String projectId;
  final String name;
  final String type;
  final List<String> emailRecipients;
  final String webhookUrl;
  final String webhookBearerToken;

  Map<String, dynamic> toJson() => {
    'projectId': projectId,
    'name': name,
    'type': type,
    'emailRecipients': type == 'EMAIL' ? emailRecipients : null,
    'webhookUrl': type == 'WEBHOOK' ? webhookUrl : null,
    'webhookBearerToken': type == 'WEBHOOK' ? webhookBearerToken : null,
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

  @override
  void dispose() {
    _name.dispose();
    _recipients.dispose();
    _url.dispose();
    _token.dispose();
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
  });

  final List<Map<String, dynamic>> monitors;
  final List<Map<String, dynamic>> resources;
  final bool loading;
  final VoidCallback onCreate;
  final ValueChanged<Map<String, dynamic>> onEdit;
  final ValueChanged<Map<String, dynamic>> onDelete;
  final ValueChanged<Map<String, dynamic>> onTest;
  final ValueChanged<Map<String, dynamic>> onHistory;

  @override
  Widget build(BuildContext context) => _PageScroll(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _PageHeading(
          eyebrow: 'MONITOR CONFIGURATION',
          title: '监控配置',
          subtitle: '配置 HTTP、TCP、DNS、TLS 检查和故障恢复条件。',
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
                subtitle: '为网站、主机或服务添加 HTTP、TCP、DNS、TLS 检查。',
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
                  final target = type == 'HTTP'
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
    required this.port,
    required this.recordType,
    required this.resolver,
    required this.expectedRecordValue,
    required this.minimumValidityDays,
    required this.intervalSeconds,
    required this.timeoutSeconds,
    required this.failureThreshold,
    required this.recoveryThreshold,
    required this.expectedStatusCodes,
    required this.textContains,
    required this.critical,
    required this.enabled,
  });

  final String type;
  final String resourceId;
  final String name;
  final String target;
  final String method;
  final int? port;
  final String? recordType;
  final String? resolver;
  final String? expectedRecordValue;
  final int? minimumValidityDays;
  final int intervalSeconds;
  final int timeoutSeconds;
  final int failureThreshold;
  final int recoveryThreshold;
  final List<int> expectedStatusCodes;
  final String? textContains;
  final bool critical;
  final bool enabled;

  Map<String, dynamic> toJson() => {
    'resourceId': resourceId,
    'name': name,
    'type': type,
    'intervalSeconds': intervalSeconds,
    'timeoutSeconds': timeoutSeconds,
    'failureThreshold': failureThreshold,
    'recoveryThreshold': recoveryThreshold,
    'critical': critical,
    'enabled': enabled,
    if (type == 'HTTP') ...{
      'targetUrl': target,
      'method': method,
      'expectedStatusCodes': expectedStatusCodes,
      'textContains': textContains,
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

class _MonitorDialog extends StatefulWidget {
  const _MonitorDialog({required this.resources, this.existing});
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
      widget.resources.first['id'] as String;
  late String _method = widget.existing?['method'] as String? ?? 'GET';
  late String _recordType = widget.existing?['recordType'] as String? ?? 'A';
  late final _name = TextEditingController(
    text: widget.existing?['name'] as String? ?? '',
  );
  late final _target = TextEditingController(
    text: _type == 'HTTP'
        ? (widget.existing?['targetUrl'] as String? ?? '')
        : (widget.existing?['targetHost'] as String? ?? ''),
  );
  late final _port = TextEditingController(
    text: '${widget.existing?['port'] ?? (_type == 'TLS' ? 443 : 80)}',
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
    text: '${widget.existing?['timeoutSeconds'] ?? (_type == 'DNS' ? 5 : 10)}',
  );
  late final _failure = TextEditingController(
    text: '${widget.existing?['failureThreshold'] ?? 3}',
  );
  late final _recovery = TextEditingController(
    text: '${widget.existing?['recoveryThreshold'] ?? 2}',
  );
  late final _statuses = TextEditingController(
    text: _statusText(widget.existing?['expectedStatusCodes'] as List?),
  );
  late final _contains = TextEditingController(
    text: widget.existing?['textContains'] as String? ?? '',
  );
  late bool _critical = widget.existing?['critical'] as bool? ?? true;
  late bool _enabled = widget.existing?['enabled'] as bool? ?? true;

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
    _resolver.dispose();
    _expectedRecordValue.dispose();
    _minimumValidityDays.dispose();
    _interval.dispose();
    _timeout.dispose();
    _failure.dispose();
    _recovery.dispose();
    _statuses.dispose();
    _contains.dispose();
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
    final previousTimeoutDefault = previousType == 'DNS' ? 5 : 10;
    final nextTimeoutDefault = type == 'DNS' ? 5 : 10;
    final previousPortDefault = previousType == 'TLS' ? 443 : 80;
    final nextPortDefault = type == 'TLS' ? 443 : 80;
    if (_interval.text == '$previousIntervalDefault') {
      _interval.text = '$nextIntervalDefault';
    }
    if (_timeout.text == '$previousTimeoutDefault') {
      _timeout.text = '$nextTimeoutDefault';
    }
    if (_port.text == '$previousPortDefault') _port.text = '$nextPortDefault';
    setState(() => _type = type);
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
                onChanged: (value) => setState(() => _resourceId = value!),
              ),
              const SizedBox(height: 11),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: '检查类型'),
                items: const [
                  DropdownMenuItem(value: 'HTTP', child: Text('HTTP/HTTPS')),
                  DropdownMenuItem(value: 'TCP', child: Text('TCP 端口')),
                  DropdownMenuItem(value: 'DNS', child: Text('DNS 记录')),
                  DropdownMenuItem(value: 'TLS', child: Text('TLS 证书')),
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
                const SizedBox(height: 11),
                TextFormField(
                  controller: _contains,
                  maxLength: 500,
                  decoration: const InputDecoration(
                    labelText: '响应内容必须包含（可选）',
                    hintText: '例：service is healthy',
                  ),
                ),
              ] else ...[
                TextFormField(
                  controller: _target,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(
                    labelText: _type == 'DNS' ? '查询域名' : '目标主机名',
                    hintText: _type == 'DNS'
                        ? (_recordType == 'PTR'
                              ? '域名或 IP 地址'
                              : 'status.example.com')
                        : 'example.com',
                  ),
                  validator: (value) => !_validMonitorHost(value ?? '')
                      ? '请输入有效主机名或 IP 地址'
                      : null,
                ),
                const SizedBox(height: 11),
                if (_type == 'TCP' || _type == 'TLS')
                  _IntField(
                    controller: _port,
                    label: '端口',
                    minimum: 1,
                    maximum: 65535,
                  ),
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
                ],
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
                  _type == 'HTTP'
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

  void _submit() {
    if (!_formKey.currentState!.validate()) return;
    final interval = int.parse(_interval.text);
    final timeout = int.parse(_timeout.text);
    if (interval <= timeout) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('检查间隔必须大于单次超时时间')));
      return;
    }
    Navigator.pop(
      context,
      _MonitorDraft(
        type: _type,
        resourceId: _resourceId,
        name: _name.text.trim(),
        target: _target.text.trim(),
        method: _method,
        port: _type == 'TCP' || _type == 'TLS' ? int.parse(_port.text) : null,
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
        failureThreshold: int.parse(_failure.text),
        recoveryThreshold: int.parse(_recovery.text),
        expectedStatusCodes: _parseStatuses()!,
        textContains: _contains.text.trim().isEmpty
            ? null
            : _contains.text.trim(),
        critical: _critical,
        enabled: _enabled,
      ),
    );
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

class _CheckHistoryDialog extends StatelessWidget {
  const _CheckHistoryDialog({required this.monitor, required this.results});
  final Map<String, dynamic> monitor;
  final List<Map<String, dynamic>> results;

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text('${monitor['name']} · 检查历史'),
    content: SizedBox(
      width: 620,
      height: 440,
      child: results.isEmpty
          ? const _EmptyState(
              icon: Icons.history_rounded,
              title: '还没有检查结果',
              subtitle: '首次运行后，最近执行记录会显示在这里。',
            )
          : ListView.separated(
              itemCount: results.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (context, index) {
                final result = results[index];
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
                        style: const TextStyle(color: _muted, fontSize: 10),
                      ),
                    ],
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
                      '${incident['resourceName'] ?? '资源'}  ·  开始于 ${_dateTime(incident['openedAt'] as String?)}  ·  ${incident['occurrenceCount']} 次失败',
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
  _ => type,
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
  const _PageScroll({required this.child});
  final Widget child;

  @override
  Widget build(BuildContext context) => SingleChildScrollView(
    padding: const EdgeInsets.fromLTRB(28, 27, 28, 36),
    child: Center(
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
  _ => Icons.language_rounded,
};

String _monitorTypeName(String type) => switch (type) {
  'TCP' => 'TCP',
  'DNS' => 'DNS',
  'TLS' => 'TLS',
  _ => 'HTTP',
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
