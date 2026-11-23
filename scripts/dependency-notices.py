# /// script
# requires-python = ">=3.14"
# dependencies = []
# ///
"""Record resolved Android and Rust dependency licenses and notice texts."""
from pathlib import Path
import json
import os
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "local/dependency-notices.txt"
ENV = os.environ.copy()
ENV.setdefault("ANDROID_HOME", ENV.get("ANDROID_SDK_ROOT") or str(
    Path.home() / ("Library/Android/sdk" if os.uname().sysname == "Darwin" else "Android/Sdk")
))

android = subprocess.check_output(
    [str(ROOT / "android/gradlew"), "-p", str(ROOT / "android"),
     ":app:runtimeLicenseInventory", "-q"], text=True, env=ENV,
)
rust = json.loads(subprocess.check_output(
    ["cargo", "metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android"],
    cwd=ROOT / "renderer", text=True,
))
packages = {package["id"]: package for package in rust["packages"]}
nodes = {node["id"]: node["deps"] for node in rust["resolve"]["nodes"]}
seen = set()
pending = [rust["resolve"]["root"]]
while pending:
    node = pending.pop()
    if node in seen:
        continue
    seen.add(node)
    pending.extend(dep["pkg"] for dep in nodes[node] if any(
        kind["kind"] != "dev" for kind in dep["dep_kinds"]
    ))

lines = ["Android release runtime dependencies", ""]
for line in android.splitlines():
    if "\t" not in line:
        continue
    coords, pom = line.split("\t", maxsplit=1)
    licenses = []
    if pom:
        root = ET.parse(pom).getroot()
        for license in root.findall("{*}licenses/{*}license"):
            name = license.findtext("{*}name") or "License"
            url = license.findtext("{*}url")
            licenses.append(f"{name} ({url})" if url else name)
    lines.append(f"{coords}  {'; '.join(licenses) or 'See upstream POM / artifact notices'}")

lines += ["", "Rust Android dependencies and build dependencies", ""]
appendix = []
for pkg in sorted((packages[node] for node in seen), key=lambda item: item["name"]):
    label = f"{pkg['name']} {pkg['version']}"
    lines.append(f"{label}  {pkg.get('license') or pkg.get('license_file') or 'See upstream'}")
    directory = Path(pkg["manifest_path"]).parent
    if directory.is_relative_to(ROOT):
        continue
    notices = sorted({path for pattern in ("LICENSE*", "COPYING*", "NOTICE*")
                      for path in directory.glob(pattern) if path.is_file()})
    for path in notices:
        appendix += ["", f"{label}  {path.name}", path.read_text(errors="replace")]

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text("\n".join(lines + appendix) + "\n")
print(OUTPUT)
