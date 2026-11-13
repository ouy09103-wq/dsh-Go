// 右划快捷入口面板底部的 root 开关：和「插件 / 本机引擎 / 设置」那排快捷键挨着。
//
// 和 设置 › Root 权限 里那个是同一个开关，只是不用为了开一下翻进设置。
// 开着时整条走警示色——危险功能不该安安静静待在那儿。

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../state/phone_state.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// 「▣ 启用 root              点击开启」
/// 「▣ root 已启用           点击关闭」
///
/// 开与关只翻一个开关，不重启引擎：`shell` 每次调用时现查（见 PhoneCore）。
class RootSwitchPill extends ConsumerStatefulWidget {
  const RootSwitchPill({super.key});

  @override
  ConsumerState<RootSwitchPill> createState() => _RootSwitchPillState();
}

class _RootSwitchPillState extends ConsumerState<RootSwitchPill> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final dsh = DshColors.of(context);
    final status = ref.watch(phoneProvider.select((s) => s.status));
    final on = status.rootShell;
    final color = on ? dsh.warning : theme.colorScheme.onSurfaceVariant;
    final title = _busy
        ? '处理中…'
        : on
        ? 'root 已启用'
        : '启用 root';
    final detail = _busy
        ? ''
        : on
        ? '点击关闭'
        : switch (status.rootShellOffReason) {
            'expired' => '已到期',
            'strikes' => '已自动关闭',
            _ => '点击开启',
          };
    return Semantics(
      button: true,
      label: on ? 'root 已启用，点击关闭' : '启用 root',
      onTap: _busy ? null : () => _toggle(on),
      excludeSemantics: true,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(10, 0, 10, 6),
        child: InkWell(
          key: const ValueKey('root-switch-pill'),
          onTap: _busy ? null : () => _toggle(on),
          customBorder: const StadiumBorder(),
          child: Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 14),
            decoration: ShapeDecoration(
              color: on
                  ? dsh.warning.withValues(alpha: 0.12)
                  : theme.colorScheme.onSurface.withValues(alpha: 0.04),
              shape: const StadiumBorder(),
            ),
            child: Row(
              children: [
                Icon(AppIcons.terminal, size: 18, color: color),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(color: color, fontWeight: FontWeight.w600),
                  ),
                ),
                if (detail.isNotEmpty) ...[
                  const SizedBox(width: 8),
                  Text(
                    detail,
                    maxLines: 1,
                    style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 开启前问一句；关闭不需要。确认这一步只是拦一下误触，不保护什么。
  Future<void> _toggle(bool on) async {
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
      if (confirmed != true || !mounted) return;
    }
    setState(() => _busy = true);
    try {
      // 写完会回读原生状态，所以这里 await 到的是落盘后的真相。
      await ref.read(phoneProvider.notifier).setRootShell(!on);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
