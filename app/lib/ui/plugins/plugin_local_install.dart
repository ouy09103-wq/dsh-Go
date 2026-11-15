// 插件 › 从本地安装：系统选择器挑一个插件包文件，解到 App 临时目录、
// 重新打成规范 .tgz、放进 rootfs，再让引擎直接装它。
//
// 和「插件市场」的区别只有来源：不联网、不查注册表、不写市场缓存。
// 装好之后它就是这台主机上一个普通的插件，开关、卸载、更新提示都一样。

import 'dart:async';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../share_code/guest_fs.dart';
import '../../state/plugins_state.dart';
import '../common/app_dialog.dart';
import '../common/brief_notice.dart';
import 'plugin_actions.dart';

/// 选哪个文件：一个 provider，测试里换掉就不用弹系统选择器。
final localPluginPickerProvider = Provider<Future<({String name, Uint8List bytes})?> Function()>(
  (ref) => () async {
    final file = await FilePicker.pickFile();
    if (file == null) return null;
    final bytes = await file.readAsBytes();
    return (name: file.name, bytes: bytes);
  },
);

/// 「从本地安装」的完整一步：挑文件 → 整理成引擎能装的包 → 确认 → 装。
///
/// 失败都在这里说清楚：解不开、没有 package.json、太大、主机不支持，
/// 不是每种都值得弹对话框，但每一种都要说一句人能看懂的话。
Future<void> installPluginFromPhone(BuildContext context, WidgetRef ref) async {
  final l10n = context.l10n;
  final messenger = ScaffoldMessenger.maybeOf(context);
  final container = ProviderScope.containerOf(context, listen: false);
  final host = container.read(pluginHostProvider);
  if (host == null || !host.canInstall) {
    messenger?.showBriefNotice(l10n.pluginsLocalInstallUnsupported);
    return;
  }
  if (!host.isLocal) {
    messenger?.showBriefNotice(l10n.pluginsLocalInstallNotLocal);
    return;
  }

  final picked = await ref.read(localPluginPickerProvider)();
  if (picked == null || !context.mounted) return;

  final LocalPluginPackage package;
  try {
    package = await stageLocalPluginPackage(fs: await GuestFs.local(), fileName: picked.name, bytes: picked.bytes);
  } on LocalPluginException catch (error) {
    if (!context.mounted) return;
    messenger?.showBriefNotice(localPluginProblemText(l10n, error));
    return;
  }
  if (!context.mounted) return;

  // 装插件等于让一段代码进到能干活的引擎里：先说明这是什么，再动手。
  final confirmed = await showAppDialog<bool>(
    context: context,
    builder: (dialog) => AlertDialog(
      title: Text(l10n.pluginsLocalInstallConfirmTitle(package.title ?? package.name)),
      content: SingleChildScrollView(child: Text(l10n.pluginsLocalInstallConfirmBody(package.fileName, package.name))),
      actions: [
        TextButton(onPressed: () => Navigator.pop(dialog, false), child: Text(l10n.commonCancel)),
        FilledButton(onPressed: () => Navigator.pop(dialog, true), child: Text(l10n.pluginsLocalInstall)),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) return;

  final job = await runPluginInstall(
    context,
    PluginInstallRequest(
      name: package.name,
      spec: package.guestPath,
      title: package.title,
      // 本地包没有注册表版本，也没有官方市场的校验和可以核对；
      // 上游已经确认过同一个包不会被重复装，这里不需要 replaceInstalled。
    ),
  );
  if (!context.mounted) return;
  if (job.phase == PluginInstallPhase.succeeded) {
    messenger?.showBriefNotice(l10n.pluginsLocalInstallDone(package.title ?? package.name));
  }
}

/// [LocalPluginException] 说成人话。解压原因放在后面，不影响主句。
String localPluginProblemText(L10n l10n, LocalPluginException error) => switch (error.problem) {
  LocalPluginProblem.notAnArchive =>
    error.detail == null ? l10n.pluginsLocalInstallNotBundle : l10n.pluginsLocalInstallExtractFailed(error.detail!),
  LocalPluginProblem.notBundle => l10n.pluginsLocalInstallNotBundle,
  LocalPluginProblem.noName => l10n.pluginsLocalInstallNoName,
  LocalPluginProblem.tooBig => l10n.pluginsLocalInstallTooBig(localPluginMaxBytes ~/ (1024 * 1024)),
};
