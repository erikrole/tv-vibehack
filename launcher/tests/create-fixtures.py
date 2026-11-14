#!/usr/bin/env python3
"""Build eight clearly labelled emulator apps with oversized banners; no accounts or network."""
import os
from pathlib import Path
import struct
import subprocess
import sys
import zlib

sdk, output = map(Path, sys.argv[1:])
resources = output / 'res' / 'drawable-nodpi'
resources.mkdir(parents=True, exist_ok=True)
colors = [(61, 96, 138), (108, 64, 83), (45, 103, 99), (106, 84, 59),
          (71, 67, 115), (69, 92, 112), (92, 100, 62), (111, 66, 57)]


def png(color):
    width, height = 2048, 1152
    row = b'\0' + bytes(int(v * (.55 + .45 * x / width)) for x in range(width) for v in color)

    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(row * height)) + chunk(b'IEND', b''))


aliases = []
for i, color in enumerate(colors, 1):
    (resources / f'demo{i}.png').write_bytes(png(color))
    aliases.append(f'''<activity-alias android:name=".Demo{i}" android:targetActivity="android.app.Activity"
        android:label="Demo {i:02}" android:banner="@drawable/demo{i}" android:exported="true">
        <intent-filter><action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/></intent-filter></activity-alias>''')
manifest = output / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.gathin.tvhome.fixture">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/>
    <application android:hasCode="false" android:label="Launcher test fixtures">
    <activity android:name="android.app.Activity" android:exported="true"/>'''
                    + ''.join(aliases) + '</application></manifest>')
tools = sdk / 'build-tools' / '34.0.0'
subprocess.run([str(tools / 'aapt2'), 'compile', '--dir', str(output / 'res'), '-o', str(output / 'res.zip')], check=True)
subprocess.run([str(tools / 'aapt2'), 'link', '-I', str(sdk / 'platforms/android-34/android.jar'),
                '--manifest', str(manifest), '-o', str(output / 'fixture.apk'), str(output / 'res.zip')], check=True)
subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(Path.home() / '.android/debug.keystore'),
                '--ks-pass', 'pass:android', str(output / 'fixture.apk')], check=True)
