// 设置 › 能力 › MCP 对接: the saved server list round-trips through the native
// wire format even when entries are malformed, so the settings page can always
// draw one card per usable server instead of an empty page.

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('an empty or absent list decodes to no servers', () {
    expect(McpServerConfig.listFrom(null), isEmpty);
    expect(McpServerConfig.listFrom(''), isEmpty);
    expect(McpServerConfig.listFrom('[]'), isEmpty);
  });

  test('a saved server keeps its name, url, token and switch', () {
    const json =
        '[{"name":"MT","url":"http://127.0.0.1:8787/mcp","token":"secret","enabled":true}]';
    final servers = McpServerConfig.listFrom(json);
    expect(servers, hasLength(1));
    expect(servers.single.name, 'MT');
    expect(servers.single.url, 'http://127.0.0.1:8787/mcp');
    expect(servers.single.token, 'secret');
    expect(servers.single.enabled, isTrue);
    // The list the page shows is what goes back to native, unchanged.
    expect(serializeMcpServersJson(servers), json);
  });

  test('a server without enabled stays on, and is trimmed', () {
    final servers = McpServerConfig.listFrom(
      '[{"name":"  MT  ","url":" https://example.test/mcp "}]',
    );
    expect(servers.single.name, 'MT');
    expect(servers.single.url, 'https://example.test/mcp');
    expect(servers.single.enabled, isTrue);
    expect(servers.single.token, isEmpty);
  });

  test('malformed entries are dropped, the rest still list', () {
    final servers = McpServerConfig.listFrom(
      '[{"url":"http://no-name.test"},{"name":"OK","url":"https://ok.test/mcp"}]',
    );
    expect(servers.map((s) => s.name), ['OK']);
  });

  test('broken json is not fatal', () {
    expect(McpServerConfig.listFrom('{not json'), isEmpty);
    expect(McpServerConfig.listFrom('{"name":"MT"}'), isEmpty);
  });

  test('names accept the mcp-client alphabet only', () {
    expect(McpServerConfig.validName('MT'), isTrue);
    expect(McpServerConfig.validName('mt-local_1'), isTrue);
    expect(McpServerConfig.validName(''), isFalse);
    expect(McpServerConfig.validName('has space'), isFalse);
    expect(McpServerConfig.validName(List.filled(33, 'a').join()), isFalse);
  });

  test('an edited entry is found again after a reload from native', () {
    const before = McpServerConfig(name: 'MT', url: 'http://127.0.0.1:8787/mcp');
    // A reload builds new objects; the page still has to know this is the entry
    // the person edited, so equality is by value.
    final after = McpServerConfig.listFrom(serializeMcpServersJson([before])).single;
    expect(after, before);
    expect(after.copyWith(enabled: false), isNot(before));
  });
}
