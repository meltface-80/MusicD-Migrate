#!/usr/bin/env python3
"""
Check the app can still load its own page.

v0.1.0 shipped without a network security config. Android denies cleartext
HTTP by default at targetSdk 28+, the entire UI is served over plain HTTP from
127.0.0.1, and so every launch showed net::ERR_CLEARTEXT_NOT_PERMITTED. The
APK compiled, every test passed, and the app was unusable.

Nothing in the Kotlin suite can catch that — the policy is enforced by the
platform, off-device. So this checks the two declarations that have to be
right, and CI runs it.

It also checks the exemption stays SCOPED TO LOOPBACK. Flipping
cleartextTrafficPermitted onto base-config would fix the symptom and quietly
allow cleartext to anywhere, in an app that holds two services' access tokens.

With --apk it checks the built artifact instead of the sources, which is the
stronger claim: that the manifest inside the APK really carries the attribute
and the resource really is packaged.
"""

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
CONFIG = ROOT / "android/app/src/main/res/xml/network_security_config.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"

LOOPBACK = {"127.0.0.1", "localhost"}


def fail(msg):
    print(f"FAIL: {msg}")
    sys.exit(1)


def check_manifest_source():
    tree = ET.parse(MANIFEST)
    app = tree.getroot().find("application")
    if app is None:
        fail("no <application> in the manifest")
    value = app.get(f"{ANDROID}networkSecurityConfig")
    if value != "@xml/network_security_config":
        fail("the manifest does not point <application> at "
             "@xml/network_security_config — the WebView will refuse to load "
             f"the app's own page (found: {value!r})")
    # usesCleartextTraffic="true" would also work and is exactly the blunt
    # instrument this is here to prevent.
    if app.get(f"{ANDROID}usesCleartextTraffic") == "true":
        fail("android:usesCleartextTraffic=\"true\" permits cleartext to "
             "EVERY destination. Scope it to loopback in "
             "network_security_config.xml instead.")
    print("ok: manifest declares networkSecurityConfig")


def check_config():
    root = ET.parse(CONFIG).getroot()

    base = root.find("base-config")
    if base is not None and base.get("cleartextTrafficPermitted") == "true":
        fail("base-config permits cleartext, which allows it to every "
             "destination. This app only ever needs it for its own loopback "
             "server; keep the exemption in a domain-config.")

    permitted = set()
    for dc in root.findall("domain-config"):
        if dc.get("cleartextTrafficPermitted") != "true":
            continue
        for d in dc.findall("domain"):
            if d.text:
                permitted.add(d.text.strip())

    missing = LOOPBACK - permitted
    if missing:
        fail("network_security_config.xml does not permit cleartext for "
             f"{sorted(missing)}. The UI is served over http from 127.0.0.1, "
             "so the WebView cannot load it.")

    stray = permitted - LOOPBACK
    if stray:
        fail(f"cleartext is permitted for non-loopback hosts {sorted(stray)}. "
             "Every destination that leaves the device must be HTTPS — this "
             "app holds Qobuz and Spotify access tokens.")

    print(f"ok: cleartext permitted for exactly {sorted(permitted)}")


def _tool(name):
    """Find an SDK tool, preferring PATH, falling back to ANDROID_HOME."""
    from shutil import which
    found = which(name)
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        hits = sorted(Path(home).rglob(name))
        if hits:
            return str(hits[-1])
    fail(f"could not find {name} — set ANDROID_HOME or put it on PATH")


def check_apk(apk):
    """
    The stronger claim: the config is really IN the built artifact, and really
    says what the source says.

    Reading it back is not as simple as unzipping a path. A release build runs
    aapt2's resource optimiser, which renames every resource to a short name —
    the config lands at something like res/8G.xml — and rewrites the manifest
    attribute to a numeric reference. So the file is found by resolving the
    resource through aapt2 rather than by guessing its name, which is what an
    earlier version of this script did (and it reported a perfectly good APK as
    broken).
    """
    aapt2 = _tool("aapt2")

    manifest = subprocess.run([aapt2, "dump", "xmltree", "--file",
                               "AndroidManifest.xml", apk],
                              capture_output=True, text=True)
    if manifest.returncode != 0:
        fail(f"aapt2 could not read {apk}: {manifest.stderr.strip()[:200]}")
    if "networkSecurityConfig" not in manifest.stdout:
        fail(f"the manifest inside {apk} does not declare "
             "networkSecurityConfig — the WebView will refuse to load the "
             "app's own page")
    if re.search(r"usesCleartextTraffic=true", manifest.stdout):
        fail("the packaged manifest sets usesCleartextTraffic=true, which "
             "permits cleartext to every destination")
    print("ok: the APK's own manifest declares networkSecurityConfig")

    # xml/network_security_config -> res/XX.xml, whatever aapt2 renamed it to.
    resources = subprocess.run([aapt2, "dump", "resources", apk],
                               capture_output=True, text=True).stdout
    m = re.search(r"xml/network_security_config\b.*?\(file\)\s+(\S+)",
                  resources, re.S)
    if not m:
        fail(f"xml/network_security_config is not packaged in {apk}")
    path = m.group(1)

    tree = subprocess.run([aapt2, "dump", "xmltree", "--file", path, apk],
                          capture_output=True, text=True).stdout
    if not tree.strip():
        fail(f"could not decode the packaged config at {path}")

    # The decoded binary XML, checked for the same two properties as the source.
    permitted = set(re.findall(r"T: '([^']+)'", tree))
    missing = LOOPBACK - permitted
    if missing:
        fail(f"the packaged config ({path}) does not permit cleartext for "
             f"{sorted(missing)}")
    stray = permitted - LOOPBACK
    if stray:
        fail(f"the packaged config permits cleartext for non-loopback hosts "
             f"{sorted(stray)}")
    if "base-config" in tree:
        fail("the packaged config has a base-config — cleartext must be "
             "scoped to a domain-config, not allowed globally")

    print(f"ok: packaged at {path}, cleartext for exactly {sorted(permitted)}")


if __name__ == "__main__":
    check_manifest_source()
    check_config()
    if "--apk" in sys.argv:
        check_apk(sys.argv[sys.argv.index("--apk") + 1])
    print("network security config is correct")
