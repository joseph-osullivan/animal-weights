# Animal Weights — Test Catalog

This directory contains the Tier-2 GameTests for the mod. Each test boots a real Minecraft dedicated server, sets up a scenario in-world, exercises a production handler, and asserts an outcome.

**Two tiers in total:**

- **Tier 1 — JUnit 5** at [`src/test/java/.../unit/`](../../../../../test/java/io/github/josephosullivan/animalweights/unit/). 4 files. Pure-int math and predicate logic that doesn't need a Minecraft server.
- **Tier 2 — NeoForge GameTest** (this directory). 73 tests across 12 test classes. Each test pairs with a JSON registration at `src/main/resources/data/animalweights/test_instance/<id>.json`.

Run them with:

```bash
./gradlew test                  # Tier 1 only (fast)
./gradlew runGameTestServer     # Tier 2 only (~10s server boot + run)
./gradlew integrationCheck      # both (what CI runs)
```

---

## Tier 1 — JUnit (4 tests)

Pure-int logic with no Minecraft dependencies. Fast, no server boot.

| File | Pins |
|---|---|
| `AnimalWeightAttachmentTest` | Clamp behaviour: `set(-3) → 0`, `set(99) → 8`, default value, table-driven parameterized cases. |
| `DeltaTableTest` | `DELTA_BY_CONDITIONS[n]` is `-2, -2, -1, 0, +1` for `n = 0..4` — pins the daily-eval reward curve. |
| `DropScalingMathTest` | `bonusFor(weight)` matches the accelerating curve `{0, 0, 1, 2, 3, 4, 6, 8, 11}`; clamps for negative + over-max inputs. |
| `SickStatePredicateTest` | `isSick(0) == true`; `isSick(1..8) == false`. |

---

## Tier 2 — GameTests by area

### Weight attachment (`WeightAttachmentGameTests`)

| Test | What it pins |
|---|---|
| `fresh_cow_has_default_weight` | A newly-spawned cow with no prior `set()` call reads back `WEIGHT_DEFAULT` (1). |
| `set_then_get_returns_same_value` | Set-then-get roundtrip — value stored equals value read. |
| `set_clamps_out_of_range_values` | `set(cow, -3) → 0`, `set(cow, 99) → 8`. |
| `weight_survives_nbt_roundtrip` | Set weight, serialize via `saveWithoutId`, load into a fresh cow — value survives. |

### Daily evaluation — base mechanic (`DailyEvalGameTests`)

The largest single test class. Each test builds a private pen at an isolated world location, spawns a cow, sets a baseline weight, then calls `DailyEvalHandler.runEvaluation(level)` directly to check the conditions-met → delta computation.

| Test | Conditions | Expected delta |
|---|---|---|
| `cow_in_perfect_pen_increases_weight` | 4 met (light, water, grazing, stretching) | +1 |
| `cow_in_bad_pen_decreases_weight` | 0 met | −2 |
| `cow_with_three_conditions_plateaus` | 3 met | 0 |
| `cow_with_two_conditions_decreases` | 2 met | −1 |
| `cow_with_one_condition_decreases_hard` | 1 met | −2 |
| `cow_at_weight_eight_in_perfect_pen_stays_at_eight` | upper clamp | (clamp at 8) |
| `cow_at_weight_zero_in_perfect_pen_climbs_to_one` | lower bound + climb | weight 0 → 1 |
| `cow_with_light_at_threshold_thirteen_fails_light` | brightness 13 < 14 | light=false |
| `cow_with_water_at_eight_radius_passes_water` | water within 8 | water=true |
| `cow_with_flowing_water_fails` | flowing water (not source) | water=false |
| `cow_adjacent_to_chicken_loses_stretching` | farm-animal crowding (chicken) | stretching=false |
| `cow_adjacent_to_zombie_keeps_stretching` | non-farm-animal nearby | stretching=true (zombie excluded) |
| `two_adjacent_cows_both_fail_stretching` | mutual crowding | both stretching=false |
| `cow_adjacent_to_pig_loses_stretching` | farm-animal crowding (pig) | stretching=false |

### BFS reachability — fence/space rules (`BfsReachabilityGameTests`)

