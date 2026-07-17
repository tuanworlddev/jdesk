#!/usr/bin/env node
// create-jdesk-app — scaffolds a new JDesk desktop application.
//
//   npm create jdesk-app@latest my-app
//   npx create-jdesk-app@latest my-app --template react --package com.acme.myapp
//   npx create-jdesk-app@latest            # fully interactive
//
// A thin, dependency-free wrapper around the JDesk project generator (dev.jdesk.cli,
// bundled here as jdesk-cli.jar). Node.js is a scaffold-time tool only; the generated
// application is pure Java + a system WebView and needs no Node at runtime. A JDK 25+ is
// required to scaffold (you need it to build a JDesk app anyway).

import { spawn, spawnSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { dirname, resolve, basename } from "node:path";
import { fileURLToPath } from "node:url";
import process from "node:process";
import { text, select, confirm, spinner, isInteractive, Cancelled, color } from "./prompts.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const CLI_JAR = resolve(HERE, "jdesk-cli.jar");
const CLI_MODULE = "dev.jdesk.cli";
const MIN_JDK = 25;

const TEMPLATES = ["basic", "structured", "vanilla", "react", "vue", "svelte", "solid", "maven"];
const BUILD_CHOICES = [
  { value: "gradle", label: "Gradle", hint: "recommended — full tooling (run, dev/HMR, package, installer)" },
  { value: "maven", label: "Maven", hint: "pom.xml; build + run only (no packaging yet)" },
];
const TEMPLATE_CHOICES = [
  { value: "basic", label: "Basic", hint: "single module, plain HTML/JS (great for learning)" },
  { value: "vanilla", label: "Vanilla + Vite", hint: "single module, Vite + vanilla TypeScript" },
  { value: "react", label: "React + Vite", hint: "single module, Vite + React" },
  { value: "vue", label: "Vue + Vite", hint: "single module, Vite + Vue" },
  { value: "svelte", label: "Svelte + Vite", hint: "single module, Vite + Svelte" },
  { value: "solid", label: "Solid + Vite", hint: "single module, Vite + Solid" },
  { value: "structured", label: "Structured", hint: "multi-module: domain / application / infrastructure / desktop" },
];

const NAME_RE = /^[A-Za-z0-9._-]+$/;
const PACKAGE_RE = /^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)+$/;

function fail(message) {
  process.stderr.write(`\n${color.red("create-jdesk-app:")} ${message}\n`);
  process.exit(1);
}

function slug(name) {
  const s = String(name).toLowerCase().replace(/[^a-z0-9]+/g, "");
  return s && !/^\d/.test(s) ? s : `app${s}`;
}

/** Parses argv into a project name plus flags. */
function parseArgs(argv) {
  const flags = {};
  let name = null;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--help" || arg === "-h") flags.help = true;
    else if (arg === "--force") flags.force = true;
    else if (arg === "--yes" || arg === "-y") flags.yes = true;
    else if (arg === "--template" || arg === "-t") flags.template = argv[++i];
    else if (arg === "--build" || arg === "-b") flags.build = argv[++i];
    else if (arg === "--maven") flags.build = "maven";
    else if (arg === "--package" || arg === "-p") flags.package = argv[++i];
    else if (arg === "--jdesk-version") flags.jdeskVersion = argv[++i];
    else if (arg === "--jdesk-source") flags.jdeskSource = argv[++i];
    else if (arg.startsWith("-")) fail(`unknown option: ${arg}`);
    else if (name === null) name = arg;
    else fail("only one project name may be given");
  }
  return { name, flags };
}

