import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../phone/android_shell_channel.dart';
import '../../root/root_channel.dart';
import '../../state/phone_state.dart';
import '../common/app_dialog.dart';
import '../common/list_page_row.dart';
import '../common/risk_notice.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

String androidShellErrorText(L10n l10n, String code) => switch (code) {
  'disabled' => l10n.androidShellErrorDisabled,
  'notConnected' => l10n.androidShellErrorNotConnected,
  'shizukuMissing' => l10n.androidShellErrorShizukuMissing,
  'shizukuStopped' => l10n.androidShellErrorShizukuStopped,
  'permissionRequired' => l10n.androidShellErrorPermissionRequired,
  'adbNotPaired' => l10n.androidShellErrorAdbNotPaired,
  'connectFailed' => l10n.androidShellErrorConnectFailed,
  'identityChanged' => l10n.androidShellErrorIdentityChanged,
  'busy' => l10n.androidShellErrorBusy,
  'invalidArguments' => l10n.androidShellErrorInvalidArguments,
  'cancelled' => l10n.androidShellErrorCancelled,
  'timeout' => l10n.androidShellErrorTimeout,
  'confirmationDenied' => l10n.androidShellErrorConfirmationDenied,
  'cleanupFailed' => l10n.androidShellErrorCleanupFailed,
  'locked' => l10n.androidShellErrorLocked,
  'confirmationUnavailable' => l10n.androidShellErrorConfirmationUnavailable,
  'transportLost' => l10n.androidShellErrorTransportLost,
  'protocolMismatch' => l10n.androidShellErrorProtocolMismatch,
  'nativeUnavailable' => l10n.androidShellErrorNativeUnavailable,
  'invalidResponse' => l10n.androidShellErrorInvalidResponse,
  'stopFailed' => l10n.androidShellErrorStopFailed,
  // root 通道每种失败都有自己的码，页面按码说清楚是哪一步坏了。
  'suMissing' => l10n.androidShellRootSuMissing,
  'suNotExecutable' => l10n.androidShellRootSuNotExecutable,
  'rootNotGranted' => l10n.androidShellRootNotGranted,
  'rootSuFailed' => l10n.androidShellRootSuFailed,
  'rootNotRoot' => l10n.androidShellRootNotRoot,
  'rootDomainUnknown' => l10n.androidShellRootDomainUnknown,
  'rootWrongDomain' => l10n.androidShellRootWrongDomain,
  'rootHostFailed' => l10n.androidShellRootHostFailed,
  _ => l10n.androidShellErrorUnknown,
};

/// Setup is separate from screen control; only its master switch gates enabling.
class AndroidShellPage extends ConsumerStatefulWidget {
  const AndroidShellPage({super.key});

  @override
  ConsumerState<AndroidShellPage> createState() => _AndroidShellPageState();
}

class _AndroidShellPageState extends ConsumerState<AndroidShellPage> {
  static const _channel = AndroidShellChannel();
  static const _margin = EdgeInsets.fromLTRB(16, 12, 16, 12);

  static const _root = RootChannel();
  static const _maxOutputLines = 40;

  late final AppLifecycleListener _lifecycle;
  AndroidShellStatus _status = const AndroidShellStatus();
  bool _loading = true;
  bool _busy = false;
  bool _stopping = false;
  String? _errorCode;
  int _read = 0;
  int _operation = 0;