| Test | What it pins |
|---|---|
| `cow_in_fenced_2x2_pen_loses_weight` | Fence-isolated cow can't count outside water/grazing. Reach = 4 < 6 → stretching fails. Met=2 → delta=−1. |
| `cow_on_top_of_fence_cannot_reach_outside` | BFS doesn't walk on top of fence posts (`isCollisionShapeFullBlock` floor check). Water 2 blocks outside the fence doesn't count. |
| `cow_blocked_by_other_animal_has_smaller_reach` | Other farm animals occupying neighbour cells block BFS expansion via the `getEntitiesOfClass` filter. |
| `cow_with_exactly_six_reachable_cells_passes_stretching` | `MIN_ROAMING_CELLS = 6` — exactly 6 walkable cells satisfies stretching. |
| `cow_with_five_reachable_cells_fails_stretching` | 5 walkable cells fails — strict threshold. |

### Water cauldrons + reachable-only water (`WaterCauldronGameTests`)

| Test | What it pins |
|---|---|
| `cauldron_inside_pen_counts_as_water` | A filled `Blocks.WATER_CAULDRON` adjacent to a reachable cell satisfies the water condition. |
| `empty_cauldron_does_not_count` | `Blocks.CAULDRON` (empty) does NOT satisfy water — only `WATER_CAULDRON`. |
| `sunken_cauldron_y_minus_one_counts` | A cauldron at `y-1` (flush with the ground) counts via the `neighbour.below()` check. |
| `water_source_across_fence_does_not_count` | Water on the far side of a fence is not adjacent to any reachable cell — water=false. |
| `water_source_inside_pen_counts` | Water inside the pen counts — water=true. |

### Drop scaling — base species (`DropScalingGameTests`)

| Test | What it pins |
|---|---|
| `kill_weight_1_cow_drops_baseline` | Weight 1 = no bonus (vanilla drops). |
| `kill_weight_4_cow_drops_three_extra_beef` | Weight 4 = +3 to beef + leather + XP. |
| `kill_weight_8_cow_drops_seven_extra_beef` *(legacy)* | Pre-curve test, still asserts a healthy-weight bonus. |
| `kill_weight_4_pig_drops_three_extra_porkchop` | Pig primary drops scale (porkchop, raw + cooked). |
| `kill_weight_4_sheep_drops_three_extra_mutton_and_wool` | Sheep mutton + any-colour wool scale. |
| `kill_weight_4_sheep_with_red_wool_dye` | `ItemTags.WOOL` covers every dye colour. |
| `kill_weight_4_cow_on_fire_drops_cooked_beef` | Cooked variants scale (fire-kill path). |
| `kill_weight_4_zombie_does_not_scale` | Non-target species unaffected. |

### Drop scaling — accelerating curve (`DropCurveGameTests`)

Pins the curve table `{0, 0, 1, 2, 3, 4, 6, 8, 11}`.

| Test | Weight | Expected bonus |
|---|---|---|
| `kill_weight_six_cow_drops_six_extra_beef` | 6 | +6 |
| `kill_weight_seven_cow_drops_eight_extra_beef` | 7 | +8 |
| `kill_weight_eight_cow_drops_eleven_extra_beef` | 8 | +11 |
| `xp_at_weight_eight_uses_same_curve` | 8 | XP +11 |

### Sick state (`SickStateGameTests`)

| Test | What it pins |
|---|---|
| `weight_zero_cow_gets_slowness_after_one_tick` | Slowness I applied within 2 ticks for weight-0 cow. |
| `weight_zero_cow_keeps_slowness_after_sixty_ticks` | Re-application path — effect persists past `SLOWNESS_DURATION_TICKS = 40`. |
| `weight_one_cow_has_no_slowness` | Healthy mob doesn't get slowness. |
| `weight_zero_zombie_not_affected_by_slowness` | Non-target species unaffected. |
| `weight_zero_cow_pair_breed_cancelled_and_both_reset` | Sick-parent breed cancel: both parents' `inLove` reset. |
| `weight_zero_cow_pair_breed_cancelled` | Both parents sick → cancel. |
| `mixed_weight_cow_pair_breed_cancelled` | One parent sick → cancel. |
| `healthy_cow_pair_breed_not_cancelled` | Two healthy parents breed normally. |

### Sick drop reduction (`SickDropReductionGameTests`)

| Test | What it pins |
|---|---|
| `sick_cow_drops_capped_to_one_per_primary` | Weight-0 cow: each primary drop stack count ≤ 1. (50% removal chance is intentionally not tested deterministically.) |

