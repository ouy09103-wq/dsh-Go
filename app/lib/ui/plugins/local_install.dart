// 本地安装插件：把手机上选的文件放进 guest，再交给 host 装。
//
// 为什么不是"直接读路径"：Android 的文件选择器给的是 content URI，App 拿不到真实路径，
// 而 host 是 guest 里的 `dsh plugin add`，它只看得到 guest 的文件系统。所以这里先把字节
// 写到 guest 的 DSH home 下的临时目录（rootfs 在 App 的 files 目录里，Dart 直接能写），
// 再把 `file:` 形式的 spec 交给 host。
//
// 支持 host 认识的两种：`.tgz` / `.tar.gz`（npm pack 的产物）和目录打包成的 `.zip`。
// 后者先解到临时目录，spec 指向目录。

import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../share_code/guest_fs.dart';
import '../../state/plugins_state.dart';
import 'plugin_actions.dart';

/// 本地包暂存在 profile 目录里面。
///
/// 位置是有讲究的：`dsh plugin add` 要把这个包记进 profile 的 package.json，而它只接受
/// 能相对 profile 解析的地址。放在 profile 外面（比如 DSH home 下）时，host 会回
/// `unaddressable` / `management-required`，界面上就显示「不能在这里修改」——包其实
/// 装进去了，只是没法登记。
const _profileDir = '${GuestFs.dshHome}/profiles/web';
const _stagingDir = '$_profileDir/local-plugin-staging';

/// A picked file, already read. The picker hands back a content URI, so the
/// bytes are the only thing that survives the trip.
class LocalPluginFile {
  const LocalPluginFile({required this.name, required this.bytes});

  final String name;
  final List<int> bytes;

  /// Host-readable spec suffixes; anything else is refused before staging.
  static const _archives = ['.tgz', '.tar.gz', '.zip'];

  bool get usable => _archives.any((s) => name.toLowerCase().endsWith(s));
}

/// Stages [file] into the guest and installs it on the local host.
///
/// Returns null when there is no local rootfs or no host to install on; the
/// caller then says why (see [canInstallLocalPlugin]).
Future<PluginInstallJob?> installLocalPlugin(BuildContext context, LocalPluginFile file) async {
  final container = ProviderScope.containerOf(context, listen: false);
  final host = container.read(pluginHostProvider);
  if (host == null || !host.canInstall || !host.isLocal) return null;

  final guest = await GuestFs.local();
  if (guest == null) return null;

  final dir = guest.dir(_stagingDir);
  if (dir.existsSync()) dir.deleteSync(recursive: true);
  dir.createSync(recursive: true);

  // The name has to round-trip through a shell and npm's file: spec: keep it
  // boring rather than escaping it in three places.
  final safe = _safeName(file.name);
  final staged = File('${dir.path}/$safe');
  staged.writeAsBytesSync(file.bytes);

  // A profile-relative spec: that is the form `dsh plugin add` can record in the
  // profile's package.json. An absolute path outside the profile comes back
  // `unaddressable` — the package is unpacked but never registered.
  final spec = 'file:./local-plugin-staging/$safe';
  final request = PluginInstallRequest(
    name: _packageGuess(safe),
    spec: spec,
    title: _packageGuess(safe),
    replaceInstalled: true,
  );
  // The staging above awaited file IO; `runPluginInstall` grabs everything it
  // needs from the context synchronously, so it only has to still be mounted.
  if (!context.mounted) return null;
  try {
    return await runPluginInstall(context, request);
  } finally {
    // The host copied it into the profile; the staging copy is done.
    try {
      staged.deleteSync();
    } on FileSystemException {
      // Left behind; the next install clears the directory anyway.
    }
  }
}

/// Whether a local install can run at all, and the sentence when it can't.
String? localPluginBlockedReason(BuildContext context, WidgetRef ref) {
  final host = ref.watch(pluginHostProvider);
  if (host == null) return context.l10n.pluginsConnectToManage;
  if (host.safeMode) return context.l10n.pluginsSafeModeOperationsBlocked;
  if (!host.isLocal) return context.l10n.pluginsInstallHost(host.label);
  if (!host.canInstall) return context.l10n.pluginsRelayToggleOnly(host.label);
  return null;
}

/// `my-plugin-1.2.3.tgz` → `my-plugin`. npm's spec resolves the package name
/// from the archive, but the job key (and the progress card) needs one first.
String _packageGuess(String fileName) {
  var name = fileName;
  for (final suffix in ['.tgz', '.tar.gz', '.zip']) {
    if (name.toLowerCase().endsWith(suffix)) {
      name = name.substring(0, name.length - suffix.length);
      break;
    }
  }
  // `name-1.2.3` and `name-1.2.3-beta.1`: drop the version tail when it looks
  // like one, so the card says "my-plugin" instead of "my-plugin-1.2.3".
  // `.tar.gz` was already removed above, so `my-plugin-1.2.3` is the common case.
  final withoutVersion = name.replaceFirst(RegExp(r'-\d+\.\d+\.\d+(?:[-+].*)?$'), '');
  // A file literally named `1.2.3.tgz` would leave nothing: keep the original.
  return withoutVersion.isEmpty ? name : withoutVersion;
}

/// Files and directories in a shell are happier without these.
String _safeName(String name) {
  final cleaned = name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
  return cleaned.isEmpty ? 'plugin.tgz' : cleaned;
}