function usage() {
  process.stdout.write(`${color.bold("create-jdesk-app")} — scaffold a JDesk desktop app

${color.bold("Usage")}
  npm create jdesk-app@latest [name] [options]
  npx create-jdesk-app@latest [name] [options]

Run with no arguments for a guided setup. Any option you pass is used as-is and its
prompt is skipped.

${color.bold("Options")}
  -b, --build <system>    gradle | maven  (default: gradle)
      --maven             Shorthand for --build maven
  -t, --template <name>   basic | structured | vanilla | react | vue | svelte | solid
                          (Gradle only; default: basic)
  -p, --package <id>      Reverse-DNS Java package / application id (default: com.example.<name>)
      --jdesk-version <v> Framework version to depend on
      --jdesk-source <d>  Use a local JDesk checkout (composite build) — for framework development
  -y, --yes               Accept all defaults, no prompts
      --force             Overwrite files in a non-empty directory
  -h, --help              Show this help
`);
}

/** Locates a JDK >= MIN_JDK: honors JAVA_HOME, else `java` on PATH. */
function resolveJava() {
  const candidates = [];
  if (process.env.JAVA_HOME) {
    const exe = process.platform === "win32" ? "java.exe" : "java";
    candidates.push(resolve(process.env.JAVA_HOME, "bin", exe));
  }
  candidates.push("java");
  for (const java of candidates) {
    const probe = spawnSync(java, ["-version"], { encoding: "utf8" });
    const text = `${probe.stderr || ""}${probe.stdout || ""}`;
    const match = text.match(/version "(\d+)/);
    if (match) {
      if (Number(match[1]) >= MIN_JDK) return java;
      fail(`found Java ${match[1]} but JDesk needs JDK ${MIN_JDK}+.\n`
        + `  Set JAVA_HOME to a JDK ${MIN_JDK} (e.g. from https://adoptium.net).`);
    }
  }
  fail(`no JDK ${MIN_JDK}+ found.\n`
    + `  Install one (https://adoptium.net) and ensure java is on PATH or JAVA_HOME is set.`);
  return null;
}

/** Runs the bundled Java generator with a spinner; resolves on success. */
function scaffold(java, cliArgs, projectName) {
  return new Promise((resolvePromise) => {
    const spin = spinner(`Creating ${color.cyan(projectName)} …`);
    const child = spawn(java, cliArgs, { stdio: ["ignore", "pipe", "pipe"] });
    let stderr = "";
    child.stdout.on("data", () => {}); // suppress the CLI's own lines; we print our own
    child.stderr.on("data", (d) => { stderr += d; });
    child.on("error", (e) => {
      spin.stop(false, `Failed to launch Java: ${e.message}`);
      process.exit(1);
    });
    child.on("close", (code) => {
      if (code === 0) {
        spin.stop(true, `Created ${color.cyan(projectName)}`);
        resolvePromise();
      } else {
        spin.stop(false, "Scaffolding failed");
        if (stderr.trim()) process.stderr.write(stderr.replace(/^/gm, "  "));
        process.exit(code ?? 1);
      }
    });
  });
}

async function collect(name, flags) {
  const interactive = isInteractive() && !flags.yes;

  // 1. Project name
  let projectName = name;
  if (!projectName) {
    projectName = interactive
      ? await text("Project name", {
          defaultValue: "my-jdesk-app",
          validate: (v) => (NAME_RE.test(v) ? null : "Use letters, digits, dots, dashes or underscores."),
        })
      : "my-jdesk-app";
  }
  if (!NAME_RE.test(basename(projectName))) {
    fail(`invalid project name '${projectName}'. Use letters, digits, . _ -`);
  }

  // 2. Overwrite check
  const targetDir = resolve(process.cwd(), projectName);
  if (existsSync(targetDir) && readdirSync(targetDir).length > 0 && !flags.force) {
    if (interactive) {
      const ok = await confirm(`Directory ${color.yellow(projectName)} is not empty. Continue?`, false);
      if (!ok) throw new Cancelled();
      flags.force = true;
    } else {
      fail(`directory '${projectName}' is not empty (use --force to overwrite).`);
    }
  }

  // 3. Package / application id — default com.example.<slug>, Enter accepts.
  const defaultPkg = `com.example.${slug(basename(projectName))}`;
  let pkg = flags.package;
  if (!pkg) {
    pkg = interactive
      ? await text("Package name (application id)", {
          defaultValue: defaultPkg,
          validate: (v) => (PACKAGE_RE.test(v) ? null : "Use reverse-DNS lowercase, e.g. com.example.app"),
        })
      : defaultPkg;
  }
  if (!PACKAGE_RE.test(pkg)) fail(`invalid package '${pkg}'. Use reverse-DNS lowercase, e.g. com.example.app`);

  // 4. Build system. Maven uses a fixed single-module template; Gradle offers a frontend
  //    choice. An explicit --template wins over the build prompt.
  let template = flags.template;
  if (template && !TEMPLATES.includes(template)) {
    fail(`unknown template '${template}'. Choose one of: ${TEMPLATES.join(", ")}`);
  }
  if (!template) {
    let build = flags.build;
    if (build && build !== "gradle" && build !== "maven") {
      fail(`unknown build system '${build}'. Choose 'gradle' or 'maven'.`);
    }
    if (!build) {
      build = interactive ? await select("Build system", BUILD_CHOICES, 0) : "gradle";
    }
    if (build === "maven") {
      template = "maven";
    } else {
      // 5. Frontend template — arrow-key select (Gradle only).
      template = interactive ? await select("Select a template", TEMPLATE_CHOICES, 0) : "basic";
    }
  }

  return { projectName, targetDir, pkg, template };
}

async function main() {
  const { name, flags } = parseArgs(process.argv.slice(2));
  if (flags.help) {
    usage();
    return;
  }
  if (!existsSync(CLI_JAR)) {
    fail(`bundled generator missing (${CLI_JAR}). Reinstall create-jdesk-app.`);
  }
  if (!name && !isInteractive() && !flags.yes) {
    usage();
    process.exit(2);
  }

  process.stdout.write(`\n${color.bold(color.cyan("create-jdesk-app"))} ${color.dim("· let's set up your JDesk app")}\n\n`);

  const { projectName, pkg, template } = await collect(name, flags);

  const java = resolveJava();
  const cliArgs = ["-p", CLI_JAR, "-m", `${CLI_MODULE}/dev.jdesk.cli.JDeskCli`,
    "create", projectName, "--template", template, "--package", pkg];
  if (flags.jdeskVersion) cliArgs.push("--jdesk-version", flags.jdeskVersion);
  if (flags.jdeskSource) cliArgs.push("--jdesk-source", resolve(flags.jdeskSource));
  if (flags.force) cliArgs.push("--force");

  process.stdout.write("\n");
  await scaffold(java, cliArgs, projectName);

  process.stdout.write(`\n${color.green("Done!")} Next steps:\n\n`);
  process.stdout.write(`  ${color.cyan(`cd ${projectName}`)}\n`);
  if (template === "maven") {
    process.stdout.write(`  ${color.cyan("(cd ui && java Build.java)")}   ${color.dim("# build the UI")}\n`);
    process.stdout.write(`  ${color.cyan("mvn compile")}                 ${color.dim("# resolve JDesk from Maven Central")}\n`);
    process.stdout.write(`  ${color.cyan("mvn exec:exec")}               ${color.dim("# run the app")}\n\n`);
  } else {
    const needsNode = template !== "basic" && template !== "structured";
    if (needsNode) {
      process.stdout.write(`  ${color.cyan("npm install --prefix ui")}   ${color.dim("# install the frontend deps")}\n`);
    }
    process.stdout.write(`  ${color.cyan("./gradlew run")}              ${color.dim("# launch the app on this OS")}\n`);
    process.stdout.write(`  ${color.cyan("./gradlew dev")}              ${color.dim("# dev loop with frontend hot-reload")}\n\n`);
  }
  process.stdout.write(`${color.dim("Docs: https://github.com/tuanworlddev/jdesk/tree/main/docs")}\n\n`);
}

main().catch((error) => {
  if (error instanceof Cancelled) {
    process.stdout.write(`\n${color.yellow("Cancelled.")} No files were written.\n`);
    process.exit(130);
  }
  fail(error?.message ?? String(error));
});
