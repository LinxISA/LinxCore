# LinxCore Agent Bootstrap

This repository uses the pinned Superpowers workflow under
`tools/superpowers` together with the LinxCore domain skill.

At the start of every agent session, before responding or taking an action:

1. Read `tools/superpowers/skills/using-superpowers/SKILL.md` completely.
2. Follow its platform guidance in
   `tools/superpowers/skills/using-superpowers/references/codex-tools.md` when
   running under Codex.
3. Invoke every applicable Superpowers process skill before implementation.
4. For Chisel/LinxCore work, also invoke the installed `linx-core` domain
   skill. Its ownership, identity, verification, cross-model, and
   superproject-pin gates remain authoritative for hardware acceptance.

Direct user instructions and parent-repository instructions take precedence.
Do not edit the vendored Superpowers checkout in a LinxCore change. Update it
only by changing the pinned submodule revision and recording the upstream
release in `docs/flows/superpowers.md`.

If the submodule is not initialized, report that fact and continue with the
available LinxCore workflow; initialize it with:

```bash
git submodule update --init --recursive tools/superpowers
```
