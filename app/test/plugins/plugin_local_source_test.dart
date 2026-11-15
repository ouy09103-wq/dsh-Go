import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:archive/archive.dart';
import 'package:dsh_app/share_code/guest_fs.dart';
import 'package:dsh_app/state/plugins_state.dart';
import 'package:flutter_test/flutter_test.dart';

/// 一张 .tgz：键是包内路径，值是内容。
List<int> _tgz(Map<String, String> files) {
  final archive = Archive();
  for (final entry in files.entries) {
    final bytes = Uint8List.fromList(utf8.encode(entry.value));
    archive.addFile(ArchiveFile(entry.key, bytes.length, bytes));
  }
  return GZipEncoder().encode(TarEncoder().encodeBytes(archive));
}

/// 一张 .zip，和 .tgz 同一个包内容。
List<int> _zip(Map<String, String> files) {
  final archive = Archive();
  for (final entry in files.entries) {
    final bytes = Uint8List.fromList(utf8.encode(entry.value));
    archive.addFile(ArchiveFile(entry.key, bytes.length, bytes));
  }
  return ZipEncoder().encode(archive);
}

const _manifest = '{"name":"dsh-go-local-demo","version":"1.2.3","dsh":{"title":"本地演示"}}';

void main() {
  late Directory root;
  late GuestFs fs;

  setUp(() {
    root = Directory.systemTemp.createTempSync('local-plugin-');
    fs = GuestFs(root);
  });

  tearDown(() => root.deleteSync(recursive: true));

  test('a .tgz package is staged where the engine can read it', () async {
    final package = await stageLocalPluginPackage(
      fs: fs,
      fileName: 'demo-1.2.3.tgz',
      bytes: _tgz({'package/package.json': _manifest, 'package/lib/index.js': 'export default {};'}),
    );
    // name/version come from package.json, not the file name people picked.
    expect(package.name, 'dsh-go-local-demo');
    expect(package.version, '1.2.3');
    expect(package.title, '本地演示');
    expect(package.guestPath, startsWith('${GuestFs.dshHome}/dsh-app/local-plugins/'));
    // The engine gets a real path inside its own Linux, holding a real tarball.
    final staged = File('${root.path}/${package.guestPath.replaceFirst(RegExp(r'^/+'), '')}');
    expect(staged.existsSync(), isTrue);
    final entries = TarDecoder().decodeBytes(GZipDecoder().decodeBytes(staged.readAsBytesSync()));
    expect(entries.files.map((f) => f.name), containsAll(['package.json', 'lib/index.js']));
  });

  test('a .zip with one wrapping folder is flattened to the package root', () async {
    final package = await stageLocalPluginPackage(
      fs: fs,
      fileName: 'demo.zip',
      bytes: _zip({'demo-main/package.json': _manifest, 'demo-main/lib/index.js': 'export default {};'}),
    );
    expect(package.name, 'dsh-go-local-demo');
    final staged = File('${root.path}/${package.guestPath.replaceFirst(RegExp(r'^/+'), '')}');
    final entries = TarDecoder().decodeBytes(GZipDecoder().decodeBytes(staged.readAsBytesSync()));
    // package.json has to sit at the root, or pnpm finds no package at all.
    expect(entries.files.map((f) => f.name), containsAll(['package.json', 'lib/index.js']));
  });

  test('packing metadata never reaches the installable package', () async {
    final package = await stageLocalPluginPackage(
      fs: fs,
      fileName: 'demo.tgz',
      bytes: _tgz({
        'package/package.json': _manifest,
        'package/__MACOSX/._package.json': 'junk',
        './package/lib/index.js': 'export default {};',
      }),
    );
    final staged = File('${root.path}/${package.guestPath.replaceFirst(RegExp(r'^/+'), '')}');
    final names = TarDecoder()
        .decodeBytes(GZipDecoder().decodeBytes(staged.readAsBytesSync()))
        .files
        .map((f) => f.name);
    expect(names, containsAll(['package.json', 'lib/index.js']));
    expect(names.any((n) => n.contains('__MACOSX')), isFalse);
    expect(names.any((n) => n.startsWith('./')), isFalse);
  });

  test('a package without package.json is refused before anything is staged', () async {
    await expectLater(
      stageLocalPluginPackage(fs: fs, fileName: 'demo.tgz', bytes: _tgz({'readme.md': 'hi'})),
      throwsA(isA<LocalPluginException>().having((e) => e.problem, 'problem', LocalPluginProblem.notBundle)),
    );
    expect(Directory('${root.path}/${GuestFs.dshHome}').existsSync(), isFalse);
  });

  test('a manifest without a name is refused', () async {
    await expectLater(
      stageLocalPluginPackage(fs: fs, fileName: 'demo.tgz', bytes: _tgz({'package.json': '{"version":"1.0.0"}'})),
      throwsA(isA<LocalPluginException>().having((e) => e.problem, 'problem', LocalPluginProblem.noName)),
    );
  });

  test('an oversized package is refused before it is unpacked', () async {
    await expectLater(
      stageLocalPluginPackage(fs: fs, fileName: 'demo.tgz', bytes: Uint8List(localPluginMaxBytes + 1)),
      throwsA(isA<LocalPluginException>().having((e) => e.problem, 'problem', LocalPluginProblem.tooBig)),
    );
  });

  test('an unknown or corrupt archive is refused', () async {
    await expectLater(
      stageLocalPluginPackage(fs: fs, fileName: 'demo.rar', bytes: _tgz({'package.json': _manifest})),
      throwsA(isA<LocalPluginException>().having((e) => e.problem, 'problem', LocalPluginProblem.notAnArchive)),
    );
    await expectLater(
      stageLocalPluginPackage(fs: fs, fileName: 'demo.tgz', bytes: const [1, 2, 3, 4]),
      throwsA(isA<LocalPluginException>().having((e) => e.problem, 'problem', LocalPluginProblem.notAnArchive)),
    );
  });

  test('without an unpacked runtime there is nowhere to stage, so it fails closed', () async {
    await expectLater(
      stageLocalPluginPackage(fs: null, fileName: 'demo.tgz', bytes: _tgz({'package.json': _manifest})),
      throwsA(isA<LocalPluginException>()),
    );
  });
}
