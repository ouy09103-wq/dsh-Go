#!/usr/bin/env python3
"""Append the mcp* L10n methods to the checked-in gen files (flutter SDK unavailable here).

The real `flutter gen-l10n` remains authoritative: CI or a Flutter-capable machine must
rerun tool/l10n_merge.py, which regenerates lib/l10n/gen and makes this script obsolete.
This produces the same output flutter gen-l10n 11.x writes for these keys, matching the
checked-in style (intl.Intl.pluralLogic for plural, plain getters otherwise).
"""
import json
import re
import sys
from pathlib import Path

APP = Path(__file__).resolve().parent.parent
GEN = APP / "lib/l10n/gen"
ZHB = APP / "lib/l10n/app_zh.arb"
ENB = APP / "lib/l10n/app_en.arb"

zh = json.loads(ZHB.read_text())
en = json.loads(ENB.read_text())
keys = [k for k in zh if (k.startswith("mcp") or k == "searchKwMcp") and not k.startswith("@")]
# gen-l10n emits methods in template-ARB order, not alphabetical — CI diffs against that.



def dart_str(s: str) -> str:
    # $ before a letter/brace is an already-built interpolation; other $ get escaped
    out = s.replace("\\", "\\\\").replace("'", r"\'")
    out = re.sub(r"\$(?![A-Za-z_{])", r"\\$", out)
    return f"'{out}'"


def placeholder_expr(name: str, typ: str) -> str:
    if typ == "int":
        return f"${name}"
    return f"${name}"


def interpolation(text: str, placeholders: dict) -> str:
    """Convert '{name}' occurrences to '$name' or '${expr}', escaping the rest."""
    exprs = {n: placeholder_expr(n, m.get("type", "String")) for n, m in placeholders.items()}
    # longest names first so prefixes don't clobber
    for n in sorted(exprs, key=len, reverse=True):
        text = text.replace("{" + n + "}", exprs[n])
    # inside plural case bodies, placeholders already became $name; escape lone $ for Dart literals
    text = re.sub(r"\$(?![A-Za-z_{])", r"\\$", text)
    return dart_str(text)


def abstract_entry(key: str) -> str:
    msg = zh[key]
    meta = zh.get("@" + key, {})
    ph = meta.get("placeholders", {})
    if "plural" in ph:
        args = [n for n, m in ph.items() if m.get("type") == "int"]
        arg = args[0] if args else "count"
        return (
            f"  /// No description provided for @{key}.\n"
            f"  ///\n"
            f"  /// In zh, this message translates to:\n"
            f"  /// **{dart_str(msg)}**\n"
            f"  String {key}(int {arg});\n\n"
        )
    # also detect plural without explicit @ placeholders (pattern in the message itself)
    if re.search(r"\{\w+, plural,", msg):
        m2 = re.search(r"\{(\w+), plural,", msg)
        arg = m2.group(1) if m2 else "count"
        return (
            f"  /// No description provided for @{key}.\n"
            f"  ///\n"
            f"  /// In zh, this message translates to:\n"
            f"  /// **{dart_str(msg)}**\n"
            f"  String {key}(int {arg});\n\n"
        )
    if ph:
        # one simple placeholder -> a method; none -> a getter (gen-l10n behavior)
        args = [n for n, m in ph.items() if m.get("type") == "int"]
        arg = args[0] if args else next(iter(ph))
        sig_args = ", ".join(
            f"{'int' if m.get('type') == 'int' else 'String'} {n}" for n, m in ph.items()
        )
        return (
            f"  /// No description provided for @{key}.\n"
            f"  ///\n"
            f"  /// In zh, this message translates to:\n"
            f"  /// **{dart_str(msg)}**\n"
            f"  String {key}({sig_args});\n\n"
        )
    return (
        f"  /// No description provided for @{key}.\n"
        f"  ///\n"
        f"  /// In zh, this message translates to:\n"
        f"  /// **{dart_str(msg)}**\n"
        f"  String get {key};\n\n"
    )


