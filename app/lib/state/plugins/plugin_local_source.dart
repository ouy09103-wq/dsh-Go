part of '../plugins_state.dart';

// ── 从本地安装 ───────────────────────────────────────────────────────────────
//
// 插件市场走注册表，远程主机走命令，这两条都在别处。这里管的是第三条：
// 用户手机里已经有一个插件包（`pnpm pack` 出来的 .tgz、`.tar.gz`，或者别人
// 发来的 .zip），直接装进本机引擎，不联网、不经过市场。
//
// 引擎跑在自己的 Linux（proot rootfs）里，只认那个文件系统里的绝对路径。
// 所以流程是：系统选择器挑文件 → 解到 App 的临时目录并重新打包成一个规范
// 的 .tgz → 复制进 rootfs 的 DSH 缓存目录 → 把那个 Linux 路径交给
// `pluginManager/installBundle`（它本来就接受 tarball 的绝对路径）。

/// 一份已经落到 rootfs 里、可以被引擎直接安装的本地插件包。
class LocalPluginPackage {
  const LocalPluginPackage({
    required this.name,
    required this.version,
    required this.guestPath,
    required this.fileName,
    this.title,
  });

  /// `package.json` 里的 `name`：本地安装的包名以它为准。
  final String name;
  final String version;

  /// 引擎（它的 Linux）里的绝对路径。
  final String guestPath;

  /// 用户选的那个文件名，只用于说明。
  final String fileName;

  /// 插件自己起的名字，没有就是 null。
  final String? title;
}

/// 本地插件包读不懂时的原因，都是可以整句说给用户听的话。
enum LocalPluginProblem {
  /// 不是压缩包，或者坏了。
  notAnArchive,

  /// 里面没有 `package.json`。
  notBundle,

  /// `package.json` 读不出 `name`。
  noName,

  /// 太大。
  tooBig,
}

class LocalPluginException implements Exception {
  const LocalPluginException(this.problem, {this.detail});

  final LocalPluginProblem problem;

  /// 解压失败时的原始原因，可选。
  final String? detail;
}

/// 一个本地插件包最多多大。插件包是代码加一点资源，几 MB 已经很多；
/// 上限在这里是为了不让一次误选把引擎的缓存撑满。
const localPluginMaxBytes = 64 * 1024 * 1024;

/// 本地插件包在 rootfs 里的落脚处：DSH 自己的 Home 下面，重启不丢，
/// 也不和插件的安装目录混在一起。
const _localPluginStageDir = '${GuestFs.dshHome}/dsh-app/local-plugins';

/// 把选中的 [fileName] + [bytes] 整理成引擎能装的包，放进 rootfs。
///
/// [fs] 为 null（运行时还没装好）时抛 [LocalPluginException]，调用方按
/// 「这台主机不能安装」处理。
Future<LocalPluginPackage> stageLocalPluginPackage({
  required GuestFs? fs,
  required String fileName,
  required List<int> bytes,
}) async {
  if (fs == null) throw const LocalPluginException(LocalPluginProblem.notAnArchive);
  if (bytes.length > localPluginMaxBytes) {
    throw const LocalPluginException(LocalPluginProblem.tooBig);
  }
  final files = _readLocalPluginFiles(fileName, bytes);
  final manifest = _readLocalPluginManifest(files);
  final name = manifest.$1;
  final version = manifest.$2;
  final title = manifest.$3;

  // 重新打成一个规范 tar.gz：引擎只认 .tgz / .tar.gz，而用户给的可能是 .zip，
  // 也可能顶层还套着一层目录（GitHub 的源码包就是这样）。重打包时把它们都抹平，
  // 让 package.json 一定在包根上。
  final archive = Archive();
  for (final entry in files.entries) {
    final data = entry.value;
    archive.addFile(ArchiveFile(entry.key, data.length, data));
  }
  final tar = TarEncoder().encodeBytes(archive);
  final gzipped = GZipEncoder().encode(tar);

  final safeName = name.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '-');
  final staged = '${safeName}-${version.isEmpty ? 'local' : version}.tgz';
  final target = fs.file('$_localPluginStageDir/$staged');
  await target.parent.create(recursive: true);
  await target.writeAsBytes(gzipped, flush: true);

  return LocalPluginPackage(
    name: name,
    version: version,
    guestPath: '$_localPluginStageDir/$staged',
    fileName: fileName,
    title: title,
  );
}

