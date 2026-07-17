#!/usr/bin/env python3
"""Fail a release when any independently published JDesk version drifts."""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys


ROOT = pathlib.Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"release-version: {message}", file=sys.stderr)
    raise SystemExit(1)


def gradle_version() -> str:
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"(?m)^version=(.+)$", properties)
    if not match:
        fail("gradle.properties has no version")
    return match.group(1).strip()


def package(path: str) -> dict[str, object]:
    return json.loads((ROOT / path).read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="release tag expected to equal v<version>")
    args = parser.parse_args()

    version = gradle_version()
    expected_tag = f"v{version}"
    if args.tag and args.tag != expected_tag:
        fail(f"tag {args.tag!r} does not match {expected_tag!r}")

    published_packages = (
        "js/create-jdesk-app/package.json",
        "js/jdesk-client/package.json",
    )
    for path in published_packages:
        actual = str(package(path).get("version", ""))
        if actual != version:
            fail(f"{path} is {actual!r}; expected {version!r}")

    for name in ("jdesk-client", "create-jdesk-app"):
        lock = package(f"js/{name}/package-lock.json")
        if lock.get("version") != version:
            fail(f"js/{name}/package-lock.json top-level version drifted")
        root_lock = lock.get("packages", {}).get("", {})
        if root_lock.get("name") != name or root_lock.get("version") != version:
            fail(f"js/{name} package-lock root metadata drifted")

    cli_source = (ROOT / "modules/jdesk-cli/src/main/java/dev/jdesk/cli/JDeskCli.java").read_text(
        encoding="utf-8"
    )
    cli_match = re.search(r'DEFAULT_VERSION\s*=\s*"([^"]+)"', cli_source)
    if not cli_match or cli_match.group(1) != version:
        actual = cli_match.group(1) if cli_match else "missing"
        fail(f"JDeskCli.DEFAULT_VERSION is {actual!r}; expected {version!r}")

    templates = ROOT.glob("modules/jdesk-cli/src/main/resources/templates/*/ui/package.json")
    checked = 0
    for template in templates:
        dependency = json.loads(template.read_text(encoding="utf-8")).get("dependencies", {}).get(
            "jdesk-client"
        )
        if dependency is None:
            continue
        checked += 1
        if dependency != "^@JDESK_VERSION@":
            fail(f"{template.relative_to(ROOT)} uses jdesk-client {dependency!r}")
    if checked < 5:
        fail(f"only found {checked} frontend templates with jdesk-client")

    node_wrapper = (ROOT / "js/create-jdesk-app/index.mjs").read_text(encoding="utf-8")
    java_templates = set(
        re.search(r"Set\.of\(([^;]+)\);", cli_source, re.DOTALL).group(1).replace('"', "").replace(
            "\n", ""
        ).replace(" ", "").split(",")
    )
    node_match = re.search(r"const TEMPLATES = \[([^]]+)]", node_wrapper)
    if not node_match:
        fail("create-jdesk-app TEMPLATES is missing")
    node_templates = {value.strip().strip('"') for value in node_match.group(1).split(",")}
    if node_templates != java_templates:
        fail(f"Node templates {sorted(node_templates)} differ from Java {sorted(java_templates)}")

    print(
        f"release-version: OK version={version} tag={args.tag or '(not checked)'} "
        f"packages={len(published_packages)} templates={checked}"
    )


if __name__ == "__main__":
    main()
