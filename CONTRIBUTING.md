# Contributing to PinGuard

This file is the plugin's internals: how to build it, how it is put together, and
why. For what PinGuard does from a user's chair, see [README.md](README.md).

## Building

```bash
./gradlew test           # unit and in-IDE tests
./gradlew buildPlugin    # -> build/distributions/pinguard-0.0.0.zip
./gradlew verifyPlugin   # IntelliJ Plugin Verifier
./gradlew runIde         # sandbox IDE with the plugin installed
./gradlew runIdeSplitMode # backend + JetBrains Client, where the frontend module runs
```

Requires JDK 21. CI (`.github/workflows/build.yml`) runs `test`, `buildPlugin`
and `verifyPlugin` on every pull request, and on demand from the Actions tab,
which is what keeps the compatibility claim in the README true after a platform
bump rather than at the moment someone last ran the verifier by hand.

`runIde` is an ordinary monolithic sandbox. Split mode is deliberately *not*
configured on the `intellijPlatform` extension: those settings are conventions for
every task that has a sandbox, so setting them would silently turn `runIde` itself
into a backend plus a client. `runIdeSplitMode` configures itself and is the only
task that needs to.

Every build that is not a release is version `0.0.0`, which is why the zip above is
named that way. The version a release ships under is not stored in this repository
at all: it comes from the release tag, and `-PpluginVersion=` or the
`PLUGIN_VERSION` environment variable is how a build is told which one it is
producing. There is nothing to bump before a release and nothing to edit in
`plugin.xml`.

## Releasing

There is no changelog file. The body of the GitHub release *is* the change notes:
`release.yml` writes it to `build/release-notes.md` and the descriptor renders it to
HTML from there. Marketplace documents change-notes as accepting simple HTML, so
keep a release body to headings, paragraphs, lists, links, emphasis and inline code
— a table, an image or a collapsible block is outside what is documented. Since
nothing records user-visible changes in the repository any more, say what a change
means for users in the pull request, where it can be lifted into a release body.

