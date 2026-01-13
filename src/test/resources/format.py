#!/usr/bin/env python3
"""
格式化同目录下的 1.xml 和 2.xml：重排每个开始标签的属性（按属性名字母序）。
用法: 直接运行脚本即可，会备份原文件为 <name>.bak 并覆盖原文件。
"""

import re
from pathlib import Path

# 要处理的文件名列表（与脚本同目录）
FILES = ["2.xml"]

# 匹配开始标签（排除注释、CDATA、处理指令和DOCTYPE）
TAG_RE = re.compile(r'<\s*(?P<tag>[A-Za-z_:][^\s/>]*)\s*(?P<attrs>[^<>]*?)\s*(?P<self>/?)>')
# 匹配属性键值对（支持单/双引号）
ATTR_RE = re.compile(r'(?P<key>[A-Za-z_:][^=\s]*)\s*=\s*(?P<quote>["\'])(?P<val>.*?)(?P=quote)', re.DOTALL)


def reorder_attrs_in_tag(match: re.Match) -> str:
    tag = match.group('tag')
    attrs = match.group('attrs') or ''
    selfclose = match.group('self') or ''

    # 跳过特殊标签（例如 <!DOCTYPE ...>, <?xml ...?>, <!-- ... --> 等）
    if tag.startswith('!') or tag.startswith('?') or tag == '--':
        return match.group(0)

    # 找出所有属性
    found = list(ATTR_RE.finditer(attrs))
    if not found:
        # 没有属性，保持原样
        return f"<{tag}{(' /' if selfclose else '')}>" if not attrs.strip() else match.group(0)

    # 提取属性并按 key 排序
    attrs_kv = []
    for m in found:
        key = m.group('key')
        quote = m.group('quote')
        val = m.group('val')
        attrs_kv.append((key, quote, val))

    attrs_kv.sort(key=lambda x: x[0])

    # 重新构造属性字符串，使用双引号统一输出（保留原始引号会复杂且价值不大）
    new_attrs = ' '.join(f'{k}="{v}"' for k, q, v in attrs_kv)

    # 保持自闭合斜杠位置
    if selfclose:
        return f"<{tag} {new_attrs} / >".replace('/ >', '/>')
    else:
        return f"<{tag} {new_attrs}>"


def process_file(path: Path) -> None:
    text = path.read_text(encoding='utf-8')

    # 在替换时跳过注释、CDATA 和处理指令：我们在回调中已对 tag 做检查
    new_text = TAG_RE.sub(reorder_attrs_in_tag, text)

    # 备份原文件并写入新内容
    bak = path.with_suffix(path.suffix + '.bak')
    bak.write_text(text, encoding='utf-8')
    path.write_text(new_text, encoding='utf-8')
    print(f'Processed {path} -> backup: {bak.name}')


if __name__ == '__main__':
    base = Path(__file__).parent
    for fname in FILES:
        p = base / fname
        if p.exists():
            process_file(p)
        else:
            print(f'Skipped missing file: {p.name}')
