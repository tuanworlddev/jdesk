#!/usr/bin/env node
// Copies the freshly-built jdesk-cli modular jar into this package so it ships in the
// npm tarball. Run by `npm run bundle` and automatically before publish.
// Build the jar first: ./gradlew :modules:jdesk-cli:jar

import { copyFileSync, existsSync, readdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..", "..");
const libsDir = resolve(repoRoot, "modules", "jdesk-cli", "build", "libs");

if (!existsSync(libsDir)) {
  console.error("bundle-cli: build the jar first: ./gradlew :modules:jdesk-cli:jar");
  process.exit(1);
}
const jar = readdirSync(libsDir)
  .filter((f) => f.startsWith("jdesk-cli-") && f.endsWith(".jar") && !f.includes("sources"))
  .sort()
  .pop();
if (!jar) {
  console.error(`bundle-cli: no jdesk-cli jar in ${libsDir}. Run ./gradlew :modules:jdesk-cli:jar`);
  process.exit(1);
}
const dest = resolve(here, "..", "jdesk-cli.jar");
copyFileSync(resolve(libsDir, jar), dest);
console.log(`bundle-cli: bundled ${jar} -> jdesk-cli.jar`);
