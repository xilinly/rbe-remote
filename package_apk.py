"""Add D8 output to AAPT2's resource-only APK without changing either input."""

import shutil
import sys
import zipfile
from pathlib import Path

project = Path(__file__).resolve().parent
root = Path(sys.argv[1]) if len(sys.argv) > 1 else project / "build_manual"
base = root / "base.apk"
dex = root / "dex/classes.dex"
output = root / "unsigned.apk"
if output.exists():
    raise FileExistsError(output)
with zipfile.ZipFile(base) as archive:
    if "AndroidManifest.xml" not in archive.namelist():
        raise ValueError("AAPT2 APK has no manifest")
if not dex.is_file():
    raise FileNotFoundError(dex)
shutil.copyfile(base, output)
with zipfile.ZipFile(output, "a") as archive:
    archive.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
with zipfile.ZipFile(output) as archive:
    if archive.testzip() is not None:
        raise ValueError("APK ZIP integrity test failed")
    print("APK members:", archive.namelist())