### Baby skip (`BabySkipGameTests`)

| Test | What it pins |
|---|---|
| `baby_cow_does_not_get_evaluated` | Baby in a bad-pen layout where an adult would go to weight 0 stays at weight 1. |
| `baby_cow_does_not_get_slowness` | Baby with weight 0 gets no slowness — `isBaby()` guard in `SickStateHandler`. |
| `adult_cow_in_same_conditions_does_get_slowness` | Control case — adult does get slowness when conditions match. |

### Species coverage

Each non-cow species has its own test class mirroring the cow tests:

| Class | What it covers |
|---|---|
| `ChickenGameTests` | Daily eval + drop scaling + sick state + chicken-specific crowding interactions. |
| `RabbitGameTests` | Daily eval + drop scaling + sick state; explicit guard that `RABBIT_FOOT` is NOT scaled. |
| `MooshroomGameTests` | Eval + drops + sick state — verifies `instanceof AbstractCow` in production handlers correctly picks up Mooshroom (which is a sibling of `Cow` under `AbstractCow` in MC 26.1, not a subclass). |

### Aggregator

`ModGameTests.java` — the `DeferredRegister<TestFunction>` aggregator. Every test function above is registered here, paired with a `data/animalweights/test_instance/<id>.json` file that gates the test's `max_ticks` timeout and structure template.

---

## Authoring conventions

- **Spec-first.** Each test asserts what the spec says the code *should* do, not what it currently does. The spec lives in this repo's `README.md` and the production handler javadoc.
- **Isolated locations.** Tests that need precise control over surroundings (daily-eval scenarios, BFS reachability) teleport their mobs to a private absolute world position so neighbouring test cells don't bleed conditions. Each test class picks a distinct `ISOLATION_BASE_X` (100k, 200k, …, 800k) so suites don't collide.
- **`Entity#snapTo`, not `moveTo`.** MC 26.1 renamed the position-set method.
- **Tier choice.** If you can write the assertion with pure-int helpers and no Minecraft classes, it's Tier 1. Otherwise Tier 2.
- **No-shared-state.** Tests use `helper.runAfterDelay(...)` or `succeedWhen(...)` for async assertions; no `Thread.sleep`.

## Known issues — follow-up needed

Five sick-state tests are currently marked **`required: false`** in their `test_instance/*.json` files. They still run and surface results in CI but do not block the build:

| Test | Symptom |
|---|---|
| `weight_zero_rabbit_has_slowness` | Handler doesn't apply `SLOWNESS` in ~30% of test-server boots. |
| `weight_zero_chicken_has_slowness` | Same. |
| `weight_zero_mooshroom_has_slowness` | Same. |
| `weight_zero_cow_gets_slowness_after_one_tick` | Same. |
| `weight_zero_cow_keeps_slowness_after_sixty_ticks` | Slowness expires before tick 60 in some runs — re-application path doesn't fire. |

The production `SickStateHandler` IS firing correctly in real gameplay (verified by manual playtest: slowness particles + slower movement visible on sick cows in-world). But inside the GameTest harness, `EntityTickEvent.Post` doesn't reliably fire for spawned test entities. Suspected causes:

- A test-entity flag in `helper.spawnWithNoFreeWill` that suppresses event firing for some run conditions.
- Tick-ordering race between `helper.succeedWhen` lambda execution and entity ticking in the same tick.

A proper fix likely requires one of:

1. Adding a test seam to `SickStateHandler` — e.g. a static `applySlownessIfSick(LivingEntity)` method tests can call directly to bypass the event-loop dependency.
2. Restructuring the tests around a tick-driver harness that pumps `EntityTickEvent` manually.
3. Migrating to a JUnit-with-bootstrap-MC pattern that can call handler methods directly with a real `ServerLevel`.

Until that work happens, treat these tests as smoke signals — green runs are useful confirmation, red runs are CI noise, not a regression. The handler's `isSick(weight)` predicate is also covered deterministically by Tier-1 `SickStatePredicateTest`, so the core logic isn't unverified.

### Fixed flake worth noting

`cow_blocked_by_other_animal_has_smaller_reach` had a related flake (entity chunk index lag after `snapTo`) which IS fixed deterministically via `helper.succeedWhen(...)` polling. That pattern is the recommended approach for any future tests that depend on entity positions reflecting recent `snapTo` calls.
