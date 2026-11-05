// The places added after the first four (docs/design/plugin-platform.md §2): the ＋ panel's tools,
// a conversation's actions in 信息, per-turn notes, a panel on the plugin's own page and a panel
// that opens as a sheet. Older bridges send none of them.

import 'package:dsh_app/plugins/native/native_model.dart';
import 'package:dsh_app/plugins/native/native_surface.dart';
import 'package:dsh_app/ui/chat/widgets/plus_panel.dart';
import 'package:dsh_app/ui/chat/widgets/turn_footnote.dart';
import 'package:dsh_app/ui/plugins/native/native_panel.dart';
import 'package:dsh_app/ui/plugins/native/native_plugin_section.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart' show Consumer;
import 'package:flutter_test/flutter_test.dart';

import '../../chat/support/harness.dart';
import 'support.dart';

Map<String, Object?> _plugin({
  List<Map<String, Object?>> tools = const [],
  List<Map<String, Object?>> actions = const [],
  List<Map<String, Object?>> notes = const [],
  List<Map<String, Object?>> panels = const [],
}) {
  final row = plugin('p', title: '工具箱', panels: panels);
  row['contributes'] = <String, Object?>{
    ...(row['contributes']! as Map<String, Object?>),
    'composer.tools': [
      for (final t in tools) {'ready': true, ...t},
    ],
    'session.actions': [
      for (final a in actions) {'ready': true, ...a},
    ],
    'turn.note': notes,
  };
  return row;
}

NativePlugin _shape(Map<String, Object?> row) => nativeContributionsFrom(contributions([row])).plugin('p')!;

