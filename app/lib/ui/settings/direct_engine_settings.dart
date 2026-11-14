import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import 'settings_widgets.dart';

/// Direct launch, tawcroot launch and the root launch: all off by default and at most
/// one on; a change applies only after a deliberate engine stop/start.
class DirectEngineSettings extends ConsumerStatefulWidget {
  const DirectEngineSettings({super.key});

  @override
  ConsumerState<DirectEngineSettings> createState() => _DirectEngineSettingsState();
}

/// Everything a root launch can report: its own failure, and the su/domain checks that stop it
/// before a process exists. Direct's hint must not wear any of them.
const _rootFallbacks = {
  'suMissing',
  'suNotExecutable',
  'rootSuFailed',
  'rootNotGranted',
  'rootNotRoot',
  'rootDomainUnknown',
  'rootWrongDomain',
};

class _DirectEngineSettingsState extends ConsumerState<DirectEngineSettings> {
  bool _saving = false;

  @override
  Widget build(BuildContext context) {
    final runtime = ref.watch(localRuntimeProvider);
    final status = runtime.status;
    final l10n = context.l10n;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    final enabled =
        runtime.loaded && stopped && !_saving && (status.directEngineAvailable || status.directEngineEnabled);
    // Safe mode launches compatibly by design: not a sign the runtime needs it.
    // After Direct failed to launch, native reports the switch off until the
    // user turns it on again (a new try).
    final fallback = status.directEngineFallbackReason;
    final directFailed = fallback == 'directFailed';
    // A tawcroot start's fallback is the tawcroot switch's to explain.
    final runtimeNeedsCompatible =
        !status.tawcEngineEnabled &&
        fallback != null &&
        fallback != 'safeRecovery' &&
        !directFailed &&
        !fallback.startsWith('tawcroot') &&
        // A root launch's fallback belongs to the root switch, never to Direct.
        !_rootFallbacks.contains(fallback);
    final tawcFailed = fallback == 'tawcrootFailed';
    final tawcEnabled =
        runtime.loaded && stopped && !_saving && (status.tawcEngineAvailable || status.tawcEngineEnabled);
    final en = l10n.localeName == 'en';
    final rootEnabled =
        runtime.loaded && stopped && !_saving && (status.rootEngineAvailable || status.rootEngineEnabled);
    final rootHint = _rootHint(
      en: en,
      available: status.rootEngineAvailable,
      fallback: status.rootEngineEnabled ? null : fallback,
      stopped: stopped,
      safe: status.inSafeMode,
      l10n: l10n,
    );
    final tawcHint = !status.tawcEngineAvailable
        ? l10n.phoneTawcUnavailable
        : tawcFailed && !status.tawcEngineEnabled
        ? l10n.phoneTawcFailedHint
        : status.tawcEngineEnabled && fallback != null && fallback != 'safeRecovery'
        ? l10n.phoneDirectCompatibleHint
        : !stopped
        ? l10n.phoneDirectStopFirst
        : status.inSafeMode
        ? l10n.phoneDirectSafeHint
        : l10n.phoneTawcHint;
    final hint = !status.directEngineAvailable
        ? l10n.phoneDirectNeedsRuntime
        : directFailed && !status.directEngineEnabled
        ? l10n.phoneDirectFailedHint
        : runtimeNeedsCompatible
        ? l10n.phoneDirectCompatibleHint
        : !stopped
        ? l10n.phoneDirectStopFirst
        : status.inSafeMode
        ? l10n.phoneDirectSafeHint
        : l10n.phoneDirectHint;

    return SettingsSection(
      title: l10n.phoneDeveloperOptions,
      children: [
        // Only after a direct launch failed: a fallback already runs compatibly
        // and the switch's hint says so.
        if (status.directEngineEnabled && status.launchSelectedMode == 'direct' && status.launchFailureReason != null)
          SettingsTile(
            title: l10n.phoneReturnCompatible,
            subtitle: stopped ? null : l10n.phoneDirectStopFirst,
            onTap: enabled ? () => _change(false) : null,
          ),
        SettingsSwitchTile(
          key: const ValueKey('direct-engine-switch'),
          title: l10n.phoneDirectEngine,
          subtitle: hint,
          value: status.directEngineEnabled,
          onChanged: enabled ? _change : null,
        ),
        SettingsSwitchTile(
          key: const ValueKey('tawc-engine-switch'),
          title: l10n.phoneTawcEngine,
          subtitle: tawcHint,
          value: status.tawcEngineEnabled,
          onChanged: tawcEnabled ? _changeTawc : null,
        ),
        SettingsSwitchTile(
          key: const ValueKey('root-engine-switch'),
          title: en ? 'Root launch (uid 0)' : 'root 启动（真实 uid 0）',
          subtitle: rootHint,
          value: status.rootEngineEnabled,
          onChanged: rootEnabled ? _changeRoot : null,
        ),
      ],
    );
  }

