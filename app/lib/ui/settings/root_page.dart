// 设置 › Root 权限：这个 root 版的控制台。
//
// 三块：
//  1) 引擎——真 root（su + chroot，uid 0）开关、探针结果、现在到底在用什么。
//  2) root 工具——`mcp__root__*` 总开关（模型能不能 exec / frida）、桥接状态、清挂载。
//  3) 手动面板——设备上的 root 命令、rootfs 里的 root 命令、frida-server、常驻 root shell。
//
// 所有 su 调用都发生在点按之后；只有 `status` 不 spawn 进程。

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../root/root_channel.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
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
  final _guest = TextEditingController(text: 'id -u && uname -a && pwd');
  final _shellInput = TextEditingController();
  final _output = <String>[];
  final _scroll = ScrollController();

  final _fridaPath = TextEditingController();
  RootStatus? _status;
  List<FridaCandidate> _candidates = const [];
  bool _busy = false;
  bool _savingEngine = false;
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
    _fridaPath.dispose();
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

  Future<void> _toggleEngine(bool value) async {
    final runtime = ref.read(runtimeBridgeProvider);
    setState(() => _savingEngine = true);
    try {
      await runtime.setRootEngine(value);
      _log(value ? '真 root 引擎已启用，下次启动生效' : '已切回 proot，下次启动生效');
    } on PlatformException catch (error) {
      _log('切换引擎失败：${error.code} ${error.message ?? ''}');
      if (mounted) showBriefNotice(context, '切换失败：${error.message ?? error.code}');
    } finally {
      if (mounted) setState(() => _savingEngine = false);
      unawaited(_refresh());
    }
  }

  Future<void> _probe() async {
    setState(() => _busy = true);
    _log('申请 root 授权（管理器会弹窗）…');
    try {
      final support = await _root.supported();
      final usable = support['usable'] == true;
      _log(
        'root 探针\n'
        'su：${support['su'] ?? '未找到'}\n'
        '管理器：${support['manager'] ?? '未知'}\n'
        'uid：${support['uid'] ?? -1}\n'
        'chroot：${support['chroot'] == true ? '可用' : '不可用'}\n'
        'mount：${support['mount'] == true ? '可用' : '不可用'}\n'
        '内核：${support['kernel'] ?? '?'}\n'
        '结论：${usable ? '真 root 引擎可用' : '不可用（${support['reason'] ?? 'unknown'}）'}',
      );
    } on PlatformException catch (error) {
      _log('探针失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
      unawaited(_refresh());
    }
  }

  Future<void> _toggleTools(bool value) async {
    try {
      final now = await _root.setToolsEnabled(value);
      _log('root 工具（mcp__root__*）：${now ? '开' : '关'} · 重启引擎后生效');
    } on PlatformException catch (error) {
      _log('切换 root 工具失败：${error.message ?? error.code}');
    } finally {
      unawaited(_refresh());
    }
  }

  Future<void> _scanFrida() async {
    setState(() => _busy = true);
    try {
      final found = await _root.scanFrida();
      if (!mounted) return;
      setState(() => _candidates = found);
      _log(found.isEmpty
          ? '本机没找到 frida-server；可以用「安装并启动」联网下载'
          : '本机 frida-server：\n${found.map((c) => '· ${c.path}（${c.sizeMb}${c.compressed ? ' · xz' : ''}）').join('\n')}');
    } on PlatformException catch (error) {
      _log('扫描失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _installFridaLocal() async {
    final path = _fridaPath.text.trim();
    if (path.isEmpty) {
      showBriefNotice(context, '先填一个 frida-server 文件路径，或点「扫描本机」');
      return;
    }
    setState(() => _busy = true);
    _log('从本地文件启动 frida-server：$path');
    try {
      final state = await _root.installFrida(path: path);
      _log('frida-server\n${state.text.isNotEmpty ? state.text : state.report}');
    } on PlatformException catch (error) {
      _log('本地安装失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _installFrida() async {
    setState(() => _busy = true);
    _log('安装 frida-server…（下载可能较慢）');
    try {
      final state = await _root.installFrida();
      _log('frida-server\n${state.text.isNotEmpty ? state.text : state.report}');
    } on PlatformException catch (error) {
      _log('frida-server 安装失败：${error.code} ${error.message ?? ''}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stopFrida() async {
    setState(() => _busy = true);
    try {
      final state = await _root.fridaStop();
      _log('frida 停止\n${state.text.isNotEmpty ? state.text : state.report}');
    } on PlatformException catch (error) {
      _log('frida 停止失败：${error.code} ${error.message ?? ''}');
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
    final runtime = ref.watch(localRuntimeProvider);
    final rt = runtime.status;
    final stopped = !rt.phase.isBusy && rt.phase != RuntimePhase.running;
    final engineSwitchable = runtime.loaded && stopped && !_savingEngine;
    final support = rt.rootEngineSupport;
    final reason = (support['reason'] as String?) ?? '';
    final ready = (status?.usable ?? false) || rt.rootEngineAvailable;
    final engineHint = !ready
        ? switch (reason) {
            'noSu' => '这台设备没有 su：引擎继续用 proot（免 root），root 工具也用不了。',
            'notGranted' => '管理器还没给你授权：点「重新探针」，在弹窗里允许。',
            'noChroot' => '这台设备的 su 不给 chroot：引擎继续用 proot。',
            _ => '还没探针过；点「重新探针」跑一次 su 检查。',
          }
        : !stopped
        ? '先停引擎再切换。'
        : rt.inSafeMode
        ? '安全模式下始终用 proot：退出安全模式后再开。'
        : rt.rootEngineActive
        ? '当前就是真 root 引擎（uid 0）。'
        : '开启后：su + chroot 直接跑 rootfs，进程是真 uid 0，glibc 不再受 App 的 seccomp 限制。';

    return AppPage.list(
      title: 'Root 权限',
      subtitle: Text(
        status == null
            ? '正在读取…'
            : status.hasSu
            ? '${status.manager.isEmpty ? 'su' : status.manager} · ${status.abi} · Android ${status.sdk}'
            : '未检测到 su（继续用 proot）',
      ),
      children: [
        SettingsSection(
          title: '真 root 引擎',
          children: [
            SettingsSwitchTile(
              title: '用 su + chroot 启动引擎',
              subtitle: engineHint,
              value: rt.rootEngineEnabled,
              onChanged: engineSwitchable && (ready || rt.rootEngineEnabled) ? _toggleEngine : null,
            ),
            SettingsTile(
              icon: AppIcons.systemAccess,
              title: '重新探针',
              subtitle: '真跑一次 su：id -u、chroot、uname',
              value: Text(_busy ? '…' : 'su'),
              onTap: _busy ? null : _probe,
            ),
            SettingsTile(
              icon: AppIcons.engine,
              title: 'su 路径',
              value: Text(status?.su ?? (support['su'] as String?) ?? '—'),
            ),
            SettingsTile(
              icon: AppIcons.verified,
              title: '现在用的引擎',
              value: Text(
                rt.rootEngineActive
                    ? '真 root（chroot · uid 0）'
                    : (rt.currentExecutionMode ?? (rt.directEngineActive ? 'direct' : 'proot')),
              ),
            ),
            SettingsTile(
              icon: AppIcons.info,
              title: '内核',
              value: Text((status?.kernel.isNotEmpty ?? false) ? status!.kernel : ((support['kernel'] as String?) ?? '—')),
            ),
          ],
        ),
        SettingsSection(
          title: 'root 工具（给模型用）',
          children: [
            SettingsSwitchTile(
              title: 'mcp__root__* 工具',
              subtitle: (status?.enabled ?? true)
                  ? '开：模型能 status / exec / guest / frida_start / frida_stop / frida_status / frida_ps / frida_script。改完重启引擎。'
                  : '关：模型看不到 exec / frida 这些工具。',
              value: status?.enabled ?? true,
              onChanged: _busy ? null : _toggleTools,
            ),
            SettingsTile(
              icon: AppIcons.plugin,
              title: '桥接',
              value: Text((status?.listening ?? false) ? '监听中' : '未启动（引擎起来后才有）'),
            ),
            SettingsTile(
              icon: AppIcons.refresh,
              title: '清理残留挂载',
              subtitle: '上次异常退出把 /proc /sys /dev 留在 rootfs 里时用',
              onTap: _busy
                  ? null
                  : () async {
                      await _root.cleanup();
                      _log('已清理 rootfs 里的挂载点');
                    },
            ),
          ],
        ),
        SettingsSection(
          title: '设备上的 root 命令',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _command,
                minLines: 1,
                maxLines: 4,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(hintText: 'su -c "…"', border: OutlineInputBorder()),
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
          title: 'rootfs 里的 root 命令',
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _guest,
                minLines: 1,
                maxLines: 4,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(hintText: '/bin/sh -lc "…"', border: OutlineInputBorder()),
              ),
            ),
            SettingsTile(
              icon: AppIcons.terminal,
              title: '在 rootfs 里执行',
              subtitle: 'chroot 可用时是真的 uid 0，否则走 proot',
              value: Text(_busy ? '…' : '运行'),
              onTap: _busy ? null : () => _run(() => _root.guest(_guest.text), 'Linux 执行'),
            ),
          ],
        ),
        SettingsSection(
          title: 'Frida',
          children: [
            SettingsTile(
              icon: AppIcons.refresh,
              title: '扫描本机 frida-server',
              subtitle: '在 /sdcard/Download、/data/local/tmp 里找现成的二进制',
              value: Text(_busy ? '…' : (_candidates.isEmpty ? '未扫描' : '${_candidates.length} 个')),
              onTap: _busy ? null : _scanFrida,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
              child: TextField(
                controller: _fridaPath,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: const InputDecoration(
                  hintText: '/sdcard/Download/DSH-Go/frida-server-17.25.1-arm64',
                  border: OutlineInputBorder(),
                ),
              ),
            ),
            SettingsTile(
              icon: AppIcons.plugin,
              title: '用本地文件启动（推荐）',
              subtitle: '不联网：直接把这个文件装到 /data/local/tmp 并以 root 启动',
              value: Text(_busy ? '…' : '127.0.0.1:27042'),
              onTap: _busy ? null : _installFridaLocal,
            ),
            if (_candidates.isNotEmpty)
              for (final c in _candidates)
                SettingsTile(
                  icon: AppIcons.command,
                  title: c.path.split('/').last,
                  subtitle: c.path,
                  value: Text('${c.sizeMb}${c.compressed ? ' · xz' : ''}'),
                  onTap: _busy
                      ? null
                      : () {
                          _fridaPath.text = c.path;
                          unawaited(_installFridaLocal());
                        },
                ),
            SettingsTile(
              icon: AppIcons.plugin,
              title: '联网安装并启动',
              subtitle: '本机没有现成文件时用这个',
              value: Text(_busy ? '…' : '127.0.0.1:27042'),
              onTap: _busy ? null : _installFrida,
            ),
            SettingsTile(
              icon: AppIcons.engine,
              title: 'frida-server 状态',
              onTap: _busy ? null : _fridaStatus,
            ),
            SettingsTile(
              icon: AppIcons.privacy,
              title: '停止 frida-server',
              onTap: _busy ? null : _stopFrida,
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
                    icon: const Icon(Icons.send),
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