/// 包里的文件，键是相对包根的路径。顶层只有一个目录时整层剥掉。
Map<String, List<int>> _readLocalPluginFiles(String fileName, List<int> bytes) {
  final lower = fileName.toLowerCase();
  Archive? archive;
  try {
    if (lower.endsWith('.zip')) {
      archive = ZipDecoder().decodeBytes(bytes);
    } else if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) {
      archive = TarDecoder().decodeBytes(GZipDecoder().decodeBytes(bytes));
    } else if (lower.endsWith('.tar')) {
      archive = TarDecoder().decodeBytes(bytes);
    }
  } on Object catch (error) {
    throw LocalPluginException(LocalPluginProblem.notAnArchive, detail: '$error');
  }
  if (archive == null) throw const LocalPluginException(LocalPluginProblem.notAnArchive);

  final files = <String, List<int>>{};
  for (final file in archive.files) {
    if (!file.isFile) continue;
    final path = _localPluginPath(file.name);
    // 打包工具会塞进 pax_global_header、__MACOSX/ 之类的东西：它们不是插件的一部分。
    if (path == null) continue;
    files[path] = file.content as List<int>;
  }
  if (files.isEmpty) throw const LocalPluginException(LocalPluginProblem.notAnArchive);
  return _stripSingleRoot(files);
}

/// 一条包内路径的规范化：去掉前导 `./`、拒绝绝对路径和 `..`，丢掉压缩工具自己的元数据。
String? _localPluginPath(String raw) {
  var path = raw.replaceAll('\\', '/');
  while (path.startsWith('./')) {
    path = path.substring(2);
  }
  if (path.isEmpty || path.startsWith('/')) return null;
  final parts = path.split('/').where((p) => p.isNotEmpty && p != '.').toList();
  if (parts.isEmpty || parts.any((p) => p == '..')) return null;
  // macOS 的压缩工具会在任意层级塞一个 __MACOSX，里面是资源分支的副本。
  if (parts.contains('__MACOSX')) return null;
  if (parts.last == '.DS_Store') return null;
  if (parts.length == 1 && parts.first == 'pax_global_header') return null;
  return parts.join('/');
}

/// GitHub 的源码包、`tar czf` 建出来的包常常顶层多一层目录。只有一个、
/// 而且它不是 `package.json` 所在的那层时剥掉，让包根认得出来。
Map<String, List<int>> _stripSingleRoot(Map<String, List<int>> files) {
  if (files.containsKey('package.json')) return files;
  final tops = {for (final path in files.keys) path.split('/').first};
  if (tops.length != 1) return files;
  final top = tops.single;
  final prefix = '$top/';
  if (!files.keys.every((path) => path.startsWith(prefix))) return files;
  final stripped = <String, List<int>>{};
  for (final entry in files.entries) {
    stripped[entry.key.substring(prefix.length)] = entry.value;
  }
  return stripped;
}

/// `package.json` 的 name / version / 展示名；name 缺失就是不是插件包。
(String, String, String?) _readLocalPluginManifest(Map<String, List<int>> files) {
  final raw = files['package.json'];
  if (raw == null) throw const LocalPluginException(LocalPluginProblem.notBundle);
  Object? decoded;
  try {
    decoded = jsonDecode(utf8.decode(raw, allowMalformed: true));
  } on Object {
    throw const LocalPluginException(LocalPluginProblem.notBundle);
  }
  if (decoded is! Map) throw const LocalPluginException(LocalPluginProblem.notBundle);
  final name = decoded['name'];
  if (name is! String || name.trim().isEmpty) throw const LocalPluginException(LocalPluginProblem.noName);
  final version = decoded['version'];
  final display = decoded['dsh'];
  String? title;
  if (display is Map) {
    final value = display['title'];
    if (value is String && value.trim().isNotEmpty) title = value.trim();
  }
  return (name.trim(), version is String ? version.trim() : '', title);
}
