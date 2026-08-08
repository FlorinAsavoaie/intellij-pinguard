# Contributing to PinGuard

This file is the plugin's internals: how to build it, how it is put together, and
why. For what PinGuard does from a user's chair, see [README.md](README.md).

## Building

```bash
./gradlew test           # unit and in-IDE tests
./gradlew buildPlugin    # -> build/distributions/pinguard-0.1.0.zip
./gradlew verifyPlugin   # IntelliJ Plugin Verifier
./gradlew runIde         # sandbox IDE with the plugin installed
./gradlew runIdeSplitMode # backend + JetBrains Client, where the frontend module runs
```

Requires JDK 21. CI (`.github/workflows/build.yml`) runs `test`, `buildPlugin`
and `verifyPlugin` on every push to `main` and every pull request, which is what
keeps the compatibility claim in the README true after a platform bump rather
than at the moment someone last ran the verifier by hand.

`runIde` is an ordinary monolithic sandbox. Split mode is deliberately *not*
configured on the `intellijPlatform` extension: those settings are conventions for
every task that has a sandbox, so setting them would silently turn `runIde` itself
into a backend plus a client. `runIdeSplitMode` configures itself and is the only
task that needs to.

### Changelog

The descriptor's change notes are rendered from `CHANGELOG.md` by the
`org.jetbrains.changelog` plugin, so the two cannot disagree. Put user-visible
changes under `[Unreleased]`; there is nothing to edit in `plugin.xml`.

## How it works

PinGuard wraps the IDE's own close actions and takes pinned tabs out of them.

The obvious mechanism — the platform's `com.intellij.virtualFilePreCloseCheck`
extension point — does not work for this, and it is worth writing down why. That
hook is consulted from exactly one place, `FileEditorManagerImpl.canCloseFile`,
reached only via `closeFileWithChecks`, whose only caller is the `CloseEditor`
action. `CloseEditor` ships with no keyboard shortcut and is in no menu. Every
close a user actually performs takes a different route:

| What the user does | Action | Reaches the veto hook? | Guarded by PinGuard |
| --- | --- | --- | --- |
| <kbd>Cmd</kbd>+<kbd>W</kbd> / <kbd>Ctrl</kbd>+<kbd>F4</kbd>, tab menu → Close | `CloseContent` | no | yes |
| Close All, Close Others | `CloseAllEditors`, `CloseAllEditorsButActive` | no | yes |
| Close to the Left / Right, Close Unmodified, Close Readonly | `CloseEditorsActionBase` subclasses | no | not needed — see below |

So the guard sits on the actions instead. The extension point is still
registered — it costs nothing, and it is the only close veto the platform offers —
but it is not what does the work.

The last row is the one worth knowing about. Those four actions protect pinned
tabs already: `CloseEditorsActionBase` consults `EditorComposite.isPinned` before
offering a tab up, so a pinned tab is never among the files they would close.
PinGuard guarded them anyway at first, through reflection on a non-public
platform method — a `setAccessible` call, on the most breakable dependency in the
plugin, spent re-refusing closes that were never going to happen. That guard and
its reflection are gone. What replaced it is a test that fails the day the
platform stops excluding pinned tabs, which is the day those four would need
guarding again.

Three properties fall out of guarding at the action layer:

- **Closing a project or the IDE is never interrupted.** Neither dispatches these
  actions, so this is structural rather than a condition the plugin has to get
  right.
- **A refused close still closes everything else.** Cancelling a "Close All"
  outright because one tab is pinned would strand the tabs you did mean to close,
  so PinGuard narrows the action to its unpinned tabs and performs that itself.
- **"Close All Unpinned Tabs" is untouched.** It already leaves pinned tabs alone.

A file counts as pinned if any editor window in any open project has it pinned, so
a tab pinned in one split is protected from being closed in another.

### What the IDE already does

The tab's own **×** button needs no help: it checks the pin itself and refuses to
close a pinned tab — with the `ide.editor.tabs.interactive.pin.button` registry
key on it un-pins instead, and with it off it does nothing. PinGuard leaves that
button alone. It is also not reachable from `ActionManager`, being constructed per
tab rather than registered.

## Where the plugin runs

