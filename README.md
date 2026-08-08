# PinGuard

An IntelliJ Platform plugin that keeps pinned editor tabs from being closed.

Pinning a tab is a statement that you want to keep it open, but the IDE will
still close it via **Close**, **Close All**, **Close Others**, the tab context
menu or the keyboard shortcut. PinGuard refuses those closes — or asks first, if
you prefer a safety net over a hard stop.

## Settings

**Settings | Editor | PinGuard**

| Setting | Default | Effect |
| --- | --- | --- |
| Protect pinned tabs from being closed | on | Master switch. Off means PinGuard does nothing at all. |
| Ask for confirmation instead of blocking | off | Show a confirmation dialog instead of refusing the close outright. |

When PinGuard asks, it asks once per close and lists every pinned tab that close
would have taken, rather than putting one dialog in front of you per tab.

## What is protected

| What you do | What happens |
| --- | --- |
| <kbd>Cmd</kbd>+<kbd>W</kbd> / <kbd>Ctrl</kbd>+<kbd>F4</kbd>, tab menu → **Close** | Guarded by PinGuard. |
| **Close All**, **Close Others** | Guarded by PinGuard. Every unpinned tab still closes. |
| **Close to the Left** / **Right**, **Close Unmodified**, **Close Readonly** | Already leave pinned tabs alone — the IDE does this itself. |
| **Close All Unpinned Tabs** | Untouched. It does exactly what its name says. |
| A terminal moved into the editor with **Move to Editor** and pinned | Guarded by PinGuard, including its own **Close Tab** — see below. |
| The tab's own **×** button | Not guarded — see below. |
| Closing a project, or quitting the IDE | Never interrupted. |

Two consequences worth knowing:

- **A refused close still closes everything else.** Cancelling a "Close All"
  outright because one tab is pinned would strand the tabs you did mean to close,
  so PinGuard narrows the close to the unpinned tabs and performs that instead.
- **A pin anywhere counts.** A file is pinned if any editor window in any open
  project has it pinned, so a tab pinned in one split is protected from being
  closed in another.

### Terminals in the editor

The terminal ships a **Close Tab** of its own, bound to the same shortcut as the
editor's **Close**, and it takes precedence when the terminal has focus. PinGuard
guards it too, so <kbd>Cmd</kbd>+<kbd>W</kbd> respects a pin on a terminal tab
just as it does on a file. Terminal tabs in the tool window are not editor tabs
and cannot be pinned, so nothing there changes.

One limit: ending the session still closes the tab. Typing `exit`, or
<kbd>Ctrl</kbd>+<kbd>D</kbd> at an empty prompt, shuts the shell down, and the
IDE then closes the tab programmatically rather than through any close action.
PinGuard is not consulted, by design — the same reason it never interrupts
closing a project.

### The tab's × button

That button needs no help from PinGuard: the IDE checks the pin itself and
refuses to close a pinned tab. With the `ide.editor.tabs.interactive.pin.button`
registry key on it un-pins the tab instead, and with it off it does nothing.
Either way, PinGuard leaves it alone.

## Compatibility

Declared for build 253 (2025.3) and later, with no upper bound. PinGuard depends
only on `com.intellij.modules.platform`, so it installs into every JetBrains IDE
that has pinned editor tabs — IDEA, PyCharm, WebStorm, GoLand, Rider, CLion,
RubyMine, PhpStorm.

Verified, however, only against IntelliJ IDEA: every build runs the JetBrains
Plugin Verifier against `IU-253.28294.334` and `IU-262.8665.258` and reports the
plugin compatible with both. The other IDEs are expected to work — nothing here
reaches past `com.intellij.modules.platform` — but "installs everywhere" is a
statement about the dependency, not a result anyone has measured.

### Remote development and Code With Me

PinGuard is built for these. The guard ships as a frontend module, so it loads in
the JetBrains Client — where your editor tabs and the close commands actually
live — rather than only on the backend the plugin is installed on. This layout
has not yet been confirmed against a live remote session; if you use PinGuard
that way, reports are welcome.

## Known gaps

- **Middle-click on a tab** is not guarded. Its handler was not located in the
  platform, so whether it needs guarding at all is unverified.
- **Drag-and-drop a tab out of a window** is not treated as a close.
- PinGuard works by wrapping the IDE's own close actions, which means depending
  on their action IDs. If one ever disappears, that single close path quietly
  reverts to stock IDE behaviour and PinGuard logs a line. The failure mode is
  "that path stops being protected", never a broken IDE.

## Diagnosing "why didn't my tab close?"

Every close involving a pinned tab writes one line to `idea.log` (**Help | Show
Log in Finder/Explorer**) at the default log level, naming the decision, your
settings, and the project whose window holds the pin — a pin in a project you are
not looking at is the usual answer:

```
INFO - #tech.florin.pinguard.PinnedCloseGate - BLOCK for [Main.kt] pinned in [acme-backend] (enabled=true, confirmInsteadOfBlock=false) -> allowed=false
```

For per-window detail, add `tech.florin.pinguard` under **Help | Diagnostic Tools
| Debug Log Settings** and reproduce.

## Reporting a crash

If PinGuard itself throws, the IDE's error dialog gets a **Report on GitHub**
button. It composes the issue locally — the stack trace, your IDE, OS and JVM
versions, and whatever you typed into the dialog — and opens GitHub's own new-issue
form with all of it prefilled. Nothing leaves the IDE on its own: you read the
report and submit it, or close the tab and nothing happens.

A prefilled issue has to travel in a URL, and GitHub's budget for one is smaller
than it looks, so a long stack trace is shortened to fit. It is shortened rather
than cut off: every `Caused by:` and `Suppressed:` header survives, because the
root cause of a wrapped exception is in the last one. Whenever anything was left
out, the untouched report goes on your clipboard and the issue body says so — so
the fix is one paste.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build and test the plugin, and
for why it is put together the way it is.

## License

Copyright 2026 Florin Asăvoaie.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE).

---

<a href="https://florin.tech">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/logo-dark.svg">
    <img src="docs/brand/logo-light.svg" alt="Florin — Sustainable Software" width="194">
  </picture>
</a>
