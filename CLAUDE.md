# CLAUDE.md

Project: Minecraft NeoForge 26.1.x mod (Java 25, ModDevGradle 2, Gradle 9.2).

Mod ID: `animalweights`. Base package: `io.github.josephosullivan.animalweights`.
Entry class: `AnimalWeights` (see `src/main/java/.../AnimalWeights.java`).

This file is conventions and active landmines. Every entry should trace back
to a shipped or playtest-caught bug — no speculative warnings.

---

## Quick commands

```bash
./gradlew build                # compile + package
./gradlew runClient            # interactive client (GUI)
./gradlew runServer            # dedicated server — verifies client/server isolation
./gradlew --stop               # kill daemons if anything hangs
```

---

## Testing

Two tiers, both wired through Gradle:

- **Tier 1 — JUnit 5** at `src/test/java/io/github/josephosullivan/animalweights/unit/`.
  Pure-data classes (state machines, NBT roundtrip, math, rules). Runs fast.
  Bound to `check`.
- **Tier 2 — NeoForge GameTest** at `src/test/java/io/github/josephosullivan/animalweights/gametest/`.
  Anything that needs a `ServerLevel`: entity behavior, save/reload, recipe
  firing. Boots a real MC server (~30s); not bound to `check`.

```bash
./gradlew test                 # Tier 1 only — fast
./gradlew runGameTestServer    # Tier 2 only — boots an MC server
./gradlew integrationCheck     # check + Tier 2 (pre-merge)
```

**Decision rule**: if the class compiles and exercises without `ServerLevel`,
it's Tier 1. Otherwise Tier 2.

Name tests as one-line behavioural sentences
(`cow_weight_matches_real_world_average`, not `testCowWeight1`) — they're
documentation as much as verification.

---

## Client / server isolation

- Client-only classes live under `io.github.josephosullivan.animalweights.client.*`.
  Never import one from common code — dedicated server crashes with
  `ClassNotFoundException`.
- Client-only event handlers: `@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)`.
- Run `./gradlew runServer` before merging anything that touches renderer or
  screen code.

---

## Logging

Use `AnimalWeights.LOGGER` (SLF4J-backed). INFO for meaningful state changes.
DEBUG for high-frequency.

---

## Vanilla feature currency

The mod targets the **latest stable Minecraft**. Before claiming "X isn't in
vanilla MC yet" or recommending a workaround, web-search to confirm — knowledge
cutoffs go stale.

---

## Commit and PR hygiene

- One feature = one branch = one PR. Branch slug mirrors the feature name.
- Commit titles ≤ 72 chars; body explains the why.
- Every feature-adding PR bumps `mod_version` in `gradle.properties` — single
  source of truth. The toml uses `${mod_version}` and is expanded by
  `processResources`.

---

## Updating this file

Append entries that trace to a shipped or playtest-caught bug. Keep them to
one or two lines. Do not add speculative warnings — the file's signal
degrades the moment it becomes a wish list.