PinGuard ships as a modular plugin. `META-INF/plugin.xml` carries the identity
and registers nothing; everything the plugin does is registered in one content
module, `tech.florin.pinguard.frontend`, which declares a dependency on
`intellij.platform.frontend`.

That dependency is the whole design. The platform loads a content module only
where its dependencies resolve, and `intellij.platform.frontend` resolves in
exactly the processes that own editor tabs:

| Process | Module loads | Why it matters |
| --- | --- | --- |
| A monolithic IDE | yes | The ordinary case. |
| JetBrains Client (remote development, Code With Me) | yes | The client is a real IDE frontend: `FileEditorManager` there is `FrontendFileEditorManager`, a `FileEditorManagerImpl` subclass with real splitters, real `EditorWindow`s and real pins. It also registers `CloseContent`, `CloseAllEditors` and the rest in its own `ActionManager`, and declares `virtualFilePreCloseCheck`. |
| A remote development backend | no | It dispatches none of the close actions, so a guard there would wrap actions nobody invokes. |

The guard code is identical in all of them — this is a packaging decision, not a
second implementation. A close action is guarded in the process that dispatches
it, which is the only place the guard can work.

The module is declared `loading="required"`. The default, `optional`, would skip
a module whose dependencies did not resolve and leave PinGuard installed,
enabled, and doing nothing — the exact failure this layout exists to rule out.

One packaging detail is load-bearing and easy to get wrong: the module jar must
be named after the module. The platform resolves `<content>` by opening
`lib/modules/<module name>.jar`, so `frontend/build.gradle.kts` overrides
Gradle's default archive name to `tech.florin.pinguard.frontend`. Under any
other name the module is never found. `PluginDescriptorTest` asserts the
descriptor file name, the `<content>` entry and the frontend dependency stay in
step.

## The crash reporter

If PinGuard throws, the IDE's error dialog gets a **Report on GitHub** button that
composes the issue locally and opens GitHub's own new-issue form with it
prefilled. Nothing leaves the IDE on its own.

A prefilled issue has to travel in a URL, and GitHub's limit on those is smaller
than it looks — the documented 414 at 8192 bytes is never the one you hit, because
the real budget is roughly 7065 bytes shared between the URL *and* the request
headers, and a logged-in browser spends 1.5–2.5 KB of that on session cookies
alone. Going over produces a bare "Internal server error" page that the plugin has
no way to detect, so the URL is capped at 4000 bytes.

A trace too big for that is shortened rather than cut off: every `Caused by:` and
`Suppressed:` header survives — the root cause of a wrapped exception is in the
*last* one, which is precisely what head-only truncation throws away — and the
frames are spent on the two ends in preference to the wrappers between them. Only
when the headers alone will not fit, which takes a cause chain about thirty deep
or an exception message of several thousand characters, does the trace get clipped
outright. Whenever anything was left out the untouched report goes on the user's
clipboard and the issue body says so, so the fix is one paste.

The reporter sends only `title` and `body`. GitHub returns 404 for `labels`,
`assignees`, `milestone` and `projects` when whoever opens the link lacks the
matching permission on the repository, and most people reporting a crash are
strangers to it — so labels are the maintainer's job, not the URL's.

## Design and tests

The close decision is kept free of platform types so it can be driven by fast
in-process tests, with the IDE-facing pieces behind small interfaces:

| Type | Responsibility |
| --- | --- |
| `PinnedCloseGate` | The whole decision — allow, block, or ask — and the prompting when the answer is "ask". |
| `PinGuardState` | The two settings, in the shape they persist in. |
| `PinGuardSettings` | Application-level persisted settings. |
| `PinnedFileSelector` | Which tabs in a close request are pinned, and where. |
| `PlatformPinnedFiles` | Walks the IDE's editor windows, per split or across every project. |
| `CloseActionScope` | Which tabs a given close action is about to close. One implementation per action family. |
| `GuardedCloseAction` | Wraps a platform close action and applies the gate. |
| `PinGuardActionGuards` | Installs the wrappers, once per IDE run, and puts the platform's own actions back on unload. |
| `PinGuardCloseActions` | The startup activity that asks for `PinGuardActionGuards`. |
| `PinnedTabCloseGuard` | The extension point. Covers `CloseEditor` only. |
| `MessagesConfirmationPrompt` | The real dialog. |
| `Failures` | The one rule for a failure that was caught but cannot be acted on. |
| `StackTraceDigest` | Reads a trace as cause blocks, so shortening one keeps the root cause. |
| `GitHubIssueUrl` | Pure: an exception and an environment become a prefilled issue URL that fits GitHub's budget. |
| `ReportEnvironment` | The IDE facts an issue needs, as plain strings. |
| `PlatformReportEnvironment` | Reads the running IDE's version, OS, JVM and locale. |
| `PinGuardErrorSubmitter` | The `errorHandler` extension: the Report button, and the hand-off to the browser. |

