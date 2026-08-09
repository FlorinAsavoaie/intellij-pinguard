# Working in this repository

For what PinGuard does, see [README.md](README.md); for how it is put together and
why, [ARCHITECTURE.md](ARCHITECTURE.md); for building and releasing,
[CONTRIBUTING.md](CONTRIBUTING.md). `./gradlew test` runs everything.

What follows is the one convention those three do not cover.

## Comments

This codebase comments heavily — 38% of the main sources are comment lines — and it
earns that by never spending a line on what the code already says. A comment here
exists because a reader who understands Kotlin and has the file open would still
guess wrong. Almost all of them are about the IntelliJ platform, which cannot be read
from this repository at all.

### KDoc or `//`

KDoc says what a declaration **is** and what its contract is. `//` says why **this
line** reads the way it does, and sits immediately above that line. A hazard
belonging to one statement goes at the statement, never in the enclosing KDoc.

### The first sentence

Exactly one sentence, saying what the thing is or does. A noun phrase for data,
constants and enum entries; a third-person verb for functions and classes — `Wraps…`,
`Decides…`, `Picks out…`, `Bridges…`. Nothing else may be in it. Rationale, the
rejected alternative, platform behaviour and failure modes all follow after a blank
`*` line.

```kotlin
/**
 * Picks out the pinned tabs from a close request.
 *
 * Takes [CloseTarget]s rather than bare files because a pin belongs to one editor
 * window rather than to the file: whether it stands in a close's way depends on
 * which window that close is aimed at.
 */
```

Paragraph order is fixed: summary, then rationale, then deliberate deviations, then
`Public because …`, then `@param`/`@return`. Every `public` declaration ends its
prose by saying why it is public — `Public because the service container instantiates
it reflectively.` Constructor parameters are documented from the enclosing KDoc with
`[name]`; `@param` appears only where prose cannot carry it (10 times in the whole of
main). Deliberate omissions get a `-` bullet list under a colon line, as in
`Left out on purpose:` and `Two ways a guarded action differs from the one it
displaced, both deliberate:`.

### When a declaration gets none

When the name and the signature already say everything and nothing is surprising:
every file-level `private val LOG`, overrides that only forward, private constants
whose name is the unit. This is not "overrides go undocumented" — `update`,
`dispose` and `canCloseFile` all carry KDoc, because each has a deviation to state.
The moment a constant needs justifying it gets a full block; compare bare
`MAX_TITLE_CHARS` with the nine lines on `MAX_URL_BYTES`.

### Length

One line, or four to seven. Median six. Longer than ten is exceptional — twelve
blocks in the repository, each earning it. Wrap comment prose, indent and `* ` or
`// ` included, at about 80 columns and never past 88, while code runs to 131. The
one line that overruns is a fully qualified class name with nowhere to break.

Inline runs in main sources are **never a single line** — nothing in main is worth
exactly one line of explanation and no more. Tests are looser and one-liners are
normal there. Split a multi-topic run with a bare `//`, never a blank line.

### Inline comments

Always a full line above the code, at its indentation. There are no trailing
end-of-line comments anywhere. Most open with the verdict, a colon, then the reason.
They carry the rejected alternative, platform behaviour invisible from the call site,
what breaks if the line goes, why a branch is deliberately empty, or an invariant the
code cannot express:

```kotlin
// isFilePinned throws when the file is not open in that window, which is
// the normal case for splits, so check openness first.
```

### Naming things

`[Brackets]` when the name resolves from this file — an imported type, a sibling
declaration, a parameter. `` `Backticks` `` when it would not: an action id, an
unimported platform member, an annotation, an extension point. Backticks are correct
in `//` comments; `[links]` never appear in one.

### Tests

Test names are backticked lowercase declarative sentences stating a fact, often with
a trailing `, because` or `rather than` clause carrying the reason — ``closeAllExcept
is what makes standing aside in a window safe``. None say *should* or *must*; none
begin with *test*. No `@Test` function carries KDoc: its name and its opening comment
do that work.

A test body's opening `//` says why the test exists, never what the code does. A test
that reads a platform contract names the plugin code that must change the day it
fails.

Roughly four assertions in ten carry a message, and it states the **consequence** of
failing rather than the expectation — the expectation is already in the test name and
the expected value. Lowercase, no terminal period, diagnosis and consequence joined
by `;` or `, so`: `"'$id' was never wrapped, so nothing was proven"`. Ordinary steps
assert bare; the assertion carrying the point gets the message. Setup checks are
prefixed `precondition:`, and a check that exists to stop the test passing vacuously
says so.

### Comments versus ARCHITECTURE.md

A comment answers *why does this line read the way it does* for someone with the file
open: the mechanism, in the fewest sentences that make the line unsurprising, at the
statement that depends on it. ARCHITECTURE.md answers *why is the plugin shaped this
way*, and is the only place carrying a rejected alternative worked through in full,
the history of what used to be there, the user-visible symptom, enumerations across
processes or actions, verbatim log output, and the provenance of a measured constant.
A comment growing a second paragraph to hold an alternative you rejected is holding
an ARCHITECTURE.md paragraph.

The conclusion is written in both, in each one's own words, and neither defers to the
other. No Kotlin file references ARCHITECTURE.md, README.md or CONTRIBUTING.md — not
once. Where prose would cite a document, a comment cites a test: `` `GuardedCloseActionTest`
holds the platform to that.``

### Register

Third person, present indicative, describing what is true now; counterfactuals take
`would`. No first person, no addressing the reader, no contractions, no hedging —
limits are stated flatly rather than softened. Em dashes are spaced. British
spelling. The signature move is **"X rather than Y, because Z"**: name the thing you
would otherwise have done, then the reason, consequence last.

> Copied by hand rather than by extending `AnActionWrapper`, which would also copy
> the delegate's shortcut set.

### Never

- `TODO`, `FIXME`, `XXX`, `HACK`, commented-out code, dates, ticket numbers, names,
  attributions, file or licence headers. None of these appear in the repository.
- Trailing end-of-line comments, and `/* … */` blocks that are not KDoc.
- `This class…`, `This method…`, `Returns…` as an opening.
- A comment that restates the line below it, or that stops before giving a reason.
- Given/When/Then or Arrange/Act/Assert markers, and section banners. Blank lines
  separate the phases of a test.
- `obviously`, `simply`, `just`, `note that`, `probably`, exclamation marks, emoji.
