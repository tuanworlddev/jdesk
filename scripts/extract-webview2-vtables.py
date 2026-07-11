#!/usr/bin/env python3
"""Extracts IIDs and vtable method orders for selected interfaces from WebView2.h.

Usage: extract-webview2-vtables.py <path-to-WebView2.h> <Interface> [<Interface>...]

Emits, for each interface, its GUID and the zero-based vtable slot of every method
(including the 3 IUnknown slots and inherited interface methods), computed from the
C++ MIDL_INTERFACE declarations. Used to generate/verify the constants in
jdesk-platform-windows; keep the SDK version in the generated comment.
"""
import re
import sys


def parse(header_text):
    interfaces = {}
    pattern = re.compile(
        r'MIDL_INTERFACE\("([0-9a-fA-F-]+)"\)\s*\n\s*(\w+)\s*:\s*public\s+(\w+)\s*\{(.*?)\n\s*\};',
        re.S)
    for match in pattern.finditer(header_text):
        guid, name, parent, body = match.groups()
        methods = re.findall(
            r'virtual\s+(?:/\*.*?\*/\s*)?HRESULT\s+STDMETHODCALLTYPE\s+(\w+)\(', body)
        interfaces[name] = {"guid": guid, "parent": parent, "methods": methods}
    return interfaces


def vtable(interfaces, name):
    if name == "IUnknown":
        return ["QueryInterface", "AddRef", "Release"]
    if name not in interfaces:
        raise SystemExit(f"interface not found: {name}")
    info = interfaces[name]
    return vtable(interfaces, info["parent"]) + info["methods"]


def main():
    header_path, *names = sys.argv[1:]
    text = open(header_path, encoding="utf-8", errors="replace").read()
    interfaces = parse(text)
    for name in names:
        info = interfaces.get(name)
        if info is None:
            print(f"!! NOT FOUND: {name}")
            continue
        slots = vtable(interfaces, name)
        print(f"{name} IID={info['guid']} parent={info['parent']}")
        for index, method in enumerate(slots):
            print(f"  [{index:2}] {method}")
        print()


if __name__ == "__main__":
    main()
