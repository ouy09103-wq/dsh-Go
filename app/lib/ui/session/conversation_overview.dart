// 基础信息: what a phone's header has no room for. The whole title, where the conversation
// runs and with which model, and how far it has come.

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/agents_state.dart' show subagentParentProvider;
import '../../state/chat_state.dart';
import '../../state/connection_state.dart';
import '../../state/models_state.dart';
import '../../state/phone_state.dart';
import '../../state/session_search.dart' show folderLabel;
import '../../state/sessions_state.dart';
import '../../plugins/native/native_state.dart';
import '../chat/model_sheet.dart';
import '../plugins/native/native_taps.dart' show runNativeAction;
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'session_detail_page.dart';

class ConversationOverview extends ConsumerWidget {
  const ConversationOverview({super.key, required this.sessionId});

  final String sessionId;

  void _pickModel(BuildContext context, WidgetRef ref, ModelSelection? current) {
    // Picked for this conversation on this connection, or for neither.
    final scope = ref.read(modelSelectionScopeProvider);
    final client = ref.read(activeConnectionProvider).value?.client;
    showModelSheet(
      context,
      current: current,
      onSelected: (selection) {
        if (!identical(ref.read(modelSelectionScopeProvider), scope) ||
            !identical(ref.read(activeConnectionProvider).value?.client, client) ||
            ref.read(currentSessionProvider) != sessionId ||
            // The list can learn what kind of subagent this is while the sheet is open.
            ref.read(subagentParentProvider(sessionId))?.address.mode == SubagentMode.oneShot) {
          return;
        }
        ref.read(chatProvider(sessionId).notifier).setModel(selection);
      },
    );
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final summary = ref.watch(
      sessionsProvider.select((s) => s.value?.where((session) => session.sessionId == sessionId).firstOrNull),
    );
    // The list knows the title before the conversation has loaded.
    final title = [
      ref.watch(chatProvider(sessionId).select((s) => s.title)),
      summary?.title,
    ].map((t) => t?.trim()).firstWhere((t) => t?.isNotEmpty ?? false, orElse: () => null);
    // A one-shot subagent takes no messages, nor another model.
    final readOnly = ref.watch(
      subagentParentProvider(sessionId).select((p) => p?.address.mode == SubagentMode.oneShot),
    );
    final model = ref.watch(chatProvider(sessionId).select((s) => s.model));
    final catalog = ref.watch(currentModelCatalogProvider);
    final effective = model ?? catalog?.defaultSelection;
    final effort = effective?.reasoningEffort;
    final host = ref.watch(activeConnectionProvider.select((c) => c.value?.label));
    final workspace = folderLabel(summary?.cwd);
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
      children: [
        Text(title ?? l10n.commonNewChat, style: theme.textTheme.titleMedium),
        const SizedBox(height: 12),
        SessionHero(sessionId: sessionId, title: null, gutter: 0),
        if (workspace != null) _Row(icon: AppIcons.workspace, label: l10n.shellPanelWorkspace, value: workspace),
        _Row(
          key: const ValueKey('overview-model'),
          icon: AppIcons.model,
          label: l10n.shellPanelModel,
          value: [modelShortName(effective, catalog), if (effort != null) effortLabel(effort)].join(' · '),
          onTap: readOnly ? null : () => _pickModel(context, ref, effective),
        ),
        _Row(icon: AppIcons.remoteHosts, label: l10n.shellPanelHost, value: host ?? l10n.commonThisPhone),
        const _RootRow(),
        // What plugins can do to this conversation, each signed with whose it is.
        for (final action in ref.watch(nativeSessionActionsProvider))
          _Row(
            key: ValueKey('overview-action|${action.package}|${action.id}'),
            icon: action.icon ?? AppIcons.plugin,
            label: action.label,
            value: ref.watch(nativePluginTitleProvider(action.package)),
            onTap: () => runNativeAction(context, ref, action, scope: DshGoScope(sessionId: sessionId)),
          ),
      ],
    );
  }
}

/// Root 权限的开关，摆在主机下面一点：和 设置 › Root 权限 里那个是同一个开关，
/// 只是不用为了开一下翻进设置。
class _RootRow extends ConsumerStatefulWidget {
  const _RootRow();

  @override
  ConsumerState<_RootRow> createState() => _RootRowState();
}

class _RootRowState extends ConsumerState<_RootRow> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    final status = ref.watch(phoneProvider.select((s) => s.status));
    final on = status.rootShell;
    final value = _busy
        ? '处理中…'
        : on
        ? '已启用'
        : switch (status.rootShellOffReason) {
            'expired' => '已到期',
            'strikes' => '已自动关闭',
            _ => '关闭',
          };
    return _Row(
      key: const ValueKey('overview-root'),
      icon: AppIcons.terminal,
      label: 'Root 权限',
      value: value,
      onTap: _busy ? null : () => _toggle(on),
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

class _Row extends StatelessWidget {
  const _Row({super.key, required this.icon, required this.label, required this.value, this.onTap});

  final IconData icon;
  final String label;
  final String value;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.onSurfaceVariant;
    return Semantics(
      button: onTap != null,
      label: '$label: $value',
      excludeSemantics: true,
      onTap: onTap,
      child: InkWell(
        onTap: onTap,
        customBorder: Squircle.border(AppShape.md),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 44),
          child: LayoutBuilder(
            builder: (context, constraints) => Row(
              children: [
                Icon(icon, size: 18, color: muted),
                const SizedBox(width: 10),
                // A plugin's label at a large text size: it stops short of pushing the value out.
                ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: constraints.maxWidth * 0.6),
                  child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodyMedium),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    value,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.end,
                    style: theme.textTheme.bodyMedium?.copyWith(color: muted),
                  ),
                ),
                if (onTap != null) Icon(AppIcons.expand, size: 16, color: muted),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
