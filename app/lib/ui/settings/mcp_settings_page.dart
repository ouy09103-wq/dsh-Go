// 设置 → MCP 对接: the external MCP bridge. A local-host page listing the
// configured MCP servers (name, endpoint URL, bearer token), each with an
// enable switch; the whole list takes effect on the next engine start via
// native `McpGuest` (a `--patch` overlay of `dsh-mcp-client` instances).
// The token is stored by the app and written into the guest overlay file at
// every start; it is shown masked after being saved.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/empty_view.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';

/// MCP server list for the settings page.
///
/// The status event only carries a snapshot, so this notifier keeps the list the
/// person just saved instead of letting a later, older snapshot wipe it: [build]
/// seeds from status, [save] writes through, and [reload] asks the native side
/// again (the page's refresh button, and once when a first snapshot came back
/// empty because the page opened before the engine had reported anything).
final mcpServersProvider =
    NotifierProvider<McpServersNotifier, List<McpServerConfig>>(McpServersNotifier.new);

class McpServersNotifier extends Notifier<List<McpServerConfig>> {
  /// What this session last got from the native side; survives a snapshot that
  /// predates it, so the list never flickers back to empty on its own.
  List<McpServerConfig>? _known;

  @override
  List<McpServerConfig> build() {
    final live = ref.watch(localRuntimeProvider).status.mcpServers;
    if (live.isNotEmpty) _known = live;
    return _known ?? live;
  }

  /// Reads the saved list straight from the native side.
  Future<List<McpServerConfig>> reload() async {
    try {
      final status = await ref.read(runtimeBridgeProvider).status();
      final list = status.mcpServers;
      _known = list;
      if (ref.mounted) state = list;
      return list;
    } catch (_) {
      return _known ?? state;
    }
  }

  Future<bool> save(List<McpServerConfig> servers) async {
    try {
      await ref.read(runtimeBridgeProvider).setMcpServers(servers);
      _known = servers;
      state = servers;
      return true;
    } catch (_) {
      return false;
    }
  }
}

class McpSettingsPage extends ConsumerStatefulWidget {
  const McpSettingsPage({super.key});

  @override
  ConsumerState<McpSettingsPage> createState() => _McpSettingsPageState();
}

class _McpSettingsPageState extends ConsumerState<McpSettingsPage> {
  /// False until the first list has come back, so an empty page doesn't read as
  /// "nothing here" while the native side is still being asked.
  bool loaded = false;

  @override
  void initState() {
    super.initState();
    // The saved list lives in native prefs; the status snapshot may not have it
    // yet when this page is the first thing opened, so ask once directly.
    WidgetsBinding.instance.addPostFrameCallback((_) => _reload());
  }

  Future<void> _reload() async {
    await ref.read(mcpServersProvider.notifier).reload();
    if (mounted) setState(() => loaded = true);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final servers = ref.watch(mcpServersProvider);
    final runtime = ref.watch(localRuntimeProvider);
    final running = runtime.status.phase == RuntimePhase.running;
    final mountedNow = runtime.status.mcpActive;
    // A list that arrived from a status event counts as loaded too.
    final ready = loaded || servers.isNotEmpty;
    return AppPage.list(
      title: l10n.mcpTitle,
      subtitle: Text(l10n.mcpSubtitle),
      actions: [
        GlassIconButton(
          icon: AppIcons.refresh,
          tooltip: l10n.mcpRefresh,
          onPressed: _reload,
        ),
      ],
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(children: [
            if (mountedNow)
              _Badge(label: l10n.mcpActiveBadge, tone: Colors.green)
            else if (running && servers.any((s) => s.enabled))
              _Badge(label: l10n.mcpNextStartBadge, tone: null),
            const Spacer(),
            if (ready) Text(l10n.mcpServerCount(servers.length), style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
        const SizedBox(height: 8),
        if (ready && servers.isEmpty)
          EmptyView(
            icon: AppIcons.plugin,
            title: l10n.mcpEmptyTitle,
            message: l10n.mcpEmptyMessage,
            actionLabel: l10n.mcpAddServer,
            onAction: () => _editServer(context, null, servers),
          ),
        for (final (i, server) in servers.indexed)
          _ServerCard(
            key: ValueKey(server.name + i.toString()),
            server: server,
            running: running,
            onChanged: (next) async {
              final list = [...servers];
              list[i] = next;
              final ok = await ref.read(mcpServersProvider.notifier).save(list);
              if (mounted && ok) _toast(l10n.mcpSaved);
              if (mounted && !ok) _toast(l10n.mcpSaveFailed(l10n.commonFailedTryAgain));
            },
            onEdit: () => _editServer(context, server, servers),
            onDelete: () async {
              final confirmed = await showDialog<bool>(
                context: context,
                builder: (_) => AlertDialog(
                  title: Text(l10n.mcpServerDeleteConfirmTitle),
                  content: Text(l10n.mcpServerDeleteConfirmBody(server.name)),
                  actions: [
                    TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l10n.commonCancel)),
                    TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l10n.mcpServerDelete)),
                  ],
                ),
              );
              if (confirmed != true) return;
              final list = [...servers]..removeAt(i);
              final ok = await ref.read(mcpServersProvider.notifier).save(list);
              if (!mounted) return;
              _toast(ok ? l10n.mcpSaved : l10n.mcpSaveFailed(l10n.commonFailedTryAgain));
            },
          ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Column(children: [
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: servers.length >= mcpGuestMaxServers
                  ? null
                  : () => _editServer(context, null, servers),
              icon: const Icon(AppIcons.add),
              label: Text(l10n.mcpAddServer),
            ),
            const SizedBox(height: 8),
            Text(l10n.mcpEnabledHint, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 4),
            Text(l10n.mcpDocsHint, style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
      ],
    );
  }

  static const mcpGuestMaxServers = 8;

  void _toast(String message) {
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _editServer(BuildContext context, McpServerConfig? existing, List<McpServerConfig> servers) async {
    final l10n = context.l10n;
    final saved = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _ServerSheet(existing: existing, servers: servers),
    );
    if (saved != true || !mounted) return;
    // The sheet wrote through the bridge; read it back so the cards show what
    // the native side actually kept (a renamed entry included).
    await _reload();
    if (mounted) _toast(l10n.mcpSaved);
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.tone});

