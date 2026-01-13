#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
XML Formatter: 格式化 XML 文件（含属性排序 + 缩进 + 原地覆盖）
支持备份、保留声明、UTF-8、安全处理。
警告：不支持 DTD / XInclude / 处理指令（PI）的深度保真，但会尽力保留。
"""

import sys
import os
import argparse
import xml.etree.ElementTree as ET
from xml.dom import minidom
from typing import Optional, List, Tuple

def sort_attributes(elem: ET.Element) -> None:
    """递归地对 elem 及其所有子元素的 attrib 字典按键排序（原地修改）"""
    if elem.attrib:
        # 按属性名字母序重排（稳定排序，不影响值）
        elem.attrib = dict(sorted(elem.attrib.items()))
    for child in elem:
        sort_attributes(child)

def prettify_element(
    elem: ET.Element,
    indent: str = "  ",
    newline: str = "\n",
    level: int = 0,
) -> str:
    """
    手动美化 Element（比 minidom 更可控，避免属性乱序 & 注释错位）
    注意：此函数不处理 tail/text 的缩进逻辑（由后续 minidom 补足），仅确保属性有序。
    实际使用：先 sort_attributes → 再用 minidom.toxml() → 最后用 minidom.parseString().toprettyxml()
    """
    pass  # 我们将采用「先排序 + minidom 重序列化」组合策略（更鲁棒）

def format_xml_file(
    filepath: str,
    backup: bool = True,
    encoding: str = "utf-8",
    add_bom: bool = False,
    indent_char: str = "  ",
    newlines: bool = True,
) -> bool:
    """
    格式化单个 XML 文件（原地写入），支持备份
    返回: True=成功，False=失败
    """
    if not os.path.isfile(filepath):
        print(f"❌ 错误：文件不存在 — {filepath}")
        return False

    # ✅ 步骤1：读取原始内容（保留 BOM & 声明）
    try:
        with open(filepath, "rb") as f:
            raw_data = f.read()
        # 检测并记录 BOM
        has_bom = raw_data.startswith(b"\xef\xbb\xbf")
        if has_bom and not add_bom:
            # 去掉 BOM 以便解析（ET 不关心 BOM，但需避免干扰）
            raw_text = raw_data[3:].decode(encoding)
        else:
            raw_text = raw_data.decode(encoding)
    except Exception as e:
        print(f"❌ 读取失败：{filepath} — {e}")
        return False

    # ✅ 步骤2：解析为 ElementTree（容忍部分不规范，但非严重错误）
    try:
        # 先尝试用 ET 解析（不丢失命名空间）
        parser = ET.XMLParser(strip_cdata=False)
        root = ET.fromstring(raw_text, parser)
        tree = ET.ElementTree(root)
    except ET.ParseError as e:
        print(f"❌ XML 解析失败：{filepath} — {e}")
        return False

    # ✅ 步骤3：递归排序所有元素的属性（核心需求！）
    sort_attributes(root)

    # ✅ 步骤4：转为字符串 → 用 minidom 二次美化（解决 ET.write 不支持缩进 & 属性顺序易乱的问题）
    try:
        rough_string = ET.tostring(root, encoding=encoding).decode(encoding)
        # 用 minidom 解析再美化（它能保证属性顺序！因为此时 attrib 已排序）
        dom = minidom.parseString(rough_string)
        # topyxml() 会添加换行和缩进；我们手动控制 indent
        pretty_str = dom.toprettyxml(indent=indent_char, encoding=encoding).decode(encoding)
        # ⚠️ minidom 会在第一行加空行，且可能多一个换行 → 清理
        lines = [line for line in pretty_str.splitlines() if line.strip() or not newlines]
        if lines and lines[0].strip() == "":
            lines = lines[1:]
        pretty_str = "\n".join(lines)
        if newlines and not pretty_str.endswith("\n"):
            pretty_str += "\n"
    except Exception as e:
        print(f"❌ 美化失败：{filepath} — {e}")
        return False

    # ✅ 步骤5：写回（带备份）
    if backup:
        backup_path = filepath + ".bak"
        try:
            if os.path.exists(backup_path):
                os.remove(backup_path)
            os.rename(filepath, backup_path)
            print(f"📦 已备份至：{backup_path}")
        except OSError as e:
            print(f"⚠️  备份失败（继续写入）：{e}")

    # ✅ 步骤6：写入格式化后的内容
    try:
        write_data = pretty_str
        if add_bom and not has_bom:
            write_data = "\ufeff" + write_data  # UTF-8 BOM
        with open(filepath, "w", encoding=encoding, newline="") as f:
            f.write(write_data)
        print(f"✅ 已格式化并写入：{filepath}")
        return True
    except Exception as e:
        print(f"❌ 写入失败：{filepath} — {e}")
        return False

def main():
    parser = argparse.ArgumentParser(
        description="🔧 XML 格式化工具（属性字母序 + 缩进 + 原地覆盖）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例用法：
  python xml_formatter.py config.xml                      # 格式化单个文件（自动备份）
  python xml_formatter.py *.xml --no-backup              # 批量格式化，不备份
  python xml_formatter.py data.xml -i "  " --bom         # 用两个空格缩进 + 添加 BOM
        """,
    )
    parser.add_argument("files", nargs="+", help="要格式化的 XML 文件路径（支持通配符，如 *.xml）")
    parser.add_argument("--no-backup", action="store_true", help="禁用自动备份（危险！）")
    parser.add_argument("-i", "--indent", default="  ", help="缩进字符（默认两个空格）")
    parser.add_argument("--bom", action="store_true", help="输出时添加 UTF-8 BOM")
    parser.add_argument("--encoding", default="utf-8", help="文件编码（默认 utf-8）")
    parser.add_argument("--no-newlines", action="store_true", help="禁用末尾空行")

    args = parser.parse_args()

    success_count = 0
    total_count = len(args.files)

    for pattern in args.files:
        import glob
        matched = glob.glob(pattern)
        if not matched:
            print(f"🔍 未匹配到文件：{pattern}")
            continue
        for fp in matched:
            if not fp.lower().endswith(".xml"):
                print(f"⚠️  跳过非 XML 文件：{fp}")
                continue
            if format_xml_file(
                filepath=fp,
                backup=not args.no_backup,
                encoding=args.encoding,
                add_bom=args.bom,
                indent_char=args.indent,
                newlines=not args.no_newlines,
            ):
                success_count += 1

    print(f"\n🎉 完成：{success_count}/{total_count} 个文件格式化成功。")
    if success_count < total_count:
        sys.exit(1)

if __name__ == "__main__":
    main()