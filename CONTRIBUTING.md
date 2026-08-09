# Contributing to PinGuard

For what PinGuard does from a user's chair, see [README.md](README.md). For how it
is put together and why, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Prerequisites

JDK 21.

## Building and testing

```bash
./gradlew test            # unit and in-IDE tests
./gradlew buildPlugin     # -> build/distributions/pinguard-0.0.0.zip
./gradlew verifyPlugin    # IntelliJ Plugin Verifier
./gradlew runIde          # sandbox IDE with the plugin installed
./gradlew runIdeSplitMode # backend + JetBrains Client, where the frontend module runs
```

CI runs `test`, `buildPlugin` and `verifyPlugin` on every pull request. Every build
that is not a release is version `0.0.0`, which is why the zip above is named that
way: there is nothing to bump before a release and nothing to edit in `plugin.xml`.

## Making a change

- `./gradlew test` and `./gradlew verifyPlugin` should both be green before you
  open a pull request.
- Read [ARCHITECTURE.md](ARCHITECTURE.md) first if you are touching the guards, the
  module layout or the crash reporter. Some of the tests are there to catch the
  platform changing under the plugin rather than to check PinGuard's own logic — if
  one of those fails, it is telling you something real.
- Say what the change means for users in the pull request. There is no changelog
  file: the body of the GitHub release is the change notes, and it is written from
  the pull requests that went into the release.

## Releasing

Maintainers only. Releasing needs a GitHub environment named `Marketplace` carrying
four secrets: `PUBLISH_TOKEN` from your
[Marketplace tokens](https://plugins.jetbrains.com/author/me/tokens), and
`CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD` from a signing key
made as [plugin signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
describes. Without the signing three, `publishPlugin` refuses to upload rather than
quietly shipping an unsigned plugin. `releaseDraft` additionally needs an
authenticated `gh` and an `origin` remote.

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
the signed archive back to the release.

Four things to know:

- Marketplace documents change notes as accepting simple HTML, so keep a release body
  to headings, paragraphs, lists, links, emphasis and inline code — a table, an image
  or a collapsible block is outside what is documented.
- A pre-release version picks its own Marketplace channel: `1.2.3-beta.1` goes to
  `beta`, which only people who added that channel's URL receive. `releaseDraft` marks
  such a draft as a pre-release on GitHub to match, and `release.yml` refuses to
  publish if the two ever disagree.
- `build/release-notes.md` is read by every build that finds it, so a file left over
  from a release becomes the change notes of your next local build. `clean` removes
  it, along with any notes you were about to release.
- JetBrains requires the **first** publication of a plugin to be uploaded by hand, so
  none of the above applies to it — following it would tag and publish a release that
  fails at the last step. Instead write the notes into `build/release-notes.md`
  yourself, build the signed archive with the signing variables exported, and upload
  it on the plugin's Marketplace page:

  ```bash
  ./gradlew signPlugin -PpluginVersion=1.2.3   # -> build/distributions/pinguard-1.2.3-signed.zip
  ```

  Then tag that commit, so `releaseDraft` has something to measure later releases
  against — it reads git tags and GitHub releases, and can see nothing that exists
  only on Marketplace.

## License

By contributing you agree that your contributions are licensed under the Apache
License, Version 2.0, the same as the rest of the project. See
[LICENSE](LICENSE) and [NOTICE](NOTICE).