Releasing needs a GitHub environment named `Marketplace` carrying four secrets:
`PUBLISH_TOKEN` from your [Marketplace tokens](https://plugins.jetbrains.com/author/me/tokens),
and `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` from a signing key
made as [plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
describes. Without the signing three, `publishPlugin` refuses to upload rather than
quietly shipping an unsigned plugin. `releaseDraft` additionally needs an
authenticated `gh` and an `origin` remote.

### The first upload

JetBrains requires the first publication of a plugin to be uploaded by hand, so the
procedure below does not apply to it — following it would tag and publish a release
that fails at the last step. Instead, write the notes into `build/release-notes.md`
yourself, build the signed archive with the signing variables exported, and upload
it on the plugin's Marketplace page:

```bash
./gradlew signPlugin -PpluginVersion=1.2.3   # -> build/distributions/pinguard-1.2.3-signed.zip
```

Then tag that commit, so the guard below has something to measure later releases
against — it reads git tags and GitHub releases, and can see nothing that exists only
on Marketplace.

### Every release after that

```bash
./gradlew releaseDraft --release-version=1.2.3
```

That checks what is still cheap to get wrong — the version's shape, no uncommitted
changes to tracked files, a pushed HEAD, no tag or release already using the version,
and that it is newer than every version tagged here or released on GitHub — and then
opens a **draft** release pinned to the current commit. No tag exists yet, so an
abandoned draft costs nothing: delete it.

Write the notes into that draft, then publish it. Publishing is what creates the tag
and starts `release.yml`, which tests, verifies, publishes to Marketplace and attaches
the signed archive back to the release. Publishing is left to a person because that is
where the notes get written; if you ever automate it, it must not be with the
workflow's own `GITHUB_TOKEN`, which triggers no workflow at all — `gh` with your own
credentials is fine.

A pre-release version picks its own Marketplace channel: `1.2.3-beta.1` goes to `beta`,
which only people who added that channel's URL receive. `releaseDraft` marks such a
draft as a pre-release on GitHub to match, and `release.yml` refuses to publish if the
two ever disagree.

Nothing in the release job writes to this repository.

Note that `build/release-notes.md` is read by every build that finds it, so a file
left over from a release becomes the change notes of your next local build; `clean`
removes it, along with any notes you were about to release.

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
| Close All | `CloseAllEditors` | no | yes |
| Close Others | `CloseAllEditorsButActive` | no | on one of its two branches — see below |
| Close to the Left / Right, Close Unmodified, Close Readonly | `CloseEditorsActionBase` subclasses | no | not needed — see below |

So the guard sits on the actions instead. The extension point is still
registered — it costs nothing, and it is the only close veto the platform offers —
but it is not what does the work.

Close Others is the split case. `CloseAllEditorsButActiveAction` branches on
whether the event carries an `EditorWindow`, and only one of the two branches can
reach a pinned tab. With a window — the tab's context menu, or anything invoked
with the editor focused — it calls `EditorWindow.closeAllExcept`, which skips
pinned files itself, so `OtherTabsScope` names no targets there and the action
runs exactly as the IDE ships it. With no window — **Window | Editor Tabs | Close
Others** while the focus is in a tool window, or the same action from Find
Action — it closes each sibling through `FileEditorManagerEx.closeFile`, which has
no pin check at all, and that is the branch the guard exists for. Both halves of
that claim are read off the platform by a test rather than trusted.

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

The module is left at the default `loading="optional"`, and the temptation to
write `loading="required"` is worth naming, because it looks like the stricter
choice and is the wrong one. `required` fails the whole plugin wherever the
module's dependencies do not resolve — and the third row of that table is a
process where they never do, by design. Under it, `runIdeSplitMode` gives you:

```
Module intellij.platform.monolith    is not enabled because dependency intellij.platform.frontend is not available
Module tech.florin.pinguard.frontend is not enabled because dependency intellij.platform.frontend is not available

WARN - #c.i.i.p.PluginManager - Problems found loading plugins:
  Plugin 'PinGuard' (tech.florin.pinguard) has dependency on 'intellij.platform.frontend' which is not installed
```

The first two lines are the split working. The platform disables its own
`intellij.platform.monolith` for exactly the same reason and says nothing further
about it; only PinGuard escalates, and the backend then refuses to load it at all.
That is not cosmetic — in a real remote session the host serves plugins to the
client, so a plugin the host will not load is a plugin the client never receives.

What `required` was meant to catch — PinGuard installed, enabled and inert — is a
monolith-only worry, and cannot happen there: `intellij.platform.frontend` always
resolves in a monolithic IDE. The way to actually get it wrong in a monolith is
the module jar name below, which `PluginDescriptorTest` asserts directly.

Every bundled JetBrains plugin with a frontend module is packaged this way.
`intellij.editorconfig.frontend` declares the same dependency with no `loading`
attribute; compare its descriptor if this ever looks doubtful again.

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

Some of those tests exist to catch the platform changing under the plugin rather
than to check PinGuard's own logic. One asserts that the Close
Left/Right/Unmodified/Readonly family still excludes pinned tabs by itself, which
is the reason the plugin no longer guards those four at all. One asserts that
PinGuard is really on the `virtualFilePreCloseCheck` extension point and that
`closeFileWithChecks` really honours its veto. Two more read the pair of calls
Close Others branches between — `EditorWindow.closeAllExcept`, which skips pinned
files, and `FileEditorManagerEx.closeFile`, which does not — because that
asymmetry is the entire reason `OtherTabsScope` guards one branch and stands
aside on the other, and either half of it changing should fail here rather than
quietly leaving a close path guarded for nothing or unguarded for real. All of
them fail loudly rather than degrading quietly, which is the whole point of
moving that reflection out of the plugin and into a test.

The one thing no unit test can settle is whether GitHub accepts a URL of a given
size, since that depends on the cookies in the reporter's browser. The 4000-byte
cap was measured against the live service with a realistic header set; changing it
means measuring again, logged in, with a genuinely deep exception.

## Verifier caveats

`verifyPlugin` runs the Plugin Verifier against the latest release build of every
IntelliJ IDEA major from `pluginSinceBuild` onwards and reports the plugin
compatible with each. `pluginVerification.ides` in `build.gradle.kts` selects that
set from the declared compatibility range rather than naming builds, so a major
released after the last edit to that file cannot go unverified;
`./gradlew printProductsReleases` prints the same range with the pre-release
channels still in it, so its newest lines are often the EAP or RC build of a major
rather than the stable one the verifier takes. Widening `types` there to the other
JetBrains IDEs is what would turn "installs everywhere" from a statement about the
dependency into a measured result.

Because the set is resolved afresh, `release.yml` runs `verifyPlugin` against
whatever majors exist on the day: a platform released since the last green build can
block a release. Pinning `select { untilBuild = … }`, or naming builds with
`create(...)` for that one release, is the way past it.

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