The decision, the settings, the extension point and the whole of the issue
composer are covered by unit tests that run in-process with no IDE fixture.

The scopes and the action wrapper need a real `AnActionEvent`, real splits and a
real `FileEditorManagerImpl`, so they are covered by `RealEditorTestCase` — which
swaps the headless stub for the production editor manager, without which every pin
lookup reports nothing and a test built on it passes while exercising nothing.
That is where the per-split behaviour, the project-wide fallbacks and the
narrowed close are all pinned down.

Two of those tests exist to catch the platform changing under the plugin rather
than to check PinGuard's own logic: one asserts that the Close
Left/Right/Unmodified/Readonly family still excludes pinned tabs by itself, which
is the reason the plugin no longer guards those four at all, and one asserts that
PinGuard is really on the `virtualFilePreCloseCheck` extension point and that
`closeFileWithChecks` really honours its veto. Both fail loudly rather than
degrading quietly, which is the whole point of moving that reflection out of the
plugin and into a test.

The one thing no unit test can settle is whether GitHub accepts a URL of a given
size, since that depends on the cookies in the reporter's browser. The 4000-byte
cap was measured against the live service with a realistic header set; changing it
means measuring again, logged in, with a genuinely deep exception.

## Verifier caveats

`verifyPlugin` runs the Plugin Verifier against `IU-253.28294.334` and
`IU-262.8665.258` and reports the plugin compatible with both. Adding the other
JetBrains IDEs to `pluginVerification.ides` in `build.gradle.kts` is what would
turn "installs everywhere" from a statement about the dependency into a measured
result.

- The verifier reports three experimental-API usages: `VirtualFilePreCloseCheck`
  (the interface and the overridden method) and `LightEditCompatible`, which the
  wrapper for `CloseContent` carries because the action it wraps does.
- The wrappers deliberately do **not** reproduce
  `ActionRemoteBehaviorSpecification.Frontend`, which the wrapped actions carry:
  it is `@ApiStatus.Internal` and the verifier rejects it. Replacing an action
  that carries it drops the marker. Since the guards now run in the frontend
  there is no cross-process routing to get wrong, but what a split-mode
  dispatcher makes of the resulting null behaviour has not been confirmed
  against a live session.
- Wrapping the platform's close actions means depending on their action IDs.
  They are read defensively: an ID that no longer exists leaves that one action
  with its stock behaviour and logs a line. The failure mode is "that path stops
  being protected", never a broken IDE.
- Nothing in the plugin reflects on platform internals any more. The one place
  that did — the scope for Close to the Left/Right, Close Unmodified and Close
  Readonly — was removed once those actions turned out to exclude pinned tabs
  themselves. The reflection now lives in a test, where failing to resolve is a
  red build rather than a silently unguarded close path.
- `buildSearchableOptions` is turned off. The 2025.3 distribution that the
  unified `idea` artifact resolves to ships several bundled plugins as empty
  directories, so the headless IDE that task starts logs SEVERE for extensions it
  cannot instantiate and exits non-zero before reaching this plugin. PinGuard
  itself loads clean in that same run.
- `ErrorReportSubmitter.getPluginDescriptor()` is `@ApiStatus.OverrideOnly` on
  the 2025.3 release build, so the descriptor is captured in
  `setPluginDescriptor` on the way in rather than read back out.
- `com.intellij.util.Consumer`, which the `submit` signature requires, is
  `@ApiStatus.Obsolete` in both. Obsolete is an IDE inspection marker rather than
  a deprecation, so it neither fails this build nor registers with the verifier —
  and there is no non-obsolete overload to move to.

## License

By contributing you agree that your contributions are licensed under the Apache
License, Version 2.0, the same as the rest of the project. See
[LICENSE](LICENSE) and [NOTICE](NOTICE).