void main() {
  setUpAll(loadTestFonts);

  group('the model', () {
    test('an older bridge sends none of it, and nothing is declared', () {
      final old = _shape(plugin('p', title: 'x'));
      expect(old.composerTools, isEmpty);
      expect(old.sessionActions, isEmpty);
      expect(old.turnNotes, isEmpty);
      expect(old.declared, isEmpty);
    });

    test('caps ask for the new places, the first four stay', () {
      expect(DshGoSurfaces.all, containsAll(DshGoSurfaces.v1));
      expect(DshGoSurfaces.all, containsAll(['composer.tools', 'session.actions', 'turn.note', 'plugin']));
    });

    test('labels are cut, unready ones and turn notes without a registration are left out', () {
      final p = _shape(
        _plugin(
          tools: [
            {'id': 'a', 'label': '一二三四五六七八九'},
            {'id': 'b', 'label': 'B', 'ready': false},
          ],
          actions: [
            {'id': 'c', 'label': '导出'},
          ],
          notes: [
            {'id': 'on', 'ready': true},
            {'id': 'off', 'ready': false},
          ],
        ),
      );
      expect(p.composerTools.single.label, '一二三四五六七八');
      expect(p.composerTools.single.surface, NativeSurface.composerTools);
      expect(p.sessionActions.single.surface, NativeSurface.sessionActions);
      expect(p.turnNotes, ['on']);
      expect(p.declared, {NativeSurface.composerTools, NativeSurface.sessionActions, NativeSurface.turnNote});
    });

    test('a panel keeps its presentation and the plugin placement', () {
      final p = _shape(
        _plugin(
          panels: [
            {
              'id': 's',
              'title': 'S',
              'presentation': 'sheet',
              'placement': ['plugin'],
            },
            {'id': 'd', 'title': 'D'},
          ],
        ),
      );
      expect(p.panels.first.sheet, isTrue);
      expect(p.panels.first.placement, {'plugin'});
      expect(p.panels.last.sheet, isFalse);
    });
  });

  group('the ＋ panel', () {
    Future<PlusChoice?> open(WidgetTester tester, Map<String, Object?> row, {Finder? tap}) async {
      usePhoneView(tester);
      PlusChoice? choice;
      await tester.pumpWidget(
        testApp(
          Builder(
            builder: (context) => Scaffold(
              body: TextButton(onPressed: () async => choice = await showPlusPanel(context), child: const Text('打开')),
            ),
          ),
          overrides: nativeOverrides(FakeBridge(contributions([row]))),
        ),
      );
      await tester.pump();
      await tester.pump();
      await tester.tap(find.text('打开'));
      await tester.pumpAndSettle();
      if (tap != null) {
        await tester.tap(tap);
        await tester.pumpAndSettle();
      }
      return choice;
    }

    testWidgets('without tools it has no extra section', (tester) async {
      await open(tester, _plugin());
      expect(find.byKey(const ValueKey('plus-plugin-tools')), findsNothing);
    });

    testWidgets('a tool sits on the tiles\' grid, and tapping closes with it', (tester) async {
      final row = _plugin(
        tools: [
          {'id': 'stickers', 'label': '贴纸', 'icon': 'image'},
        ],
      );
      await open(tester, row);
      final key = find.byKey(const ValueKey('plus-tool|p|stickers'));
      final gallery = tester.getSize(find.widgetWithText(PlusTile, '相册'));
      expect(tester.getSize(key).width, moreOrLessEquals(gallery.width, epsilon: 40));
      await tester.tap(key);
      await tester.pumpAndSettle();
      expect(key, findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('a full panel keeps to its limit and overflows nothing', (tester) async {
      final row = _plugin(
        tools: [
          for (var i = 0; i < 12; i++) {'id': 't$i', 'label': '工具$i'},
        ],
      );
      final choice = await open(tester, row);
      expect(choice, isNull);
      expect(tester.takeException(), isNull);
      expect(find.byType(PlusTile), findsWidgets);
    });
  });

  group('a panel\'s presentation', () {
    testWidgets('a sheet comes up over the page, a page is pushed', (tester) async {
      usePhoneView(tester);
      const sheet = NativePanel(package: 'p', id: 's', title: '弹层', sheet: true);
      await tester.pumpWidget(
        testApp(
          Builder(
            builder: (context) => Scaffold(
              body: TextButton(onPressed: () => openNativePanel(context, sheet), child: const Text('打开')),
            ),
          ),
          overrides: nativeOverrides(FakeBridge(contributions([_plugin()]))),
        ),
      );
      await tester.tap(find.text('打开'));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('native-panel-sheet|p|s')), findsOneWidget);
      expect(find.byType(NativePanelPage), findsNothing);
    });
  });

  group('the plugin\'s own page', () {
    testWidgets('shows what asked for it, and nothing when switched off', (tester) async {
      usePhoneView(tester);
      final row = _plugin(
        panels: [
          {
            'id': 'about',
            'title': '状态',
            'placement': ['plugin'],
          },
          {'id': 'other', 'title': '别处'},
        ],
      );
      await tester.pumpWidget(
        testApp(
          const Scaffold(
            body: SingleChildScrollView(child: NativePluginPanels(package: 'p')),
          ),
          overrides: nativeOverrides(FakeBridge(contributions([row]))),
        ),
      );
      await tester.pump();
      await tester.pump();
      expect(find.byKey(const ValueKey('plugin-page-panel|p|about')), findsOneWidget);
      expect(find.byKey(const ValueKey('plugin-page-panel|p|other')), findsNothing);
    });
  });

  group('a turn\'s note', () {
    TurnTrace trace() => TurnTrace(
      turn: 3,
      startSeq: 1,
      start: DateTime(2026, 11, 5, 9),
      end: DateTime(2026, 11, 5, 9, 1),
      spans: const [],
    );

    test('is the first thing to go', () {
      final variants = TurnFootnote.variants(trace(), note: '¥0.03');
      expect(variants.first, contains('¥0.03'));
      expect(variants.skip(1).any((v) => v.contains('¥0.03')), isFalse);
      expect(TurnFootnote.variants(trace()), variants.skip(1).toList());
    });

    Widget note() => Consumer(builder: (context, ref, _) => Text(ref.watch(nativeTurnNoteProvider(('s1', 3))) ?? '没有'));

    testWidgets('a conversation with no such plugin has none, and subscribes to nothing', (tester) async {
      final bridge = FakeBridge(contributions([_plugin()]));
      await tester.pumpWidget(testApp(note(), overrides: nativeOverrides(bridge)));
      await pumpFrames(tester);
      expect(find.text('没有'), findsOneWidget);
      expect(bridge.sockets.expand((s) => s.sent).where((m) => m['t'] == 'sub'), isEmpty);
    });

    testWidgets('a plugin\'s push reaches its turn, and only that turn', (tester) async {
      final bridge = FakeBridge(
        contributions([
          _plugin(
            notes: [
              {'id': 'cost', 'ready': true},
            ],
          ),
        ]),
      );
      await tester.pumpWidget(testApp(note(), overrides: nativeOverrides(bridge)));
      await pumpFrames(tester);
      final sub = bridge.socket.sent.firstWhere((m) => m['t'] == 'sub');
      expect(sub['id'], 'cost');
      expect(sub['scope'], {'sessionId': 's1'});
      bridge.socket.push({
        't': 'data',
        'sub': sub['sub'],
        'rev': '1',
        'data': {
          '2': {'text': '别的回合'},
          '3': {'text': '¥0.03', 'tone': 'muted'},
        },
      });
      await pumpFrames(tester);
      expect(find.text('¥0.03'), findsOneWidget);
    });
  });
}
