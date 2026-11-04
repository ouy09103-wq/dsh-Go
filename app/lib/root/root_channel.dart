// Real-root channel (app/android/.../root/RootPlugin.kt)：设备上的 su、单条命令执行、
// 打包的 Ubuntu rootfs（以真 root 身份）、frida-server 安装与常驻 root shell。
//
// 只有显式点按钮才会 spawn su：管理器的授权弹窗出现在 [probe] / [exec] / [guest] 里。

import 'package:flutter/services.dart';

/// Root 侧的一张“能力清单”，不 spawn 任何进程。
class RootStatus {
  const RootStatus({
    required this.su,
    required this.manager,
    required this.sdk,
    required this.abi,
    required this.guestRootfs,
    required this.shells,
  });

  final String? su;
  final String manager;
  final int sdk;
  final String abi;
  final bool guestRootfs;
  final int shells;

  bool get hasSu => su != null && su!.isNotEmpty;

  static RootStatus from(Object? raw) {
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return RootStatus(
      su: map['su'] as String?,
      manager: (map['manager'] as String?) ?? '',
      sdk: (map['sdk'] as int?) ?? 0,
      abi: (map['abi'] as String?) ?? '',
      guestRootfs: map['guestRootfs'] == true,
      shells: (map['shells'] as int?) ?? 0,
    );
  }
}

/// 一条命令的结果。
class RootResult {
  const RootResult({
    required this.ok,
    required this.uid,
    required this.exitCode,
    required this.stdout,
    required this.stderr,
  });

  final bool ok;
  final int uid;
  final int exitCode;
  final String stdout;
  final String stderr;

  bool get isRoot => ok && uid == 0;

  static RootResult from(Object? raw) {
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return RootResult(
      ok: map['ok'] == true,
      uid: (map['uid'] as int?) ?? -1,
      exitCode: (map['exitCode'] as int?) ?? -1,
      stdout: (map['stdout'] as String?) ?? '',
      stderr: (map['stderr'] as String?) ?? '',
    );
  }

  String get report {
    final buffer = StringBuffer();
    if (stdout.trim().isNotEmpty) buffer.write(stdout.trimRight());
    if (stderr.trim().isNotEmpty) {
      if (buffer.isNotEmpty) buffer.write('\n');
      buffer.write(stderr.trimRight());
    }
    return buffer.isEmpty ? '(无输出，退出码 $exitCode)' : buffer.toString();
  }
}

/// frida-server 安装/状态。
class FridaState {
  const FridaState({
    required this.ok,
    this.version,
    this.path,
    this.port,
    this.pid,
    this.error,
    this.running = false,
    this.pids = '',
    this.listening = false,
    this.env = '',
  });

  final bool ok;
  final String? version;
  final String? path;
  final int? port;
  final String? pid;
  final String? error;
  final bool running;
  final String pids;
  final bool listening;

  /// 启动时注入的 ART 环境变量名（诊断用：空说明没读到 zygote 的环境）。
  final String env;

  String get report {
    if (!ok) return '失败：${error ?? '未知原因'}';
    final buffer = StringBuffer(running ? 'frida-server 运行中' : 'frida-server 未运行');
    if (pids.trim().isNotEmpty) buffer.write(' · PID ${pids.trim()}');
    if (listening) buffer.write(' · 27042 在监听');
    if (version != null) buffer.write('\n版本 $version');
    if (path != null) buffer.write('\n$path');
    if (env.trim().isNotEmpty) buffer.write('\nART 环境：${env.trim()}');
    return buffer.toString();
  }

  static FridaState from(Object? raw) {
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return FridaState(
      ok: map['ok'] == true,
      version: map['version'] as String?,
      path: map['path'] as String?,
      port: (map['port'] as int?),
      pid: map['pid'] as String?,
      error: map['error'] as String?,
      running: map['running'] == true || (map['pid'] as String? ?? '').isNotEmpty,
      pids: (map['pids'] as String?) ?? (map['pid'] as String?) ?? '',
      listening: map['listening'] == true,
      env: (map['env'] as String?) ?? '',
    );
  }
}

/// 一轮 shell 输出。
class ShellOutput {
  const ShellOutput({required this.stdout, required this.stderr, required this.closed});

  final String stdout;
  final String stderr;
  final bool closed;

  static ShellOutput from(Object? raw) {
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return ShellOutput(
      stdout: (map['stdout'] as String?) ?? '',
      stderr: (map['stderr'] as String?) ?? '',
      closed: map['closed'] == true,
    );
  }
}

class RootChannel {
  const RootChannel([this.channel = const MethodChannel('dsh/root')]);

  final MethodChannel channel;

  Future<RootStatus> status() async => RootStatus.from(await channel.invokeMethod<Object?>('status'));

  /// 触发管理器的 root 授权；返回 `id -u` 的结果。
  Future<RootResult> probe() async => RootResult.from(await channel.invokeMethod<Object?>('probe'));

  Future<RootResult> exec(String command, {int timeoutMs = 60000}) async => RootResult.from(
        await channel.invokeMethod<Object?>('exec', {'command': command, 'timeoutMs': timeoutMs}),
      );

  /// 在打包的 Ubuntu rootfs 里执行，身份是真 root。
  Future<RootResult> guest(String command, {int timeoutMs = 120000}) async => RootResult.from(
        await channel.invokeMethod<Object?>('guest', {'command': command, 'timeoutMs': timeoutMs}),
      );

  /// 装 frida-server。不给 [version] 时用 guest 里 `frida` 包的版本，server 和 client 才对得上。
  Future<FridaState> installFrida({String? version, int port = 27042}) async => FridaState.from(
        await channel.invokeMethod<Object?>('installFrida', {'version': version, 'port': port}),
      );

  Future<FridaState> fridaStatus() async => FridaState.from(await channel.invokeMethod<Object?>('fridaStatus'));

  /// guest 里 `frida` 的版本；读不到是 null。
  Future<String?> fridaClientVersion() async {
    final raw = await channel.invokeMethod<Object?>('fridaClientVersion');
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return map['version'] as String?;
  }

  /// frida-server 起不来时的替代：把 agent 注进一个普通 App（不碰 system_server / zygote）。
  Future<FridaState> fridaInject(String package, {String? script, String? version}) async =>
      FridaState.from(await channel.invokeMethod<Object?>(
        'fridaInject',
        {'package': package, 'script': script, 'version': version},
      ));

  Future<int> shellOpen({String? cwd}) async {
    final raw = await channel.invokeMethod<Object?>('shellOpen', {'cwd': cwd});
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return (map['id'] as int?) ?? -1;
  }

  Future<bool> shellWrite(int id, String text) async {
    final raw = await channel.invokeMethod<Object?>('shellWrite', {'id': id, 'text': text});
    final map = (raw as Map?)?.cast<String, Object?>() ?? const <String, Object?>{};
    return map['written'] == true;
  }

  Future<ShellOutput> shellPoll(int id) async =>
      ShellOutput.from(await channel.invokeMethod<Object?>('shellPoll', {'id': id}));

  Future<void> shellClose(int id) async => channel.invokeMethod<Object?>('shellClose', {'id': id});
}
