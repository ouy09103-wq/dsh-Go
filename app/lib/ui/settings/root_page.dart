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
      final state = await _root.installFrida();
      _log(state.ok
          ? 'frida-server 已启动：${state.version} · PID ${state.pid} · 127.0.0.1:${state.port}\n${state.path}'
          : 'frida-server 安装失败：${state.error ?? '未知原因'}');
    } on PlatformException catch (error) {
      _log('frida-server 安装失败：${error.code} ${error.message ?? ''}');
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

  /// 聊天页 `shell` 工具的总开关：写进 SharedPreferences，引擎每次调用时现查。
  Future<void> _setAgentRoot(bool on) async {
    final ok = await ref.read(phoneProvider.notifier).setRootShell(on);
    if (!mounted) return;
    if (!ok) _log('root 开关没有写进去：原生通道没接通。');
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
            SettingsSwitchTile(
              icon: AppIcons.command,
              title: '允许模型执行 root 命令',
              subtitle: '开着时模型可以调 shell 工具，以 root 身份执行任意命令：无确认、无预览。',
              value: ref.watch(phoneProvider.select((s) => s.status.rootShell)),
              onChanged: (on) => _setAgentRoot(on),
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
              value: Text(_busy ? '…' : '127.0.0.1:27042'),
              onTap: _busy ? null : _installFrida,
            ),
            SettingsTile(
              icon: AppIcons.engine,
              title: 'frida-server 状态',
              onTap: _busy ? null : _fridaStatus,
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