  // root 那半页的状态：su 能力清单和控制台，和上面「Android Shell 通道」各管各的。
  RootStatus? _rootStatus;
  /// su 清单读过一次没有：读不到（原生模块不在）和「还在读」要分开说。
  bool _rootRead = false;
  bool _rootBusy = false;
  bool _rootSwitchBusy = false;
  final _rootCommand = TextEditingController(text: 'id');
  final _rootOutput = <String>[];

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(
      onResume: () {
        unawaited(ref.read(phoneProvider.notifier).refreshStatus());
        unawaited(_refresh());
      },
    );
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _rootCommand.dispose();
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final read = ++_read;
    final status = await _channel.status();
    if (!mounted || read != _read) return;
    setState(() {
      _status = status;
      _loading = false;
    });
    // su 清单是另一半，读它不该拖住这一半：缺原生模块（MissingPluginException）
    // 和原生报错一样只是让 root 那一段显示「读不到」，通道状态照常画。
    unawaited(_refreshRootStatus());
  }

  Future<void> _refreshRootStatus() async {
    RootStatus? rootStatus;
    try {
      rootStatus = await _root.status();
    } on Object {
      rootStatus = null;
    }
    if (!mounted) return;
    setState(() {
      _rootStatus = rootStatus;
      _rootRead = true;
    });
  }

  Future<void> _run(Future<AndroidShellResult> Function() action) async {
    if (_busy || _stopping || !_status.supported) return;
    final operation = ++_operation;
    setState(() {
      _busy = true;
      _errorCode = null;
    });
    try {
      final result = await action();
      if (!mounted || operation != _operation) return;
      setState(() => _errorCode = result.ok ? null : result.errorCode ?? 'invalidResponse');
      await _refresh();
    } finally {
      if (mounted && operation == _operation) setState(() => _busy = false);
    }
  }

  Future<void> _toggle(bool enabled) => _run(() async {
    if (enabled) {
      final l10n = context.l10n;
      final accepted = await confirmRisk(
        context,
        RiskNotice(
          id: 'phone.android-shell.enable',
          level: RiskLevel.high,
          title: l10n.androidShellRiskTitle,
          body: l10n.androidShellRiskBody,
          action: l10n.androidShellRiskAction,
        ),
      );
      if (!accepted || !mounted) return const AndroidShellResult(true);
      final phone = ref.read(phoneProvider);
      if (!phone.loaded || !phone.status.ready || !phone.status.enabled) {
        return const AndroidShellResult(false, errorCode: 'disabled');
      }
    }
    return _channel.configure(enabled: enabled, backend: _status.backend);
  });

  Future<void> _authorize() =>
      _run(() async => AndroidShellResult(await _channel.requestPermission(), errorCode: 'permissionRequired'));

  Future<void> _adbInput({required bool pairing}) async {
    if (_busy || _stopping) return;
    final input = await showAppDialog<_AdbInput>(
      context: context,
      builder: (_) => _AdbInputDialog(pairing: pairing),
    );
    if (input == null || !mounted) return;
    await _run(() => pairing ? _channel.pair(input.code!, port: input.port) : _channel.connect(port: input.port));
  }

  /// Stop remains available while a pairing/connection request is pending.
  Future<void> _stop() async {
    if (_stopping) return;
    final operation = ++_operation;
    setState(() {
      _stopping = true;
      _errorCode = null;
    });
    try {
      final stopped = await _channel.stop();
      if (!mounted || operation != _operation) return;
      setState(() => _errorCode = stopped ? null : 'stopFailed');
      await _refresh();
    } finally {
      if (mounted && operation == _operation) {
        setState(() {
          _stopping = false;
          _busy = false;
        });
      }
    }
  }

  // ── root 的一半：开关、档位、控制台 ──────────────────────────────────────
  //
  // 这个开关就是「模型能不能以 root 身份动手」的那一个，和聊天页的 root 胶囊
  // 是同一个值；档位是 DSHA 那三档，决定命令内容怎么拦。

  void _rootLog(String line) {
    if (!mounted) return;
    setState(() => _rootOutput.insert(0, line));
    if (_rootOutput.length > _maxOutputLines) _rootOutput.removeLast();
  }

  Future<void> _runRoot(Future<RootResult> Function() action, String title) async {
    if (_rootBusy) return;
    setState(() => _rootBusy = true);
    try {
      final result = await action();
      _rootLog('$title\n${result.report}');
    } on PlatformException catch (error) {
      _rootLog('$title\n失败：${error.code} ${error.message ?? ''}');
    } on Object catch (error) {
      _rootLog('$title\n失败：$error');
    } finally {
      if (mounted) setState(() => _rootBusy = false);
    }
  }

  /// 打开前问一句（关掉不需要）；确认只是拦一下误触，不保护什么。
  Future<void> _toggleRootShell(bool on) async {
    if (_rootSwitchBusy) return;
    final l10n = context.l10n;
    if (!on) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: Text(l10n.androidShellRootRiskTitle),
          content: Text(l10n.androidShellRootRiskBody),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialog, false), child: Text(l10n.commonCancel)),
            FilledButton(onPressed: () => Navigator.pop(dialog, true), child: Text(l10n.androidShellRootSwitch)),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
    }
    setState(() => _rootSwitchBusy = true);
    try {
      final ok = await ref.read(phoneProvider.notifier).setRootShell(!on);
      if (!mounted) return;
      if (!ok) _rootLog(l10n.androidShellRootChanged);
      await _refresh();
    } finally {
      if (mounted) setState(() => _rootSwitchBusy = false);
    }
  }

  Future<void> _setRootPolicy(RootPolicyMode mode) async {
    final ok = await ref.read(phoneProvider.notifier).setRootPolicyMode(mode);
    if (!mounted || ok) return;
    _rootLog(context.l10n.androidShellRootChanged);
  }

  /// 开关下面那行字：开着说到期时间，关着说明为什么关。
  String _rootSwitchSubtitle(L10n l10n, PhoneStatus status) {
    if (status.rootShell) {
      final until = status.rootShellUntil;
      if (until == null) return l10n.androidShellRootSwitchShort;
      final left = DateTime.fromMillisecondsSinceEpoch(until).difference(DateTime.now());
      return l10n.androidShellRootSwitchOn(left.inHours < 1 ? 1 : left.inHours);
    }
    return switch (status.rootShellOffReason) {
      'expired' => l10n.androidShellRootSwitchExpired,
      'strikes' => l10n.androidShellRootSwitchStrikes,
      _ => l10n.androidShellRootSwitchOff,
    };
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final phone = ref.watch(phoneProvider);
    final canSetup = !_loading && _status.supported && !_busy && !_stopping;
    final canEnable = canSetup && phone.loaded && phone.status.ready && phone.status.enabled;
    final reasonCode = _status.reasonCode;
    final timeout = _status.limits['maxTimeoutMs'];
    final output = _status.limits['maxOutputBytes'];
    final selected = _status.backend;
    final shizuku = selected == AndroidShellBackend.shizuku;
    final adb = selected == AndroidShellBackend.adb;
    final root = selected == AndroidShellBackend.root;
    final authorize = ListPageRow(
      key: const ValueKey('android-shell-authorize'),
      title: l10n.androidShellAuthorize,
      titleMaxLines: 2,
      subtitle: !_status.shizuku.installed
          ? l10n.androidShellErrorShizukuMissing
          : !_status.shizuku.running
          ? l10n.androidShellErrorShizukuStopped
          : null,
      subtitleMaxLines: 3,
      onTap: canSetup ? () => unawaited(_authorize()) : null,
    );
    final pairing = [
      ListPageRow(
        key: const ValueKey('android-shell-start-pairing'),
        title: l10n.androidShellStartPairing,
        titleMaxLines: 2,
        onTap: canSetup ? () => unawaited(_run(_channel.startPairing)) : null,
      ),
      ListPageRow(
        key: const ValueKey('android-shell-pair'),
        title: l10n.androidShellPair,
        onTap: canSetup ? () => unawaited(_adbInput(pairing: true)) : null,
      ),
    ];
    return AppPage.list(
      title: l10n.androidShellTitle,
      children: [
        PhoneNote(tone: NoteTone.warn, text: l10n.androidShellConfirmationNote),
        if (!phone.status.enabled) PhoneNote(text: l10n.androidShellMasterOff),
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              icon: AppIcons.terminal,
              title: l10n.androidShellEnable,
              titleMaxLines: 2,
              trailing: Switch(
                key: const ValueKey('android-shell-enable'),
                value: _status.enabled,
                onChanged: canEnable || (canSetup && _status.enabled) ? (value) => unawaited(_toggle(value)) : null,
              ),
            ),
            ListPageRow(
              title: l10n.androidShellStatus,
              subtitle: _loading
                  ? l10n.phoneNotReadyYet
                  : !_status.supported
                  ? androidShellErrorText(l10n, reasonCode ?? 'invalidResponse')
                  : reasonCode != null
                  ? androidShellErrorText(l10n, reasonCode)
                  : !_status.enabled
                  ? l10n.androidShellErrorDisabled
                  : _status.ready
                  ? l10n.androidShellReady
                  : l10n.androidShellErrorNotConnected,
              subtitleMaxLines: 6,
            ),
            ListPageRow(title: l10n.androidShellRefresh, onTap: _loading ? null : () => unawaited(_refresh())),
          ],
        ),
        if (_errorCode case final code?)
          PhoneNote(
            key: const ValueKey('android-shell-operation-error'),
            tone: NoteTone.warn,
            text: androidShellErrorText(l10n, code),
          ),
        ListPageCard(
          margin: _margin,
          title: l10n.androidShellBackend,
          children: [
            for (final backend in AndroidShellBackend.values)
              Semantics(
                selected: _status.backend == backend,
                inMutuallyExclusiveGroup: true,
                child: ListPageRow(
                  key: ValueKey('android-shell-backend-${backend.name}'),
                  leading: Icon(_status.backend == backend ? AppIcons.radioOn : AppIcons.radioOff),
                  title: switch (backend) {
                    AndroidShellBackend.shizuku => 'Shizuku',
                    AndroidShellBackend.adb => l10n.androidShellAdb,
                    AndroidShellBackend.root => l10n.androidShellRoot,
                  },
                  onTap: !canSetup || _status.backend == backend
                      ? null
                      : () => unawaited(_run(() => _channel.configure(enabled: _status.enabled, backend: backend))),
                ),
              ),
          ],
        ),
        if (shizuku && !_status.shizuku.granted)
          ListPageCard(margin: _margin, children: [authorize])
        else if (adb && !_status.adb.paired)
          ListPageCard(margin: _margin, children: pairing),
        // root 不用配对，只要有 su 就能连；没有 su 就把原因摆在按钮上。
        if (!_status.ready && (shizuku ? _status.shizuku.granted : !adb || _status.adb.paired))
          ListPageCard(
            margin: _margin,
            children: [
              ListPageRow(
                key: ValueKey(
                  shizuku
                      ? 'android-shell-connect-shizuku'
                      : adb
                      ? 'android-shell-connect'
                      : 'android-shell-connect-root',
                ),
                title: shizuku ? l10n.androidShellConnectChannel : l10n.androidShellConnect,
                titleMaxLines: 2,
                subtitle: !_status.enabled
                    ? l10n.androidShellErrorDisabled
                    : root && !_status.root.available
                    ? androidShellErrorText(l10n, 'suMissing')
                    : null,
                subtitleMaxLines: 3,
                onTap: canSetup && _status.enabled && !(root && !_status.root.available)
                    ? () => unawaited(adb ? _adbInput(pairing: false) : _run(_channel.connect))
                    : null,
              ),
            ],
          ),
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              key: const ValueKey('android-shell-stop'),
              icon: AppIcons.stop,
              title: l10n.androidShellStop,
              titleMaxLines: 2,
              danger: true,
              onTap: _loading || _stopping ? null : () => unawaited(_stop()),
            ),
          ],
        ),
        // ── Root 权限 ────────────────────────────────────────────────────────
        //
        // 这里就是「Root 权限」的全套：一个总开关（模型能否以 root 动手）、
        // 一个命令拦截档位（怎么拦）、su 能力清单、和一个能直接跑命令的控制台。
        // 它跟上面那条 Android Shell 通道共用一个页面，因为它们本来就是同一件事：
        // 从这台手机上执行系统命令，只是换了个身份。
        _rootSection(context, l10n, phone.status),
        PhoneOptionsFold(
          key: const ValueKey('android-shell-details'),
          title: l10n.androidShellDetails,
          children: [
            PhoneNote(text: l10n.androidShellSubtitle),
            PhoneNote(text: l10n.androidShellEnableHint),
            PhoneNote(
              text: shizuku
                  ? l10n.androidShellShizukuHint
                  : adb
                  ? l10n.androidShellAdbHint
                  : l10n.androidShellRootHint,
            ),
            ListPageCard(
              margin: _margin,
              children: [
                ListPageRow(
                  key: const ValueKey('android-shell-uid'),
                  title: l10n.androidShellUid,
                  value: _status.uid?.toString() ?? l10n.androidShellUidUnknown,
                ),
                if (timeout != null && output != null)
                  ListPageRow(title: l10n.androidShellLimits(timeout ~/ 1000, output), titleMaxLines: 3),
                if (reasonCode != null)
                  ListPageRow(
                    key: const ValueKey('android-shell-reason'),
                    title: l10n.androidShellErrorCode(reasonCode),
                    titleMaxLines: 3,
                  ),
                if (_errorCode case final code?) ListPageRow(title: l10n.androidShellErrorCode(code), titleMaxLines: 3),
                if (shizuku) ...[
                  _fact(l10n.androidShellInstalled, _status.shizuku.installed),
                  _fact(l10n.androidShellRunning, _status.shizuku.running),
                  _fact(l10n.androidShellGranted, _status.shizuku.granted),
                  if (_status.shizuku.granted) authorize,
                ] else if (adb) ...[
                  _fact(l10n.androidShellPaired, _status.adb.paired),
                  _fact(l10n.androidShellConnected, _status.adb.connected),
                  if (_status.adb.paired) ...pairing,
                ] else ...[
                  ListPageRow(
                    key: const ValueKey('android-shell-root-su'),
                    title: l10n.androidShellRootSu,
                    titleMaxLines: 2,
                    value: _status.root.su ?? l10n.androidShellRootNoSu,
                  ),
                  ListPageRow(
                    key: const ValueKey('android-shell-root-manager'),
                    title: l10n.androidShellRootManager,
                    titleMaxLines: 2,
                    value: _status.root.manager.isEmpty ? l10n.androidShellUidUnknown : _status.root.manager,
                  ),
                ],
              ],
            ),
            PhoneNote(
              text: shizuku
                  ? l10n.androidShellAuthorizeHint
                  : adb
                  ? l10n.androidShellPairingGuide
                  : l10n.androidShellRootHint,
            ),
            PhoneNote(text: l10n.androidShellStopHint),
          ],
        ),
      ],
    );
  }

  /// Root 权限那一段：总开关、档位、su 清单、控制台。
  ///
  /// 开关只有这一个：聊天页胶囊、这里、以及任何别的地方读的都是同一个值
  /// （[PhoneStatus.rootShell]），不存在第二个能打开 root 的入口。
  Widget _rootSection(BuildContext context, L10n l10n, PhoneStatus phone) {
    final rootEnabled = phone.rootShell;
    final mode = phone.rootPolicyMode;
    final rootStatus = _rootStatus;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              key: const ValueKey('android-shell-root-portal'),
              icon: AppIcons.terminal,
              title: l10n.androidShellRootPermission,
              titleMaxLines: 2,
              subtitle: l10n.androidShellRootPermissionHint,
              subtitleMaxLines: 4,
              trailing: Switch(
                key: const ValueKey('android-shell-root-switch'),
                value: rootEnabled,
                onChanged: _rootSwitchBusy || !_status.supported || !phone.enabled
                    ? null
                    : (value) => unawaited(_toggleRootShell(value)),
              ),
            ),
            ListPageRow(
              key: const ValueKey('android-shell-root-switch-state'),
              title: l10n.androidShellRootSwitch,
              titleMaxLines: 2,
              subtitle: _rootSwitchSubtitle(l10n, phone),
              subtitleMaxLines: 4,
            ),
          ],
        ),
        // 档位只在开着的时候有意义：关着时它拦谁去。
        if (rootEnabled)
          ListPageCard(
            margin: _margin,
            title: l10n.androidShellRootPolicy,
            children: [
              for (final choice in RootPolicyMode.values)
                Semantics(
                  selected: mode == choice,
                  inMutuallyExclusiveGroup: true,
                  child: ListPageRow(
                    key: ValueKey('android-shell-root-policy-${choice.wire}'),
                    leading: Icon(mode == choice ? AppIcons.radioOn : AppIcons.radioOff),
                    title: _policyLabel(l10n, choice),
                    titleMaxLines: 2,
                    subtitle: _policyHint(l10n, choice),
                    subtitleMaxLines: 4,
                    onTap: mode == choice ? null : () => unawaited(_setRootPolicy(choice)),
                  ),
                ),
              if (mode != RootPolicyMode.strict)
                PhoneNote(
                  key: const ValueKey('android-shell-root-policy-warning'),
                  tone: NoteTone.warn,
                  text: mode == RootPolicyMode.full
                      ? l10n.androidShellRootPolicyWarnFull
                      : l10n.androidShellRootPolicyWarn,
                ),
            ],
          ),
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              key: const ValueKey('android-shell-root-status'),
              icon: AppIcons.systemAccess,
              title: l10n.androidShellRootSu,
              titleMaxLines: 2,
              subtitle: !_rootRead
                  ? l10n.androidShellRootReading
                  : rootStatus == null || !rootStatus.hasSu
                  ? l10n.androidShellRootNoSu
                  : '${rootStatus.manager.isEmpty ? 'su' : rootStatus.manager} · ${rootStatus.abi} · '
                        'Android ${rootStatus.sdk} · ${l10n.androidShellRootLinux}: '
                        '${rootStatus.guestRootfs ? l10n.androidShellRootLinuxReady : l10n.androidShellRootLinuxMissing}',
              subtitleMaxLines: 4,
              value: rootStatus?.su ?? '—',
            ),
            ListPageRow(
              key: const ValueKey('android-shell-root-probe'),
              icon: AppIcons.verified,
              title: l10n.androidShellRootProbe,
              titleMaxLines: 2,
              value: _rootBusy ? '…' : l10n.androidShellRootProbeRun,
              onTap: _rootBusy ? null : () => unawaited(_runRoot(_root.probe, 'id -u')),
            ),
          ],
        ),
        PhoneNote(text: l10n.androidShellRootLinuxNote),
        ListPageCard(
          margin: _margin,
          title: l10n.androidShellRootCommand,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
              child: TextField(
                key: const ValueKey('android-shell-root-command'),
                controller: _rootCommand,
                minLines: 1,
                maxLines: 4,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                decoration: InputDecoration(
                  hintText: l10n.androidShellRootCommandHint,
                  border: const OutlineInputBorder(),
                ),
              ),
            ),
            ListPageRow(
              key: const ValueKey('android-shell-root-run'),
              icon: AppIcons.command,
              title: l10n.androidShellRootCommandRun,
              value: _rootBusy ? '…' : '▶',
              onTap: _rootBusy ? null : () => unawaited(_runRoot(() => _root.exec(_rootCommand.text), 'root 执行')),
            ),
          ],
        ),
        if (_rootOutput.isNotEmpty)
          ListPageCard(
            margin: _margin,
            title: l10n.androidShellRootOutput,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
                child: SelectableText(
                  _rootOutput.join('\n\n'),
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12, height: 1.4),
                ),
              ),
              ListPageRow(
                title: l10n.androidShellRootOutputCopy,
                onTap: () => Clipboard.setData(ClipboardData(text: _rootOutput.join('\n\n'))),
              ),
              ListPageRow(title: l10n.androidShellRootOutputClear, onTap: () => setState(_rootOutput.clear)),
            ],
          ),
      ],
    );
  }

  static String _policyLabel(L10n l10n, RootPolicyMode mode) => switch (mode) {
    RootPolicyMode.strict => l10n.androidShellRootPolicyStrict,
    RootPolicyMode.relaxed => l10n.androidShellRootPolicyRelaxed,
    RootPolicyMode.full => l10n.androidShellRootPolicyFull,
  };

  static String _policyHint(L10n l10n, RootPolicyMode mode) => switch (mode) {
    RootPolicyMode.strict => l10n.androidShellRootPolicyStrictHint,
    RootPolicyMode.relaxed => l10n.androidShellRootPolicyRelaxedHint,
    RootPolicyMode.full => l10n.androidShellRootPolicyFullHint,
  };

  Widget _fact(String title, bool ready) => ListPageRow(
    title: title,
    titleMaxLines: 2,
    value: ready ? context.l10n.phoneReady : context.l10n.phoneNotReadyYet,
  );
}

