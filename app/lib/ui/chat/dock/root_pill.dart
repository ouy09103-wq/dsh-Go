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
