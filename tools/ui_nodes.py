"""Print every text node with its centre coordinates from a uiautomator dump."""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ui3.xml"
text = open(path, encoding="utf-8", errors="replace").read()
nodes = re.findall(r"<node[^>]*>", text)
print("nodes:", len(nodes))
for node in nodes:
    label = re.search(r'text="([^"]*)"', node)
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not label or not label.group(1) or not bounds:
        continue
    x = (int(bounds.group(1)) + int(bounds.group(3))) // 2
    y = (int(bounds.group(2)) + int(bounds.group(4))) // 2
    print(f"{label.group(1)}\t{x}\t{y}")
