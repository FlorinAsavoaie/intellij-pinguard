# Changelog

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-08-08

### Added

- Pinned tabs are excluded from Close, Close All, Close Others, and the rest of the tab context menu; only the tab's own
  close button can close a pinned tab.
- Close All still closes every unpinned tab, so a pin never strands tabs you meant to close.
- Optional setting (Settings | Editor | PinGuard) to ask for confirmation instead of blocking, prompted once per close
  action rather than once per pinned tab.
- Built for remote development and Code With Me. The guard ships as a frontend content module, so it loads in the
  JetBrains Client, where the editor tabs and the close actions actually live, rather than only on the backend the
  plugin is installed on. Not yet confirmed against a live remote session.
- Minimum supported build is 253 (2025.3). Frontend content modules are the supported way to reach a remote development
  client, and 2025.3 is where JetBrains documents them.

[0.1.0]: https://github.com/FlorinAsavoaie/intellij-pinguard/commits/v0.1.0
