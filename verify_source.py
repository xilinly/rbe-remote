"""Check the prototype constants against the captured TV data in ../rbe902vc_capture.

Every value the app hard codes must come from a TV file, so this script fails when the two drift
apart. Run it after editing MainActivity.java:  python verify_source.py
"""

import re
from pathlib import Path

root = Path(__file__).resolve().parent
src = root / "app/src/main/java/org/local/rbe902vc/MainActivity.java"
capture = root.parent / "rbe902vc_capture"

source = src.read_text(encoding="utf-8")
report = (capture / "tv_20261110_201639.txt").read_text(encoding="utf-8")
layout = (capture / "CH_BT_RC.kl").read_text(encoding="utf-8")
autopair = (capture / "rtkbt_autopair_config.xml").read_text(encoding="utf-8")
vhid = (capture / "rtkbt_plugin_vhid.conf").read_text(encoding="utf-8")

checked = []

# 1. HID report map copied from the TV's pairing record must match byte for byte.
hid = re.search(r'Tag="HidDescriptor" Type="binary">([0-9a-f]+)<', report)
constant = re.search(r'REPORT_MAP = hex\((.*?)\);', source, re.S)
if not hid or not constant:
    raise ValueError("HID descriptor not found")
actual = "".join(re.findall(r'"([0-9a-f]+)"', constant.group(1)))
if actual != hid.group(1):
    raise ValueError(f"HID report map mismatch: source {len(actual)//2} bytes, TV {len(hid.group(1))//2} bytes")
checked.append(f"HID report map matches TV ({len(actual)//2} bytes)")

# 2. Key mappings: measured usages plus the settings key inferred from CH_BT_RC.kl.
mapping = {
    (188, "DPAD_UP"): "UP", (189, "DPAD_DOWN"): "DOWN",
    (190, "DPAD_LEFT"): "LEFT", (191, "DPAD_RIGHT"): "RIGHT",
    (198, "DPAD_CENTER"): "OK", (199, "BACK"): "BACK",
    (220, "VOLUME_UP"): "VOL_UP", (221, "VOLUME_DOWN"): "VOL_DOWN",
    (178, "CHANGHONGIR_SETTINGS"): "SETTINGS_KEY",
}
for (code, name), constant_name in mapping.items():
    if not re.search(rf'^key\s+{code}\s+{name}\b', layout, re.M):
        raise ValueError(f"TV key layout differs: {code} -> {name}")
    if not re.search(rf'{constant_name} = 0x{code:02X}\b', source):
        raise ValueError(f"source constant {constant_name} is not 0x{code:02X} (TV scancode {code})")
checked.append(f"{len(mapping)} key usages match CH_BT_RC.kl (178 = CHANGHONGIR_SETTINGS)")

# 3. PnP ID must be the pair the TV firmware has a vhid report map for.
if "ReportMap_Vid_0416_Pid_0300" not in vhid:
    raise ValueError("TV has no vhid report map for VID 0416 PID 0300")
if not re.search(r'PNP_ID,\s*new byte\[\]\{2, 0x16, 0x04, 0, 0x03, 0x11, 0x01\}', source):
    raise ValueError("source PnP ID is not VID 0x0416 PID 0x0300 version 0x0111")
checked.append("PnP ID 0x0416:0x0300 matches the TV's vhid plugin entry")

# 4. Realtek autopair manufacturer data must be the advert prefix from the TV config.
spec = re.search(r'<specidata_value>([0-9A-Fa-f:]+)</specidata_value>', autopair)
if not spec:
    raise ValueError("autopair manufacturer data not found")
spec_bytes = bytes(int(part, 16) for part in spec.group(1).split(":"))
company = int.from_bytes(spec_bytes[:2], "little")
payload = spec_bytes[2:].hex()
if not re.search(rf'RTK_COMPANY_ID = 0x{company:04X}\b', source):
    raise ValueError(f"source RTK company id is not 0x{company:04X}")
if not re.search(rf'RTK_COMPANY_DATA = hex\("{payload}"\)', source):
    raise ValueError(f"source RTK manufacturer payload is not {payload}")
checked.append(f"autopair advert prefix matches TV config (company 0x{company:04X}, data {payload})")

# 5. The TV's voice dongle service writes reports 0xFB (1 byte) and 0xFA (20 bytes) to the remote.
#    Without those output report characteristics the host logs "bta_hh_le_write_rpt: no matching
#    report" and closes the HID link a couple of seconds after opening it (measured 22:11:13).
for rid, size in ((0xFB, 1), (0xFA, 20)):
    if not re.search(rf'addOutputReport\(hid, 0x{rid:02X}, {size}\);', source):
        raise ValueError(f"missing output report 0x{rid:02X} of {size} bytes")
checked.append("output reports 0xFB(1B) and 0xFA(20B) present, as the TV's dongle service writes them")

# 6. Report 1 travels as 8 bytes of payload (modifier, reserved, six key slots) with no report id
#    inside the value: the TV's hidraw reader prints "report id + payload" (read len = 9 for the real
#    remote). Report 3 (Consumer, power) travels as 2 bytes, also without a report id — sending
#    3 bytes made the TV parse usage 0x3003 and ignore the power key.
if not re.search(r'byte\[\] pressed = new byte\[8\];', source):
    raise ValueError("keyboard report payload is not 8 bytes")
if not re.search(r'pressed\[2 \+ i\]', source):
    raise ValueError("key array does not start at offset 2 of the 8-byte report")
if not re.search(r'byte\[\] pressed = new byte\[2\];\s*\n\s*pressed\[0\] = \(byte\) \(usage', source):
    raise ValueError("consumer report payload is not a 2-byte usage")
checked.append("report 1 = 8-byte payload (keys at offset 2), report 3 = 2-byte usage, report id only in the descriptor")

# 7. 换源 is scancode 183 "SOURCE" in CH_BT_RC.kl, measured on 2026-11-10 23:00: usage 0xB7 arrives as
#    keycode 2001 and pops the TV's 节目源 panel (HDMI1/HDMI2/AV输入/数字电视/模拟电视). 213
#    CHANGHONGIR_INPUT (0xD5 -> keycode 4102) only reaches the app that owns the screen, so it stays
#    as the secondary 输入源 key.
if not re.search(r'SOURCE_KEY = 0xB7, INPUT_KEY = 0xD5', source):
    raise ValueError("source key must be 0xB7, with 0xD5 kept as the secondary input key")
if 'key 183   SOURCE' not in layout:
    raise ValueError("CH_BT_RC.kl no longer maps scancode 183 to SOURCE")
checked.append("换源 key 0xB7 = scancode 183 (SOURCE) opens the TV's 节目源 panel")

for line in checked:
    print("ok:", line)
