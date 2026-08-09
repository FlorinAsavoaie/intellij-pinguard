# PinGuard internals

How the plugin is put together, and why. For building and contributing, see
[CONTRIBUTING.md](CONTRIBUTING.md); for what PinGuard does from a user's chair, see
[README.md](README.md).

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
| The same shortcut on a terminal moved into the editor | `Terminal.CloseTab` | no | yes — see below |
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

`CloseContent` is not only about editor tabs, and that matters more than it looks.
It closes whatever `CloseAction.CloseTarget` its data context names, and a tool
window names one too — <kbd>Cmd</kbd>+<kbd>W</kbd> with the focus in the Project view
hides that tool window. Such an event carries a `VirtualFile` of its own, the one
selected in the tree, which may well be pinned in the editor. `SingleTabScope`
therefore requires an `EditorWindow` in the context as well as a file: that is the
platform's own signal that a close is aimed at an editor tab, and it is the
difference between guarding a pin and answering a tool-window gesture by closing a
tab nobody aimed at.

The last row is the one worth knowing about. Those four actions protect pinned
tabs already: `CloseEditorsActionBase` consults `EditorComposite.isPinned` before
offering a tab up, so a pinned tab is never among the files they would close.
PinGuard guarded them anyway at first, through reflection on a non-public
platform method — a `setAccessible` call, on the most breakable dependency in the
plugin, spent re-refusing closes that were never going to happen. That guard and
its reflection are gone. What replaced it is a test that fails the day the
platform stops excluding pinned tabs, which is the day those four would need
guarding again.

Four properties fall out of guarding at the action layer:

- **Closing a project or the IDE is never interrupted.** Neither dispatches these
  actions, so this is structural rather than a condition the plugin has to get
  right.
- **A refused close still closes everything else.** Cancelling a "Close All"
  outright because one tab is pinned would strand the tabs you did mean to close,
  so PinGuard narrows the action to its unpinned tabs and performs that itself.
- **A confirmed close is performed here too** — see below.
- **"Close All Unpinned Tabs" is untouched.** It already leaves pinned tabs alone.

### Why a confirmed close is not handed back

The obvious shape for the confirmation setting is "ask, and on a yes let the
platform's own action run" — the close is exactly the one the user asked for, so
why reimplement it. That shape closes the wrong tab, and the reason is worth
writing down because nothing about it is visible from PinGuard's side.

The platform's close actions do not close what their event says. `CloseContent` is
`CloseAction`, which performs whatever `CloseAction.CloseTarget` its data context
names; for editor tabs that is the tabs component, and it closes
`JBTabsImpl.getTargetInfo()` — `popupInfo ?: selectedInfo` — read at the moment the
action runs. `popupInfo` is the tab a context menu was opened on, recorded by
`TabLabel.handlePopup`, and it is cleared from a `SwingUtilities.invokeLater` queued
when the menu comes down. Swing hides a menu *before* firing the item chosen from it
(`BasicMenuItemUI.doClick` calls `clearSelectedPath()` and then `doClick(0)`), so
that clearing is already queued when the action starts and normally runs after it
finishes. The right-clicked tab is what closes, and everything works.

A modal dialog pumps the event queue. So a guard that asks — and only one that asks;
blocking never puts a dialog up — runs that clearing in the middle of the close, and
the action it then hands the event back to closes whichever tab is selected by now.
Right-click a pinned tab with a different tab in front, confirm, and the tab you were
looking at closes instead.

The event PinGuard reads is not stale, which is why the dialog names the right file:
`CommonDataKeys.VIRTUAL_FILE` in a tab menu comes from `TabLabel`, which answers from
its own tab's composite, and the popup's `PreCachedDataContext` froze it when the menu
opened. The staleness is entirely on the platform's side, in state PinGuard has no
business reaching into. So the rule is the narrow one: **the original runs only where
PinGuard has said nothing to the user.** `CloseOutcome` exists to carry that
distinction — `UNOPPOSED` and `CONFIRMED` are both "yes", and only the first of them
can be delegated.

`GuardedCloseActionTest` holds all three halves of this: that a confirmed close from a
tab menu takes the tab the menu was on, that the same close through a switched-off
guard still works (so the reproduction is not the thing that is broken), and that one
turn of the event queue is what loses the aim.

