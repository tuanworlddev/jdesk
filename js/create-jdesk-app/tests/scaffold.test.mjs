import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const packageRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(packageRoot, "..", "..");
const entry = resolve(packageRoot, "index.mjs");

function run(cwd, ...args) {
  return spawnSync(process.execPath, [entry, ...args], {
    cwd,
    encoding: "utf8",
    env: { ...process.env, NO_COLOR: "1" },
  });
}

test("help exposes every supported build and frontend choice", () => {
  const result = run(repositoryRoot, "--help");
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /gradle \| maven/);
  assert.match(result.stdout, /react \| vue \| svelte \| solid/);
});

test("Solid scaffolding delegates to the bundled Java generator", async () => {
  const cwd = await mkdtemp(resolve(tmpdir(), "create-jdesk-solid-"));
  const result = run(
    cwd,
    "solid-app",
    "--yes",
    "--template",
    "solid",
    "--jdesk-source",
    repositoryRoot,
  );
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /Created solid-app/);
  assert.match(
    readFileSync(resolve(cwd, "solid-app", "ui", "package.json"), "utf8"),
    /"jdesk-client":"\^0\.1\.3"/,
  );
  assert.match(
    readFileSync(resolve(cwd, "solid-app", "ui", "src", "main.jsx"), "utf8"),
    /render/,
  );
});

test("Maven instructions resolve public artifacts", async () => {
  const cwd = await mkdtemp(resolve(tmpdir(), "create-jdesk-maven-"));
  const result = run(cwd, "maven-app", "--yes", "--maven");
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.doesNotMatch(result.stdout, /publishToMavenLocal/);
  assert.match(result.stdout, /resolve JDesk from Maven Central/);
  assert.match(
    readFileSync(resolve(cwd, "maven-app", "pom.xml"), "utf8"),
    /<jdesk\.version>0\.1\.3<\/jdesk\.version>/,
  );
});
