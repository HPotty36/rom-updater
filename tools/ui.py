"""Test helper: list the on-screen texts of the connected phone, or tap the first one containing argv[1]."""
import re
import subprocess
import sys


def dump():
    subprocess.run(["adb", "shell", "uiautomator dump /sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "shell", "cat /sdcard/ui.xml"], capture_output=True).stdout.decode("utf-8", "replace")
    return re.findall(r'text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)


sys.stdout.reconfigure(encoding="utf-8")
nodes = dump()
if len(sys.argv) > 1:
    want = sys.argv[1]
    for text, a, b, c, d in sorted(nodes, key=lambda n: n[0] != want):
        if want in text:
            subprocess.run(["adb", "shell", "input", "tap", str((int(a) + int(c)) // 2), str((int(b) + int(d)) // 2)])
            print("tapped")
            break
    else:
        print("not found")
else:
    print(" | ".join(t for t, *_ in nodes))
