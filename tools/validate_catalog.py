#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""校验 catalog/ 下的全部 JSON 与内部一致性。

用法：  python tools/validate_catalog.py [仓库根目录]

为什么需要它：JSON 不支持注释，而本清单满是中文说明，**中文引号误用成 ASCII 双引号**会
静默产生非法 JSON。PowerShell 5.1 的 ConvertFrom-Json 报错信息很差（只给偏移量），
所以用 Python 的严格解析器，并额外做四类一致性检查。

退出码：0 = 全部通过；1 = 有问题。
"""

from __future__ import annotations

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / "catalog" / "data"
SCHEMA = ROOT / "catalog" / "schema"

REQUIRED = ("id", "kind", "carrier", "sourceMod", "reach", "evidence")
KINDS = {"atomic", "family"}
REACHES = {"RA", "RB", "RC"}
EVIDENCE_LEVELS = {"verified", "inferred", "unverified", "doc_mismatch"}
EFFECT_TYPES = {
    "DAMAGE", "HEAL", "DISPLACE", "SUMMON", "DESPAWN", "APPLY_EFFECT",
    "REMOVE_EFFECT", "SET_AI_STATE", "TELEPORT", "TERRAIN", "OBSERVABLE_ONLY",
}


def check_syntax(path: pathlib.Path) -> tuple[bool, str, object]:
    """严格解析；错误时给出准确行列（比 PS 5.1 的偏移量好用得多）。"""
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        return False, f"非 UTF-8 编码：{exc}", None
    try:
        return True, "", json.loads(text)
    except json.JSONDecodeError as exc:
        snippet = exc.doc[max(0, exc.pos - 60) : exc.pos + 60].replace("\n", "\\n")
        return False, f"第 {exc.lineno} 行第 {exc.colno} 列：{exc.msg}\n      上下文: …{snippet}…", None


def check_consistency(name: str, doc: dict) -> list[str]:
    problems: list[str] = []
    ops = doc.get("operations")
    if not isinstance(ops, list):
        return [f"{name}: 缺 operations 数组"]
    if doc.get("formatVersion") != 1:
        problems.append(f"{name}: formatVersion 应为 1，实为 {doc.get('formatVersion')!r}")

    ids = [o.get("id") for o in ops]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        problems.append(f"{name}: 重复 id {sorted(dupes)}")

    for o in ops:
        oid = o.get("id", "<无 id>")
        for field in REQUIRED:
            if not o.get(field):
                problems.append(f"{oid}: 缺必填字段 {field}")
        if o.get("kind") not in KINDS:
            problems.append(f"{oid}: kind 非法 {o.get('kind')!r}")
        if o.get("reach") not in REACHES:
            problems.append(f"{oid}: reach 非法 {o.get('reach')!r}")
        if not o.get("id", "").count(":"):
            problems.append(f"{oid}: id 应形如 namespace:path")

        # 族必须有参数域
        if o.get("kind") == "family" and not o.get("params"):
            problems.append(f"{oid}: kind=family 但缺 params")

        # familyOf 必须能解析
        fo = o.get("familyOf")
        if fo and fo not in ids:
            problems.append(f"{oid}: familyOf 指向不存在的 {fo}")

        # 参数域至少要有 values / range / ref 之一
        for pname, dom in (o.get("params") or {}).items():
            if not isinstance(dom, dict) or not ({"values", "range", "ref"} & set(dom)):
                problems.append(f"{oid}.{pname}: 取值域缺 values/range/ref")

        # 证据等级与出处
        for ev in o.get("evidence") or []:
            lvl = ev.get("level")
            if lvl not in EVIDENCE_LEVELS:
                problems.append(f"{oid}: evidence.level 非法 {lvl!r}")
            if lvl == "verified" and not ev.get("cite"):
                problems.append(f"{oid}: verified 级证据必须给 cite")

        # effects 取值域
        for ef in o.get("effects") or []:
            if ef.get("type") not in EFFECT_TYPES:
                problems.append(f"{oid}: effects.type 非法 {ef.get('type')!r}")

        # duration.kind
        dur = o.get("duration") or {}
        if dur and dur.get("kind") not in (None, "INSTANT", "COMMIT", "CHANNEL"):
            problems.append(f"{oid}: duration.kind 非法 {dur.get('kind')!r}")

        # exclusivity
        for ex in o.get("exclusivity") or []:
            if not ex.get("group"):
                problems.append(f"{oid}: exclusivity 缺 group")

    return problems


def main() -> int:
    files = sorted(list(DATA.glob("*.json")) + list(SCHEMA.glob("*.json")))
    if not files:
        print(f"FAIL: 在 {ROOT} 下找不到任何 catalog JSON")
        return 1

    total_ops = 0
    all_problems: list[str] = []

    print("=== JSON 语法 ===")
    docs: dict[str, dict] = {}
    for path in files:
        ok, err, doc = check_syntax(path)
        if not ok:
            print(f"  FAIL {path.name}\n      {err}")
            all_problems.append(f"{path.name}: 语法错误")
            continue
        n = len(doc.get("operations", [])) if isinstance(doc, dict) else 0
        if n:
            total_ops += n
        print(f"  OK   {path.name:<26} {n if n else '(schema)'}")
        if n:
            docs[path.name] = doc

    print("\n=== 内部一致性 ===")
    for name, doc in docs.items():
        problems = check_consistency(name, doc)
        if problems:
            all_problems.extend(problems)
            for p in problems:
                print(f"  ** {p}")
        else:
            print(f"  OK   {name}")

    print(f"\n=== 小结 ===\n  数据文件 {len(docs)} 个，操作条目合计 {total_ops} 条，问题 {len(all_problems)} 个")
    if all_problems:
        print("\n有问题的条目请修好后再提交。提示：中文里的引号请用「」，不要用 ASCII 双引号 ——")
        print("JSON 不支持注释也不容忍未转义的引号，而这两类错误在只看 diff 时很难发现。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