def impl_entry(key: str, locale: str, locale_name: str) -> str:
    msg = (zh if locale == "zh" else en)[key]
    # @ metadata lives only in the template (app_zh.arb); gen-l10n reads it from there
    ph = zh.get("@" + key, {}).get("placeholders", {})
    if "plural" in ph or re.search(r"\{\w+, plural,", msg):
        if "plural" not in ph:
            m2 = re.search(r"\{(\w+), plural,", msg)
            ph = {(m2.group(1) if m2 else "count"): {"type": "int"}}
        return plural_impl(key, msg, ph, locale)
    if ph:
        return simple_impl(key, msg, ph, locale)
    return f"  @override\n  String get {key} => {interpolation(msg, ph)};\n\n"


def simple_impl(key: str, msg: str, ph: dict, locale: str) -> str:
    sig_args = ", ".join(
        f"{'int' if m.get('type') == 'int' else 'String'} {n}" for n, m in ph.items()
    )
    body = interpolation(msg, ph)
    return f"  @override\n  String {key}({sig_args}) {{\n    return {body};\n  }}\n\n"


def plural_impl(key: str, msg: str, ph: dict, locale: str) -> str:
    args = [n for n, m in ph.items() if m.get("type") == "int"]
    arg = args[0] if args else "count"
    # parse the ICU plural: =N{...} and other{...}; zero/one/other named cases
    cases = {}
    body = msg
    # named categories and exact matches
    for m in re.finditer(r"(=(\d+)|zero|one|two|few|many|other)\{", body):
        start = m.end() - 1
        depth = 0
        for i in range(start, len(body)):
            if body[i] == "{":
                depth += 1
            elif body[i] == "}":
                depth -= 1
                if depth == 0:
                    break
        inner = body[start + 1 : i]
        cat = m.group(1)
        cases[cat] = inner
    # exact matches (=0/=1/...) become explicit if-branches; named cases go to pluralLogic
    exact = {int(c[1:]): interpolation(inner, ph) for c, inner in cases.items() if c.startswith("=")}
    lines = ["  @override", f"  String {key}(int {arg}) {{"]
    indent = "    "
    for n, expr in sorted(exact.items()):
        lines.append(f"{indent}if ({arg} == {n}) {{")
        lines.append(f"{indent}  return {expr};")
        lines.append(f"{indent}}}")
    plural_args = [f"{c}: {interpolation(i, ph)}" for c, i in cases.items() if not c.startswith("=")]
    if len(exact) == len(cases):
        other = cases.get("other")
        if other is not None:
            lines.append(f"{indent}return {interpolation(other, ph)};")
        else:
            lines.append(f"{indent}throw StateError('unimplemented plural case');")
    else:
        lines.append(f"{indent}String _temp0 = intl.Intl.pluralLogic({arg}, locale: localeName, {', '.join(plural_args)});")
        lines.append(f"{indent}return '$_temp0';")
    lines.append("  }")
    lines.append("")
    return "\n".join(lines) + "\n"
    return f"  @override\n  String get {key} => {interpolation(msg, ph)};\n\n"



def strip_existing(path: Path, is_abstract: bool) -> None:
    """Remove mcp/searchKwMcp entries already in the file (idempotency for re-runs)."""
    text = path.read_text()
    if is_abstract:
        pat = re.compile(r"  /// No description provided for @(?:mcp\w+|searchKwMcp)\.(?:[\s\S]*?)(?=\n  /// No description provided for @|\n\}\n|\nclass )")
        text = pat.sub("", text)
    else:
        pat = re.compile(r"  @override\n  String (?:get )?(?:mcp\w+|searchKwMcp)[\s\S]*?;\n")
        text = pat.sub("", text)
    path.write_text(text)

def main() -> None:
    base = GEN / "app_localizations.dart"
    zh_f = GEN / "app_localizations_zh.dart"
    en_f = GEN / "app_localizations_en.dart"
    strip_existing(base, True)
    strip_existing(zh_f, False)
    strip_existing(en_f, False)

    abstract = "\n".join(abstract_entry(k) for k in keys)
    zh_impl = "\n".join(impl_entry(k, "zh", "zh") for k in keys)
    en_impl = "\n".join(impl_entry(k, "en", "en") for k in keys)

    insert_before_abstract(base, abstract)
    append_impl(zh_f, zh_impl)
    append_impl(en_f, en_impl)
    print(f"✓ appended {len(keys)} mcp keys to gen files")