  /// "No su" and "su is there but the manager has not granted it" are different facts: the
  /// first is about the device, the second about a prompt the user still has to accept.
  String _rootHint({
    required bool en,
    required bool available,
    required String? fallback,
    required bool stopped,
    required bool safe,
    required L10n l10n,
  }) {
    if (!available) {
      return en
          ? 'No su on this device. Grant root to this app in your root manager first.'
          : '这台设备上没有可用的 su。先在 root 管理器里给本 App 授权。';
    }
    if (fallback != null && _rootFallbacks.contains(fallback)) return _rootFailure(en, fallback);
    if (!stopped) return l10n.phoneDirectStopFirst;
    if (safe) return l10n.phoneDirectSafeHint;
    return en
        ? 'Runs the same engine as uid 0 through su, instead of proot imitating it. '
              'Only the launch method changes; the engine keeps its own rootfs.'
        : '通过 su 把同一个引擎跑成真正的 uid 0，而不是 proot 模拟出来的 0。'
              '只改启动方式，引擎仍用自己的 rootfs。';
  }

  /// One message per failure the kernel or su actually reported: a stale su, a refusal and a
  /// wrong SELinux domain have nothing in common but the switch they disabled.
  String _rootFailure(bool en, String code) => switch (code) {
    'suNotExecutable' => en
        ? 'su exists but the kernel will not run it (a stale /system/bin/su). Fix the root manager first.'
        : 'su 在，但系统不让执行（多半是残留的 /system/bin/su）。先修好 root 管理器。',
    'rootNotGranted' => en
        ? 'The root manager did not grant this app. Accept its prompt, then turn this on again.'
        : 'root 管理器没有给本 App 授权。在它的弹窗里同意，再打开这个开关。',
    'rootNotRoot' => en
        ? 'su answered with the app user id, so the child never became root.'
        : 'su 回的是 App 自己的 uid，没有真的变成 root。',
    'rootWrongDomain' => en
        ? 'uid 0 but still in an app SELinux domain: the engine could not read /data anyway.'
        : 'uid 是 0，但还在 App 的 SELinux 域里：引擎照样读不到 /data。',
    'rootDomainUnknown' => en
        ? 'Could not read the SELinux domain, so root is not proven.'
        : '读不到 SELinux 域，无法确认真的拿到了 root。',
    'suMissing' => en
        ? 'No su on this device. Grant root to this app in your root manager first.'
        : '这台设备上没有可用的 su。先在 root 管理器里给本 App 授权。',
    _ => en
        ? 'su did not answer. Check the root manager, then turn this on again.'
        : 'su 没有应答。先确认 root 管理器正常，再打开这个开关。',
  };

  Future<void> _changeRoot(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setRootEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _changeTawc(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setTawcEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _change(bool value) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setDirectEngine(value);
    } catch (_) {
      if (mounted) showBriefNotice(context, context.l10n.phoneDirectChangeFailed);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
