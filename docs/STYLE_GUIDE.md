# JDesk documentation style guide

This guide keeps the JDesk docs consistent. It is modeled on the conventions used by
mature framework documentation (Next.js, Angular) and the [Diátaxis](https://diataxis.fr)
authoring framework. Read it before writing or editing a page.

## Information architecture: Diátaxis

Every page belongs to exactly one of four types. Do not mix them on one page — link
between them instead.

| Type | Directory | User is… | Serves | Answers |
| --- | --- | --- | --- | --- |
| **Tutorial** | `getting-started/` | learning by doing | acquiring skill | "teach me to build something" |
| **How-to guide** | `guides/` | working on a task | applying skill | "how do I accomplish X" |
| **Explanation** | `concepts/` | wanting to understand | acquiring understanding | "why does it work this way" |
| **Reference** | `reference/` | looking something up | applying knowledge | "what exactly is X" |

The two axes: **acquisition ↔ application** of skill, and **practical ↔ theoretical**
knowledge. When unsure which type a page is, ask what the reader is doing when they read
it: learning (tutorial), doing (how-to), understanding (explanation), or looking up
(reference).

### Rules per type

- **Tutorials** — a lesson that guarantees success. The reader follows exact steps and
  ends with something that works. Be concrete, not abstract. Do not offer choices or
  alternatives. Do not explain more than the reader needs to proceed; link to Concepts for
  "why". Every step must work if followed literally.
- **How-to guides** — solve one real problem for a competent reader. A goal + a sequence of
  steps. Assume the reader knows the basics (they did the tutorial). Do not teach concepts;
  link to them. Title as a task: "Define a command", "Package your app".
- **Explanation** — discuss and illuminate. Give background, design rationale, trade-offs,
  and alternatives. It is fine to be discursive here. Do not give step-by-step instructions.
- **Reference** — describe the machinery accurately and completely. Dry, structured, and
  consistent, mirroring the code. State facts; do not instruct or explain design.

## Voice and tone

- Address the reader as **"you"**. Refer to the framework as **JDesk**, not "we".
- **Active voice, present tense, imperative for steps.** "JDesk serves assets over
  `jdesk://app/`." "Run `./gradlew run`." Not "Assets are served" / "You will run".
- **Concise and concrete.** Prefer a short sentence and a code block over a paragraph of
  prose. Lead with the outcome.
- **Second-person imperative for instructions**, third-person declarative for reference.
- Sentence case for headings ("Define a command", not "Define A Command").
- No hype ("blazing fast", "simply", "just", "easy"). Do not call steps easy — show them.
- Be honest about status and limitations. If something is unimplemented or verified only on
  one platform, say so and link to `../VERIFICATION.md`. Never claim a capability that is
  not real.

## Page conventions

- Start every page with an `# H1` title and a one- or two-sentence summary of what the page
  covers and who it is for.
- Use `##`/`###` for structure; keep nesting shallow.
- **Code blocks are first-class.** Every code sample must be real and copy-pasteable. Tag
  the language (` ```java `, ` ```kotlin `, ` ```ts `, ` ```json `, ` ```bash `). Show the
  actual public API — never invent names. When in doubt, verify against the source under
  `modules/` and `examples/hello-vanilla`.
- Prefer relative Markdown links between docs. Cross-link generously: tutorials link to
  guides and concepts; guides link to reference; reference links back to guides.
- Put runnable commands on their own line in a `bash` block.
- Tables for enumerable facts (options, error codes, types); prose for everything else.
- End "Getting Started" and tutorial pages with a **"Next steps"** section linking the
  natural continuation.

## Terminology (use these exact terms)

- **command** — a `@DesktopCommand` method invoked from the frontend.
- **capability** — a permission granted to a window in `jdesk-capabilities.json`.
- **the bridge** — `window.__jdesk`, the injected IPC channel.
- **adapter / platform provider** — the per-OS `PlatformProvider` (`windows-webview2`,
  `macos-wkwebview`, `linux-webkitgtk`).
- **the app origin** — `jdesk://app`, the production asset origin.
- **the runtime** — `jdesk-runtime`, the pure-Java engine.
- Say "system WebView", not "browser". Say "JDK 25+", not "Java".

## Ordering within a section

Order pages from foundational to advanced, so a reader can follow top to bottom. The
sidebar/index reflects that order. Getting Started is strictly sequential; other sections
may be read in any order but are still listed foundational-first.
