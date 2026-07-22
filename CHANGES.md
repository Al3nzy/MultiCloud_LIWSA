# What changed in this batch

## Latest round: workload realism + a safety net (read this first)

**The problem:** after the WOA fix, the sweep ran fast but the results
weren't meaningful — makespan spread between best and worst algorithm was
2.1% at 1,000 tasks and 0.04% at 100,000, versus **119.6%** in this same
codebase's own `ExperimentDemo` output at 1,000 tasks. Root cause: the
20%-utilization workload had so little contention that which algorithm did
the scheduling barely affected the outcome — fast, but scientifically
uninteresting.

**Fix 1 — `ScalabilityDemo.java`:** arrival rate is now a *fixed*
10 tasks/second at every tier (matching `FullDemo`/`Demo`'s own convention:
1,000 tasks over a 100-second window = 10/sec), instead of a utilization
target. This reproduces `ExperimentDemo`'s own ~580%-of-capacity contention
level throughout, so scheduling quality matters the same way it does in your
other results. The 1,000-task tier should now closely resemble
`ExperimentDemo`'s single-run numbers.

**Fix 2 — timeout/skip safety net, also in `ScalabilityDemo.java`:**
restoring that contention at 100,000 tasks can make `decode()` slow again in
ways that aren't fully predictable per algorithm. Every (algorithm, task
count) run now has a wall-clock budget (`simulation.perAlgorithmTimeoutSeconds`
in config.properties, default 600s = 10 min). Exceeding it skips that one
run (clearly logged, no CSV row, no chart point — not a crash) and the sweep
continues; no single algorithm can block the rest of the run again,
regardless of cause. `ChartRenderer.java` was updated to draw a gap for a
skipped point instead of a misleading drop to zero.

**Fix 3 — `WoaTaskSchedulingAlgorithm.java`:** added the same kind of
progress heartbeat LIWSA-Task already has, plus a load-balance diagnostic
(how many tasks landed on the single busiest resource) printed alongside it
— so if anything is slow again, you'll see real evidence of why instead of
another guess.

**Confirmed again: `LiwsaTaskPlanningAlgorithm.java` and
`LiwsaTaskMLPlanningAlgorithm.java` are still byte-for-byte unchanged** —
diffed against the copies you already have, zero differences. Nothing in
this round touches them.

**New config key:** `simulation.perAlgorithmTimeoutSeconds=600` in
config.properties.

---

**Correction (after real-world testing):** the first version of
`ScalabilityDemo` sized its synthetic workload's arrival window using a flat,
guessed rate (50 tasks/second). That turned out to be about **29x** more
than the default 12-resource pool can actually sustain, at every tier. All
four algorithms share the same `decode()` logic, which schedules each task
by scanning backward through its resource's growing event list for a gap —
an oversubscribed workload makes that list (and that scan) grow without
bound. `ScalabilityDemo.java` below now sizes the arrival window from the
resource pool's *actual* aggregate MIPS capacity and a conservative target
utilization (20%) instead of a guessed constant. This is a data-generation
fix inside `ScalabilityDemo` only — it does not touch `decode()` or any
other shared algorithm logic, and the two algorithm-level fixes further down
(`solitaryMove`, the OLS training cap) are unaffected and still correct.

Drop these files into the project at the same relative paths (they overwrite
the originals). All 8 files:

## 1) Config warning — confirmed resolved, README updated
- `config.properties` was already correctly moved to `src/main/resources/`
  (verified: only one copy exists, and the bundled `.classpath` already lists
  that folder as a source folder).
- `README.md`: removed the two now-stale callouts describing the old
  `src/main/java` location, fixed the Project Structure diagram (it still
  showed `config.properties` under `src/main/java`), and added the missing
  `src/main/resources` line to the Eclipse "source folders" checklist.

## 2) RequiredJars — now documented, one mismatch flagged
- `README.md`: added a tip pointing at `RequiredJars/` as a shortcut to the
  manual jar-download table, **and flagged that `RequiredJars/opencsv-5.0.jar`
  is the wrong version** — this project is built against OpenCSV **5.9**
  (see `modules/cloudsim/pom.xml` and the `.classpath` entry). Swap that one
  jar if you use the bundled folder as-is.