class _AdbInput {
  const _AdbInput({this.code, this.port});

  final String? code;
  final int? port;
}

/// Pairing and connection use separate short-lived forms and port controllers.
class _AdbInputDialog extends StatefulWidget {
  const _AdbInputDialog({required this.pairing});

  final bool pairing;

  @override
  State<_AdbInputDialog> createState() => _AdbInputDialogState();
}

class _AdbInputDialogState extends State<_AdbInputDialog> {
  final _code = TextEditingController();
  final _port = TextEditingController();

  @override
  void dispose() {
    _code.clear();
    _code.dispose();
    _port.dispose();
    super.dispose();
  }

  _AdbInput? _readInput() {
    final portText = _port.text.trim();
    final port = int.tryParse(portText);
    if (portText.isNotEmpty && (port == null || port < 1 || port > 65535)) return null;
    if (widget.pairing && !RegExp(r'^[0-9]{6}$').hasMatch(_code.text)) return null;
    return _AdbInput(code: widget.pairing ? _code.text : null, port: port);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(widget.pairing ? l10n.androidShellPair : l10n.androidShellConnect),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.pairing ? l10n.androidShellPairPortHint : l10n.androidShellConnectPortHint),
            if (widget.pairing)
              TextField(
                key: const ValueKey('android-shell-pair-code'),
                controller: _code,
                autofocus: true,
                keyboardType: TextInputType.number,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                enableIMEPersonalizedLearning: false,
                maxLength: 6,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(labelText: l10n.androidShellPairCode),
                onChanged: (_) => setState(() {}),
              ),
            TextField(
              key: ValueKey(widget.pairing ? 'android-shell-pair-port' : 'android-shell-connect-port'),
              controller: _port,
              autofocus: !widget.pairing,
              keyboardType: TextInputType.number,
              maxLength: 5,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: InputDecoration(
                labelText: widget.pairing ? l10n.androidShellPairPort : l10n.androidShellConnectPort,
              ),
              onChanged: (_) => setState(() {}),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.commonCancel)),
        FilledButton(
          key: const ValueKey('android-shell-adb-submit'),
          onPressed: _readInput() == null
              ? null
              : () {
                  // Input may change after this button was built but before the next frame.
                  final input = _readInput();
                  if (input == null) return;
                  _code.clear();
                  Navigator.pop(context, input);
                },
          child: Text(widget.pairing ? l10n.androidShellPairSubmit : l10n.androidShellConnect),
        ),
      ],
    );
  }
}
