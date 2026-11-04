// 设置 › Root 权限: 真 root 通道的入口页——状态、命令、Linux（Ubuntu rootfs）、
// frida-server、常驻 root shell。每一项都只在点按后 spawn su。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../root/root_channel.dart';
import '../../state/phone_state.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'settings_widgets.dart';

class RootPage extends ConsumerStatefulWidget {
  const RootPage({super.key});

  @override
  ConsumerState<RootPage> createState() => _RootPageState();
}

class _RootPageState extends ConsumerState<RootPage> {
  static const _root = RootChannel();

  final _command = TextEditingController(text: 'id');
  final _guest = TextEditingController(text: 'id -u && uname -a');
  final _shellInput = TextEditingController();
  final _output = <String>[];
  final _scroll = ScrollController();

  RootStatus? _status;
  bool _busy = false;

  /// guest 里 `frida` 的版本：server 要装同一个，否则连不上（版本不匹配看起来像装坏了）。
  String? _fridaVersion;
  int _shellId = -1;
  Timer? _poller;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _poller?.cancel();
    final id = _shellId;
    if (id > 0) unawaited(_root.shellClose(id));
    _command.dispose();
    _guest.dispose();
    _shellInput.dispose();
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    try {
      final status = await _root.status();
      if (!mounted) return;
      setState(() => _status = status);
    } on PlatformException catch (error) {
      _log('状态读取失败：${error.message}');
    }
    unawaited(_readFridaVersion());
  }

  /// 只读，不 spawn 任何东西：读不到就算了，安装时再退回内置版本。
  Future<void> _readFridaVersion() async {
    try {
      final version = await _root.fridaClientVersion();
      if (mounted && version != null) setState(() => _fridaVersion = version);
    } on PlatformException {
      // rootfs 还没解压时读不到，不是错。
    }
  }

  void _log(String line) {
    if (!mounted) return;
    setState(() => _output.insert(0, line));
    if (_output.length > 40) _output.removeLast();
  }

  Future<void> _run(Future<RootResult> Function() action, String title) async {
    setState(() => _busy = true);
    try {
      final result = await action();
      _log('$title\n${result.report}');
    } on PlatformException catch (error) {
      _log('$title\n失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _installFrida() async {
    setState(() => _busy = true);
    _log('安装 frida-server…（下载可能较慢）');
    try {
      final state = await _root.installFrida(version: _fridaVersion);
      _log(state.ok
          ? 'frida-server 已启动：${state.version} · PID ${state.pid} · 127.0.0.1:${state.port}\n${state.path}'
          : 'frida-server 安装失败：${state.error ?? '未知原因'}');
    } on PlatformException catch (error) {
      _log('frida-server 安装失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 注入：先选一个正在运行的 App，再问要不要带脚本。都用对话框，不悄悄动别人的进程。
  Future<void> _injectDialog() async {
    final package = await showDialog<String>(
      context: context,
      builder: (context) => _InjectDialog(clientVersion: _fridaVersion),
    );
    if (package == null || !mounted) return;
    setState(() => _busy = true);
    _log('注入 $package…');
    try {
      final state = await _root.fridaInject(package, version: _fridaVersion);
      _log(state.ok
          ? '已注入 $package（PID ${state.pid}）\n${state.report}'
          : '注入失败：${state.error ?? '未知原因'}');
    } on PlatformException catch (error) {
      _log('注入失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _fridaStatus() async {
    setState(() => _busy = true);
    try {
      final state = await _root.fridaStatus();
      _log('frida 状态\n${state.report}');
    } on PlatformException catch (error) {
      _log('frida 状态\n失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// 「允许模型执行 root 命令」的总开关：写进 SharedPreferences，引擎每次调用时现查。
  ///
  /// 开一次只算 12 小时，到点自己关；连续 3 次拿不到 root 也会关。这两件事都由原生侧算，
  /// 这里只把那一刻说给用户看，免得他以为按钮点坏了。
  Future<void> _setAgentRoot(bool on) async {
    final ok = await ref.read(phoneProvider.notifier).setRootShell(on);
    if (!mounted) return;
    if (!ok) _log('root 开关没有写进去：原生通道没接通。');
  }

  /// 开关下面那行字：开着说到期时间，关着说明为什么关。
  String _grantSubtitle(PhoneStatus status) {
    if (status.rootShell) {
      final until = status.rootShellUntil;
      if (until == null) return '开着时模型可以调 shell 工具，以 root 身份执行任意命令。';
      final left = DateTime.fromMillisecondsSinceEpoch(until).difference(DateTime.now());
      final hours = left.inHours;
      final when = hours >= 1 ? '还剩 $hours 小时' : '不到 1 小时';
      return '开着时模型可以以 root 身份执行任意命令。这次授权$when，到点自己关。';
    }
    return switch (status.rootShellOffReason) {
      'expired' => '上次的授权已经到期，自动关掉了。要接着用就再打开一次。',
      'strikes' => '连续 3 次拿不到 root，已经自动关掉。先确认 root 管理器里 DSH Go 的授权还在。',
      _ => '开着时模型可以调 shell 工具，以 root 身份执行任意命令。不碰名单、系统目录、'
          'DSH Go 自己的数据依然拦着。',
    };
  }

  Future<void> _toggleShell() async {
    if (_shellId > 0) {
      _poller?.cancel();
      await _root.shellClose(_shellId);
      setState(() => _shellId = -1);
      _log('root shell 已关闭');
      return;
    }
    try {
      final id = await _root.shellOpen();
      if (!mounted) return;
      setState(() => _shellId = id);
      _log('root shell 已打开 (#$id)，输入命令后发送');
      _poller = Timer.periodic(const Duration(milliseconds: 600), (_) => unawaited(_poll()));
    } on PlatformException catch (error) {
      _log('打开 root shell 失败：${error.code} ${error.message ?? ''}');
    }
  }

  Future<void> _poll() async {
    final id = _shellId;
    if (id <= 0) return;
    final out = await _root.shellPoll(id);
    if (out.stdout.isNotEmpty || out.stderr.isNotEmpty) {
      _log('> ${'${out.stdout}${out.stderr}'.trimRight()}');
    }
    if (out.closed && mounted) {
      _poller?.cancel();
      setState(() => _shellId = -1);
      _log('root shell 已结束');
    }
  }

  Future<void> _send() async {
    final text = _shellInput.text;
    if (text.isEmpty) return;
    if (_shellId <= 0) await _toggleShell();
    if (_shellId <= 0) return;
    await _root.shellWrite(_shellId, '$text\n');
    _shellInput.clear();
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    return AppPage.list(
      title: 'Root 权限',
      subtitle: Text(status == null
          ? '正在读取…'
          : status.hasSu
              ? '${status.manager.isEmpty ? 'su' : status.manager} · ${status.abi} · Android ${status.sdk}'
              : '未检测到 su'),
      children: [
        SettingsSection(
          title: '模型能用 root 吗',
          children: [
            Consumer(
              builder: (context, ref, _) {
                final status = ref.watch(phoneProvider.select((s) => s.status));
                return SettingsSwitchTile(
                  icon: AppIcons.command,
                  title: '允许模型执行 root 命令',
                  subtitle: _grantSubtitle(status),
                  value: status.rootShell,
                  onChanged: (on) => _setAgentRoot(on),
                );
              },
            ),
            Consumer(
              builder: (context, ref, _) {
                final strict = ref.watch(phoneProvider.select((s) => s.status.rootShellStrict));
                return SettingsSwitchTile(
                  icon: AppIcons.privacy,
                  title: '严格模式',
                  subtitle: strict
                      ? '开着：shell 也走手机操控那套规则——不碰名单里的应用、系统目录、凭据库、本 App 存储都会被拒。'
                      : '关着（默认）：shell 就是 shell，只受 root 管理器管。打开它才逐条套用手机操控的规则。',
                  value: strict,
                  onChanged: (on) => unawaited(ref.read(phoneProvider.notifier).setRootShellStrict(on)),
                );
              },
            ),
          ],
        ),
        SettingsSection(
          title: '状态',
          children: [
            SettingsTile(
              icon: AppIcons.systemAccess,
              title: 'su 路径',
              value: Text(status?.su ?? '—'),
              onTap: _refresh,
            ),
            SettingsTile(
              icon: AppIcons.verified,
              title: '申请 root 授权（id -u）',
              value: Text(_busy ? '…' : '点按执行'),
              onTap: _busy ? null : () => _run(_root.probe, 'id -u'),
            ),
            SettingsTile(
              icon: AppIcons.terminal,
              title: 'Linux 环境（rootfs）',
              value: Text((status?.guestRootfs ?? false) ? '已就绪' : '未解压'),
            ),
          ],
        ),
        SettingsSection(
          title: '执行 root 命令',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _command,
                minLines: 1,
                maxLines: 4,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(
                  hintText: 'su -c "…"',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            SettingsTile(
              icon: AppIcons.command,
              title: '以 root 执行',
              value: Text(_busy ? '…' : '运行'),
              onTap: _busy ? null : () => _run(() => _root.exec(_command.text), 'root 执行'),
            ),
          ],
        ),
        SettingsSection(
          title: 'Linux（Ubuntu rootfs，真 root）',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _guest,
                minLines: 1,
                maxLines: 4,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(
                  hintText: '/bin/sh -lc "…"',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            SettingsTile(
              icon: AppIcons.terminal,
              title: '在 rootfs 里执行',
              value: Text(_busy ? '…' : '运行'),
              onTap: _busy ? null : () => _run(() => _root.guest(_guest.text), 'Linux 执行'),
            ),
          ],
        ),
        SettingsSection(
          title: 'Frida',
          children: [
            SettingsTile(
              icon: AppIcons.plugin,
              title: '安装并启动 frida-server',
              subtitle: '版本跟 Linux 里的 frida 对齐；装到 /data/local/tmp 并以 127.0.0.1:27042 启动',
              value: Text(_busy ? '…' : (_fridaVersion ?? '自动')),
              onTap: _busy ? null : _installFrida,
            ),
            SettingsTile(
              icon: AppIcons.engine,
              title: 'frida-server 状态',
              value: Text(_busy ? '…' : '查看'),
              onTap: _busy ? null : _fridaStatus,
            ),
            SettingsTile(
              icon: AppIcons.command,
              title: '往 App 里注入（server 起不来时用）',
              subtitle: '把 agent 注进一个正在运行的普通 App。不碰 system_server / zygote：那里一崩整台手机会重启。',
              onTap: _busy ? null : _injectDialog,
            ),
          ],
        ),
        SettingsSection(
          title: '常驻 root shell',
          children: [
            SettingsTile(
              icon: AppIcons.terminal,
              title: _shellId > 0 ? '关闭 root shell' : '打开 root shell',
              value: Text(_shellId > 0 ? '#$_shellId 运行中' : '未打开'),
              onTap: _busy ? null : _toggleShell,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _shellInput,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                onSubmitted: (_) => unawaited(_send()),
                decoration: InputDecoration(
                  hintText: 'whoami / ls /data/adb',
                  border: const OutlineInputBorder(),
                  suffixIcon: IconButton(
                    icon: const Icon(AppIcons.send),
                    onPressed: () => unawaited(_send()),
                  ),
                ),
              ),
            ),
          ],
        ),
        if (_output.isNotEmpty)
          SettingsSection(
            title: '输出',
            children: [
              SizedBox(
                height: 240,
                child: Scrollbar(
                  controller: _scroll,
                  child: ListView(
                    controller: _scroll,
                    padding: const EdgeInsets.all(16),
                    children: [
                      SelectableText(
                        _output.join('\n\n'),
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.4),
                      ),
                    ],
                  ),
                ),
              ),
              SettingsTile(
                icon: AppIcons.redacted,
                title: '复制输出',
                onTap: () => Clipboard.setData(ClipboardData(text: _output.join('\n\n'))),
              ),
              SettingsTile(
                icon: AppIcons.privacy,
                title: '清空',
                onTap: () => setState(_output.clear),
              ),
            ],
          ),
      ],
    );
  }
}

/// 选一个要注入的 App。
///
/// 只列装着的普通应用，且把 frida 明确不能碰的进程挡在外面：往 system_server 或 zygote
/// 注 agent，崩一次就是整台手机重启（这台机器上已经发生过一次）。所以这两个不走列表，
/// 也不做「随便填个包名」的入口。
class _InjectDialog extends StatefulWidget {
  const _InjectDialog({this.clientVersion});

  final String? clientVersion;

  @override
  State<_InjectDialog> createState() => _InjectDialogState();
}

class _InjectDialogState extends State<_InjectDialog> {
  static const _forbidden = {'system_server', 'zygote', 'zygote64', 'zygote32', 'init', 'surfaceflinger'};

  final _query = TextEditingController();
  String? _picked;

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('注入到 App'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.clientVersion == null
                ? '写要注入的 App 包名（必须是普通应用）。'
                : '写要注入的 App 包名。用的是 frida ${widget.clientVersion}。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _query,
            autofocus: true,
            onChanged: (v) => setState(() => _picked = v.trim()),
            decoration: const InputDecoration(
              labelText: '包名',
              hintText: 'com.example.app',
              border: OutlineInputBorder(),
            ),
          ),
          if (_picked != null && _forbidden.contains(_picked))
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(
                '这个进程不能注入：崩了整台手机会重启。只对普通 App 用。',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('取消')),
        FilledButton(
          onPressed: _picked == null || _picked!.isEmpty || _forbidden.contains(_picked)
              ? null
              : () => Navigator.of(context).pop(_picked),
          child: const Text('注入'),
        ),
      ],
    );
  }
}
