"""从电视导出的 bt_config.xml 里删掉我们手机留下的僵尸绑定，保留实体遥控器。

我们手机的特征：Name 是 "CH BT RC"（克隆名）或 "RBE902VC"（电源档名）；
实体遥控器是 00:e0:29:ec:f2:32，必须保留。
"""
import sys
import xml.etree.ElementTree as ET

src, dst = sys.argv[1], sys.argv[2]
KEEP = {"00:e0:29:ec:f2:32"}
PHONE_NAMES = {"CH BT RC", "RBE902VC"}

tree = ET.parse(src)
root = tree.getroot()


def is_device(node):
    tag = node.get("Tag") or ""
    return tag.count(":") == 5 and len(tag) == 17


def name_of(node):
    for child in node:
        if child.get("Tag") == "Name":
            return (child.text or "").strip()
    return ""


removed = []
for parent in root.iter():
    for child in list(parent):
        if not is_device(child):
            continue
        mac = child.get("Tag").lower()
        if mac in KEEP:
            continue
        if name_of(child) in PHONE_NAMES:
            parent.remove(child)
            removed.append(mac)

tree.write(dst, encoding="utf-8", xml_declaration=False)

# 复核
check = ET.parse(dst)
left = []
for parent in check.getroot().iter():
    for child in list(parent):
        if not is_device(child):
            continue
        left.append((child.get("Tag").lower(), name_of(child)))
print(f"removed {len(removed)} stale phone bonds")
print(f"remaining device entries: {len(left)}")
kept = [x for x in left if x[1] in PHONE_NAMES]
print(f"remaining entries with our names: {len(kept)} -> {kept}")
print("real remote still present:", any(m == "00:e0:29:ec:f2:32" for m, _ in left))