## 3) Scalability sweep (100 → 1,000 → 10,000 → 100,000 tasks, one CSV + 4 charts)
- **`ScalabilityDemo.java`** (new): runs all four algorithms once each at
  every task count in `simulation.taskCountSweep`, appends every result to
  one combined `results/scalability-sweep.csv` (already has a `numTasks`
  column via the existing `SchedulingMetrics` format — no new CSV code
  needed), and renders four "metric vs. task count" line charts (makespan,
  cost, energy proxy, algorithm wall-clock runtime — one line per algorithm
  each) to `results/charts/`. Full reasoning is in the class's own Javadoc.
- **`ChartRenderer.java`**: added `renderLineChart(...)` — a multi-series
  line chart with point markers, a legend, and an optional log10-scaled
  x-axis (used here since 100→100,000 spans 3 orders of magnitude; a linear
  axis would crush the first three tiers into the left edge). The existing
  `renderBarChart(...)` is untouched, so `FullDemo`'s own charts are
  unaffected.
- **`SimulationConfig.java`** / **`ConfigLoader.java`**: added the
  `simulation.taskCountSweep` key (comma-separated ints).
- **`config.properties`**: added `simulation.taskCountSweep=100,1000,10000,100000`
  (and tidied a leftover editing note on the numTasks line).
- **`LiwsaTaskPlanningAlgorithm.java`**: fixed `solitaryMove()` recomputing
  the same per-individual Hamming distance/vote weight on every one of the
  `numTasks` gene positions instead of once per call — an accidental
  O(numTasks) redundancy (O(numTasks² × populationSize) overall). Fixed to
  compute it once per call. **Does not change any algorithm's output for a
  given seed** — same numbers, computed once instead of numTasks times —
  only its speed. This is what makes 10,000/100,000-task runs practical
  instead of effectively never finishing.
- **`LiwsaTaskMLPlanningAlgorithm.java`**: capped the OLS warm-start's
  training-row budget (`numTrainingSamples × numTasks`, previously
  unbounded — 40,000,000 rows at 100,000 tasks, likely an
  `OutOfMemoryError`). Behaviour at the original ~1,000-task scale is
  unchanged (400,000 rows was already under the new cap); only large task
  counts are affected, where the sample *count* scales down instead of
  memory scaling up.

## 4) WOA-specific fix (the actual cause of the 100k stall)

**Confirmed: `LiwsaTaskPlanningAlgorithm.java` and `LiwsaTaskMLPlanningAlgorithm.java`
are byte-for-byte unchanged from the previous batch** (diffed against the
copies you already have — zero differences). Only `WoaTaskSchedulingAlgorithm.java`
changed in this round.

**Root cause:** `decode()`/`findFinishTime()` in `WoaTaskSchedulingAlgorithm.java`
are structurally identical to `LiwsaTaskPlanningAlgorithm`'s (same backward-scan
scheduler) — so they aren't the difference. The actual problem is upstream:
WOA represents each whale as a *continuous* position, and its
encircling/search/spiral formulas can push that position well outside
`[0, numResources-1]` (a coefficient up to ±2.0 times a delta that can itself
be a couple of times the range). The old code (`Math.max(lo, Math.min(hi, v))`)
clamped every one of those overshoots flat to exactly resource 0 or exactly
resource 11 — piling a disproportionate share of a whale's task assignments
onto those two resources specifically. A resource with a large share of all
tasks means a long backward scan for every new insertion, which is what made
WOA — and only WOA — blow up as task count grew, even though its scheduler
code is the same as the other three algorithms'.

**Fix:** replaced the hard clamp with reflection (`reflectIntoRange`) —
an overshoot bounces back into range instead of pinning to the boundary, so
a position just past the edge lands just inside it rather than being thrown
all the way to 0 or 11. This **does change WOA's resulting genotypes**
(unlike the `solitaryMove`/training-cap fixes, which were pure
redundant-computation removal with identical output) — it's a legitimate,
literature-defensible alternative to hard clamping for exactly this
boundary-violation problem, but it is a behavior change to this one
baseline, so flagging it clearly rather than folding it in silently.



Run it: `mvn -q -pl modules/liwsa-multicloud exec:java -Dexec.mainClass=org.liwsa.multicloud.ScalabilityDemo`
