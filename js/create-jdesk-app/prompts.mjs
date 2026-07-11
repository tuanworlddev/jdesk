// Tiny zero-dependency interactive prompt toolkit for create-jdesk-app.
// Provides text input (with a default on Enter), arrow-key select, confirm, and a
// spinner. Keeping this dependency-free keeps `npx create-jdesk-app` fast and
// supply-chain-free. All prompts handle Ctrl+C gracefully and fall back cleanly when
// stdin is not a TTY.

import process from "node:process";
import { createInterface } from "node:readline";

const stdin = process.stdin;
const stdout = process.stdout;

// Minimal ANSI helpers (no picocolors dependency).
const supportsColor = stdout.isTTY && process.env.NO_COLOR === undefined;
const paint = (open, close) => (s) => (supportsColor ? `\x1b[${open}m${s}\x1b[${close}m` : s);
export const color = {
  cyan: paint(36, 39),
  green: paint(32, 39),
  red: paint(31, 39),
  yellow: paint(33, 39),
  dim: paint(2, 22),
  bold: paint(1, 22),
  gray: paint(90, 39),
};

const SYMBOL = { q: color.cyan("?"), ok: color.green("✔"), err: color.red("✖"), pointer: color.cyan("❯") };

/** Thrown when the user cancels (Ctrl+C / Esc). Callers exit cleanly. */
export class Cancelled extends Error {
  constructor() {
    super("cancelled");
    this.name = "Cancelled";
  }
}

export function isInteractive() {
  return Boolean(stdin.isTTY && stdout.isTTY);
}

/** Free-text answer. Empty input returns `defaultValue`. `validate` returns null|string. */
export function text(message, { defaultValue = "", validate } = {}) {
  return new Promise((resolve, reject) => {
    const rl = createInterface({ input: stdin, output: stdout });
    const hint = defaultValue ? color.dim(` (${defaultValue})`) : "";
    const ask = () => {
      rl.question(`${SYMBOL.q} ${color.bold(message)}${hint} `, (answer) => {
        const value = answer.trim() || defaultValue;
        const error = validate ? validate(value) : null;
        if (error) {
          stdout.write(`  ${color.red(error)}\n`);
          ask();
          return;
        }
        rl.close();
        resolve(value);
      });
    };
    rl.on("SIGINT", () => {
      rl.close();
      reject(new Cancelled());
    });
    ask();
  });
}

/** Yes/no. Enter takes `defaultValue`. */
export function confirm(message, defaultValue = false) {
  const hint = defaultValue ? "(Y/n)" : "(y/N)";
  return text(`${message} ${color.dim(hint)}`, {
    defaultValue: defaultValue ? "y" : "n",
    validate: (v) => (/^(y|yes|n|no)$/i.test(v) ? null : "Please answer y or n."),
  }).then((v) => /^(y|yes)$/i.test(v));
}

/**
 * Arrow-key single select. `choices` = [{ value, label, hint }]. Returns the chosen
 * value. Falls back to a numbered readline prompt when raw mode is unavailable.
 */
export function select(message, choices, defaultIndex = 0) {
  if (!isInteractive() || typeof stdin.setRawMode !== "function") {
    return numberedSelect(message, choices, defaultIndex);
  }
  return new Promise((resolve, reject) => {
    let index = Math.max(0, Math.min(defaultIndex, choices.length - 1));
    let rendered = 0;

    const render = () => {
      if (rendered > 0) {
        stdout.write(`\x1b[${rendered}A`); // move cursor up to overwrite
      }
      stdout.write("\x1b[0J"); // clear from cursor to end of screen
      let out = `${SYMBOL.q} ${color.bold(message)} ${color.dim("(↑/↓ to move, Enter to select)")}\n`;
      choices.forEach((choice, i) => {
        const active = i === index;
        const pointer = active ? SYMBOL.pointer : " ";
        const label = active ? color.cyan(choice.label) : choice.label;
        const hint = choice.hint ? color.dim(`  — ${choice.hint}`) : "";
        out += `${pointer} ${label}${hint}\n`;
      });
      stdout.write(out);
      rendered = choices.length + 1;
    };

    const cleanup = () => {
      stdin.setRawMode(false);
      stdin.pause();
      stdin.removeListener("data", onData);
      stdout.write("\x1b[?25h"); // show cursor
    };

    const submit = () => {
      cleanup();
      // Collapse the menu to a single confirmation line.
      stdout.write(`\x1b[${rendered}A\x1b[0J`);
      stdout.write(`${SYMBOL.ok} ${color.bold(message)} ${color.cyan(choices[index].label)}\n`);
      resolve(choices[index].value);
    };

    // A single data event may batch several keys (fast typing, paste, piped input),
    // so tokenize the buffer and handle each key in order.
    const onData = (buf) => {
      let s = buf.toString();
      let dirty = false;
      while (s.length > 0) {
        if (s.startsWith("\x1b[A") || s[0] === "k") { // up
          index = (index - 1 + choices.length) % choices.length;
          dirty = true;
          s = s.slice(s[0] === "k" ? 1 : 3);
        } else if (s.startsWith("\x1b[B") || s[0] === "j") { // down
          index = (index + 1) % choices.length;
          dirty = true;
          s = s.slice(s[0] === "j" ? 1 : 3);
        } else if (s[0] === "\r" || s[0] === "\n") { // enter
          if (dirty) render();
          submit();
          return;
        } else if (s[0] === "\x03" || s[0] === "\x1b") { // Ctrl+C or Esc
          cleanup();
          reject(new Cancelled());
          return;
        } else if (/[1-9]/.test(s[0])) { // number shortcut
          const n = Number(s[0]) - 1;
          if (n < choices.length) {
            index = n;
            dirty = true;
          }
          s = s.slice(1);
        } else {
          s = s.slice(1); // ignore unknown byte
        }
      }
      if (dirty) render();
    };

    stdout.write("\x1b[?25l"); // hide cursor
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding("utf8");
    stdin.on("data", onData);
    render();
  });
}

function numberedSelect(message, choices, defaultIndex) {
  const lines = choices
    .map((c, i) => `  ${i + 1}) ${c.label}${c.hint ? color.dim(` — ${c.hint}`) : ""}`)
    .join("\n");
  stdout.write(`${SYMBOL.q} ${color.bold(message)}\n${lines}\n`);
  return text(`Choose 1-${choices.length}`, {
    defaultValue: String(defaultIndex + 1),
    validate: (v) => {
      const n = Number(v);
      return Number.isInteger(n) && n >= 1 && n <= choices.length
        ? null
        : `Enter a number 1-${choices.length}.`;
    },
  }).then((v) => choices[Number(v) - 1].value);
}

/** Braille spinner. Returns { stop(ok, text) } to finish with a ✔/✖ line. */
export function spinner(message) {
  const frames = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];
  if (!stdout.isTTY) {
    stdout.write(`${message}\n`);
    return { stop: (ok, text) => stdout.write(`${ok ? SYMBOL.ok : SYMBOL.err} ${text || message}\n`) };
  }
  let i = 0;
  stdout.write("\x1b[?25l");
  const timer = setInterval(() => {
    stdout.write(`\r${color.cyan(frames[i = (i + 1) % frames.length])} ${message}`);
  }, 80);
  return {
    stop(ok, text) {
      clearInterval(timer);
      stdout.write(`\r\x1b[0K${ok ? SYMBOL.ok : SYMBOL.err} ${text || message}\n\x1b[?25h`);
    },
  };
}
