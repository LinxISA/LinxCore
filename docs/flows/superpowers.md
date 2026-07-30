# Superpowers Harness Integration

## Contract

LinxCore pins [obra/superpowers](https://github.com/obra/superpowers) as the
repository-local agent workflow harness. The checkout is a nested git
submodule at `tools/superpowers`; release `v6.2.0` is pinned at commit
`3dcbd5c4b48e02263fbf4a3c01e3fe4f81d584d9` under the MIT license.

The Codex plugin registers the upstream skills with the harness. `AGENTS.md`
is the repository session-start integration point: it requires the
`using-superpowers` bootstrap before any response or action. Superpowers owns
the general development process (brainstorming, planning, TDD, debugging,
review, and branch completion). The installed `linx-core` skill remains the
domain authority for RTL ownership, BID/BROB semantics, model comparison,
generated-RTL evidence, and superproject closure.

This is intentionally a pinned upstream checkout rather than copied skill
files. Local copies would silently drift and, without a session-start
bootstrap, would not constitute a working harness integration.

## Initialize

From the LinxCore repository root, use the idempotent setup wrapper:

```bash
bash tools/setup_superpowers.sh
```

The wrapper performs the equivalent explicit steps:

```bash
git submodule update --init --recursive tools/superpowers
git -C tools/superpowers describe --tags --exact-match
codex plugin marketplace add ./tools/superpowers
codex plugin add superpowers@superpowers-dev
```

The tag command must report `v6.2.0`, and `codex plugin list` must report
`superpowers@superpowers-dev` as installed and enabled.

The plugin registration is stored in the user's Codex configuration, while
the marketplace source remains this repository's pinned checkout. Re-run the
wrapper after moving the checkout. The repository pin is retained so CI,
reviewers, and other supported coding agents share one auditable workflow
version.

## Acceptance

Static repository checks:

```bash
test -f tools/superpowers/skills/using-superpowers/SKILL.md
git -C tools/superpowers diff --quiet
git submodule status tools/superpowers
codex plugin list | grep 'superpowers@superpowers-dev.*installed, enabled'
```

A new clean agent session is the behavioral acceptance gate. The first user
message below must cause `brainstorming` to be invoked before code is written:

```text
Let's make a react todo list
```

For a LinxCore hardware request, the corresponding acceptance is that the
Superpowers process skill is selected first and `linx-core` is then selected
as the domain skill before repository inspection or modification.

The 2026-07-30 clean-session acceptance passed after plugin registration: the
exact todo-list prompt announced the required Superpowers workflow and loaded
`brainstorming` before any code or scaffold action. The same test failed before
plugin registration, proving that a submodule plus passive skill files is not
sufficient.

## Updating

1. Review the upstream release notes and license.
2. Move `tools/superpowers` to an annotated stable release, never an
   unrecorded floating branch head.
3. Update the release and commit recorded in this document.
4. Run the static and clean-session acceptance gates.
5. Commit the gitlink and documentation together.

Set `SUPERPOWERS_DISABLE_TELEMETRY=1` when the optional visual-companion
version request is not desired. Superpowers documents that this disables its
optional telemetry without changing skill behavior.