def insert_before_abstract(path: Path, block: str) -> None:
    # gen-l10n emits keys in template-ARB order, interspersed with the other keys.
    # My keys form two runs in the ARB: mcp* before `mediaGenerateTile`, and
    # searchKwMcp before `searchKwGeneral`. Insert each run before its successor's
    # entry so a regenerated file matches byte-for-byte.
    text = path.read_text()
    zh_all = [k for k in zh if not k.startswith("@")]
    runs: list[tuple[list[str], str | None]] = []
    cur: list[str] = []
    for k in zh_all:
        if (k.startswith("mcp") or k == "searchKwMcp") and k in set(keys):
            cur.append(k)
        else:
            if cur:
                runs.append((cur, k))
                cur = []
    if cur:
        runs.append((cur, None))

    blocks_by_key = {}
    # split the pre-rendered block back into per-key chunks on the doc marker
    chunks = re.split(r"(?=  /// No description provided for @)", block)
    for c in chunks:
        m = re.search(r"String (?:get )?(\w+)", c)
        if m:
            blocks_by_key[m.group(1)] = c

    for run, successor in runs:
        run_block = "".join(blocks_by_key[k] for k in run)
        if successor is None:
            # last run: goes before the class close (rare; not hit today)
            anchor = "class _L10nDelegate extends LocalizationsDelegate<L10n> {"
            at = text.index(anchor)
            close = text.rindex("}\n", 0, at)
            text = text[:close] + "\n" + run_block + "\n" + text[close:]
            continue
        if successor == "searchKwGeneral":
            # search part: find the existing searchKwWebSearchKey/searchKwWebSearch getter
            m = re.search(r"  String get searchKwWebSearch;[^\n]*\n", text)
            if not m:
                raise AssertionError("anchor searchKwWebSearch not found")
            text = text[: m.end()] + run_block + text[m.end() :]
        else:
            # engine part: insert before `mediaGenerateTile`'s doc comment
            m = re.search(r"  /// No description provided for @mediaGenerateTile\.", text)
            if not m:
                raise AssertionError("anchor mediaGenerateTile not found")
            text = text[: m.start()] + run_block + text[m.start() :]
    path.write_text(text)


def append_impl(path: Path, block: str) -> None:
    # Same interleaving as the abstract class: insert each run before its
    # successor's implementation. Impl entries end with a closing brace line.
    text = path.read_text()
    zh_all = [k for k in zh if not k.startswith("@")]
    runs: list[tuple[list[str], str | None]] = []
    cur: list[str] = []
    for k in zh_all:
        if (k.startswith("mcp") or k == "searchKwMcp") and k in set(keys):
            cur.append(k)
        else:
            if cur:
                runs.append((cur, k))
                cur = []
    if cur:
        runs.append((cur, None))

    chunks = re.split(r"(?=  @override\n)", block)
    blocks_by_key = {}
    for c in chunks:
        m = re.search(r"String (?:get )?(\w+)", c)
        if m:
            blocks_by_key[m.group(1)] = c

    for run, successor in runs:
        run_block = "".join(blocks_by_key[k] for k in run)
        if successor == "searchKwGeneral":
            m = re.search(r"  String get searchKwWebSearch =>[^;]*;\n", text)
            if not m:
                raise AssertionError("anchor searchKwWebSearch impl not found")
            text = text[: m.end()] + run_block + text[m.end() :]
        else:
            m = re.search(r"  String get mediaGenerateTile =>[^;]*;\n", text)
            if not m:
                raise AssertionError("anchor mediaGenerateTile impl not found")
            text = text[: m.end()] + run_block + text[m.end() :]
    path.write_text(text)


if __name__ == "__main__":
    sys.exit(main())
