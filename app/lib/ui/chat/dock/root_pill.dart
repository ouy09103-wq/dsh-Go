// 输入条上方的「启用 root」胶囊：和待办、目标同形同高，开/关一眼看得出。

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../state/phone_state.dart';
import '../../theme/app_icons.dart';
import '../../theme/theme.dart';
import 'dock_pill.dart';

/// 「▣ 启用 root                 点击开启」
/// 「▣ root 已启用              点击关闭」
///
/// 开与关只翻一个开关，不重启引擎：`shell` 每次调用时现查（见 PhoneCore）。
/// 开着时整条走警示色，关着时是次要色——危险功能不该安安静静待在那儿。
class RootPill extends ConsumerWidget {
  const RootPill({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(phoneProvider.select((s) => s.status.rootShell));
    final theme = Theme.of(context);
    final dsh = DshColors.of(context);
    final color = on ? dsh.warning : theme.colorScheme.onSurfaceVariant;
    return Semantics(
      button: true,
      label: on ? 'root 已启用，点击关闭' : '启用 root',
      onTap: () => _toggle(context, ref, on),
      excludeSemantics: true,
      child: GlassSurface(
        tier: GlassTier.chrome,
        borderRadius: DockMetrics.shape,
        grouped: true,
        onTap: () => _toggle(context, ref, on),
        child: DockRow(
          leading: DockGlyph.icon(AppIcons.terminal, color: color),
          content: Text(
            on ? 'root 已启用' : '启用 root',
            style: DockText.title(context, color: color),
          ),
          accessory: Text(on ? '点击关闭' : '点击开启', style: DockText.detail(context)),
        ),
      ),
    );
  }

  /// 开启前问一句；关闭不需要。确认这一步只是拦一下误触，不保护什么。
  Future<void> _toggle(BuildContext context, WidgetRef ref, bool on) async {
    if (!on) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: const Text('启用 root？'),
          content: const Text(
            '打开后，模型可以以 root 身份执行任意命令：无确认、无预览，能改系统、读所有应用的数据。\n\n'
            '不用的时候记得关掉。',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialog, false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(dialog, true), child: const Text('启用')),
          ],
        ),
      );
      if (confirmed != true) return;
    }
    await ref.read(phoneProvider.notifier).setRootShell(!on);
  }
}

/// 「root 已启用」胶囊的插槽：跟着待办 / 目标 / 状态条一起进 `DockStack` 的卡片列表。
///
/// 只在开着的时候出现：关着（默认）时聊天页和原版一模一样，开着这一行就是个
/// 提醒——现在模型能以 root 身份动手。开关在 设置 › Root 权限。
class RootDockSlot extends ConsumerWidget {
  const RootDockSlot({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(phoneProvider.select((s) => s.status.rootShell));
    return DockSlot(key: const ValueKey('root'), child: on ? const RootPill() : null);
  }
}

/// 「▣ 启用 Frida                点击启动」
/// 「▣ Frida 已启用 · 17.25.2   点击关闭」
/// 「▣ Frida 未装 · 需要模块     点击看说明」
///
/// 和 root 胶囊同形，紧挨着它出现。frida-server 现在由 Frida Control 模块提供，
/// 不在 App 里：模型没法从「我能跑 shell」推断出「模块装没装、监听哪个端口」，
/// 这一行把状态摆在它眼前，也让人能一键起停。
class FridaPill extends ConsumerStatefulWidget {
  const FridaPill({super.key});

  @override
  ConsumerState<FridaPill> createState() => _FridaPillState();
}

class _FridaPillState extends ConsumerState<FridaPill> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final status = ref.watch(phoneProvider.select((s) => s.status));
    final on = status.fridaRunning;
    final installed = status.fridaInstalled;
    final theme = Theme.of(context);
    final dsh = DshColors.of(context);
    final color = on ? dsh.warning : theme.colorScheme.onSurfaceVariant;
    final version = status.fridaVersion;
    final module = status.fridaModule;
    // 没装模块时启动一定失败，与其让人点了才看到错误，不如直接说清楚。
    final title = on
        ? (version == null ? 'Frida 已启用' : 'Frida 已启用 · $version')
        : (installed ? 'Frida 已装未启动' : (module ? '启用 Frida' : 'Frida 需要模块'));
    final detail = _busy
        ? '处理中…'
        : (on ? '点击关闭' : (installed || module ? '点击启动' : '看说明'));
    return Semantics(
      button: true,
      label: on ? '$title，点击关闭' : '$title，点击启动',
      excludeSemantics: true,
      child: GlassSurface(
        tier: GlassTier.chrome,
        borderRadius: DockMetrics.shape,
        grouped: true,
        onTap: _busy ? null : () => _tap(on),
        child: DockRow(
          leading: DockGlyph.icon(AppIcons.plugin, color: color),
          content: Text(title, style: DockText.title(context, color: color)),
          accessory: Text(detail, style: DockText.detail(context)),
        ),
      ),
    );
  }

  Future<void> _tap(bool running) async {
    final notifier = ref.read(phoneProvider.notifier);
    if (running) {
      await notifier.stopFrida();
      return;
    }
    // 没装模块：给一句能照做的说明，别让人以为点了没反应。
    if (!ref.read(phoneProvider).status.fridaModule) {
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: const Text('需要 Frida Control 模块'),
          content: const Text(
            'frida-server 现在由模块提供，不再打包进 App。\n\n'
            '1. 把 frida-control-v1.3.0.zip 放进 KernelSU / APatch 刷入\n'
            '2. 把 frida-server 放到 /data/adb/frida-hide/ksud_helper（chmod 700）\n'
            '3. 回来点这一行启动\n\n'
            '默认端口 27043：27042 会被部分目标探测后 abort。',
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialog), child: const Text('知道了')),
          ],
        ),
      );
      return;
    }
    setState(() => _busy = true);
    try {
      await notifier.startFrida();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

/// Frida 胶囊的插槽：只在 root 模式开着时出现——frida-server 要 root 才能跑，
/// root 关着的时候摆一行「启用 Frida」只是噪音。
class FridaDockSlot extends ConsumerWidget {
  const FridaDockSlot({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final show = ref.watch(phoneProvider.select((s) => s.status.rootShell));
    return DockSlot(key: const ValueKey('frida'), child: show ? const FridaPill() : null);
  }
}