A file counts as pinned if any editor window in any open project has it pinned, so
a tab pinned in one split is protected from being closed in another.

### Terminals in the editor

Guarding `CloseContent` does not cover <kbd>Cmd</kbd>+<kbd>W</kbd> in a terminal
that was moved into the editor: the terminal plugin declares a close of its own
with `use-shortcut-of="CloseContent"` and promotes itself past it, so that action
is what runs. `Terminal.CloseTab` is therefore guarded too, on the same
`SingleTabScope`.

`Terminal.CloseSession` — the terminal's <kbd>Ctrl</kbd>+<kbd>D</kbd> — is left
alone. It only writes EOF to the shell; the tab that follows is closed by
`TerminalViewFileEditor` when the session terminates, programmatically, so no
action-layer guard can see it.

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
| `PinnedCloseGate` | The whole decision — allow, block, or ask — and the prompting when the answer is "ask". Its `CloseOutcome` keeps "nothing was in the way" apart from "the user was asked and said yes", which is what stops a confirmed close being handed back. |
| `PinGuardState` | The two settings, in the shape they persist in. |
| `PinGuardSettings` | Application-level persisted settings. |
| `PinGuardConfigurable` | The settings page under **Editor \| PinGuard**. |
| `PinnedFileSelector` | Which tabs in a close request are pinned, and where. |
| `PlatformPinnedFiles` | Walks the IDE's editor windows, per split or across every project. |
| `CloseActionScope` | Which tabs a given close action is about to close. One implementation per action family. |
| `GuardedCloseAction` | Wraps a platform close action and applies the gate. Two subclasses carry the marker interfaces the wrapped actions have. |
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
That is where the per-split behaviour, the project-wide fallbacks, the narrowed
close and the confirmed one are all pinned down.

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
quietly leaving a close path guarded for nothing or unguarded for real. One more
reads `JBTabsImpl.getTargetInfo` falling back to the selected tab after a turn of
the event queue, which is the whole reason a confirmed close is performed by the
plugin rather than handed back. All of them fail loudly rather than degrading
quietly, which is the whole point of moving that reflection out of the plugin and
into a test.

The one thing no unit test can settle is whether GitHub accepts a URL of a given
size, since that depends on the cookies in the reporter's browser. The 4000-byte
cap was measured against the live service with a realistic header set; changing it
means measuring again, logged in, with a genuinely deep exception.

## Build and release mechanics

`runIde` is an ordinary monolithic sandbox. Split mode is deliberately *not*
configured on the `intellijPlatform` extension: those settings are conventions for
every task that has a sandbox, so setting them would silently turn `runIde` itself
into a backend plus a client. `runIdeSplitMode` configures itself and is the only
task that needs to.

CI runs `test`, `buildPlugin` and `verifyPlugin` on every pull request, and on
demand from the Actions tab, which is what keeps the compatibility claim in the
README true after a platform bump rather than at the moment someone last ran the
verifier by hand.

The version a release ships under is not stored in this repository at all: it comes
from the release tag, and `-PpluginVersion=` or the `PLUGIN_VERSION` environment
variable is how a build is told which one it is producing. Everything else builds
as `0.0.0`.

There is no changelog file either. The body of the GitHub release *is* the change
notes: `release.yml` writes it to `build/release-notes.md` and the descriptor
renders it to HTML from there. Since nothing records user-visible changes in the
repository any more, they have to be said in the pull request, where they can be
lifted into a release body.

Publishing the draft is left to a person because that is where the notes get
written. If you ever automate it, it must not be with the workflow's own
`GITHUB_TOKEN`, which triggers no workflow at all — `gh` with your own credentials
is fine. Nothing in the release job writes to this repository.

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
- `AnAction.setShortcutSet` is `@ApiStatus.Internal` as well, and taking a guard
  back off needs the displaced action handed to `replaceAction` empty — see
  `PinGuardActionGuards.surrenderShortcutSet` for what that buys. It is emptied
  by copying from a private action that has never held a shortcut, through the
  public `copyShortcutFrom`, whose whole body is the assignment this would
  otherwise be making by hand. The same call, made from inside the platform
  rather than from here.
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
