"""Adapt a test-only APK copy to the bundled Robolectric 4.8.2 / API 32 runtime.

Production APKs use compact resource entries and require API 34. Expand their
resource table and lower only the test copy's manifest SDK declarations.
"""
import pathlib
import struct
import subprocess
import sys
import tempfile
import zipfile


def main():
    aapt, source, destination = sys.argv[1:]
    with tempfile.TemporaryDirectory() as directory:
        converted = pathlib.Path(directory) / "resources.apk"
        subprocess.run([aapt, "convert", "--output-format", "binary", "-o",
                        str(converted), source], check=True)
        with zipfile.ZipFile(converted) as apk:
            entries = {name: apk.read(name) for name in apk.namelist()}
    manifest = bytearray(entries["AndroidManifest.xml"])
    resource_ids = []
    offset = 8  # ResXMLTree header
    changed = 0
    while offset < len(manifest):
        kind, header_size, size = struct.unpack_from("<HHI", manifest, offset)
        if kind == 0x0180:  # RES_XML_RESOURCE_MAP_TYPE
            count = (size - header_size) // 4
            resource_ids = struct.unpack_from("<" + "I" * count, manifest, offset + header_size)
        elif kind == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            start, attr_size, count = struct.unpack_from("<HHH", manifest, offset + header_size + 8)
            for index in range(count):
                attr = offset + header_size + start + index * attr_size
                name = struct.unpack_from("<I", manifest, attr + 4)[0]
                # android:minSdkVersion and android:targetSdkVersion
                if name < len(resource_ids) and resource_ids[name] in (0x0101020C, 0x01010270):
                    struct.pack_into("<I", manifest, attr + 16, 32)
                    changed += 1
        offset += size
    if changed != 2:
        raise ValueError("Expected minSdkVersion and targetSdkVersion in test manifest")
    entries["AndroidManifest.xml"] = manifest
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as apk:
        for name, content in entries.items():
            apk.writestr(name, content)


if __name__ == "__main__":
    main()