  final String label;
  final Color? tone;

  @override
  Widget build(BuildContext context) {
    final colors = DshColors.of(context);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: (tone ?? colors.textDisabled).withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(label, style: Theme.of(context).textTheme.labelSmall),
    );
  }
}

class _ServerCard extends StatelessWidget {
  const _ServerCard({
    super.key,
    required this.server,
    required this.running,
    required this.onChanged,
    required this.onEdit,
    required this.onDelete,
  });

  final McpServerConfig server;
  final bool running;
  final ValueChanged<McpServerConfig> onChanged;
  final VoidCallback onEdit;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(
              child: Text(
                server.name,
                style: theme.textTheme.titleMedium?.copyWith(fontFamily: 'monospace'),
              ),
            ),
            IconButton(
              onPressed: onDelete,
              icon: const Icon(AppIcons.delete),
              tooltip: l10n.mcpServerDelete,
            ),
          ]),
          Text(server.url, style: theme.textTheme.bodySmall),
          if (server.token.isNotEmpty) Text(l10n.mcpTokenMasked, style: theme.textTheme.bodySmall),
          const SizedBox(height: 8),
          Row(children: [
            Expanded(
              child: Text(
                server.enabled ? (running ? l10n.mcpStopFirst : l10n.mcpSaved) : '',
                style: theme.textTheme.bodySmall,
              ),
            ),
            Text(l10n.mcpServerEnabled, style: theme.textTheme.bodyMedium),
            Switch.adaptive(
              value: server.enabled,
              onChanged: (v) => onChanged(server.copyWith(enabled: v)),
            ),
          ]),
          TextButton(
            onPressed: onEdit,
            child: Text(l10n.commonEdit),
          ),
        ]),
      ),
    );
  }
}

class _ServerSheet extends ConsumerStatefulWidget {
  const _ServerSheet({required this.existing, required this.servers});

  final McpServerConfig? existing;
  final List<McpServerConfig> servers;

  @override
  ConsumerState<_ServerSheet> createState() => _ServerSheetState();
}

class _ServerSheetState extends ConsumerState<_ServerSheet> {
  late final TextEditingController _name;
  late final TextEditingController _url;
  late final TextEditingController _token;
  late bool _enabled;
  String? _error;

  @override
  void initState() {
    super.initState();
    _name = TextEditingController(text: widget.existing?.name ?? '');
    _url = TextEditingController(text: widget.existing?.url ?? '');
    _token = TextEditingController(text: widget.existing?.token ?? '');
    _enabled = widget.existing?.enabled ?? true;
  }

  @override
  void dispose() {
    _name.dispose();
    _url.dispose();
    _token.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final l10n = context.l10n;
    final name = _name.text.trim();
    final url = _url.text.trim();
    if (!McpServerConfig.validName(name)) {
      setState(() => _error = l10n.mcpNameInvalid);
      return;
    }
    final duplicate = widget.servers.any((s) => s.name == name && s != widget.existing);
    if (duplicate) {
      setState(() => _error = l10n.mcpNameDuplicate);
      return;
    }
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      setState(() => _error = l10n.mcpUrlInvalid);
      return;
    }
    final next = McpServerConfig(name: name, url: url, token: _token.text, enabled: _enabled);
    final list = [...widget.servers];
    final existing = widget.existing;
    if (existing == null) {
      list.add(next);
    } else {
      // Renaming is allowed, so find the old entry by its own name; a list that
      // changed underneath still gets the edit instead of dropping it.
      final i = list.indexWhere((s) => s == existing);
      if (i >= 0) {
        list[i] = next;
      } else {
        list.add(next);
      }
    }
    final ok = await ref.read(mcpServersProvider.notifier).save(list);
    if (!mounted) return;
    if (!ok) {
      setState(() => _error = l10n.mcpSaveFailed(l10n.commonFailedTryAgain));
      return;
    }
    Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final view = View.of(context);
    final bottom = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottom + view.padding.bottom + 16, left: 16, right: 16, top: 16),
      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(
          controller: _name,
          decoration: InputDecoration(labelText: l10n.mcpServerName, helperText: l10n.mcpServerNameHint),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _url,
          keyboardType: TextInputType.url,
          decoration: InputDecoration(labelText: l10n.mcpServerUrl, helperText: l10n.mcpServerUrlHint),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _token,
          obscureText: true,
          decoration: InputDecoration(labelText: l10n.mcpServerToken, helperText: l10n.mcpServerTokenHint),
        ),
        const SizedBox(height: 12),
        SwitchListTile.adaptive(
          contentPadding: EdgeInsets.zero,
          title: Text(l10n.mcpServerEnabled),
          value: _enabled,
          onChanged: (v) => setState(() => _enabled = v),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ),
        const SizedBox(height: 12),
        FilledButton(onPressed: _submit, child: Text(l10n.mcpSave)),
      ]),
    );
  }
}
