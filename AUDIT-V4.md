# Аудит перед V4 — 02.08.2026

Полный разбор кодовой базы: семь агентов по подсистемам, каждая находка проверена
отдельным скептиком, который пытался её опровергнуть по коду. Из 81 заявленной находки
выжило 70. Ниже сначала план, затем сами находки.

---

# LensALPR V4 Plan

Ranking principle applied throughout: (probability the defect fires on a real drive) x (probability the change helps). The follow engine currently manufactures false TAILs on ordinary commutes and the pipeline can silently stop reading the one car that matters — those outrank everything, including the most interesting accuracy ideas.

Cross-cutting prerequisite: several Group 2/3 items are gated on the replay corpus (AlprWorker.startCapture + captured-crop replay). Capture and ground-truth a corpus on the next two drives **before** starting Group 3 — it is the measurement instrument for 3.1, 3.5, 3.8, 3.9, 3.11.

---

## Group 1 — Must-fix defects (ship before the next long drive)

Ordered by risk x benefit. Batches A–D are file-cohesive; items within a batch should land together.

### Batch A — pipeline lifecycle (AlprWorker.kt / FrameProcessor.kt / VehicleRegistry.kt)

**1.1 inFlight lockout on discarded jobs** (AlprWorker.kt:348, FrameProcessor.kt:477) — CRITICAL, small.
Add an `onDiscard(job)` callback (or synthesize a failure `AlprOutcome` through the existing `onResult`) on every path where a job exits without recognition: the stale drop in `loop()`, spill-offer failure inside the executor task, and the `stop()` drain. Additionally clear `runtime.inFlight` the moment a job is *accepted* for spilling — the crop no longer represents live state, so the track must become croppable again immediately. This is the single worst confirmed failure (a persistent follower silently excluded from OCR for its whole track life).

**1.2 Recycled-thumbnail crash in drainOneSpilled** (AlprWorker.kt:292) — HIGH, trivial.
`if (thumbnail !== bitmap) bitmap.recycle()`. Do in the same commit as 1.1.

**1.3 spillExecutor never restarts after stop()** (AlprWorker.kt:205) — LOW alone, but same file: recreate the executor in `start()` when shut down, or move shutdown to a terminal `close()` called from onDestroy. Note: the verifier already corrected the finding's "strands inFlight" sub-claim — this path returns false and inFlight is cleared; only crop loss is at stake.

**1.4 Dead-track orphan guard** (VehicleRegistry.kt:106) — HIGH, small-medium.
At the top of `submit`, if no live runtime exists for `job.trackId` (a non-creating peek), recycle `job.thumbnail`, optionally merge the reading into an existing plate-keyed card, and return without recreating consensus/runtime. Design the check so the Group 2 cross-lens orphan pool (2.12) can hook in here later — this is the same code seam.

**1.5 adopt() session collision** (ScanActivity.kt:193, SpillStore) — MEDIUM, small.
Write a session nonce into the spill JSON; on adopt, remap mismatched-session trackIds to negative ids (reads still create plate-keyed cards, never touch live runtimes). Restores the documented SpillStore invariant.

### Batch B — follow engine evidence (TripTracker.kt / FollowEngine.kt)

**1.6 Turn multi-fire refractory** (TripTracker.kt:168) — CRITICAL, small. **Do first in this batch; 1.7, 2.13, 3.13 all depend on clean turn events.**
After emitting a TurnEvent, enter a refractory state until heading is stable (|delta| < ~5 deg/s for 3–4 s or 200 m), then resume accumulation; determine direction from the full manoeuvre total, which also makes U_TURN reachable again. Today one junction credits ~2 sharedTurns to every car behind, halving all turn thresholds.

**1.7 Contact/evidence rework** (FollowEngine.kt) — CRITICAL+HIGH cluster, medium. One coherent State-accounting change covering four confirmed findings:
- Contact as accumulated continuous segments (close segment when gap > REACQUIRE_GAP_MS; restart firstSeenMs/firstOdometerM; feed classify() accumulated totals). Require a minimum sighting count in the current segment before `follower` can set. (FollowEngine.kt:337)
- `returnedAfterFollowing` requires route evidence: gate the TAIL branch at line 455 on `sharedTurns >= 1` minimum, ideally on the reacquiring sighting crediting a gap turn. (FollowEngine.kt:455)
- Thread `capturedAtMs` through lastSeenMs, the gap test, creditTurns age, and recentSightings (timestamp-ordered insert); keep nowMs only for throttle/UI. The spill path delivers reads minutes late by design, so this is not theoretical. (FollowEngine.kt:335)
- `turnsDuringContact` per segment, and only counted when the car is actually seen after the turn. (FollowEngine.kt:265)

**1.8 rename() merge drops evidence** (FollowEngine.kt:193) — HIGH, small.
OR the booleans (blacklisted, ignored, follower, returnedAfterFollowing, movedWithUs), sum turnsDuringContact, max bestScore, carry odometer values paired with kept timestamps, persist blacklist for the target, re-run classify(), emit onUpdate. Include the recentSightings rekey one-liner (verifier correctly downgraded its impact, but it is a one-liner in the same function).

**1.9 Distinct-places quick gate** (FollowEngine.kt:459) — HIGH, immediate mitigation only.
Raise the metElsewhere TAIL branch to `sharedTurns >= 2` now. **Explicit sensitivity tradeoff:** a genuine multi-day tail with exactly one shared turn now needs a second — accepted, because the current rule fires TAIL on same-corridor commuters daily, and 2.13 (negative evidence) restores discrimination properly. The route-distinct clustering rework is Group 3 (3.14).

### Batch C — reading selection and fusion (ScanActivity.kt / VehicleRegistry.kt / PlateFusion.kt)

**1.10 Unify anchor + vote selection, add ownership geometry** (ScanActivity.kt:1332–1335, VehicleRegistry.kt:110) — HIGH, small-medium.
Note: the [pipeline] HIGH anchor finding and the [alpr] MEDIUM "plates[0]" finding are the **same defect reported twice** — one fix. Create a single `selectReading(outcome)` (filtered by minScore, maxBy recognitionScore) used by both the registry vote and `rememberPlatePosition`; anchor only when that reading exists; prefer plates whose projected fraction lies in [0,1] over the slack zone. In `submit`, require the chosen plate's centre to fall inside the track's own detector box mapped to crop coordinates (sourceRect/anchorRect are already on the job). **Quality-reduction risk:** the geometry filter can discard a genuine read when the detector box is sloppy — safeguard: when no plate passes the ownership test, fall back to accepting the best reading but do not set the anchor from it, and log the rejection so the filter can be tuned.

**1.11 PlateFusion vote integrity** (PlateFusion.kt:38–41, VehicleRegistry.kt:137) — HIGH, small. **Blocks 2.5 and 3.1 (same function).**
Return (reading, supportCount) from fuse, where supportCount = entries equal to or truncations of the fused text; pass that to upgradePlate instead of `history.size`. Require >= 2 reads at length L before a length-L cohort can outrank a length-(L−1) cohort of size >= 3. Kills the sticky one-hallucinated-character hijack.

### Batch D — alerting, session control, data integrity

**1.12 Telegram two-lane sender + async command handling** (TelegramBot.kt:94, 452; ScanActivity.kt:453) — HIGH, small+medium.
Dedicated executor for text alerts/broadcasts; separate lane for bulk media (clips, photos, reports). Defer `resendPendingClips` a few minutes after startup or run at lowest media priority. In handleAction, dispatch DB reads/builds/uploads to a worker executor with an immediate "building…" ack so the poller keeps fetching /stop and /pause.

**1.13 /unmute absolute semantics** (TelegramBot.kt:431) — MEDIUM, trivial. `mute:on` / `mute:off`; panel button stays a toggle.

**1.14 wipeAll rebind + engine reset** (ScanActivity.kt:1260) — HIGH, medium-small.
After store.startTrip: `follow.bindTrip(newId)` plus a new `FollowEngine.reset()` clearing per-plate state and reloading (now-empty) blacklist/ignored; route the wipe through ioExecutor.

**1.15 Session/recorder state hygiene** (ScanActivity.kt:529–546, VideoRecorder.kt:93) — MEDIUM cluster, small:
- haltSession/resumeSession only set sessionRunning and call applyPause(); gate updateParkedState + its broadcasts on sessionRunning.
- haltSession sets `segmentPlate = null` before stopping the recorder; in onClipFinished, a failed restart calls `follow?.noteVideoFinished(plate)` (terminal), otherwise a tailed car is never filmed again post-/go.
- VideoRecorder: delete only for codes with no usable output (INVALID_OUTPUT_OPTIONS, NO_VALID_DATA, ENCODING_FAILED, RECORDER_ERROR); for others check outputResults/file size and deliver the partial clip flagged. Evidence loss is unrecoverable; this is the app's primary artifact.

**1.16 SQL correctness batch** (TrackingStore.kt, HtmlReportBuilder.kt:76) — HIGH+MEDIUM, all small, one commit:
- `encounters(plate, sinceMs, limit)` with the window in SQL; fix encountersSince ordering for tripIds. (Report currently drops exactly the high-history vehicles.)
- `last_seen = MAX(vehicles.last_seen, excluded.last_seen)`; `ended_at = MAX(ended_at, ?)` (spilled reads currently rewind time and split encounters).
- canonicalPlate returns whether the incoming spelling won; only overwrite display_plate on the winning path (evidence reports currently print misreads).
- prune(): exempt `ignored = 1`; add `DELETE FROM turns WHERE t_ms < ?` and trips cleanup; `CREATE INDEX idx_turn_t ON turns(t_ms)`. (Privacy: complete long-term movement pattern currently never pruned.)

**1.17 requestedZoom snap to marketing ratios** (CameraCatalog.kt:159) — verifier UNCERTAIN, but ship anyway: small.
`max(derived, canonical)` for optical steps (3.0/5.0). The fix is monotone-safe: if the HAL already activated tele at 2.79x, requesting 3.0 costs ~7% FOV; if the HAL was refusing, this un-bricks both tele slots. The verifier is the arbiter either way — confirm on the first device run via the HUD before touching anything else camera-related.

---

## Group 2 — Accuracy work worth doing now

**2.1 submitJob reorder: gate before crop** (FrameProcessor.kt:394) — small. Decision inputs (frame thumbnail + sharpness) are unchanged, so no quality risk; pure allocation/CPU win at peak load.

**2.2 Back-to-back idle spill drain** (AlprWorker.kt:338) — small. `while (running && queue.isEmpty() && drainOneSpilled()) {}`; live jobs still preempt via the isEmpty check.

**2.3 Overflow eviction inversion** (AlprWorker.kt:239) — medium. Evict the weakest queued job into the spill store; enqueue the fresh high-priority crop; spill the incoming only when it is itself weakest. **Quality note:** the evicted job is delayed, deliberately — the orphan guard (1.4) makes its late delivery safe.

**2.4 Convergence priority under load** (FrameProcessor.kt:337) — small. When queueBusy: +60 for pendingCount == requiredMatches−1, +30 pending, +10 untouched; keep the current shape when idle. **Risk:** distant never-read cars get less engine time under saturation — bounded because idle behavior is unchanged and area/score terms remain.

**2.5 Truncated reads vote at aligned positions** (PlateFusion.kt:39) — small, after 1.11. Prefix votes head positions, suffix votes tail, abstain when ambiguous; fused result must still pass PlateFormats.

**2.6 correctToLatvian reorder** (PlateFormats.kt:59) — small. Correct only when generic(cleaned) also fails (or cap correction at cost 1 when generic passes). **Quality-reduction risk:** a genuine Latvian plate misread with confusables that happens to pass generic stays uncorrected — safeguard: PlateFusion voting still converges the spelling across reads, and the change must be validated on the replay corpus before shipping.

**2.7 Strict Latvia >= 2 letters** (PlateFormats.kt:23) — small, but see the verifier-critique section: the finding's fix is **incomplete** — P104 (4 chars) and RIGA25 (6 chars) still pass generic() after the regex change. Add the sign-shape blacklist (`^[APE][0-9]{2,3}$`) and consider a small city-name list for distance panels.

**2.8 Plate-ROI sharpness for narrow-crop gating** (FrameProcessor.kt:409) — small. Separate per-track bestPlateQuality from the plate rect at native resolution; keep the vehicle metric for wide crops/evidence. **Risk:** a miscalibrated new gate rejects readable narrow crops — safeguard: same decay semantics as today, and log q_plate vs plates!=0 correlation for one drive before enforcing.

**2.9 Tracker aspect-gate skip when missed > 3** (VehicleTracker.kt:78) — small. **Implement only the aspect skip.** The verifier's own IoU math refutes the closing-car scale scenario, and the finding's proposed `^k` scale relaxation is dead weight (IoU 0.25 binds first) — do not implement it.

**2.10 LensVerifier zoom-ratio check + failed setZoomRatio closes the gate** (LensVerifier.kt:194, CameraController.kt:265) — medium. Require CONTROL_ZOOM_RATIO within ~5% of the expected step ratio; treat a failed future as gate-closing. Matters as soon as any digital step is enabled.

**2.11 Deterministic capture-request keys, safe subset** (CameraController.kt:175–192) — small:
- CONTROL_VIDEO_STABILIZATION_MODE_OFF + LENS_OPTICAL_STABILIZATION_MODE_ON (guarded by availability) — geometry determinism for PlateRoi/overlay plus a real tele sharpness win.
- CONTROL_AE_TARGET_FPS_RANGE(30,30) — caps exposure at 33 ms, fixes tracker dt. **Risk:** dimmer daylight scenes shift to higher ISO noise — acceptable in the daylight scope; verify via CaptureResult echo.
- AF: CONTINUOUS_PICTURE -> CONTINUOUS_VIDEO now (strictly less aggressive hunting, minimal risk). Full fixed-focus is Group 3 (3.2).
EV bias, antibanding, NR/EDGE changes are Group 3 — they need on-device echo/A-B evidence.

**2.12 Cross-lens consensus survival** (VehicleRegistry.kt:222) — medium. On generation-change track loss, park TrackConsensus in a short-TTL orphan pool keyed by pending plate text + CarInfo; adopt into a fresh track when the first reading is similar and attributes do not contradict. **Quality-reduction risk:** wrong adoption imports another car's votes — safeguards: TTL of a few seconds, attribute-contradiction veto, requiredMatches still applies post-adoption, and every adoption logged. Prerequisite for 3.12 and 4.3; shares the 1.4 seam.

**2.13 Negative turn evidence (missed turns)** (FollowEngine.kt:431) — medium, strictly after 1.6 + 1.7. missedTurns incremented on pendingTurns expiry (prune loop **plus the periodic sweep** — the prune loop alone only runs at the next turn); classify on sharedTurns/(shared+missed) with a minimum sample. **Risk:** hang-back tails penalized by OCR-miss false misses — safeguards: ratio-with-min-sample rather than absolute misses, and run in shadow mode (reasons text only, no classification effect) for the first drives.

**2.14 Hygiene batch** — all small: SetupActivity wipe/usage off main (SetupActivity.kt:267); botHost.setIgnored via ioExecutor (ScanActivity.kt:1201); upgradePlate merge-on-collision like rename() (VehicleRegistry.kt:331); onUpgrade PRAGMA table_info guard (TrackingStore.kt:211); SpillStore temp-name + rename-last protocol and json-required poll (SpillStore.kt:111); scheduler pause-during-transition dwell fix + `!paused` guard on the settle-timeout branch (LensRotationScheduler.kt:78); JPEG encode moved outside the recordSighting transaction (TrackingStore.kt:410).

---

## Group 3 — Worth doing, needs a measurement first

**3.1 Per-character confidence fusion** (AlprResults.kt:116, PlateFusion.kt) — the highest-expected-value accuracy item, gated on one cheap verification. Note: the [alpr] MEDIUM and [ideas] HIGH findings are **duplicates of one item**. Step 1: log raw `result.json()` for known plates (replay path exists) to confirm the confidences[2..] layout and build the index mapping through PlateFormats.clean/corrections. Step 2: charScores on PlateReading, per-position vote weights with recognitionScore fallback. Ship only after replay shows exact-plate accuracy does not regress.

**3.2 Fixed focus (AF OFF + LENS_FOCUS_DISTANCE per step)** — config-flagged trial against the 2.11 CONTINUOUS_VIDEO baseline. **Quality-reduction risk is systemic** (wrong diopter softens every frame; S25U calibration is APPROXIMATE) — safeguard: per-step configurable diopter, A/B on the same route with FocusMetric distribution + read rate, instant revert flag.

**3.3 AE metering on the tracked vehicle box** (CameraController) — device A/B. Safeguards per the finding (renew on movement, cancel on track loss, vehicle box not plate box); additionally measure **detector recall**, not just OCR — biasing AE to one car can cost detections elsewhere in frame.

**3.4 Negative EV bias (−0.3…−1, configurable)** — pairs with 3.3; drop the bias when target metering is active. Risk: shadowed plates darker — that is why it is measured, not shipped blind.

**3.5 NR MINIMAL + EDGE OFF** — check availability, confirm CaptureResult echo, replay/live A/B on FocusMetric + per-character agreement.

**3.6 Antibanding OFF** — verifier UNCERTAIN whether AUTO ever costs anything in daylight; do the CaptureResult-echo check first, ship only if a floor is actually observed.

**3.7 gpgpu_workload_balancing_enabled** — A/B via reinitializeBlocking benchmark for latency + sustained thermal throughput.

**3.8 2x upscale of small narrow crops** — pure replay A/B. Note the verifier correction: the MIN_PLATE_PX-floor benefit claim was misreasoned (the floor gates *unanchored wide* crops; this change touches anchored narrow crops), so expect a smaller win than advertised.

**3.9 Conditional contrast stretch for dark narrow crops** — replay A/B only; depends on 2.8 exposing plate-ROI contrast; the closed engine's internal normalization may already cover it.

**3.10 Make/model/colour as second identity channel** (VehicleRegistry.kt:178, FollowEngine.canonicalKey) — implement in **shadow mode**: log would-merge events for several drives, manually audit for false merges (a false merge hides a follower — the failure the whole app exists to avoid), then enable with the strict conditions (distance <= 2, make+model agree, colour agrees or null, lastSeen windows overlap ~60 s).

**3.11 Posterior confirmation + recheck backoff** (FrameProcessor.kt:332, VehicleRegistry) — replay-gated, and note the verifier's correction: confirmed rechecks feed FollowEngine.lastSeenMs and the video-absence logic (thresholds as low as 5 s), so the proposed 10 s/30 s backoff would break clip-stop behavior. Either cap backoff at ~5 s or first decouple follow's presence signal from OCR recheck cadence. Measure engine-calls-per-confirmed-plate and misconfirmation rate.

**3.12 Recognition-demand feedback into lens dwell** (LensRotationScheduler.kt:30) — after 2.12 (votes must survive the switch or the pulled-forward tele step wastes its dwell). Bounded extension/advance; measure confirmations of tracks first seen under 40 px and verify plan coverage stays within bounds.

**3.13 Turn signatures (runs/direction patterns)** (FollowEngine.kt:448) — only after 1.6 lands and direction labels are trustworthy (today one slow junction fabricates exactly the "consecutive run" this rewards). Validate on logged turn streams first.

**3.14 Route-distinct place clustering** (proper fix for 1.9) — needs corridor data from own trips in TrackingStore; design against logged encounter-start variance from real drives.

---

## Group 4 — Explicitly deferred

**4.1 Multi-frame plate stacking / super-resolution** — large; the "almost-pure translation" premise is optimistic (narrow crops rescale with the vehicle box, so registration needs scale normalization + sub-pixel refinement); gain device-dependent. Revisit after 3.1 and 2.8 ship and the replay corpus can score it.

**4.2 Range-discipline track geometry into follow decisions** — large; per-track statistics cannot span the 120 s follower window while lens rotation resets the tracker; blocked on 2.12-style re-association plus speed-conditioned thresholds. Revisit after 2.12.

**4.3 Unreadable-plate (taped-plate) advisory** — real blind spot, but as specified it depends on cross-generation attribute re-association (2.12) to accumulate lifetime, and needs a staged taped-plate test. Also note the verifier's placement correction: the watch loop belongs in ScanActivity, not the service. Revisit after 2.12.

**4.4 HTML report streaming rewrite** — verifier correctly downgraded the OOM claim (native-backed buffers don't hit the Java heap); large effort for low severity. Cheap partial if touched anyway: statically pre-trim the template (drop the runtime `trimIndent()` over the whole document).

**4.5 onDestroy shutdown trim** — verifier corrected: worst realistic block ~3.6 s, no ANR, and worker.stop() doesn't join the engine. Cheap partials (remove the 1200 ms sleep in favor of the async Finalize callback; skip the farewell await) can ride along with 1.12; the full rework is not worth standalone effort.

**4.6 Report-copy / night-work items** — night camera work out of scope per constraints.

---

## Findings the verifier let through wrongly (or under-corrected)

1. **Duplicate findings confirmed as separate items, twice.** The [pipeline] HIGH anchor finding (ScanActivity.kt:1335) and the [alpr] MEDIUM plates[0] finding (ScanActivity.kt:1332) are one defect; the [alpr] MEDIUM and [ideas] HIGH per-character-confidence findings (AlprResults.kt:116/109) are one improvement. The verifier confirmed all four without flagging the duplication — harmless for correctness, but it inflates the apparent finding count and both pairs must be planned as single changes (1.10, 3.1).

2. **Tracker scale/aspect finding: the proposed fix is half dead code.** The verifier's own IoU arithmetic refutes the closing-car scale scenario (IoU <= 1/s² kills the match before plausibleMatch runs), yet the finding's fix — relaxing scale bounds by `^k` — was left standing. Implement only the aspect-skip (2.9); the scale relaxation cannot change any outcome.

3. **truncated() merge guard: the "at minimum" fallback fix is a feature removal in disguise.** Refusing the merge "when BOTH strings are complete valid Latvian plates" refuses nearly *all* truncation merges, because a truncated read of a valid plate (AB1234 -> AB123) almost always still matches the loose Latvian regex — including the genuine-truncation case the mechanism exists for, and it directly conflicts with the truncated-position-voting improvement (2.5) which needs truncations kept in the group. Only the geometry version (shorter read's plate box touching the crop border on the missing-character side) is shippable; plan 2.5 and this guard together so they don't fight.

4. **Strict-Latvia fix is incomplete as stated.** The finding claims GENERIC_MIN=4 closes the hole once the regex requires 2 letters, but P104 (4 chars) and RIGA25 (6 chars) sail through generic() and can still confirm a phantom card. The regex change alone does not deliver the claimed outcome; the sign-shape blacklist is required, not optional (2.7).

5. **Posterior-backoff finding survived with numbers that would break the product.** The verifier flagged that a 30 s recheck backoff starves the video-absence logic (thresholds down to 5 s) but still confirmed at medium with the fix text unchanged. Treated here as replay-gated with a hard cap or a presence-signal decoupling prerequisite (3.11).

6. **requestedZoom (UNCERTAIN) is handled correctly by the verifier** — but worth stating the planning consequence: because the snap fix is monotone-safe and the downside if the finding is true is total tele failure, uncertainty argues for shipping the fix and verifying on device (1.17), not for deferring it.

---

## Dependency / ordering summary

```
1.6 (turn refractory) ─► 1.7 (contact rework) ─► 1.9 gate ─► 2.13 (negative evidence, shadow) ─► 3.13 (signatures)
1.11 (fusion votes)  ─► 2.5 (truncated votes) ─► 3.1 (per-char weights, after JSON layout check)
1.1/1.4 (lifecycle)  ─► 2.3 (eviction inversion), 2.12 (orphan pool) ─► 3.12 (dwell feedback), 4.2, 4.3
1.10 (selection unification) precedes any anchor/ownership tuning
1.17 (zoom snap) verified on device before 2.10/2.11 camera work is evaluated
2.11 (CONTINUOUS_VIDEO + fps pin baseline) precedes 3.2/3.3/3.4 A/Bs
Replay corpus captured early ─► gates 3.1, 3.5, 3.8, 3.9, 3.11
```

Batch A+D of Group 1 are independent of Batch B+C and can proceed in parallel; nothing in Group 2/3 should start before Group 1 Batches A and B land, since they change the data those experiments would measure.

---

# Находки

## [follow] CRITICAL · defect · One junction fires 2+ TurnEvents: 40-degree threshold with window clear and no refractory

`track/TripTracker.kt`:168

**Что:** detectTurn sums signed heading deltas over the 8 s window and fires the moment |total| >= TURN_DEGREES (40), then clears the window and keeps accumulating. A standard 90-degree city junction taken over 5-8 s crosses 40 mid-manoeuvre, fires, and the remaining ~50 degrees re-accumulates inside the same junction and fires a second event. A 180-degree U-turn fires 3-4 events, and TurnDirection.U_TURN (150 threshold) is unreachable in practice because the window always clears at 40.

**Почему:** Each fire snapshots recentSightings into PendingTurn.before and creditTurns grants one sharedTurn per event, so every physical junction credits ~2 shared turns to every car behind. classify() reaches TAIL at sharedTurns >= 3: an ordinary commuter that follows you through two real junctions triggers a tail alert. This single defect roughly halves the effective turn thresholds of the whole engine.

**Как чинить:** After emitting a TurnEvent, enter a refractory state: ignore further delta accumulation until heading has been stable (e.g. |delta| < 5 deg/s for 3-4 s or 200 m driven), then resume. Determine direction from the full manoeuvre total, restoring U_TURN. This is contained to detectTurn.

*Проверка (CONFIRMED):* Traced TripTracker.detectTurn lines 163-188: fires the moment |sum| >= TURN_DEGREES(40) over the 8 s window, then headingWindow.clear() with no refractory; lastBearing keeps updating so residual junction rotation immediately re-accumulates. At 0.5-1 Hz fixes a 90-degree turn taken over 5-8 s crosses 40 mid-manoeuvre (per-sample deltas 6-18 deg) and the remaining ~40-50 deg can cross again inside the same junction; U_TURN_DEGREES(150) is unreachable because total can never exceed ~40 plus one sample delta before the clear. Each fire appends to turnTimes (inflating turnsSince/turnsInGap and turnsDuringContact) and creates a PendingTurn in FollowEngine.onTurn, so one junction can credit 2 sharedTurns to every car in the pre+post windows; classify() reaches TAIL at sharedTurns >= 3. Only caveat: fast turns whose final sample overshoots (fires at ~55+) leave <40 remainder and single-fire, so not literally every junction doubles — but slow/wide junctions, roundabouts and U-turns reliably multi-fire. Severity critical stands: this corrupts the engine's core evidence unit.

## [follow] CRITICAL · defect · Contact time is first-to-last span, never reset; sparse coincidences become TAIL

`follow/FollowEngine.kt`:337

**Что:** contactMs = lastSeenMs - firstSeenMs (lines 337, 450) with firstSeenMs set once and never reset after a gap; State lives for the whole process, across bindTrip calls. becameFollower (line 338) needs only contactMs >= tailSeconds (default 120 s), and movedWithUs (line 333) measures only OUR odometer advancing 150 m, not co-motion. Two coincidental sightings of an unrelated car 10 min apart (leapfrog at a fuel stop, a parked car passed outbound and again on the return leg, a taxi seen twice downtown) yield contactMs of 10 min, movedWithUs=true, follower=true.

**Почему:** A third coincidental sighting >120 s later with any of our turns in the gap counts as a reacquisition on a follower (lines 319-323) -> returnedAfterFollowing -> TAIL at line 455. Three glimpses of the same parked or unrelated car in one afternoon of errands produces a confirmed-tail alert with video and Telegram message. The doc comment says time-behind-us alone is never scored, but span-based contact makes exactly that happen, inflated by discontinuity.

**Как чинить:** Accumulate contact as a sum of continuous segments: when gapMs exceeds REACQUIRE_GAP_MS (or ENCOUNTER_GAP_MS=180 s to match the DB), close the segment, add its duration/distance to accumulated totals, and restart firstSeenMs/firstOdometerM. Require a minimum sighting count within the current segment before follower can set. Feed classify() the accumulated values, not the raw span.

*Проверка (CONFIRMED):* Verified FollowEngine.onSighting: firstSeenMs set only when 0L (line 312) and never reset after any gap; contactMs = lastSeenMs - firstSeenMs (337, 450); becameFollower is pure span >= runtime.tailSeconds*1000 (338, default DEFAULT_TAIL=120 in RuntimeSettings); movedWithUs (333) is our own odometer advancing 150 m. The states map is never pruned, and the engine is created once in ScanActivity.onCreate (startFollowDetection, line 237), so state spans an entire day-long session. Traced the exact chain: sighting 1 sets firstSeenMs; sighting 2 ten minutes later runs the reacquisition check (316-323) before follower is set, then sets follower=true from the 10-min span; sighting 3 with gapMs>120 s and (1 turn or 800 m of our travel) sets returnedAfterFollowing -> classify() returns TAIL at 455 -> worthFilming -> video + onFollowerConfirmed. Three glimpses of an unrelated or parked car in one session do produce a confirmed tail. Minor nit: bindTrip is only called once per engine, so 'across bindTrip calls' is moot, but the within-session failure is fully real. Critical stands.

## [pipeline] CRITICAL · defect · Jobs discarded without onResult leave inFlight=true forever, locking the track out of OCR

`pipeline/AlprWorker.kt`:348

**Что:** FrameProcessor.submitJob sets runtime.inFlight=true, and the only code that clears it is VehicleRegistry.submit, reached via onResult. Three worker paths discard a job without ever calling onResult: (1) the stale drop in loop() (AlprWorker.kt:347-352) for any job queued >5 s; (2) the spill path in submit() (AlprWorker.kt:239-253) which returns true to the caller — inFlight stays set until that exact file is drained, but SpillStore.poll is newest-first LIFO with a 120 ms pause per drain, so older spills may never surface; (3) spill accepted but store.offer fails (maxFiles/maxBytes) — the job is discarded on the spill thread after submit() already returned true, so inFlight is stuck permanently. scheduleRecognition (FrameProcessor.kt:328) skips inFlight tracks, and the runtime is only removed on track loss.

**Почему:** Under sustained traffic the priority queue ages out low-priority jobs (confirmed rechecks, distant unread cars) past staleAfterMs — every such track is silently excluded from recognition for as long as it stays in view. Worst case is exactly the mission-critical one: a follower that stays in frame gets one crop spilled during a burst and is then never OCR'd again for the rest of its track life, with no visible symptom other than a label that never appears.

**Как чинить:** Give AlprWorker an onDiscard(job) callback (or route a synthetic AlprOutcome.failure through onResult) on every path where a job leaves the pipeline without recognition — stale drop, spill offer failure, stop() drain — and clear runtime.inFlight the moment a job is accepted for spilling (the crop no longer represents live state, so the track should be croppable again immediately).

*Проверка (CONFIRMED):* Exhaustive grep for inFlight: set at FrameProcessor.kt:477, cleared only at FrameProcessor.kt:481 (submit returned false) and VehicleRegistry.kt:104 (reached via onResult). The stale drop (AlprWorker.kt:347-351), the spill-offer-failure inside the executor task (AlprWorker.kt:241-245, after submit() already returned true at line 247), and the stop() drain (198-202) all call discard() without onResult, so the flag is never cleared. scheduleRecognition (FrameProcessor.kt:329) skips inFlight tracks and the runtime is removed only via onTrackLost, so a continuously-visible track is locked out of OCR until it leaves the frame. The spill-accepted case clears only when that trackId's file drains, and drainOneSpilled runs only when the queue is empty — which never happens under the sustained load that caused the spill. All three paths hold as stated; for an app whose purpose is reading persistent followers, the silent permanent lockout of a persistent track is the worst-case failure.

## [alpr] HIGH · defect · One hallucinated extra character permanently hijacks a card backed by 7 good reads

`pipeline/VehicleRegistry.kt`:137

**Что:** PlateFusion.fuse (PlateFusion.kt:38-41) lets only max-length reads vote; when a single read is one char LONGER than everything else, voters.size==1 and that read is returned as leader with no vote and no PlateFormats re-check. VehicleRegistry.submit then sets `votes = history.size` (line 137) — the size of the whole variant list, not the count of reads supporting the winner — so upgradePlate's votes-outrank-confidence rule credits the fluke with all 8 votes and renames the card.

**Почему:** Track reads 'EM7209' seven times; one frame reads the plate-frame bolt as a digit: 'EM72091' (passes generic(): 2 letters, 5 digits). similar('EM7209','EM72091') merges it into the group (truncation rule), fuse returns the singleton 7-char read, votes=8, card renamed to EM-72091 and published to Telegram/DB. It is sticky: after the bad read ages out of the 8-cap history, the correct fused 'EM7209' arrives with votes==entry.votes (tie) and PlateSimilarity.prefer picks the LONGER string, so the wrong plate is kept forever.

**Как чинить:** Make votes mean support for the winning spelling: have PlateFusion return (reading, supportCount) where supportCount = number of history entries equal to or truncations of the fused text, and pass that to upgradePlate. In fuse, do not let a length-L cohort of size 1 outrank a length-(L-1) cohort of size >= 3 (require e.g. 2 reads at the longer length before 'longer wins').

*Проверка (CONFIRMED):* Traced PlateFusion.fuse lines 38-41: voters = only max-length reads; voters.size<2 returns leader (the singleton longer read) before the PlateFormats re-check at line 63. VehicleRegistry.kt:137 sets votes=history.size (8), not support count, and upgradePlate renames on votes>entry.votes. 'EM72091' passes generic() (2 letters, 5 digits) and merges via truncated(). Stickiness confirmed and actually worse than claimed: votes cap at MAX_READINGS=8 on both sides, and on the tie PlateSimilarity.prefer line 53 picks the LONGER string — so even if entry.votes was already 8, the bad longer read wins the tie immediately, and the correct fused spelling can never win it back.

## [camera] HIGH · defect · requestedZoom from focal math (2.8x/4.6x) sits below Samsung's 3.0/5.0 switch points

`camera/CameraCatalog.kt`:159

**Что:** opticalZoom is computed as equivalentFocal/mainEquivalent, which on an S25 Ultra yields ~2.8 for the 3x module and ~4.6-4.8 for the 5x periscope, and that exact value becomes ZoomStep.requestedZoom handed to setZoomRatio. Samsung's logical HAL activates the dedicated tele modules at (or above) the marketing ratios 3.0 and 5.0; below the threshold it serves a digital crop of the next-wider sensor. So the 3x and 5x steps request a ratio the HAL fulfills with the WRONG module.

**Почему:** The verifier then honestly reports ACTIVE_ID_MISMATCH/FOCAL_MISMATCH -> FAILED every cycle: in strict mode the two tele slots — the only lenses that actually resolve a plate at distance — contribute zero recognitions for their entire dwell, every rotation. In non-strict mode the frames are silently a crop of the wide sensor, throwing away the optical resolution the rotation exists to obtain. The HUD shows a permanently failing lens with no hint the request itself is the problem. Unverified on device, but this is the most likely reason the rotation will look broken on the S25 Ultra.

**Как чинить:** Snap requestedZoom up to the canonical marketing ratio when within tolerance — the canonical table already exists in formatZoom() (0.5/0.6/1/2/3/5/10); use max(derived, canonical) for optical steps so the request lands at 3.0/5.0. Keep the verifier as the arbiter of whether the snap worked.

*Проверка (UNCERTAIN):* Code premise confirmed: CameraCatalog.kt:145 derives opticalZoom = equivalent/mainEquivalent (~2.79 and ~4.63 on S25U focal specs) and line 159 hands exactly that to requestedZoom with only a min/max coerce; formatZoom()'s canonical snapping (lines 229-239) is display-only and never feeds setZoomRatio. But the failure hinges on Samsung's HAL refusing to activate the tele modules below the 3.0/5.0 marketing ratios, which cannot be settled by reading code (the claim itself says 'Unverified on device'). Note the system fails safe in strict mode: the verifier would report the mismatch and block frames rather than silently misattribute, exactly as designed.

## [camera] HIGH · improvement · Replace CONTINUOUS_PICTURE AF with fixed focus (AF OFF + LENS_FOCUS_DISTANCE)

`camera/CameraController.kt`:178

**Что:** The interop extender sets CONTROL_AF_MODE_CONTINUOUS_PICTURE, the stills-tuned mode that refocuses aggressively. Mounted behind the rear window, CAF has a permanent near-field distractor: dirt, defroster lines and reflections on the glass centimeters from the lens. Every hunt cycle produces a burst of defocused frames, and a lock onto the glass at tele defocuses the entire scene. Meanwhile the subject distance is essentially fixed: a following car is 5-50 m away.

**Почему:** AF hunting is the cheapest source of unreadable plates to eliminate. Fixed focus makes every frame equally sharp at the distances that matter and removes hunt bursts entirely — directly more frames passing FocusMetric and feeding PlateFusion votes.

**Как чинить:** Set CaptureRequest.CONTROL_AF_MODE = CONTROL_AF_MODE_OFF and CaptureRequest.LENS_FOCUS_DISTANCE = a small fixed diopter per step (0.0f = infinity is safe for 1x/3x; the 5x periscope's hyperfocal is tens of meters, so ~0.03-0.05 diopters (~20-30 m) keeps 10-50 m sharp — re-set the key in apply() per step). Risk: S25U reports LENS_INFO_FOCUS_DISTANCE_CALIBRATION APPROXIMATE, so tune the diopter empirically; vehicles closer than ~10 m at 5x go slightly soft (they are trivially readable at 1x/3x anyway). Fallback if fixed focus is rejected: CONTROL_AF_MODE_CONTINUOUS_VIDEO (smooth, conservative refocus) plus AF regions on the tracked vehicle box.

*Проверка (CONFIRMED):* Not implemented: the only AF configuration is CONTROL_AF_MODE_CONTINUOUS_PICTURE via the interop extender (CameraController.kt:177-180); project-wide grep finds no LENS_FOCUS_DISTANCE, no AF_MODE_OFF, no metering anywhere. Mechanism is sound for this deployment: subject distance is bounded (5-50m), the near-field glass is a permanent CAF distractor, and stills-tuned CONTINUOUS_PICTURE is the most aggressive refocus mode. The claim correctly flags the APPROXIMATE focus-distance calibration risk and offers CONTINUOUS_VIDEO as fallback. Interop supports the keys and apply() is a natural per-step hook. High is justified — AF hunt bursts directly starve FocusMetric/PlateFusion.

## [camera] HIGH · improvement · Steer AE with FocusMeteringAction on the tracked vehicle box, not frame-average

`camera/CameraController.kt`:175

**Что:** The session runs default frame-average/center AE. Through a rear window the upper half of the frame is bright sky, so AE exposes for the sky and the following car's shadowed front — where the plate is — sits 1-2 stops under. The app now KNOWS where the vehicle and plate are (tracker box + PlateRoi) and never uses that to steer exposure.

**Почему:** An underexposed plate crop costs OCR contrast that no amount of PlateFusion voting recovers; correctly exposing the target region is the largest single-frame readability win available in daylight-behind-glass conditions.

**Как чинить:** Expose a method on CameraController that takes the current vehicle box in analysis coordinates and calls camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).disableAutoCancel().build()) with a SurfaceOrientedMeteringPointFactory sized to the analysis resolution (attach the ImageAnalysis use case to the factory so CameraX handles viewport/zoom/rotation mapping). Renew when the tracked box moves materially; cancelFocusAndMetering() on track loss so a stale region doesn't misexpose the search phase. Risks: do NOT hand-roll CONTROL_AE_REGIONS via interop — the rects are in zoom-adjusted active-array coordinates and easy to get wrong per lens; use the vehicle box, not the tiny plate box, or AE oscillates; re-issue after each lens step since metering resets on zoom changes.

*Проверка (CONFIRMED):* Not implemented: grep finds no FocusMeteringAction, startFocusAndMetering, or AE_REGIONS anywhere; the session runs default AE. The premise holds for this geometry (rear-window mount, sky in upper frame biases average metering against the shadowed car front), the app does have the vehicle box available per frame (FrameProcessor/tracker), and the proposed CameraX path (SurfaceOrientedMeteringPointFactory tied to the analysis use case, FLAG_AE, disableAutoCancel, renew on movement, cancel on track loss, re-issue per lens step) is the correct, supported API and avoids the per-lens active-array coordinate trap of raw CONTROL_AE_REGIONS. Mechanism sound, gain plausible, not present.

## [camera] HIGH · improvement · Pin CONTROL_AE_TARGET_FPS_RANGE (30,30) and bias AE negative to cap motion smear

`camera/CameraController.kt`:184

**Что:** Nothing bounds exposure time. Samsung's default AE fps range is variable (lower bound ~10-15 fps): under an overpass, in tree shade or heavy overcast the HAL stretches exposure to 60-80 ms. At 5x (~111 mm eq, ~200 px/deg at 4K) mount vibration of a few deg/s smears tens of pixels — character strokes are 3-5 px at the 26 px plate floor, so the plate is gone. Two keys fix this: (1) CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE = Range(30,30) caps exposure at 33 ms and gives the IoU tracker constant frame dt; (2) CameraControl.setExposureCompensationIndex for about -0.7 to -1 EV pushes daylight AE toward 1-4 ms and keeps retroreflective plates from clipping in direct sun.

**Почему:** Motion blur, not resolution, is the binding constraint on a tele crop from a vibrating mount; a 2-4 ms exposure keeps smear inside 2-4 px where OCR still works. The fixed dt also removes tracker jitter from variable frame intervals.

**Как чинить:** Add both via the existing Camera2Interop extender / CameraControl. If field tests show -1 EV is still not short enough at 10x, the full lever is CONTROL_AE_MODE_OFF with SENSOR_EXPOSURE_TIME ~2 ms and SENSOR_SENSITIVITY driven by a trivial mean-luma feedback loop — risk: AE off also disables the HAL's scene logic, and underpasses need the loop to react within a few frames, so keep it behind a daylight-only config flag. Risk of the EV bias: shadowed plates under trucks get darker; make the index configurable and consider dropping the bias when the AE metering region (previous finding) is active on the vehicle.

*Проверка (CONFIRMED):* Not implemented: grep finds no CONTROL_AE_TARGET_FPS_RANGE and no setExposureCompensationIndex anywhere; nothing bounds exposure time today. The math checks out: 111mm-eq at 3840px is ~205 px/deg, so tens-of-ms exposures on a vibrating mount smear more than a character stroke width at the 26px plate floor; Range(30,30) capping exposure at 33ms plus negative EV bias is the standard, supported lever via the existing Camera2Interop extender/CameraControl, and a fixed frame dt also benefits the IoU tracker. Risks (shadowed plates, AE-off escalation) are correctly identified and gated. Mechanism sound, gain plausible, not present.

## [data] HIGH · defect · Report filters encounters by window AFTER a LIMIT 50 ascending query — active tails vanish

`report/HtmlReportBuilder.kt`:76

**Что:** HtmlReportBuilder.build calls store.encounters(vehicle.plate) which is 'SELECT ... WHERE plate = ? ORDER BY started_at LIMIT ?' with default limit 50 (TrackingStore.kt:714), i.e. the OLDEST 50 encounters, and only then filters '.filter { it.endedAt >= options.sinceMs }'. The same pattern feeds tripIds: encountersSince() is ORDER BY started_at ASC LIMIT 600 (TrackingStore.kt:723), so the newest trips fall off first.

**Почему:** With 30-day retention (ScanActivity.RETENTION_MS) a car met twice a day accumulates ~60 retained encounters. For a 'last 24h' report the oldest 50 all predate sinceMs, the filtered list is empty, and 'if (encounters.isEmpty()) return@forEach' silently drops the vehicle from the map, timeline and cards — even though vehiclesSeenSince listed it because last_seen is fresh. The vehicles most likely to be dropped are exactly the high-history repeat vehicles the report exists to expose. Even when not fully dropped, the newest encounters (today's) are the ones truncated, and the photo picker 'index < maxPhotosPerVehicle' then embeds only stale photos.

**Как чинить:** Push the window into SQL: add a variant encounters(plate, sinceMs, limit) with 'WHERE plate = ? AND ended_at >= ? ORDER BY started_at' (the window bounds the row count so the LIMIT becomes a true safety cap), and give encountersSince 'ORDER BY started_at DESC' or a window-bounded query for tripIds derivation.

*Проверка (CONFIRMED):* Traced HtmlReportBuilder.build:75-77 -> TrackingStore.encounters:714-718. The query is 'WHERE plate = ? ORDER BY started_at LIMIT ?' (ASC, default 50), returning the OLDEST 50 encounters; the '.filter { it.endedAt >= options.sinceMs }' runs after the limit, and 'if (encounters.isEmpty()) return@forEach' drops the vehicle from the report entirely. With 30-day retention (ScanActivity.RETENTION_MS, prune at line 372) a frequently-met car easily retains >50 encounters, so a 24h report loses exactly the high-history vehicles. The photo picker 'index < maxPhotosPerVehicle' also indexes the oldest encounters first. Minor correction: encountersSince (line 723) already has 'WHERE ended_at >= ?' in SQL, so the tripIds half only truncates past 600 in-window rows — but the core defect holds as stated.

## [follow] HIGH · defect · returnedAfterFollowing promotes to TAIL with zero route evidence

`follow/FollowEngine.kt`:455

**Что:** classify() returns TAIL on state.returnedAfterFollowing alone, before any sharedTurns/places checks. follower is pure duration (line 338), so the chain is: 2 min behind you + one gap >120 s + rejoin = TAIL. No turn agreement, no distinct place, no second trip required.

**Почему:** Traffic jam scenario: every car in the jam sits behind you for 2+ minutes and becomes follower. The jam clears, you lose one, and 2.5 min later it is behind you again on the same arterial (one of our turns happened in the gap, or we drove 800 m) -> immediate TAIL alert for a car that merely shares your arterial road. On a commute this fires daily.

**Как чинить:** Require route evidence on the rejoin: set returnedAfterFollowing only when turnsInGap >= 1 AND the reacquiring sighting also credits one of those turns (it appears in a PendingTurn post-window), or gate the TAIL branch at line 455 on state.sharedTurns >= 1.

*Проверка (CONFIRMED):* Verified classify() line 454-455: returnedAfterFollowing returns TAIL before any sharedTurns/metElsewhere/reacquisitions checks; the only prior gate is movedWithUs/contactM>=150 m (452), trivially satisfied by our own driving. follower is set purely by contact span >= tailSeconds (338) with no route evidence, and returnedAfterFollowing is set on any reacquisition while follower (319-323), where the reacquisition needs only gapMs>120 s AND (1 of our turns in the gap OR 800 m of our own travel). The traffic-jam chain (2 min behind -> jam clears -> re-seen 2.5 min later after we drove 800 m) reaches TAIL with zero turn agreement, exactly as claimed. High is appropriate.

## [follow] HIGH · defect · Distinct-places rule matches every same-corridor commuter by day two

`follow/FollowEngine.kt`:459

**Что:** metElsewhere = tripsSeen >= 2 && places >= 2, and with sharedTurns >= 1 it is TAIL (line 462). places comes from TrackingStore.distinctPlaces: greedy clustering of encounter START points with PLACE_RADIUS_M = 600. Encounter start position depends on where OCR first confirmed the plate, which shifts hundreds of metres between days (queue load, light, traffic).

**Почему:** A neighbour on your commute: day 1 the encounter starts 200 m from home, day 2 it starts 900 m down the same road -> 2 clusters, 2 trips. The shared turn out of the neighbourhood gives sharedTurns = 1 -> TAIL every morning for an innocent commuter. Two points 600 m apart along one shared corridor are not independent meetings, they are one route.

**Как чинить:** Count places as distinct only when the clusters are separated by at least one of our turns between the corresponding encounters (route-distinct, not metre-distinct), or check the cluster against a corridor built from our own recent trips in TrackingStore; alternatively raise the metElsewhere TAIL branch to sharedTurns >= 2.

*Проверка (CONFIRMED):* Verified TrackingStore.distinctPlaces (669-685): greedy clustering of encounter START points only, PLACE_RADIUS_M=600, no route- or turn-awareness — two start points 700 m apart along one road count as 2 places. Encounter start_lat/lon is the capture position of the first confirmed read of a new encounter (recordSighting 369-388), which depends on OCR queue timing/light and plausibly shifts >600 m between days. tripsSeen = COUNT(DISTINCT trip_id) so day 1 + day 2 = 2. classify(): metElsewhere = tripsSeen>=2 && places>=2 (459), and metElsewhere && sharedTurns>=1 is TAIL (462); one shared neighbourhood-exit turn per morning is enough. The mechanism (metre-distinct, not route-distinct) is exactly as claimed; the day-two-commuter scenario depends on start-point variance exceeding 600 m, which is plausible but not certain every day. High stands.

## [follow] HIGH · defect · Turn crediting and reacquisition use recognition time, not capture time

`follow/FollowEngine.kt`:335

**Что:** onSighting takes capturedAtMs precisely because a queued recognition must be recorded where the vehicle was, but only the DB write uses it. creditTurns(key, nowMs) (line 335), recentSightings (341), lastSeenMs (326), and the reacquisition gap (316-319) all use nowMs. The ALPR engine is a serialized process-wide singleton; under load or thermal throttle a crop completes 30-120 s after capture (the 26 px readability floor exists exactly because the queue backs up).

**Почему:** A car captured 20 s BEFORE your turn but recognized 40 s after it is treated as seen after the turn -> false sharedTurn for a car that peeled off before the junction. A 2-minute backlog stall fabricates gapMs > REACQUIRE_GAP_MS -> phantom reacquisitions for a continuously present car. Conversely a genuine post-turn read that drains from the queue 130 s later falls outside POST_TURN_WINDOW_MS -> lost credit. Latency is highest exactly when traffic is dense, i.e. when the decision matters.

**Как чинить:** Thread capturedAtMs through the evidence path: use it for lastSeenMs, the gap test, creditTurns age, and insert into recentSightings in timestamp order; keep nowMs only for the minSightingIntervalMs throttle and UI.

*Проверка (CONFIRMED):* Verified in onSighting: only the DB write uses capturedAtMs (tMs = capturedAtMs, line 372); lastSeenMs (326), the reacquisition gap test (316-319), creditTurns (335, age check vs nowMs at 434-435) and recentSightings (341) all use nowMs, which the caller sets to recognition-completion time (ScanActivity 1368/1397). One correction to the magnitude argument: the live queue cannot deliver 30-120 s late reads because AlprWorker discards jobs older than staleAfterMs=5 s (lines 105, 347-352). However drainOneSpilled (274-321) BYPASSES that stale check and feeds disk-spilled crops — parked exactly when the queue is overloaded, drained only when the queue runs dry, minutes later (SpillStore's own doc: 'recognized a minute later'; adopt() even revives previous-session crops) — straight into onResult with submittedAtMs=capture time. So a crop captured pre-turn and recognized 40 s post-turn is a real path (false sharedTurn if the plate was already in turn.before), as are phantom reacquisitions from a backlog stall (gapMs from recognition clocks, gapM from capture odometer — mixed clocks). High stands.

## [follow] HIGH · defect · rename() merge drops blacklisted, follower, returnedAfterFollowing, odometer state

`follow/FollowEngine.kt`:193

**Что:** The merge path (lines 193-198) copies sightings, sharedTurns, reacquisitions and the seen-timestamps, but discards from the removed state: blacklisted, ignored, follower, returnedAfterFollowing, turnsDuringContact, movedWithUs, bestScore, firstOdometerM/lastOdometerM, videoActive. It also min's firstSeenMs without touching firstOdometerM, so contactM no longer corresponds to contactMs. Target level is never reclassified and no onUpdate fires, so the UI keeps the stale 'from' card and never re-renders 'to'.

**Почему:** Operator corrects a blacklisted misspelling into the true plate: the blacklist flag vanishes in memory (store.setBlacklisted is never called for 'to') and the car stops triggering BLACKLIST alerts until restart. A car whose merged half carried follower/returnedAfterFollowing loses its TAIL trigger at the exact moment the operator confirmed its identity - the merge that should strengthen evidence destroys it.

**Как чинить:** OR the boolean evidence (blacklisted, ignored, follower, returnedAfterFollowing, movedWithUs), sum turnsDuringContact, take max bestScore, carry the odometer values paired with the timestamps that were kept, persist blacklist for the target, re-run classify(), and emit onUpdate for both keys (removal for 'from', refresh for 'to').

*Проверка (CONFIRMED):* Verified FollowEngine.rename 190-209: the merge branch copies only sightings, sharedTurns(max), reacquisitions(max), firstSeenMs(min), lastSeenMs(max); the removed 'from' state's blacklisted, ignored, follower, returnedAfterFollowing, turnsDuringContact, movedWithUs, bestScore, firstOdometerM/lastOdometerM and videoActive are discarded, and firstSeenMs is min'd without the paired odometer so contactM no longer corresponds to contactMs. No classify() re-run and no onUpdate in rename(). The DB side (TrackingStore.mergeInto 530-568) preserves blacklisted via MAX(blacklisted,...), and loadBlacklist runs only at startup — so the claim's 'stops triggering BLACKLIST alerts until restart' is exactly right for the in-memory engine (worthFilming and level both read state.blacklisted). A follower/returnedAfterFollowing flag on the merged half is likewise destroyed at the moment of operator confirmation. One overstated detail: the bot caller (ScanActivity 1256-1259) does remove the 'from' card via evidenceByPlate.remove + publishVehicles, so the UI does not keep the stale 'from' card; the 'to' card is merely stale until its next sighting. Core defect confirmed, high stands.

## [follow] HIGH · improvement · Negative evidence: count turns the candidate FAILED to copy

`follow/FollowEngine.kt`:431

**Что:** PendingTurn already knows who was in 'before'; when the post window expires without that plate re-appearing, record a missedTurn on its state (a 'shed' event). Classify on the ratio sharedTurns / (sharedTurns + missedTurns) with a minimum sample, instead of the raw sharedTurns count. Additionally require >= 2 post-turn sightings before crediting, so one stale read cannot credit a turn.

**Почему:** This is the single most discriminating signal available with no new sensor: a commuter sheds at the first route divergence (ratio drops toward 0), a genuine tail never sheds (ratio stays 1.0). It directly suppresses the multi-fire and corridor false positives because coincidental companions accumulate misses as fast as credits, and the data structures already exist so runtime cost is nil.

**Как чинить:** On pendingTurns expiry (prune loop in onTurn, plus a periodic sweep), for each plate in before-minus-credited increment state.missedTurns; add the ratio gate to the TAIL/SUSPECT branches of classify with reasons text for explainability.

*Проверка (CONFIRMED):* Not implemented: no missedTurns or shed/ratio logic anywhere in FollowEngine (full file read). Mechanism is sound and cheap: PendingTurn already carries before and credited (100-103), so before-minus-credited at expiry is exactly the shed set; the prune loop in onTurn (260-264) is the natural hook, though it only runs when the NEXT turn fires, so the periodic sweep the fix mentions is genuinely required. The gain is plausible and targets the confirmed false-positive defects (multi-fire double credits and corridor commuters accumulate misses symmetrically). Main honest risk: false misses when a present-but-distant car simply fails to get an OCR-confirmed read within the 120 s post window (queue contention), which punishes hang-back tails — mitigated by the proposed ratio with a minimum sample rather than an absolute miss count. Confirmed as a high-value improvement.

## [ideas] HIGH · improvement · Parse per-character confidences from the engine JSON and vote per character, not per read

`alpr/AlprResults.kt`:109

**Что:** AlprJson.parsePlate reads only confidences[0] (global) and confidences[1] (detection) and discards the rest of the array. The SDK is rebranded ultimateALPR, whose plates JSON carries a per-character confidence after those two entries; with recogn_score_type="min" (AlprEngine.engineConfig) the global score the app keeps is literally the weakest character's score. PlateFusion.fuse then weights every position of a read by that single number, so a read that nailed six characters and hesitated on one is down-weighted at all seven positions, and the one number cannot tell WHICH position was the weak one.

**Почему:** This is the core accuracy loss in fusion today: the EM/EN7209 case is decided by whole-read weights when the engine already said 'M scored 41, everything else 90+'. Per-character weights make three mediocre reads outvote one lucky one exactly at the disputed position and nowhere else. It also rescues reads currently filtered out by VehicleRegistry's minScore gate, whose min-score global dips below threshold because of one bad character while the other characters carry good evidence.

**Как чинить:** In parsePlate, capture confidences[2..] into a FloatArray charScores on PlateReading (verify the exact layout once on-device: log result.json() for a known plate; the captureDir replay path already exists for this). Map charScores through PlateFormats corrections so indices track the normalized key. In PlateFusion.fuse, weight each position's vote by that character's own confidence (fall back to recognitionScore when absent), and set the fused read's score from the per-position winning weights rather than the outvoted leader's global. Measure: replay the captured crop corpus (AlprWorker.startCapture) with ground-truth plates; compare exact-plate accuracy and reads-to-converge before/after.

*Проверка (CONFIRMED):* AlprResults.kt parsePlate (lines 116-117) reads only confidences[0] and [1] and discards the rest of the array; AlprEngine.kt line 269 sets recogn_score_type="min", so the kept global score is the weakest character's score; PlateFusion.fuse line 47 weights every position by that one number. Nothing in the codebase parses per-character confidences, so the improvement is not already implemented and the mechanism (per-position weights) is sound. Caveats the fix already acknowledges: PlateFormats.clean strips separators/cyrillic and correctToLatvian can substitute characters, so index mapping raw->key is required, and the exact confidences[] layout must be verified on-device (the captureDir replay path in AlprWorker exists for that).

## [ideas] HIGH · improvement · Consensus voting is wiped on every lens rotation; carry it across generations

`pipeline/VehicleRegistry.kt`:222

**Что:** FrameGate.generation bumps on every lens switch, FrameProcessor.analyze then calls tracker.reset(), which fires onTrackLost for every track, and VehicleRegistry.onTrackLost drops the TrackConsensus (counts, variants, pending votes) plus the TrackRuntime (plateAnchor, bestQuality). A car sitting behind you that had 2-of-3 votes at 1x restarts from zero when the plan rotates to 3x, and again at 5x. Distant cars — the ones that need the tele dwell most — only ever get short read windows per dwell, so they may never accumulate requiredMatches inside one generation.

**Почему:** With a rotating plan the effective confirmation threshold for far vehicles is much higher than requiredMatches, costing confirmations of exactly the following-car scenario the app exists for. It also throws away the plateAnchor, so the first crops after every switch are wide 1280px-capped car crops again.

**Как чинить:** On onTrackLost caused by a generation change (pass a reason flag through), move the TrackConsensus into a short-TTL orphan pool keyed by pendingPlate text plus CarInfo (make/model/color). When a fresh track's first reading is PlateSimilarity.similar to an orphan's pending plate (and attributes do not contradict), adopt the orphan's counts/variants/history. The plate string itself is the re-association key, so no cross-lens geometry is needed. Measure: time-from-first-sighting-to-confirmation and confirmations per minute on a drive with the rotation plan enabled, before/after; count adopted orphans in the log.

*Проверка (CONFIRMED):* Traced the full path: LensRotationScheduler.onSwitch -> CameraController.apply (line 126) bumps generation and calls gate.beginTransition -> FrameProcessor.analyze (lines 127-132) calls tracker.reset() on generation change -> VehicleTracker.reset (lines 126-130) invokes onTrackLost for every live track -> ScanActivity line 302 posts registry.onTrackLost -> VehicleRegistry lines 222-225 remove the TrackConsensus (counts, variants, candidateThumb) and recognition.remove drops the TrackRuntime including plateAnchor and bestQuality. Only confirmed cards survive in the vehicles map; pending votes restart from zero on every rotation step, and the first crops after a switch are wide again. No orphan/carry-over mechanism exists. The plate-text re-association mechanism is plausible.

## [ideas] HIGH · improvement · Use make/model/colour as a second identity channel so misreads cannot split one car

`pipeline/VehicleRegistry.kt`:178

**Что:** Card deduplication (VehicleRegistry line 178) and FollowEngine.canonicalKey (FollowEngine.kt line 410) both rely solely on PlateSimilarity.similar, which accepts at most ONE confusable-character difference or a truncation. Two characters misread — routine on a 30px plate — creates a second card and a second FollowEngine state. The CarInfo the pipeline already computes (make, model, colour, bodyStyle with confidences) participates in neither decision.

**Почему:** For tail detection this is worse than a duplicate card: contact time, shared turns and reacquisitions split across two State objects, and each half can stay under every classify() threshold, so a genuine follower whose plate reads two ways stays at IGNORE. The classifier evidence that would have glued the halves together is sitting unused in the same VehicleEntry.

**Как чинить:** Add a weighted plate distance (confusable substitutions cheap via PlateSimilarity's CONFUSABLE set, other substitutions expensive) and merge when distance<=2 AND make+model agree AND colour agrees (or one is null) AND the two cards' lastSeen windows overlap within ~60s. Apply the same test in FollowEngine.canonicalKey before creating a new State. Log every merge with both spellings for manual audit. Measure: duplicate-card rate per drive (cards whose make/model/colour and time window match, plates within distance 2) before/after, plus review of the merge log for false merges.

*Проверка (CONFIRMED):* VehicleRegistry line 178: card dedup is vehicles[winner.text] ?: firstOrNull { PlateSimilarity.similar(...) }; FollowEngine.canonicalKey lines 410-412 likewise. PlateSimilarity.similar accepts exactly one confusable-pair substitution (oneConfusion) or a one-character truncation, so two misread characters create a second VehicleEntry and a second FollowEngine State, splitting contactMs, sharedTurns and reacquisitions so each half can stay under classify() thresholds. CarInfo (make/model/color, present on VehicleEntry and via mergeCar) is used in neither identity decision. The proposed guarded merge (attributes must agree, time windows overlap, merges logged) respects the existing 'false merge hides a follower' concern in PlateSimilarity's doc comment.

## [ideas] HIGH · improvement · Let a nearly-confirmed distant plate extend or pull forward the tele-lens dwell

`camera/LensRotationScheduler.kt`:30

**Что:** LensRotationScheduler walks a fixed timed plan with no feedback from recognition. When a track has pendingCount>0 but estimatedPlatePixels < ~40 (readable only barely at the current focal length), the single most effective accuracy action available to the whole system is optical: 3x/5x multiplies plate pixels by 3-5, which no software step approaches. Today the plan may rotate AWAY from the tele lens mid-convergence, or sit at 1x while a distant pending car needs 5x.

**Почему:** For a follower keeping 50-100m distance — the actual threat profile — the plate at 1x is below MIN_PLATE_PX and the car is skipped entirely (FrameProcessor line 326); it becomes readable only during the tele slice of the plan. Feedback-driven dwell converts those tracks from 'skipped most of the time' to 'read during a targeted window'.

**Как чинить:** Add a scheduler input: FrameProcessor publishes 'demand' = max over pending tracks of (pendingCount>0 or hits>3) with estimatedPlatePixels under threshold. LensRotationScheduler, on tick, extends the current tele dwell by up to N seconds while demand persists, or advances early to the next tele step when demand appears at 1x (both bounded so the plan still covers all lenses; combined with cross-lens-consensus-survival so the votes taken at 1x survive the switch). Measure: confirmations of tracks whose first sighting had estimatedPlatePixels<40, per drive, before/after; plan coverage stays within bounds.

*Проверка (CONFIRMED):* LensRotationScheduler verified as a pure timer: configure/start/advance walk the fixed plan, onStepSettled starts the dwell, and no recognition signal exists anywhere in it; advance() is already public, so pulling a step forward is feasible. FrameProcessor lines 333-336 skip unanchored tracks with estimatedPlatePixels < MIN_PLATE_PX(26) whenever the queue is non-idle — 'skipped entirely' is slightly overstated (submitted when the engine is idle, though such crops rarely read), but in traffic the skip is the norm, and the plan does rotate away mid-convergence with no feedback. Optical magnification genuinely is the only lever that multiplies plate pixels given the fixed 4K/XNNPACK constraints. Not implemented; mechanism bounded and sound.

## [pipeline] HIGH · defect · drainOneSpilled delivers a recycled thumbnail for crops 256 px wide or narrower

`pipeline/AlprWorker.kt`:292

**Что:** drainOneSpilled builds the thumbnail with Bitmap.createScaledBitmap(bitmap, minOf(width, 256), height*minOf(width,256)/width, true). When bitmap.width <= 256 the target dimensions equal the source dimensions and createScaledBitmap returns the source bitmap itself (documented Android behavior), so thumbnail === bitmap. The very next line calls bitmap.recycle(), then the recycled bitmap is put on the OcrJob and delivered via onResult to VehicleRegistry.submit.

**Почему:** Spilled narrow plate crops are routinely 100-400 px wide (PlateRoi.MIN_SIDE_PX is 48), and small vehicle crops go down to 64 px, so this triggers on ordinary data. The registry stores the recycled bitmap as candidateThumb or as a card thumbnail; the RecyclerView then throws 'Canvas: trying to use a recycled bitmap' and crashes the session — the failure appears minutes after the cause, only once spill drain has run, so on device it will look like a random crash.

**Как чинить:** Only recycle the source when the scaled bitmap is a different object: create the thumbnail, then `if (thumbnail !== bitmap) bitmap.recycle()` — or force a copy when dimensions are unchanged (bitmap.copy(ARGB_8888, false)).

*Проверка (CONFIRMED):* AlprWorker.kt:292-300: BitmapFactory.decodeFile returns an immutable bitmap; Bitmap.createScaledBitmap with target dims equal to source dims delegates to createBitmap, which returns the source object itself for an immutable full-bounds identity-matrix request. For bitmap.width <= 256 the integer math yields exactly (width, height), so thumbnail === bitmap, and bitmap.recycle() at line 300 puts a recycled bitmap on the OcrJob. VehicleRegistry stores it as candidateThumb (lines 117-118, 163) or card thumbnail (171, 294), and drawing it in the RecyclerView throws 'trying to use a recycled bitmap'. Sub-256px spilled crops are routine: wide crops of distant cars bottom out around ~116 px (MIN_PLATE_PX gate implies car width >= ~104 px), narrow plate crops at 48 px. Contrast with FrameProcessor.createThumbnail (517-523) which correctly copies when scale >= 1.

## [pipeline] HIGH · defect · Late OCR results resurrect dead-track consensus/runtime entries, leaking maps and bitmaps

`pipeline/VehicleRegistry.kt`:106

**Что:** onTrackLost removes the consensus entry and the TrackRuntime, but a job for that track can still be in the worker queue (up to staleAfterMs = 5 s) or spilled to disk. When its result lands, VehicleRegistry.submit calls recognition.of(job.trackId) (recreates the runtime) and consensus.getOrPut(job.trackId) (recreates TrackConsensus), and may store job.thumbnail as candidateThumb (lines 117-121, 163). The tracker has already fired onTrackLost for that id and never will again, so these recreated entries live until registry.clear().

**Почему:** Track lifetime after last sighting is maxMissed=12 analysed frames (~0.4-1.2 s), routinely shorter than queue latency under load, so a large fraction of tracks in dense traffic leak one TrackRuntime plus one TrackConsensus, often pinning a ~200 KB thumbnail (candidateThumb from a plate-less read). A multi-hour drive accumulates thousands of entries and tens of MB of pinned Bitmaps in a process already running a 4K pipeline — an OOM that only reproduces on long real drives. The adopted-spill path (trackId from a previous session or -1) feeds the same leak.

**Как чинить:** In ScanActivity.onRecognition (or at the top of registry.submit), treat a job whose track has no live runtime as orphaned: if recognition.peek(job.trackId) == null, recycle job.thumbnail, optionally still merge the reading into an existing plate-keyed card, and return without creating consensus/runtime entries.

*Проверка (CONFIRMED):* VehicleRegistry.submit line 103 (recognition.of = getOrPut) and line 106 (consensus.getOrPut) recreate entries for a trackId that already received its single onTrackLost (VehicleRegistry.kt:222-225). VehicleTracker.nextId is monotonic within a session, so that id never fires onTrackLost again; tracker.reset() on lens change only notifies currently-active tracks. candidateThumb is pinned at lines 117-118 (plate-less read with cars present — the common distant-car case) and 162-163. Cleanup only via registry.clear() (operator action). Timing holds: maxMissed=12 analysed frames (~0.5-1.2 s) vs multi-second queue latency (12-deep queue at several hundred ms/job) plus spill drain minutes later, so late results for dead tracks are routine under load. Adopted spills with stale or -1 trackIds feed the same map. Genuine unbounded leak on the primary multi-hour use path.

## [pipeline] HIGH · defect · Plate anchor uses plates.firstOrNull(), not the voted reading; can lock onto a neighbor

`ScanActivity.kt`:1335

**Что:** rememberPlatePosition uses outcome.plates.firstOrNull()?.box (engine JSON order, unfiltered), while VehicleRegistry.submit votes on outcome.plates.filter{score>=minScore}.maxByOrNull{recognitionScore}. A wide vehicle crop carries a 6% margin plus PlateRoi's 10% basis slack, so in traffic it regularly contains a second car's plate at the edge; anchorOf accepts fractions up to 1.25 outside the box, so the neighbor's plate can become this track's anchor. Every subsequent narrow crop is then cut around the neighbor's plate — and because those narrow reads SUCCEED, the 'empty narrow read clears the anchor' self-heal never fires, and the neighbor's text is voted into this track's consensus.

**Почему:** Two cars side by side behind the driver (the normal case for this app): track A's anchor locks onto car B's plate, narrow reads of B accumulate requiredMatches votes under track A, and track A confirms with car B's registration. Overlay, follow engine and Telegram alerts then attribute the wrong plate to the wrong vehicle — exactly the cross-vehicle mixing the tracker's plausibleMatch gate was added to prevent, reintroduced by the new two-stage path. A below-minScore garbage detection can also set the anchor for a reading the registry discarded.

**Как чинить:** Select one reading in one place: pick the filtered best reading (same rule as the registry), pass it and its box into rememberPlatePosition, and only anchor when that reading exists. Optionally prefer a plate whose projected position falls inside the vehicle box proper (fractions 0..1) over one in the slack zone.

*Проверка (CONFIRMED):* ScanActivity.kt:1332 takes outcome.plates.firstOrNull()?.box — engine JSON order, no recognitionScore filter (AlprJson.parse filters only by format and aspect) — while VehicleRegistry.kt:110-112 votes on filter{score>=minScore}.maxByOrNull{recognitionScore}, so the anchor and the voted reading can be different plates. For a wide crop, source==basis (FrameProcessor.kt:465,473: both are cropRect/vehicleRect), so any plate anywhere inside the crop — including a neighbouring car's plate inside the 6% margin or an overlapping box — maps to anchor fractions in [0,1] and always passes anchorOf's [-0.25,1.25] gate (PlateRoi.kt:83). The self-heal (ScanActivity.kt:1333-1335) only clears the anchor when a narrow read returns zero plates, so successful narrow reads of the neighbour's plate perpetuate the wrong anchor, and registry.submit then accumulates the neighbour's text into this track's consensus — cross-vehicle attribution reaching the overlay, follow engine and alerts. A below-minScore detection setting the anchor while the registry discards the reading is also confirmed by the same lines.

## [runtime] HIGH · defect · Single tg-sender thread: clip/report uploads delay tail alerts by minutes

`telegram/TelegramBot.kt`:94

**Что:** TelegramBot serializes everything through one executor (`sender`, line 94): `alert()` (line 212), `sendClip()` (line 259), `broadcast()` and the full blacklist package (vehicle photos + HTML report built inside `alert`'s sender task, lines 221-238). ScanActivity.resendPendingClips (ScanActivity.kt:453-469) enqueues up to 5 clips of up to 49 MB each at startup; TelegramClient has callTimeout 180 s per upload.

**Почему:** Start the app, drive off: the resend of yesterday's clips occupies tg-sender for up to ~15 minutes (5 uploads x 180 s worst case on flaky LTE). A TAIL alert raised during that window sits in the queue behind them — the one notification the app exists for arrives after the follower is gone. The same inversion recurs mid-drive: every 90 s segment upload of the current tail blocks the next escalation alert for up to 3 minutes.

**Как чинить:** Split outgoing traffic into two lanes: a dedicated executor (or higher-priority queue) for text alerts/broadcasts, and a separate one for bulk media (clips, encounter photos, HTML reports). Additionally defer resendPendingClips until a few minutes after startup or send them at lowest priority.

*Проверка (CONFIRMED):* Traced TelegramBot.kt: single 'tg-sender' executor (line 94) carries alert() including the full blacklist package built inside the sender task (vehicleCard photos + buildVehicleReport, lines 212-238), sendClip (259) and broadcast (286). TelegramClient.callTimeout is 180 s (TelegramClient.kt:37) and sendVideo runs once per admin chat. resendPendingClips() is invoked directly in onCreate (ScanActivity.kt:239) and enqueues up to MAX_RESEND_CLIPS=5 clips (457-473); segments are cut every MAX_CLIP_MS=90 s. A TAIL alert enqueued behind these uploads waits exactly as described.

## [runtime] HIGH · defect · Report build/upload and photo sends run on the poller thread; all commands stall

`telegram/TelegramBot.kt`:452

**Что:** handleAction executes synchronously on tg-poller: `report:` calls host.buildReport (full DB scan + photo-embedding HTML build) then sendDocument (upload, callTimeout 180 s) at lines 452-470; `photo` blocks up to 4 s in BotHost.snapshot's latch (ScanActivity.kt:1052-1080); sendVehicleCard uploads up to 6 photos serially (lines 579-581); `wipe:yes` runs the whole disk wipe inline. While any of these runs, `loop()` is not calling getUpdates.

**Почему:** Operator taps '1h report' while driving, then the situation changes and sends /stop or /pause — nothing happens for one to several minutes because the poller is still building/uploading the report. The bot appears dead exactly when a command is urgent; the driver starts fiddling with the phone instead. The long-poll backlog also means Telegram replays the queued commands in a burst afterwards.

**Как чинить:** In handle()/handleAction, dispatch anything that does DB reads, file builds, or uploads to a worker executor (reply 'building...' immediately, deliver asynchronously), keeping the poller loop free to fetch updates continuously.

*Проверка (CONFIRMED):* Traced TelegramBot.kt loop() -> handle() -> handleAction(), all on tg-poller. 'report:' calls host.buildReport then sendDocument inline (453-471); 'photo' blocks up to 4 s in BotHost.snapshot's latch.await (ScanActivity.kt:1103); sendVehicleCard uploads up to 6 photos serially (580-582); 'wipe:yes' runs host.wipeAll() inline (535). While any of these runs, getUpdates is not called, so /stop and /pause sit in Telegram's queue and arrive late in a burst.

## [runtime] HIGH · defect · wipeAll never rebinds FollowEngine: sightings land on deleted trip, stale threat state

`ScanActivity.kt`:1260

**Что:** botHost.wipeAll (ScanActivity.kt:1260-1279) wipes the DB, starts a new trip and updates `tripId`/`tripStartMs`, and clears the registry/evidence map on main — but never calls `follow.bindTrip(newId)` (FollowEngine caches tripId, FollowEngine.kt:150-154, and stamps it on every recordSighting at lines 365-371), and never clears FollowEngine's in-memory `states` (encounter counts, blacklisted/ignored flags, levels).

**Почему:** /wipe mid-drive: every sighting for the rest of the two-hour drive is written against a trip row that no longer exists, so the trip report comes back empty or orphaned. Meanwhile the engine's in-memory encounter counts and threat levels survive the wipe, so cars keep alerting at pre-wipe levels ('3 encounters' for a car the DB says was never seen) — the wipe visibly did not wipe. wipeAll also runs on the tg-poller thread concurrently with ioExecutor writes to the same store.

**Как чинить:** In wipeAll, after store.startTrip: post to main and call follow.bindTrip(tripId) plus a new FollowEngine.reset() that clears per-plate state and reloads blacklist/ignored (now empty); route the wipe itself through ioExecutor so it serializes with the other DB writers.

*Проверка (CONFIRMED):* wipeAll (ScanActivity.kt:1297-1316) starts a new trip and clears registry/evidenceByPlate but never calls follow.bindTrip and never clears FollowEngine.states. FollowEngine caches tripId (FollowEngine.kt:152-154) and stamps the stale id on every recordSighting (365-371), so post-wipe encounters reference the deleted trip; HtmlReportBuilder derives tripIds from encounters (61-65) and fetches track per tripId (128-129), so route maps come back empty for the rest of the drive. In-memory encounter counts, levels and blacklisted flags survive the wipe, so alerting continues at pre-wipe levels while the DB says the car was never seen. wipeAll also runs on the tg-poller thread as claimed.

## [alpr] MEDIUM · defect · truncated() merges two different valid plates when one is a prefix/suffix of the other

`alpr/PlateSimilarity.kt`:74

**Что:** similar() with length diff 1 calls truncated(), which accepts any startsWith/endsWith with short.length >= 5 and requires no confusable character at all. 'AB123' vs 'AB1234' (both complete, valid Latvian registrations in the same letter series) and 'B1234' vs 'AB1234' (1-letter and 2-letter series) are declared the same car. VehicleRegistry consumes this three ways: group counting (line 127), the confirmed-track branch (line 150), and the GLOBAL card dedup (lines 178-181).

**Почему:** Two physically different cars whose plates differ only by number-block length — systematic across a plate series, not a glyph accident — collapse into one card and one follow-engine identity. Per the project's own stated priority, this false merge hides a follower. If a benign 'AB-123' is nearby while 'AB-1234' tails you, the tail's sightings land on the benign card.

**Как чинить:** Only allow the truncation merge when truncation is plausible: the shorter reading's plate box (PlateReading.box, in crop-bitmap pixels; job.width/height are available in VehicleRegistry.submit) touches the crop border on the side of the missing character. At minimum, refuse the merge when BOTH strings are complete valid Latvian plates (parse(..., strict) returns latvian && !corrected for each), since a truncated read that still parses as Latvian is the rare case and a distinct registration the common one.

*Проверка (CONFIRMED):* PlateSimilarity.kt:74-77: truncated() requires only short.length>=5 and startsWith/endsWith — no confusable or border check. 'AB123'/'AB1234' both match the LATVIA regex ([A-Z]{1,4}[0-9]{2,4}) and are declared similar. Consumed at VehicleRegistry.kt:127 (group counting), 150 (confirmed-track branch), 178-180 (global card dedup). The mechanism is real and the false merge does fold two physical cars onto one card. Downgraded to medium: it requires both registrations to be confirmed in the same in-memory session (registry holds max 200 cards, cleared per session), a modest-probability coincidence, unlike the claim-1 path which needs only one bad read.

## [alpr] MEDIUM · defect · correctToLatvian rewrites valid foreign plates into invented Latvian ones before generic()

`alpr/PlateFormats.kt`:59

**Что:** parse() tries correctToLatvian BEFORE the generic fallback. A foreign plate that fails the Latvian regex but contains 1-2 map-listed characters is 'repaired' at cost <= 2 instead of being kept verbatim. Concrete trace: Polish 'WA1234S' -> split 'WA1'+'234S' -> 1->I (cost 1), S->5 (cost 2) -> 'WAI2345', returned as Latvian 'WAI-2345', corrected=true. generic() would have accepted 'WA1234S' unchanged. Same mechanism turns the weight-limit sign '10t' ('10T') into 'I-07' via 1->I, T->7.

**Почему:** The app exists to identify followers, who 'may well be foreign' per the code's own comment. Every read of the Polish car deterministically produces the invented WAI-2345, so the card, Telegram alert, HTML report and DB all carry a registration that does not exist; a later lookup of the real plate finds nothing, and an operator cross-checking the photo sees text that does not match the card.

**Как чинить:** Reorder: attempt correctToLatvian only when generic(cleaned) also fails, or restrict correction to cost 1 when the uncorrected string already passes generic(). (Corrections of strings that pass NOTHING uncorrected, like 'EM72O9', keep working.)

*Проверка (CONFIRMED):* PlateFormats.parse lines 58-61 tries correctToLatvian before generic. Traced 'WA1234S': fails LATVIA; correctToLatvian split=3 gives letterPart 'WA1' (1->I, cost 1) + digitPart '234S' (S->5, cost 2) = 'WAI2345', matches LATVIA, returned as corrected=true — generic('WA1234S') (7 chars, 3 letters, 4 digits) is never reached and would have kept the real Polish registration verbatim. The '10T'->'I-07' trace also checks out (split=1: 1->I cost 1, T->7 cost 2). Deterministic per read, so every card/alert/DB row for such a car carries an invented registration.

## [alpr] MEDIUM · defect · PlateRoi anchor locks onto plates[0] while the registry counts the best-scoring plate

`ScanActivity.kt`:1332

**Что:** rememberPlatePosition uses `outcome.plates.firstOrNull()?.box` (engine JSON order), but VehicleRegistry.submit picks `maxByOrNull(recognitionScore)` filtered by minScore. With detect_minscore set to 0.1 in engineConfig (AlprEngine.kt:253) a vehicle crop routinely yields several plate candidates (bumper stickers, dealer frames, the neighbouring car's plate inside the CROP_MARGIN-expanded box), and nothing guarantees plates[0] is the one being counted.

**Почему:** The two-stage crop then aims every subsequent narrow crop at the wrong text region: the narrow read returns junk or nothing, the anchor is dropped, and the cycle repeats — each iteration burns an engine call (the scarcest resource behind the fair lock) and stalls exactly the high-value close-range car the ROI stage was built for. Worse, if plates[0] is the neighbour's plate, narrow crops actively feed the neighbour's registration into this track's consensus.

**Как чинить:** Anchor the same reading the registry consumed: choose max recognitionScore above minScore (falling back to best below threshold), and among candidates prefer the box nearest the crop centre / largest area.

*Проверка (CONFIRMED):* ScanActivity.kt:1332 uses outcome.plates.firstOrNull()?.box while VehicleRegistry.kt:110-112 uses filter(recognitionScore>=minScore).maxByOrNull(recognitionScore). AlprJson.parse preserves engine JSON order and additionally drops entries rejected by PlateFormats/aspect, so plates[0] is not guaranteed to be the counted reading; nothing in the code sorts by score. detect_minscore=0.1 confirmed at AlprEngine.kt:253. The narrow-crop failure loop is real: line 1334 clears the anchor when a narrow crop returns no plate, so a wrong anchor costs an engine call per cycle. OcrJob carries sourceRect/anchorRect so the proposed fix is implementable.

## [alpr] MEDIUM · defect · Registry counts the best-scoring plate anywhere in the crop with no ownership check

`pipeline/VehicleRegistry.kt`:110

**Что:** submit() selects `outcome.plates.filter{score>=minScore}.maxByOrNull{recognitionScore}` and attributes it to job.trackId unconditionally. The crop is the vehicle box expanded by CROP_MARGIN and, in traffic queues seen through a rear window, overlapping detections mean one car's crop regularly contains a slice of the car beside/behind it — whose closer, sharper plate outscores the crop owner's own plate frame after frame.

**Почему:** The neighbour's plate reaches requiredMatches on the wrong track: the follower's registration is confirmed on an innocent car's track (or vice versa), the follow engine receives sightings with the wrong plate identity, and global dedup then folds the two tracks onto one card. This is a systematic cross-contamination path in dense following traffic — the app's primary scenario.

**Как чинить:** Filter/score readings by geometry: map the track's own detector box into crop-bitmap coordinates (the job already carries sourceRect and anchorRect) and require the plate box centre to fall inside it, or down-weight plates near the crop border.

*Проверка (CONFIRMED):* VehicleRegistry.submit lines 110-112 attributes the best-scoring plate anywhere in the crop to job.trackId with no ownership/geometry check; PlateReading.box exists (AlprResults.kt:45) and OcrJob carries sourceRect and anchorRect (AlprWorker.kt:36,53) but neither is consulted. One correction to the mechanism, not the conclusion: CROP_MARGIN is only 0.06 (FrameProcessor.kt:604), so the neighbour's plate enters via genuinely overlapping detector boxes in dense traffic rather than the margin — which is exactly the app's primary rear-window scenario, so the cross-contamination path stands.

## [alpr] MEDIUM · defect · Strict Latvian gate accepts route signs (E67, A12, P104) and distance panels (RIGA25)

`alpr/PlateFormats.kt`:23

**Что:** LATVIA = ^[A-Z]{1,4}[0-9]{2,4}$ admits 1-letter+2-digit strings, which is exactly Latvian road numbering: A10-A15 main roads, P-roads (P80, P104), E-routes (E22, E67, E77), plus city-distance panels ('RIGA 25' -> cleaned 'RIGA25' -> RIGA-25). These pass even with strictPlateFormat=true — the strict flag never applies because they match the base regex, not the correction path. The aspect gate does not help: plausiblePlateShape (AlprResults.kt:135) accepts anything 1.15-9 wide and returns true when warpedBox is missing or under 4 px, and rectangular sign panels are comfortably in range; detect_minscore 0.1 makes the engine generous with such text boxes.

**Почему:** A gantry or roadside sign passing through several consecutive crops of a tracked car (common under signage with a car close behind) can hit requiredMatches and confirm a phantom vehicle 'E-67', which then enters the follow engine and reappears every time the route is driven — a recurring false 'seen again' signal on exactly the roads the driver repeats.

**Как чинить:** Require 2 letters minimum in the strict Latvian regex (the standard series is exactly two letters + 1-4 digits), keeping 1-letter matches only via the generic path where GENERIC_MIN=4 already blocks 'E67'/'A12'; optionally blacklist the letter+2-digit shape when the letter is A/P/E.

*Проверка (CONFIRMED):* PlateFormats.kt:23 LATVIA = ^([A-Z]{1,4})([0-9]{2,4})$ provably accepts E67, A12, P104 and RIGA25 (cleaned). The strictLatvia flag only gates the generic fallback (line 60) — base-regex matches bypass it, so strict mode does not help. plausiblePlateShape (AlprResults.kt:135-140) returns true when the box is missing or under 4px and accepts aspects 1.15-9, which rectangular sign panels satisfy. The engine reading arbitrary roadside text is not speculative: the file's own header documents measured reads of 'GOOD'/'XAKEP'/'INTERNET' at score 89. Reaching requiredMatches on one track needs the sign to persist across several crops — plausible in slow traffic under signage — so medium is right.

## [alpr] MEDIUM · improvement · Per-char OCR confidences in engine JSON discarded; fusion weights by global min score

`alpr/AlprResults.kt`:116

**Что:** AlprJson.parsePlate reads only confidences[0]/[1], but with recogn_score_type='min' (AlprEngine.kt:269) the ultimateALPR payload's confidences array also carries per-character scores, and the global score equals the WORST character. PlateFusion then weights every position of a read by that single worst-character score.

**Почему:** A read that is 95 everywhere except one smeared character — precisely the read character-voting exists to exploit — carries weight ~40 at all positions and gets outvoted even where it was certain, while a uniformly mediocre read dominates. Weighting each position by that character's own confidence is strictly better evidence and the data is already in the JSON being parsed; on repeated one-char disagreements (the measured EM/EN case) it flips the vote the right way.

**Как чинить:** Parse the remaining confidences entries into PlateReading (e.g. charScores: FloatArray aligned with text) and have PlateFusion.fuse use charScores[position] (falling back to recognitionScore) as the vote weight.

*Проверка (CONFIRMED):* AlprResults.kt:116-117: parsePlate reads only confidences[0] (recognition) and [1] (detection); the rest of the array is discarded. AlprEngine.kt:269 sets recogn_score_type='min', so the global score equals the worst character. PlateFusion.kt:47 weights every position of a read by that single recognitionScore. Not implemented anywhere (PlateReading has no per-char field). The ultimateALPR JSON layout with per-character entries after the two global ones is the same layout assumption the code already relies on for indices 0/1, so the data is plausibly present; mechanism and gain are sound for the stated one-smeared-character case.

## [camera] MEDIUM · defect · Verifier never checks zoom ratio: same-module transitions verify mid-ramp

`camera/LensVerifier.kt`:194

**Что:** LensVerifier.evaluate() matches only LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID and LENS_FOCAL_LENGTH. For any transition between two steps on the SAME module (5x optical -> 6x/10x digital, 10x -> 5x on rotation wrap), every frame delivered after retarget() matches immediately — including frames captured before setZoomRatio took effect and frames captured mid-ramp while Samsung animates the crop. 10 matches at ~30 fps arrive in ~330 ms, VERIFIED is published, CameraController.onVerification() settles the gate, and FrameGate opens while the actual magnification is still the old/intermediate one. Separately, applyZoom()'s ListenableFuture failure is only logged (CameraController.kt:265) — a rejected setZoomRatio leaves the whole dwell running at the previous zoom, fully VERIFIED.

**Почему:** Frames whose true FOV is 5x get attributed to the 10x step for up to the whole dwell if the zoom silently failed, or for the first few hundred ms of every same-module transition. Everything downstream that trusts the step (PlateRoi remapping for the tight plate crop, the 26 px readability floor, tracker scale gates) computes with the wrong pixel-per-degree scale — exactly the 'crop attributed to the wrong lens' failure the gate exists to prevent. The default optical-only plan is protected by the focal check; any plan with a digital step enabled is not.

**Как чинить:** In evaluate(), additionally read CaptureResult.CONTROL_ZOOM_RATIO (always present at minSdk 31) and require it within ~5% of PhysicalLensRoutingPolicy.controlZoomRatio(route, step) before counting a match; pass the expected ratio into retarget(). Also treat a failed setZoomRatio future as gate-closing (call gate.updateLens(usable=false, settled=true) or re-apply) instead of just logging.

*Проверка (CONFIRMED):* Traced LensVerifier.evaluate() (LensVerifier.kt:170-213): it checks only LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID and LENS_FOCAL_LENGTH, never CONTROL_ZOOM_RATIO. CameraCatalog digital steps (lines 165-178) reuse the telephoto module, so 5x<->6x/10x transitions share expectedPhysicalId and expectedFocalMm and every frame after retarget() matches; 10 matches at 30fps opens FrameGate in ~330ms regardless of ramp state. applyZoom()'s future failure is only logged (CameraController.kt:263-266) with no gate action, so a rejected setZoomRatio leaves the whole dwell VERIFIED at the old ratio. Downgraded to medium: the default plan is optical-only (defaultEnabledStepIds returns OPTICAL steps), cross-module transitions are protected by the focal check, the mid-ramp window in the common case is sub-second (frames before VERIFIED are dropped by the gate since transitioning=true), and setZoomRatio failure on a live camera is rare.

## [camera] MEDIUM · defect · Clip deleted on ANY Finalize error, destroying usable evidence of a tail

`camera/VideoRecorder.kt`:93

**Что:** The Finalize handler does `if (event.hasError()) { ...; runCatching { file.delete() } }`. Several VideoRecordEvent.Finalize error codes still produce a playable MP4 — notably ERROR_SOURCE_INACTIVE (use case unbound mid-recording, which is exactly what happens on a forceRebind or the bind-retry-without-video path calling detach()->stop()), ERROR_INSUFFICIENT_STORAGE and ERROR_FILE_SIZE_LIMIT_REACHED, whose whole point is that the file up to the cut is valid.

**Почему:** The one artifact the app exists to produce — the clip proving a car held station behind the driver — can contain 80+ seconds of the pursuit and then be unconditionally deleted because the session rebound or storage ran low in the final second. Evidence loss is unrecoverable.

**Как чинить:** Only delete for error codes with no usable output (ERROR_INVALID_OUTPUT_OPTIONS, ERROR_NO_VALID_DATA, ERROR_ENCODING_FAILED, ERROR_RECORDER_ERROR); for the others check event.outputResults/file size and hand the partial clip to onFinished with a flag.

*Проверка (CONFIRMED):* VideoRecorder.kt:91-93: the Finalize handler deletes the file for ANY hasError() code with no outputResults/size check. ERROR_SOURCE_INACTIVE (system revokes the camera mid-recording — a scenario this codebase explicitly anticipates under heat, ScanActivity.kt:861-862) and ERROR_INSUFFICIENT_STORAGE (free-space is a tracked preflight concern) both finalize a playable MP4 that this code then deletes, destroying tail evidence. Minor overreach in the claim: ERROR_FILE_SIZE_LIMIT_REACHED cannot occur (FileOutputOptions is built without a size limit; segmentation uses a clean watchdog stop() at MAX_CLIP_MS which finalizes without error), and the app's own halt/destroy paths stop recording before unbinding. Core defect stands at medium.

## [camera] MEDIUM · improvement · Pin EIS off (CONTROL_VIDEO_STABILIZATION_MODE_OFF) and OIS on explicitly

`camera/CameraController.kt`:175

**Что:** Neither stabilization key is set, so the vendor decides — and binding VideoCapture is precisely the case where Samsung likes to enable EIS session-wide. EIS crops the FOV (so analysis geometry and the PlateRoi remap no longer match the assumed step FOV), adds a frame of latency, and its rolling-shutter warp bends straight plate edges at tele. OIS, by contrast, is the mechanism that actually cancels the 10-100 Hz engine/road vibration that smears a 240 mm-eq frame, and should be pinned on rather than left to a scene heuristic.

**Почему:** An EIS crop appearing only when clips are recording means the same step yields different fields of view with and without a VideoRecorder bound — overlay drift and inconsistent plate pixel sizes between sessions. Pinned OIS is a direct sharpness win at 3x/5x/10x on a vibrating mount.

**Как чинить:** setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CONTROL_VIDEO_STABILIZATION_MODE_OFF) and (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, LENS_OPTICAL_STABILIZATION_MODE_ON), guarded by LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION. Risks: stabilization mode is per-session on most HALs, so the recorded clip is also unstabilized (acceptable — the clip is evidence, and the rigid mount plus OIS keeps it watchable); a rare HAL may couple OIS to EIS, so confirm via CaptureResult that OIS stays on.

*Проверка (CONFIRMED):* Not implemented: grep finds neither CONTROL_VIDEO_STABILIZATION_MODE nor LENS_OPTICAL_STABILIZATION_MODE anywhere, so both are left to vendor defaults, and the session does bind VideoCapture (VideoRecorder.buildUseCase into the same UseCaseGroup, CameraController.kt:206-213) — the exact configuration where vendor EIS heuristics vary. The geometry argument is real for this codebase: PlateRoi remapping and the overlay assume a stable per-step FOV, and an EIS crop appearing only when the video use case binds would silently break that. Pinning EIS off and OIS on (guarded by the availability key) is cheap, deterministic, and supported via the existing extender. Whether Samsung actually enables EIS here is device behavior, so the realized gain may be a no-op, but the mechanism is sound and the determinism gain stands; medium as claimed.

## [data] MEDIUM · defect · prune() deletes ignored vehicles, so a dismissed car starts alerting again after 30 days

`data/TrackingStore.kt`:771

**Что:** prune() ends with 'DELETE FROM vehicles WHERE blacklisted = 0 AND plate NOT IN (SELECT DISTINCT plate FROM encounters)'. It exempts blacklisted rows but not ignored = 1 rows. The VehicleRow doc and setIgnored() contract say ignored means 'never alerts, never records, never climbs a level again'.

**Почему:** Operator taps ignore on a neighbour's car; the car is then not encountered for a month (holiday, changed parking); prune() at the next trip start (ScanActivity.kt:372) removes its encounters and then its vehicles row, taking the ignored flag with it. The next sighting recreates the row with ignored = 0 via the recordSighting upsert, FollowEngine.loadIgnored() no longer suppresses it, and the car raises 'new contact' alerts again — the exact thing one tap was supposed to stop forever.

**Как чинить:** Change the final delete to 'WHERE blacklisted = 0 AND ignored = 0 AND plate NOT IN (...)' so operator dispositions survive retention like blacklist entries do (an ignored row is a tiny record with no photos, so retention cost is nil).

*Проверка (CONFIRMED):* TrackingStore.prune:770-772 is 'DELETE FROM vehicles WHERE blacklisted = 0 AND plate NOT IN (SELECT DISTINCT plate FROM encounters)' — no ignored=0 exemption. The recordSighting upsert (lines 431-454) never sets 'ignored', so a recreated row gets the column default 0, and FollowEngine.loadIgnored (line 223-235) reads ignoredPlates() which no longer contains the plate; the car alerts again. Mechanism fully verified and it breaks the documented setIgnored contract. Severity corrected to medium: it requires the car to be unseen for the full 30-day retention window, and the harm is a spurious re-alert the operator can dismiss again, not a missed threat or lost evidence.

## [data] MEDIUM · defect · JPEG encoding and file I/O run inside the exclusive recordSighting transaction

`data/TrackingStore.kt`:410

**Что:** recordSighting invokes photoWriter (FollowEngine.kt:387 -> writeJpeg, Bitmap.compress JPEG 85 + FileOutputStream, FollowEngine.kt:525) between beginTransaction and endTransaction. The database is not in WAL mode and beginTransaction takes the exclusive connection lock, so every other store call — bot searchPlates, blacklistedPlates, HtmlReportBuilder queries, updateEvidence — blocks for the full compress+fsync duration on every sighting that wants a photo. Additionally, if any later statement in the transaction throws (e.g. SQLiteFullException in the counts/upsert), the encounter row is rolled back but enc_<id>.jpg stays on disk; prune() only deletes files whose paths appear in encounters.photo (TrackingStore.kt:760), so such orphans are never reclaimed until a full StorageCleaner.wipe.

**Почему:** Under a burst of new encounters (junction with several cars) the DB is serially locked behind repeated JPEG encodes; a concurrent /report or bot query stalls while frames queue behind one lock. The orphan-file path leaks storage in the evidence dir that the retention pass can never see.

**Как чинить:** Move the photo write outside the transaction: commit the sighting first, then write enc_<id>.jpg and run the single 'UPDATE encounters SET photo = ?' as its own statement (the wantsPhoto decision data is already in hand). Alternatively write to a temp file before beginTransaction and only rename+UPDATE inside it.

*Проверка (CONFIRMED):* Verified: photoWriter is invoked between beginTransaction (TrackingStore.kt:333) and endTransaction (line 468), at lines 410-417; it is FollowEngine.writeJpeg (lines 525-531), Bitmap.compress JPEG 85 into a FileOutputStream. Grep confirms no enableWriteAheadLogging/WAL anywhere, and all store methods share one helper.writableDatabase, so bot queries, report queries and updateEvidence serialize behind the exclusive transaction for the compress duration on every photo-worthy sighting. The orphan-file path is also real (rollback removes the encounter row, prune:760-766 only deletes paths present in encounters.photo), with one mitigating nuance the claim misses: the AUTOINCREMENT sequence rolls back too, so the next new encounter usually reuses the id and adopts/overwrites enc_<id>.jpg — the leak is typically transient rather than permanent. Medium is the right severity.

## [data] MEDIUM · defect · Canonical merge keeps winning plate key but overwrites display_plate with losing read

`data/TrackingStore.kt`:437

**Что:** In recordSighting, canonicalPlate() may map the incoming read onto an existing row when the stored spelling wins ('if (PlateSimilarity.prefer(...)) return match.first', line 491). The subsequent vehicles upsert then executes 'display_plate = excluded.display_plate' unconditionally with the ORIGINAL caller-supplied displayPlate (the losing read's formatting) while the row key stays match.first. FollowEngine only guards its in-memory displayPlate for engine-level merges ('if (key == plate) state.displayPlate = displayPlate', FollowEngine.kt:329); DB-side merges against yesterday's rows (the states map is empty at session start) bypass that guard.

**Почему:** Stored row AB123CD with best_score 92; a session starts and the first read comes back AB123CO at score 60. canonicalPlate correctly lands the sighting on AB123CD, but the card's display_plate becomes the misread 'AB123CO' formatting. The HTML report prints displayPlate as the plate ('.put("plate", vehicle.displayPlate)', HtmlReportBuilder.kt:96), so the evidence document shows a wrong registration for the vehicle, and the spelling flip-flops with each low-score read — undermining the vote-count-over-confidence rule this release is supposed to deliver.

**Как чинить:** Make canonicalPlate return whether the incoming spelling won, and when the stored spelling won set display_plate = COALESCE(vehicles.display_plate, excluded.display_plate) in the upsert (only overwrite display_plate on the rename path where the new read is preferred).

*Проверка (CONFIRMED):* Fully traced: canonicalPlate (TrackingStore.kt:490-491) returns match.first when PlateSimilarity.prefer(match.first, match.second, plate, score) says the stored spelling wins (prefer verified: same length -> scoreA >= scoreB), yet the upsert at line 437 executes 'display_plate = excluded.display_plate' unconditionally with the caller-supplied displayPlate of the losing read. FollowEngine's guard 'if (key == plate) state.displayPlate = displayPlate' (line 329) only protects in-memory engine merges; at session start states is empty, so the first misread of yesterday's car passes its own spelling as displayPlate and it lands on the winning row. HtmlReportBuilder:96 prints vehicle.displayPlate as the plate, so the evidence report shows the misread registration. Holds exactly as stated.

## [data] MEDIUM · defect · prune() never deletes turns or trips rows; turnsSince() full-scans the growing table

`data/TrackingStore.kt`:759

**Что:** prune() deletes from sightings, encounters, track and vehicles, but not from turns or trips, despite its own doc ('plate data is personal data, so it does not live forever') — turns rows carry lat/lon of every route decision the driver ever made and accumulate forever. turnsSince() ('SELECT ... FROM turns WHERE t_ms >= ? ORDER BY t_ms LIMIT ?', line 271) has only idx_turn_trip available, so it is a full table scan + sort every time a report is built.

**Почему:** Months of daily driving leaves tens of thousands of turn rows: the driver's complete long-term movement pattern survives every retention pass (a worse privacy exposure than the plate data that IS pruned), the DB file grows without bound, and every /report pays a linearly growing scan while holding the shared connection lock. trips grows too, one row per session forever.

**Как чинить:** Add 'DELETE FROM turns WHERE t_ms < ?' and 'DELETE FROM trips WHERE ended_at IS NOT NULL AND ended_at < ?' to prune(olderThanMs), and create 'CREATE INDEX IF NOT EXISTS idx_turn_t ON turns(t_ms)' in onCreate.

*Проверка (CONFIRMED):* Verified prune() (TrackingStore.kt:759-773): it deletes from sightings, encounters, track and vehicles only; grep shows turns and trips rows are deleted nowhere else except wipeDatabase (line 788). turns stores lat/lon of every route decision (schema lines 185-196) and so outlives the 30-day retention the doc promises. turnsSince (line 271-272) filters on t_ms with only idx_turn_trip on trip_id (line 197) available, so it is a full scan plus sort per report on the shared connection. All parts of the claim hold; medium is appropriate.

## [data] MEDIUM · defect · Deferred (spilled) recognitions rewind last_seen and encounter ended_at

`data/TrackingStore.kt`:444

**Что:** The vehicles upsert sets 'last_seen = excluded.last_seen' and the running-encounter update sets 'ended_at = ?' (line 365) unconditionally with tMs. tMs is capturedAtMs, which FollowEngine explicitly documents as possibly old ('A recognition that waited on disk must be recorded where the vehicle was, not where we are now', FollowEngine.kt:288-292) — the two-stage crop spill queue delivers reads minutes late by design.

**Почему:** Live read at 12:05 sets last_seen=12:05; a spilled crop captured at 12:01 finishes OCR at 12:06 and rewinds last_seen to 12:01 and the encounter's ended_at from 12:05 back to 12:01. Consequences: vehiclesSeenSince(sinceMs) can drop a car that was genuinely just seen, the report's 'last' column and encounter durations are wrong, and a rewound ended_at can make the next sighting's gap check (tMs - endedAt > ENCOUNTER_GAP_MS = 3 min) split one continuous contact into two encounters — inflating the encounters count the follow engine escalates on.

**Как чинить:** Use 'last_seen = MAX(vehicles.last_seen, excluded.last_seen)' in the upsert, and 'ended_at = MAX(ended_at, ?)' in the encounter update, updating end_lat/end_lon only when tMs actually advances the encounter.

*Проверка (CONFIRMED):* Fully traced the late-delivery path: SpillStore preserves original capture time (SpilledCrop.capturedAtMs, meta 't'), AlprWorker.kt:305 sets submittedAtMs = crop.capturedAtMs, ScanActivity.kt:1382 passes capturedAtMs = job.submittedAtMs, FollowEngine.kt:372 binds tMs = capturedAtMs into recordSighting. There, a stale tMs older than the running encounter's ended_at yields a negative gap which passes 'tMs - it.endedAt <= ENCOUNTER_GAP_MS' (line 355), so line 365 rewinds ended_at (and end_lat/end_lon) unconditionally, and the vehicles upsert line 444 rewinds last_seen via 'last_seen = excluded.last_seen'. The next live read then measures its 3-minute gap from the rewound ended_at and can split one continuous contact into two encounters, inflating the count FollowEngine escalates on; vehiclesSeenSince also keys off the rewound last_seen. Holds as stated.

## [follow] MEDIUM · defect · Per-frame scale/aspect gate applied across 12 coasted frames splits identities

`detect/VehicleTracker.kt`:78

**Что:** plausibleMatch bounds (scale 0.45-2.2, aspect 0.55-1.8) are documented as between-two-frames limits, but the comparison is track.box vs the new detection, and during coasting the box is only offset (line 116), never rescaled. After maxMissed=12 missed updates - which under thermal throttling is not a fixed wall time, easily 1-4 s - the size delta is 12 frames' worth. A car closing from ~30 m to ~12 m behind during a 2 s occlusion (truck, wiper sweep) more than doubles in width -> scale gate rejects. A car turning across the frame (side profile to rear, aspect changes 2-3x) is rejected by the aspect gate on reappearance.

**Почему:** The rejected detection spawns a new track id; onTrackLost fires for the old id, so the recognition bookkeeping and the remembered PlateRoi are discarded, and two-stage cropping falls back to the whole-car 1280 px crop - the identity split defeats both the tracker's 12-frame coasting and the new crop pipeline for precisely the approaching-fast car that matters most to follow detection.

**Как чинить:** Scale the tolerance with elapsed misses: allow scale in [MIN^k, MAX^k] with k = min(track.missed + 1, 4) (compare in log space), and skip the aspect check when track.missed > 3 (orientation legitimately changes over an occlusion). Keep the strict bounds for frame-to-frame matches, which is the case the truck-steal comment describes.

*Проверка (CONFIRMED):* Half the claim is correct, half is misattributed. Confirmed: plausibleMatch compares track.box, which during coasting is only offset (line 116) never rescaled, against the new detection, so the documented per-frame bounds are silently applied across up to 12 coasted frames; the ASPECT gate (0.55-1.8) then rejects a legitimate reappearance whose orientation changed over the occlusion (side profile ~2.0-2.5 w/h vs rear ~1.1-1.3 is a ~1.8-2x aspect change) even though IoU passes (width halves -> IoU can be ~0.5), spawning a new id, firing onTrackLost and discarding the PlateRoi. Refuted: the headline closing-car scenario. For a car scaling both dimensions by s, IoU <= 1/s^2 regardless of position, so the claimed 2.5x growth gives IoU <= 0.16 < the 0.25 threshold — the match dies at the IoU check (line 74) before plausibleMatch is consulted, and the scale bounds (0.45-2.2) are strictly looser than what IoU 0.25 permits (~0.5-2.0) for uniform scaling. That identity split predates this gate, and the proposed scale-bound relaxation would not fix it (IoU still rejects). Real defect via the aspect gate only; downgrade high -> medium.

## [follow] MEDIUM · defect · turnsDuringContact never decays; one nearby turn permanently disables the highway gate

`follow/FollowEngine.kt`:265

**Что:** onTurn increments turnsDuringContact for every state whose lastSeenMs falls within 60 s before the turn (lines 265-267), including cars that exited before the junction and were never seen again. The counter is never reset per encounter or segment. classify() (line 468) uses turnsDuringContact == 0 to select the 8 km SUSPECT_HIGHWAY_M gate instead of 2.5 km.

**Почему:** A highway fellow-traveller first glimpsed near an interchange (one turn while it was 'recently seen') and then legitimately re-encountered 100 km later on the same motorway is judged with the 2.5 km city gate forever, reaching SUSPECT from plain co-travel that the straightRoad rule was written to excuse. Combined with the multi-fire and stale-timestamp defects this feeds the TAIL branch (sharedTurns >= 2 && contact >= 5 min).

**Как чинить:** Keep turnsDuringContact per contact segment (reset when the segment closes per the contact-span fix), and only count a turn for a car that was actually seen again within the post-turn window - i.e. derive it from credited turns plus 'seen after' checks rather than a blind pre-window sweep.

*Проверка (CONFIRMED):* Verified: turnsDuringContact appears exactly three times in FollowEngine — init (119), blind increment in onTurn for every state with lastSeenMs within the 60 s pre-window (265-267, including cars that exited before the junction and are never seen again), and the classify read (468) where turnsDuringContact == 0 selects the 8 km SUSPECT_HIGHWAY_M gate vs 2.5 km. No reset anywhere — not per encounter, segment, or trip — so a single turn near first contact permanently switches a highway fellow-traveller to the 2.5 km city gate, and SUSPECT is then reachable from 3 min + 2.5 km of plain co-travel (473). Medium is right: it degrades the straightRoad excuse rather than directly producing TAIL.

## [follow] MEDIUM · improvement · Score turn signatures (consecutive runs, direction patterns), not a bare count

`follow/FollowEngine.kt`:448

**Что:** TurnEvent carries direction and degrees but classify() only counts credits. Store per-state the ordered ids of credited turns and score runs: two CONSECUTIVE shared turns with no missed turn between them are combinatorially far rarer than three shared turns scattered over an hour; a shared right-then-left pair (a box/detour pattern) is stronger evidence than two same-direction credits, which one curved arterial can explain.

**Почему:** Makes TAIL both harder for commuters (scattered same-direction credits on a winding trunk road stop qualifying) and easier for real tails (a deliberate verification detour - turn off, turn back - confirms in two junctions instead of three), improving both error directions using data TripTracker already produces.

**Как чинить:** Keep an ArrayDeque of (turnId, direction) per state fed from creditTurns; in classify, compute the longest consecutive run and direction alternation, e.g. a run >= 2 counts as sharedTurns + 1, and an L/R alternating run of 2 within 3 of our turns qualifies for TAIL directly.

*Проверка (CONFIRMED):* Not implemented: classify() (448-478) uses only the scalar state.sharedTurns; no per-state record of which turns were credited or their directions exists, while TurnEvent (TripTracker 35-42) already carries direction and degrees, so the data is available and the ArrayDeque-per-state cost is trivial. The discrimination argument (consecutive shared turns and L/R alternation are rarer under the null than the same count scattered) is plausible. Two caveats that bound the gain: it is dependent on fixing turn-multi-fire first — today one slow junction can emit 2+ same-direction events, which would fabricate exactly the 'consecutive run' this signal rewards and make false positives worse — and direction quality is currently degraded (U_TURN unreachable, split turns mislabelled ~40-degree events). As a mechanism layered on a corrected detectTurn, sound; medium stands.

## [follow] MEDIUM · improvement · Feed track geometry (range discipline) from VehicleTracker into the follow decision

`detect/VehicleTracker.kt`:14

**Что:** VehicleTrack already has per-frame box height (proportional to inverse distance for a rear view) and lateral center. Summarize per track: median relative range, range variance, and lateral re-centering after our lane changes; pass the summary through the recognition pipeline into onSighting alongside the lens tag.

**Почему:** A tail exhibits range discipline: box-height variance stays low for minutes and the car re-centers behind us after our lane changes; a commuter closes monotonically and overtakes, or drifts away. This separates 'held station' from 'happened to be behind' - exactly the distinction the duration-only follower flag cannot make - using frames the pipeline already processes. Combined with our own speedMps from GeoFix it also enables a 'never overtakes despite opportunity' signal.

**Как чинить:** Accumulate height/center statistics in VehicleTrack (Welford, a few floats), expose a snapshot when a recognition is dispatched, and require low range variance over the follower window before becameFollower sets in FollowEngine, adding the value to FollowEvidence.reasons.

*Проверка (CONFIRMED):* Not implemented: VehicleTrack (VehicleTracker.kt 14-35) stores only box/velocity/score/hits/missed timestamps — no height/center statistics — and FollowEngine.onSighting receives no track geometry. The physics is sound for a rear-window mount within one lens: box height is monotone in inverse range, and Welford accumulation is a few floats per track. Real engineering caveats the claim glosses over but that do not break the mechanism: the tracker resets on every zoom change (reset(), line 126-130) and tracks die after 12 misses, so no single track spans the 120 s follower window — the statistics must be carried as per-recognition snapshots (which the fix does propose) and aggregated per plate and per lens inside FollowEngine; and range variance in stop-go traffic is high for everyone, so the 'low variance' gate needs speed-conditioned thresholds. As a gate on the currently duration-only follower flag (line 338) it attacks a confirmed weakness with data already computed. Medium stands.

## [ideas] MEDIUM · improvement · Register and stack consecutive narrow plate crops into one averaged (2x-grid) image

`pipeline/FrameProcessor.kt`:381

**Что:** The PlateRoi anchor already yields a plate-tight region per frame, but each narrow crop is sent to the engine alone and its pixels are then discarded. The plate is the same rigid object across frames; consecutive narrow crops differ by an almost-pure translation (the anchor is already box-relative, so the residual is a few pixels). Keep a ring of the last 4-6 narrow crops per pending track (a 400x200 RGBA crop is ~320KB; cap the ring to the 1-2 highest-priority tracks), register each to the sharpest one by integer-shift search on downsampled luma (+-6px, coarse-to-fine, sum-of-absolute-differences — microseconds at this size), then accumulate onto a 2x-resolution grid using the sub-pixel shift estimates and average.

**Почему:** Averaging N registered frames cuts sensor noise by sqrt(N) — the dominant degradation for small dusk/shade plates at 4K high ISO — and the sub-pixel 2x accumulation recovers genuine detail beyond any single frame (classic multi-frame super-resolution, no model needed). This attacks the 26-40px plates that currently bounce between low-score disagreeing reads and never converge.

**Как чинить:** Implement PlateStack in Kotlin (IntArray luma ops only). Trigger an extra engine call with the stacked image only when a track has >=2 disagreeing reads or all reads scored below ~70, so it costs an engine slot only where single frames already failed. Reject any frame whose registration SAD residual exceeds a threshold (misregistration ghosting must not reach the engine). Measure: replay corpus — recognition score and exact-read rate of stacked vs best-single crop per track; live — conversion rate of previously non-converging distant tracks.

*Проверка (CONFIRMED):* Not implemented anywhere (no stacking/ring of crops; buffers are pooled and recycled right after submission in AlprWorker, so pixels are indeed discarded). The multi-frame averaging mechanism is textbook-sound and the trigger condition (only for tracks with disagreeing/low reads) bounds the cost. Overstated, though: PlateRoi.project sizes the narrow crop proportionally to the current vehicle box, so consecutive crops differ in scale as the box changes, not by 'almost-pure translation' — registration needs rescaling to a common geometry, and sub-pixel 2x accumulation from an integer SAD search needs a refinement step the claim glosses over. Real but a substantial engineering item with device-dependent gain, not a high-severity sure win; downgraded to medium.

## [ideas] MEDIUM · improvement · Confirm via per-character posterior and back off rechecks instead of flat 2.5s cadence

`pipeline/FrameProcessor.kt`:332

**Что:** Confirmation is 'requiredMatches identical strings' regardless of how decisive the reads were, and every confirmed track is re-read at a flat CONFIRMED_RECHECK_MS=2500 forever (FrameProcessor line 332). TrackConsensus already stores the full variants history, so it can maintain per-position log-odds: accumulate log(confidence) per candidate character per position across reads (using per-character confidences from the per-char-confidence-fusion finding, else whole-read score). The plate posterior is the product of per-position margins.

**Почему:** Engine calls are the scarce resource (one serialized native engine, 4K crops). Today a plate confirmed with three 95-score reads costs the same recheck budget forever as one confirmed by three scraping-by-threshold reads, and a pending track needs the same 3 identical strings whether reads were decisive or noisy. Converting to a posterior both confirms decisive plates in 2 reads and keeps spending on genuinely ambiguous ones — more correctly-read plates per engine-second.

**Как чинить:** Confirm when every position's top character exceeds the runner-up by a fixed log-odds margin AND >=2 reads agree (floor keeps the current safety). After confirmation, grow the recheck cooldown geometrically (2.5s -> 10s -> 30s, capped) while the posterior stays high, and reset it to 2.5s if a recheck disagrees. Expose the margin so scheduleRecognition can rank pending tracks by 'lowest certainty first' among unread ones. Measure: engine calls per confirmed plate, confirmations per minute in dense traffic, and misconfirmation rate on the replay corpus (must not rise).

*Проверка (CONFIRMED):* Core facts hold: VehicleRegistry line 160 confirms on count >= requiredMatches identical (similar-grouped) strings regardless of read decisiveness, TrackConsensus.variants keeps the full history, and FrameProcessor lines 352-356 apply a flat cooldown forever. Detail error: anchored confirmed tracks recheck at CONFIRMED_RECHECK_NARROW_MS=1200, not a flat 2500 — even more frequent, so the budget argument stands but the claim as stated misreads the cadence. Bigger overstatement: confirmed-track rechecks are what feed FollowEngine.onSighting (only CONFIRMED/UPDATED results reach it, ScanActivity 1377), and lastSeenMs drives checkFollowerAbsence (videoAbsentSeconds can be as low as 5 s) and turn crediting — a 30 s backoff would break clip-stop logic and thin contact evidence, so the backoff cap must be far tighter than proposed. Mechanism sound with that constraint; medium, not high.

## [ideas] MEDIUM · improvement · Gate narrow crops on plate-ROI sharpness at native resolution, not the 256px car thumbnail

`pipeline/FrameProcessor.kt`:409

**Что:** submitJob computes quality from thumbnailOf(frame, vehicleRect) — the whole car scaled to 256px — even when the job is a narrow plate crop. On that thumbnail the plate is ~20px wide, so FocusMetric is dominated by car-body edges; the deliberate low band (FocusMetric DEFAULT_TOP 0.35) helps but still mostly measures bumper and lights. Defocus on the plate itself, a shadow edge across it, or plate-scale compression smear are invisible, so BLUR_REJECT_RATIO passes crops whose plate is unreadable and can reject ones whose plate is fine.

**Почему:** The blur gate exists to save engine calls for readable frames; measuring the wrong pixels means it saves the wrong calls. The plate ROI is tiny (about 300x150), so scoring it at native resolution costs microseconds — cheaper than the car thumbnail it would supplement.

**Как чинить:** When plateAnchor exists, run FocusMetric.sharpness over the projected plate rect pixels (full-height band, since the crop IS the plate region) and keep a separate per-track bestPlateQuality with the same decay; gate narrow submissions on that, keep the existing vehicle-level metric for wide crops and for the thumbnail/evidence path. Measure: correlation between logged q= and plates!=0 in the existing per-crop log line, before vs after — the gate is working iff the correlation strengthens and reads-per-engine-call rises.

*Проверка (CONFIRMED):* FrameProcessor.submitJob lines 433-443: quality = sharpness(thumbnailOf(frame, vehicleRect)) — always the whole-vehicle 256 px thumbnail, explicitly also for narrow jobs (the comment at 431 says the score is 'measured on the same picture' for comparability), and the BLUR_REJECT_RATIO gate at line 439 uses it. On a 256 px car thumbnail the plate is ~20 px, so FocusMetric's 0.35-0.95 band is dominated by bumper/lights edges, and plate-local defocus is invisible to the gate exactly as claimed. Scoring the projected plate rect at native resolution is cheap (FocusMetric takes an IntArray and band parameters already). Mechanism sound, not implemented.

## [ideas] MEDIUM · improvement · Under load, prioritize tracks one vote short of confirmation over untouched tracks

`pipeline/FrameProcessor.kt`:337

**Что:** scheduleRecognition's priority gives +40 to tracks with pendingCount==0, i.e. it prefers STARTING new vehicles over FINISHING ones that already have votes. In dense traffic with a saturated queue this spreads engine calls thin: five cars each stuck at 1-2 votes produce zero confirmed plates, while the follow engine — the actual consumer — only sees confirmed reads (FollowEngine.onSighting).

**Почему:** A confirmed plate has step-function value (it starts contact-time accumulation, turn credit, alerts); a pending vote has none until confirmed. Maximizing confirmed-plates-per-second under saturation means finishing the nearest-to-done track first. The current bonus does the opposite exactly when it matters (worker.queueDepth>=2).

**Как чинить:** When queueBusy, replace the pendingCount==0 bonus with a convergence bonus: +60 for tracks with pendingCount == requiredMatches-1, +30 for pendingCount>0, +10 for untouched (keep the current shape when the queue is idle, where breadth is free). One expression change in the priority formula plus reading requiredMatches into FrameProcessor. Measure: confirmed plates per minute and median time-to-confirm in a heavy-traffic replay/drive with queueDepth>=2, before/after.

*Проверка (CONFIRMED):* FrameProcessor lines 360-363: priority = (confirmed ? 0 : 100) + (pendingCount==0 ? 40 : 0) + areaScore*30 + score*10. areaScore is capped at 1.0 and detector score at ~1.0, so the +40 for untouched tracks dominates: any never-read vehicle outranks any vehicle sitting at requiredMatches-1 votes, both in the per-frame candidate sort and in AlprWorker's priority queue (priority is carried on the OcrJob, queue comparator line 116). Under saturation (queueDepth>=2) this maximizes breadth when the step-function value is in finishing, and FollowEngine only consumes confirmed reads. The proposed conditional inversion is a one-expression change and keeps idle-queue behaviour. Accurate as stated.

## [ideas] MEDIUM · improvement · Let truncated reads vote at the positions they actually saw

`alpr/PlateFusion.kt`:39

**Что:** PlateFusion.fuse discards every reading shorter than the longest length: voters = readings.filter { it.text.length == length }. A crop that clipped the last character (common while the anchor drifts or the car sits at the frame edge) agrees perfectly on the six characters it did see, yet contributes nothing. With MAX_READINGS=8, a track with five EM720 truncations and one each of EM7209/EN7209 fuses from just two voters and the disputed M/N position is decided by a coin-flip of scores.

**Почему:** More voters per position means faster convergence and fewer engine calls per confirmed plate — the truncations are already in the history, already paid for, and PlateSimilarity.truncated has already established they are prefix/suffix aligned before they entered the group.

**Как чинить:** Align each shorter read to the fused length: if longest.startsWith(short) it votes positions 0..short.length-1; if endsWith, the tail positions; if both match (ambiguous) it abstains. Weight as today (or per-character with the confidence finding). Guardrails unchanged — result must still pass PlateFormats. Measure: replay corpus, reads-until-correct-confirmation and exact-plate accuracy for tracks containing truncated variants.

*Проверка (CONFIRMED):* PlateFusion.kt line 39: voters = readings.filter { it.text.length == length } — every shorter read is excluded from voting, and with voters.size < 2 the fusion degenerates to best-single-read (line 41). VehicleRegistry line 127 groups readings via PlateSimilarity.similar, which admits one-character truncations (truncated() requires prefix or suffix match), so histories genuinely fill with truncated variants that then contribute nothing; the claimed 5x-EM720 + EM7209 + EN7209 scenario fuses from exactly 2 voters. Caveat: truncations are established relative to the group key, not the fused longest, so the fix's own alignment check (startsWith/endsWith against the fused length, abstain when ambiguous) is necessary and sufficient. Mechanism sound, guardrails preserved, not implemented.

## [ideas] MEDIUM · improvement · Raise an advisory for long-contact tracks that never confirm a plate

`follow/FollowEngine.kt`:274

**Что:** FollowEngine only ever learns about vehicles through onSighting, which fires on confirmed plate reads. A vehicle whose plate is dirty, obscured, angled, or simply absent produces zero follow-engine evidence no matter how long it holds station — the tracker and the make/model classifier both see it the whole time, but that signal dies in TrackRuntime.

**Почему:** This is the cheapest possible countermeasure for a tail to exploit: smear the plate and the entire detection system is blind to you, while the app happily records every honest commuter. The data to close the gap already exists per track: firstSeenMs/lastSeenMs on VehicleTrack, makeModel on TrackRuntime, thumbnails in the crop path.

**Как чинить:** In the scanner service, watch tracks whose lifetime (lastSeenMs-firstSeenMs) exceeds runtime.tailSeconds with confirmedPlate==null; combined with cross-lens survival, re-associate across generations by make/model+colour+size so lens rotation does not reset the clock. Emit a WATCH-grade advisory (UI banner + optional Telegram photo) keyed 'UNKNOWN <make> <colour>', not a DB row, so it cannot pollute plate history. Rate-limit to one advisory per attribute key per trip. Measure: advisories per normal commute (noise floor must stay near zero on highways) and a staged test with a taped-over plate following for tailSeconds.

*Проверка (CONFIRMED):* Verified: FollowEngine's only evidence input is onSighting, and ScanActivity lines 1377-1400 call it exclusively on Change.CONFIRMED/UPDATED, i.e. confirmed plate reads — a plate-less vehicle generates zero follow evidence however long it holds station, while VehicleTrack.firstSeenMs/lastSeenMs and TrackRuntime.makeModel exist unused. Two real caveats that keep this at medium: (1) with a rotating plan the generation reset (see cross-lens finding) caps track lifetime at one dwell, and tracker maxMissed=12 frames splits tracks on brief occlusion, so the advisory as specified depends on the cross-generation attribute re-association the fix itself names as a prerequisite; (2) the fix says 'scanner service' but ScanSessionService owns no logic — the watch loop belongs in ScanActivity. The blind spot is real and the advisory-not-DB-row design is sound.

## [pipeline] MEDIUM · defect · adopt() replays previous-session spills whose trackIds collide with live tracks

`ScanActivity.kt`:193

**Что:** SpillStore.adopt() carries over crops recorded with the previous session's track ids, but each new VehicleTracker starts nextId at 1, so the first cars of the new session reuse exactly those small ids. drainOneSpilled builds an OcrJob with crop.trackId and the CURRENT generation (AlprWorker.kt:302-318), and registry.submit then merges the old car's reads into the live track's consensus: sets its runtime.confirmedPlate to yesterday's plate, moves it onto the 2.5 s CONFIRMED_RECHECK_MS cooldown and ~0 scheduler priority, and clobbers its inFlight flag.

**Почему:** Start the app after a heavy previous drive (spill backlog present) and the first vehicles tracked in the new session — ids 1..N — get labeled with the previous session's plates the moment the engine idles and drains a spill; their real plates are then deprioritized as 'already confirmed'. The SpillStore class doc even states cross-session crops 'could not be merged into the current consensus' and are deleted — adopt() violates the documented invariant.

**Как чинить:** Record a session nonce in the spill JSON; on adopt, either discard mismatched-session files (matching the class doc) or remap their trackIds to values no live track can carry (e.g., negative ids), so the reads still create plate-keyed cards but never touch a live track's consensus or runtime.

*Проверка (CONFIRMED):* ScanActivity.kt:192-195 calls adopt() at every onCreate, which re-queues previous-session files (SpillStore.kt:51-62) rather than deleting them as the class doc (SpillStore.kt:34-35) promises. VehicleTracker.kt:54 starts nextId at 1 per tracker instance, so the new session's first tracks reuse the adopted trackIds. drainOneSpilled (AlprWorker.kt:302-318) stamps crop.trackId with currentGeneration(), and neither onRecognition nor registry.submit checks provenance, so registry.submit (VehicleRegistry.kt:103-106) merges the old car's reads into the live track's runtime and consensus: confirmedPlate set to the previous session's plate, the live car dropped to confirmed-recheck cooldown and ~0 priority, inFlight cleared. Drain fires exactly at session start when the engine idles and ids 1..N are live. Medium is right: requires a leftover backlog, but the doc-stated invariant is plainly violated.

## [pipeline] MEDIUM · improvement · Overflow sacrifices the incoming highest-priority crop; evict-weakest branch is dead code

`pipeline/AlprWorker.kt`:239

**Что:** submit() checks `spill != null` before the priority-eviction branch, and ScanActivity always installs a spill store, so the evict-weakest logic (lines 255-264) never runs in production. When the queue is full, the fresh crop — which the scheduler just ranked highest (unread vehicle, big box, +140 priority) — is diverted to disk behind a 6-deep JPEG writer, while priority~0 confirmed-recheck jobs keep their queue slots and engine time.

**Почему:** Exactly during the heaviest traffic (queue full), the crops most likely to yield a NEW correctly-read plate are deferred to a newest-first disk store that only drains at idle, while already-confirmed cars are re-verified. Inverting this reads measurably more unread plates per minute at peak load, which is the stated goal.

**Как чинить:** When over capacity and the incoming job outranks the weakest queued job, evict the weakest INTO the spill store (its delay costs least) and enqueue the fresh crop; only spill the incoming job when it is itself the weakest.

*Проверка (CONFIRMED):* AlprWorker.kt:238-253: the spill branch returns (true or false) whenever spill != null, so the evict-weakest logic at 255-264 is unreachable in production — ScanActivity.kt:200 unconditionally sets worker.spill and nothing ever nulls it. At overflow the incoming job — which scheduleRecognition just ranked, often +140 for an unread close car — goes to a store that drains only at engine idle, newest-first, while priority~0 confirmed-recheck jobs keep their queue slots and get engine time via the priority queue. The proposed inversion (spill the weakest queued job, enqueue the fresh one) is mechanically feasible with the existing minWithOrNull/remove machinery and is not implemented. Gain plausible: fresh high-priority crops are the ones most likely to read a new plate.

## [pipeline] MEDIUM · improvement · Idle spill drain processes at most one parked crop per 120 ms poll timeout

`pipeline/AlprWorker.kt`:338

**Что:** In loop(), a null poll after 120 ms triggers exactly one drainOneSpilled() and then continues into another blocking 120 ms poll, even when the queue is still empty and hundreds of spilled crops wait. Each deferred crop therefore costs engine-time plus a mandatory 120 ms idle wait.

**Почему:** A 600-crop backlog (maxFiles) costs at least 72 s of pure sleeping on top of OCR time; deferred plates from a traffic burst — including potential followers whose inFlight flag is parked with them — surface minutes later than the engine could deliver. Draining back-to-back while the live queue stays empty roughly doubles deferred plates read per idle minute.

**Как чинить:** After a successful drain, retry immediately while the live queue is empty: `while (running && queue.isEmpty() && drainOneSpilled()) {}` before returning to the blocking poll, so live jobs still preempt instantly via the queue.isEmpty() check.

*Проверка (CONFIRMED):* AlprWorker.kt:333-342: the elvis chain runs drainOneSpilled() exactly once per null poll, yields null, and continues into the next blocking queue.poll(120 ms). With an empty live queue, drain throughput is capped at one crop per (120 ms + OCR time); a 600-file backlog carries >=72 s of pure waiting on top of engine time. No back-to-back drain loop exists anywhere. The proposed while-loop keeps live-job preemption via the queue.isEmpty() check (a live job waits at most one drain's OCR time, same as today). Straightforward, not implemented, gain arithmetic checks out.

## [pipeline] MEDIUM · improvement · submitJob builds the full crop before the blur gate that may discard it, every frame

`pipeline/FrameProcessor.kt`:394

**Что:** The order in submitJob is: Bitmap.createBitmap of the vehicle/plate region with filtered scaling (~5 MB for a close car), then thumbnailOf(frame, vehicleRect), then sharpness, then the queueBusy blur rejection which recycles the crop unused. The thumbnail and focus score are computed from the FRAME and vehicleRect, not from the crop (createThumbnail(crop) is only a fallback for sub-8px rects), so the expensive crop is not needed for the reject decision. Rejected candidates keep lastSubmitMs unchanged, so a blurred track re-enters submitJob on every analysed frame (up to 40 Hz) and repeats the wasted crop+scale each time.

**Почему:** During the ~20-frame decay window after one very sharp frame, each blurred visible track burns roughly 5 MB of allocation plus a filtered scale per analysed frame purely to discard it — GC churn and CPU stolen from detection and from crops that would actually be submitted, on a thermally throttled device where that headroom is plates per minute.

**Как чинить:** Reorder: compute thumbnailOf + sharpness + the bestQuality/blur decision first; only on pass, create the crop and buffer (keep createThumbnail(crop) as fallback only when thumbnailOf returned null, in which case create the crop first).

*Проверка (CONFIRMED):* FrameProcessor.kt submitJob order confirmed: full crop with filtered scaling at 418-428 (a close car is ~1280-wide ARGB, ~5 MB), then thumbnailOf(frame, vehicleRect) at 433, sharpness at 434, and the queueBusy blur reject at 439-443 which recycles the crop unused. The reject decision's inputs come from the frame and vehicleRect, not the crop — createThumbnail(crop) is only the fallback when thumbnailOf returns null (rect < 8 px or thumbnail===frame), effectively unreachable for a >=64 px vehicle rect. lastSubmitMs is written only at line 478 after the gate, so a rejected track passes the cooldown check and repeats the wasted crop every analysed frame until bestQuality decays (0.97/visit, ~10-20 frames for BLUR_REJECT_RATIO 0.55). Reorder is sound and not implemented.

## [runtime] MEDIUM · defect · /unmute toggles mute: sending it while already unmuted silences all alerts

`telegram/TelegramBot.kt`:431

**Что:** Both "/mute" (line 430) and "/unmute" (line 431) dispatch the same toggle action "mute" (handleAction lines 507-514: `muted = !muted`).

**Почему:** The commands are documented in HELP as a pair with fixed semantics. A driver who sends /unmute to make sure alerts are on — a natural thing to do before a suspicious stretch — actually turns them off if they were already on. The confirmation reply is the only clue, easy to miss at the wheel; every subsequent tail alert is then dropped in alert() (line 178: `if (!config.enabled || muted) return`).

**Как чинить:** Give the commands absolute semantics: "/mute" -> muted = true, "/unmute" -> muted = false (e.g. actions "mute:on"/"mute:off"), keeping the panel button as the toggle.

*Проверка (CONFIRMED):* TelegramBot.kt lines 431-432: both "/mute" and "/unmute" dispatch the same action "mute"; handleAction does muted = !muted (508-515). Sending /unmute while already unmuted sets muted=true, and alert() then drops every alert at line 177 (if (!config.enabled || muted) return). HELP (line 727) documents them as a fixed pair.

## [runtime] MEDIUM · defect · haltSession/resumeSession set gate.paused directly, desyncing it from pause flags

`ScanActivity.kt`:546

**Что:** applyPause (line 1479) computes gate.paused = userPaused || lifecyclePaused || parkedPause || !sessionRunning, but resumeSession sets `gate.paused = false` directly (line 546) and haltSession sets it true (line 532). Neither consults the other flags, and the recordingWatchdog keeps running updateParkedState while the session is halted (lines 477-496, 502-522).

**Почему:** Sequence /pause then /stop then /go: the gate opens even though userPaused is still true — the HUD pause button reads 'Resume', /status and the bot panel report paused, yet frames are being recognized; the operator's mental model and the machine diverge for the rest of the drive. Conversely, while halted with autoPauseParked on, the watchdog flips parkedPause and broadcasts sleep/wake messages about a session that is stopped, and the stale parkedPause value then interacts with the next resume.

**Как чинить:** Have haltSession/resumeSession only set sessionRunning and then call applyPause(); gate the parked-state logic and its broadcasts on sessionRunning inside updateParkedState.

*Проверка (CONFIRMED):* applyPause (ScanActivity.kt:1521-1523) computes gate.paused from userPaused || lifecyclePaused || parkedPause || !sessionRunning, but haltSession sets gate.paused=true directly (556) and resumeSession sets gate.paused=false directly (570) without consulting userPaused. After /pause -> /stop -> /go, userPaused stays true (isPaused(), HUD button and bot panel all report paused) while the gate is open and frames are recognized. The recordingWatchdog (500-520) is never cancelled by haltSession and updateParkedState (526-546) has no sessionRunning check, so parked sleep/wake broadcasts fire about a stopped session and parkedPause goes stale exactly as claimed.

## [runtime] MEDIUM · defect · haltSession leaves segmentPlate set: segment cut in flight wedges follow video state

`ScanActivity.kt`:529

**Что:** The watchdog cuts long clips by setting `segmentPlate = target` then recorder.stop() (lines 488-490). If /stop (haltSession, lines 525-535) lands before the async Finalize, haltSession calls videoRecorder?.stop() but leaves segmentPlate set and shuts the camera down. onClipFinished (lines 417-431) then sees `continues == true`, calls videoRecorder?.start(plate) against a VideoCapture whose use case was just unbound, and — crucially — skips `follow?.noteVideoFinished(plate)` on that branch.

**Почему:** During a two-hour drive with an active tail, a Telegram /stop within the finalize window leaves FollowEngine believing a video for that plate is still in progress (noteVideoFinished never fires). After /go, onFollowerConfirmed for the same follower short-circuits and that car is never filmed again for the rest of the drive; at best the failed restart also produces a zero-length orphan file in clips/.

**Как чинить:** In haltSession set segmentPlate = null before stopping the recorder (as onDestroy and applyThermal already do), and in onClipFinished treat a failed restart as terminal: if videoRecorder?.start(plate) != true, call follow?.noteVideoFinished(plate).

*Проверка (CONFIRMED):* The watchdog sets segmentPlate=target then recorder.stop() (ScanActivity.kt:512-513). haltSession (549-559) stops the recorder and shuts down the camera without clearing segmentPlate — unlike onFollowerLost (416), applyThermal (891), setIgnored (1234) and onDestroy (1653), which all clear it. When the async Finalize lands, onClipFinished (421-435) takes the continues branch: the restart against the unbound VideoCapture either returns false (only hideBanner()) or returns true and later finalizes with error (file deleted, onFinished not invoked) — either way follow?.noteVideoFinished(plate) is skipped, state.videoActive stays true (FollowEngine.kt:177-179), and the worthFilming gate at FollowEngine.kt:360-363 never fires onFollowerConfirmed for that plate again until process restart.

## [runtime] MEDIUM · defect · SetupActivity runs storage usage scan and full wipe on the main thread

`SetupActivity.kt`:267

**Что:** confirmWipe (lines 267-284) calls StorageCleaner.usage (bottom-up walk of every photo/clip/report, StorageCleaner.kt:39-47) on the click handler, and the positive button runs StorageCleaner.wipe — deleting the database plus every media file one by one — synchronously on main. ScanActivity's botHost.wipeAll does the same work off-main, so only this path is exposed.

**Почему:** After weeks of driving the data set is thousands of encounter JPEGs plus multi-GB clips; walking then deleting them on the UI thread freezes the setup screen for seconds and trips the 5-second input-dispatch ANR exactly during the 'clean up before a fresh drive' ritual the screen is designed for. An ANR here can leave a half-wiped store (DB cleared, files partially deleted, or vice versa).

**Как чинить:** Move both the usage scan and the wipe onto a background thread (plain Thread or executor, matching testTelegram's pattern), show a progress state, and re-enable the button + toast the WipeStats from runOnUiThread.

*Проверка (CONFIRMED):* confirmWipe (SetupActivity.kt:267-284) calls StorageCleaner.usage (bottom-up walk of every evidence/clip/report file, StorageCleaner.kt:39-47) in the click handler and runs StorageCleaner.wipe (per-file delete plus DB row wipe) synchronously in the dialog's positive-button callback — both on the main thread. testTelegram directly below (294-306) shows the project's own background-thread pattern, so only this path is exposed. With weeks of JPEGs and multi-GB clips the freeze and half-wiped-state risk are real.

## [alpr] LOW · defect · upgradePlate re-keys a card without checking the target key, silently deleting a card

`pipeline/VehicleRegistry.kt`:331

**Что:** upgradePlate does `vehicles.remove(entry.plate); entry.plate = reading.text; vehicles[entry.plate] = entry` with no occupancy check — unlike the operator rename() (line 253), which merges on collision. Pairwise similarity is only enforced at confirmation instants and is not transitive over time (operator rename() can create a card similar to an existing one; vote-driven respellings move keys later), so a vote-driven rename can land exactly on another card's key.

**Почему:** The LinkedHashMap put replaces the other VehicleEntry: a confirmed vehicle — sightings, lenses, thumbnail, follow history linkage by plate — vanishes without any Change event. Low probability, but the failure mode is silent loss of a confirmed (possibly flagged) vehicle.

**Как чинить:** In upgradePlate, when vehicles[reading.text] already holds a different entry, merge into it exactly as rename() does (sum sightings, union lenses, keep better score/thumbnail) instead of overwriting.

*Проверка (CONFIRMED):* VehicleRegistry.kt:327-331: upgradePlate does remove/reassign/put with no check of vehicles[reading.text], while rename() (lines 250-268) merges on collision. The collision is reachable via the confirmed-track branch (line 150 -> touch -> upgradePlate), which never checks the target key: similarity is not transitive (EM7209 and EH7209 can coexist as cards since M-H is not a confusable pair, yet both are one confusion from EN7209, so a vote respell of either can land on a key the other later takes), and operator rename() creates keys without any similarity check. LinkedHashMap.put silently replaces the other VehicleEntry with no Change event. Low severity is accurate.

## [alpr] LOW · improvement · engineConfig: gpgpu_workload_balancing_enabled unset despite thermal being a problem

`alpr/AlprEngine.kt`:248

**Что:** engineConfig sets gpgpu_enabled=true but not the SDK's documented companion key gpgpu_workload_balancing_enabled, which the ultimateALPR configuration docs recommend enabling on embedded/mobile devices to balance load between CPU and GPU instead of saturating CPU cores.

**Почему:** This project just added PowerManager thermal throttling because the phone overheats on a sunlit rear window; shifting engine work off the CPU cores that XNNPACK (YOLO) is also fighting for is a config-only lever aimed directly at that. Needs on-device verification against this SDK build (libttvalpr is ultimateALPR-derived; key names have been stable), so treat it as an experiment, not a guaranteed win.

**Как чинить:** Add put("gpgpu_workload_balancing_enabled", true) to engineConfig and A/B via the existing benchmark path (reinitializeBlocking) for latency and sustained-throughput/thermal behaviour.

*Проверка (CONFIRMED):* AlprEngine.kt engineConfig (lines 244-272) sets gpgpu_enabled=true but contains no gpgpu_workload_balancing_enabled key — not already implemented. The key is a documented ultimateALPR config option recommended for embedded devices, and the proposed A/B path exists: AlprEngine.reinitializeBlocking (line 144) is already used by OcrBenchmark.kt. Mechanism sound, gain plausible but device-dependent; the claim correctly frames it as an experiment, so low/improvement is the right rating.

## [camera] LOW · defect · Pause during a lens transition zeroes the dwell; resume advances immediately

`camera/LensRotationScheduler.kt`:78

**Что:** setPaused(true) captures remainingWhenPaused only `if (holding)`; paused during the settle window (holding == false) leaves it 0. While paused, holding still becomes true — either via onStepSettled() (the controller settles regardless of pause) or via the ticker's 4 s settle-timeout branch at line 115, which is not guarded by `paused`. setPaused(false) then unconditionally sets holdUntil = now + remainingWhenPaused = now + 0, and the next tick advances instantly.

**Почему:** With thermal throttling now pausing/resuming the pipeline (PowerManager), any pause that lands in the ≤2 s transition window causes the step's entire dwell to be skipped on resume — the rotation churns lenses with no recognition time. Frequent thermal pauses degrade into a rotation that spends most of its wall clock switching. Also visible as 'remaining 0:00' on the HUD while paused.

**Как чинить:** Track whether the pause began while holding; on resume, if it did not, leave holdUntil as set by onStepSettled (or grant the full dwellMs). Additionally guard the ticker's settle-timeout branch with !paused so it cannot start a dwell during a pause.

*Проверка (CONFIRMED):* Mechanism traced and correct: setPaused(true) with holding == false leaves remainingWhenPaused at 0 (LensRotationScheduler.kt:75); while paused, holding can still become true via onStepSettled (the verifier keeps receiving capture callbacks — pause never unbinds the camera and the 2s settle ceiling posts regardless) or via the ticker's 4s settle-timeout branch at line 115 which is not guarded by paused; setPaused(false) then sets holdUntil = now + 0 (line 78) and the next 100ms tick advances. However the claimed driver is wrong: thermal throttling never pauses the scheduler — applyThermal (ScanActivity.kt:876-908) only sets processor.throttle and stops video. Real triggers are user/parked/lifecycle pause via applyPause (ScanActivity.kt:1521-1524); parked auto-pause does cycle at traffic stops, but only a pause that begins inside the ~2s settle window loses anything, and the cost is exactly one step's dwell, after which the rotation is normal again. Downgraded to low.

## [camera] LOW · improvement · Set CONTROL_AE_ANTIBANDING_MODE_OFF in daylight to avoid an 8-10 ms exposure floor

`camera/CameraController.kt`:175

**Что:** Antibanding defaults to AUTO. When the flicker detector triggers — and PWM-driven LED DRLs/headlights of the very car being tracked can trigger it — the HAL restricts exposure to multiples of the flicker period (>= 8.3/10 ms), an order of magnitude above the 1-3 ms daylight AE would otherwise pick.

**Почему:** A silently imposed 10 ms floor reintroduces exactly the tele motion smear the exposure-ceiling work removes, and it comes and goes with traffic, which makes field results look random.

**Как чинить:** setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CONTROL_AE_ANTIBANDING_MODE_OFF) after checking CONTROL_AE_AVAILABLE_ANTIBANDING_MODES contains OFF. Risks: none for passive daylight plates (they do not flicker); some vendor HALs ignore OFF — verify via the CaptureResult echo; night/artificial light would band, but night is out of scope — gate on the daylight config.

*Проверка (UNCERTAIN):* Not implemented (no ANTIBANDING key anywhere — default AUTO stands, CameraController.kt:175-192 sets only AF/NR/EDGE), and the key is settable via the existing extender. But the claimed gain rests entirely on the Samsung HAL imposing an 8-10ms exposure floor in bright daylight when its flicker detector triggers on PWM DRLs: at base ISO a forced 10ms would overexpose several stops, and typical HAL implementations skip banding quantization when the needed exposure is below one flicker period, so whether AUTO ever costs anything here cannot be settled from code. Downgraded to low pending the CaptureResult-echo verification the claim itself proposes.

## [camera] LOW · improvement · Trial NOISE_REDUCTION MINIMAL + EDGE_MODE_OFF for the OCR stream

`camera/CameraController.kt`:181

**Что:** The extender currently requests NOISE_REDUCTION_MODE_FAST and EDGE_MODE_FAST. Spatial NR smears the thin strokes of distant plate glyphs (3-5 px at the 26 px floor), and edge enhancement adds overshoot halos that double-strike glyph boundaries — both are tuned for human viewing, not OCR. In daylight the sensor sits at base ISO, so NR is doing almost nothing useful while still costing stroke fidelity.

**Почему:** Cleaner glyph edges at native resolution feed directly into the new tight plate crops and PlateFusion character voting; FAST-mode halos are a plausible contributor to systematic 8/B and 0/D confusions at range.

**Как чинить:** Request CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL and CaptureRequest.EDGE_MODE_OFF, checking NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES / EDGE_AVAILABLE_EDGE_MODES first and confirming the CaptureResult echoes them (some HALs only honor MINIMAL on reprocessable sessions and silently substitute FAST). Risk: if the vendor pipeline ignores the keys this is a no-op; A/B on-device with FocusMetric and per-character vote agreement as the metric before keeping it.

*Проверка (CONFIRMED):* Premise confirmed: the extender explicitly requests NOISE_REDUCTION_MODE_FAST and EDGE_MODE_FAST (CameraController.kt:181-188), and no MINIMAL/OFF variant exists anywhere. The mechanism (spatial NR erodes 3-5px glyph strokes at the 26px floor; edge enhancement halos double-strike boundaries; daylight base-ISO makes NR nearly free to drop) is standard OCR-pipeline practice and feeds directly into the new native-resolution tight plate crops. The claim correctly demands availability-mode checks, CaptureResult echo verification, and an on-device A/B before keeping it. Low as claimed.

## [data] LOW · defect · Report built through 5+ full in-memory copies of a ~15 MB string; OOM risk mid-session

`report/HtmlReportBuilder.kt`:168

**Что:** With maxPhotoBytes = 12 MB of base64 in the JSON, build() materialises: (1) the JSONObject tree holding every data URI, (2) payload = .toString() (~24 MB as UTF-16, plus StringBuilder doubling garbage), (3) another full copy from .replace("<", "\\u003c"), (4) the interpolated template string in document() including inlined leaflet.js, and (5) yet another full copy from .trimIndent() applied to the entire interpolated document, before (6) writeText's UTF-8 byte[]. Peak transient footprint is on the order of 100-150 MB of large contiguous char[] allocations.

**Почему:** This runs while the scan session is live: the heap already holds 4K RGBA analysis buffers (~33 MB each), the ONNX arena and possibly an active video encode. Large contiguous char[] requests are exactly what triggers OutOfMemoryError on a fragmented Android heap even when total free memory looks sufficient — a /report command taken mid-drive can kill the whole surveillance session and lose the trip.

**Как чинить:** Stream the document: pre-split the HTML template into static head/tail (statically trimmed, no runtime trimIndent), open a BufferedWriter on the target file, write the head, then stream the JSON payload embedding each photo's base64 one file at a time (escaping '<' as it is written), then write the tail. Peak memory drops to roughly a single photo's base64.

*Проверка (CONFIRMED):* The copy chain is real and verified in code: JSONObject tree with 12MB of base64 (Options.maxPhotoBytes), payload.toString() (line 177), literal-string .replace("<", "\\u003c") full copy (line 179), interpolation into the document template, runtime .trimIndent() over the whole interpolated ~30MB document (line 511), then writeText's UTF-8 byte[]. However the severity rationale overstates heap pressure: CameraX ImageProxy 4K buffers, the ONNX arena, and Bitmap pixel data (API 26+) are native allocations that do not count against the Java heap, and ART's large object space does not suffer Dalvik-style contiguous-array fragmentation. Concurrent Java-heap peak is roughly payload+document+trimmed ~80-90MB, which a flagship's 256MB+ heap limit normally absorbs. Real, wasteful, worth the streaming fix, but the mid-session-OOM likelihood is overstated: severity low.

## [data] LOW · defect · onUpgrade swallows any ALTER failure while the version still commits to 3

`data/TrackingStore.kt`:211

**Что:** onUpgrade runs 'runCatching { db.execSQL("ALTER TABLE vehicles ADD COLUMN ignored ...") }' assuming the only possible failure is 'duplicate column name'. SQLiteOpenHelper stamps the new version (3) after onUpgrade returns regardless of what happened, and onUpgrade never runs again for this database.

**Почему:** If the ALTER fails for any other reason on a real v2 device (SQLITE_FULL / I/O error during the schema rewrite), the exception is silently discarded, the DB is permanently marked version 3 without the column, and every query built from VEHICLE_COLUMNS (which names 'ignored'), plus setIgnored/ignoredPlates/mergeInto, throws 'no such column: ignored' forever — the store is bricked with no retry path.

**Как чинить:** Before the ALTER, check PRAGMA table_info(vehicles) for the 'ignored' column and skip only when it already exists; let any other ALTER failure propagate so SQLiteOpenHelper does not commit the version bump.

*Проверка (CONFIRMED):* Verified onUpgrade (TrackingStore.kt:205-212): runCatching swallows every failure of the ALTER, not just 'duplicate column name'; SQLiteOpenHelper stamps version 3 once onUpgrade returns normally, and it never re-runs. VEHICLE_COLUMNS (lines 875-878) selects 'ignored', so every vehicle/search/blacklist query, plus setIgnored/ignoredPlates/mergeInto, would throw 'no such column: ignored' permanently with no retry path. The mechanism is exactly as claimed; it requires a rare I/O or disk-full failure at the precise moment of a v2->v3 upgrade, so the claimed low severity is correct.

## [follow] LOW · defect · Merge/rename rewrites pendingTurns but not recentSightings; turn credit lost

`follow/FollowEngine.kt`:423

**Что:** canonicalKey (lines 423-426) and rename (204-207) rewrite PendingTurn.before/credited but leave recentSightings entries under the old key. Entries live SIGHTING_LOG_MS = 180 s. Every onTurn in that window snapshots the OLD key into before (line 258), while creditTurns is called with the NEW key and tests 'plate in turn.before' (line 436) - never matches.

**Почему:** Canonical renames happen mid-pursuit, while the car is behind us being read repeatedly and a higher-scoring spelling arrives. For up to 60 s of pre-turn history after each rename, the exact car being followed collects no sharedTurns for turns it demonstrably copied - a false negative concentrated on actively-followed vehicles. The comment at line 422 shows the author knew turn credit is plate-keyed but missed the second structure.

**Как чинить:** In both rename paths, walk recentSightings and replace old-key entries with the new key (ArrayDeque of pairs; rebuild in place preserving order).

*Проверка (CONFIRMED):* The structural fact is confirmed: rename (204-207) and canonicalKey (423-426) rewrite pendingTurns only; recentSightings entries keep the old key. But the claimed impact ('never matches', up to 60 s of lost credit per rename) is substantially overstated: canonicalKey runs inside onSighting, and the very read that triggers the rename appends the NEW key to recentSightings at line 341 in the same call, so any later turn's before-set contains the new key and creditTurns matches normally; turns fired before the rename have their before/credited sets rewritten. The residual loss windows are narrow: (a) the renaming read is throttled — canonicalKey at line 300 executes before the minSightingIntervalMs return at 303, so the rename happens without the addLast — leaving only old-key entries until the next recorded read (~2-5 s for an actively read car); (b) an operator rename() while the car is momentarily unread. A real inconsistency worth the one-line fix, but the '60 s of no sharedTurns on the actively-followed car' scenario mostly cannot occur. Downgrade to low.

## [ideas] LOW · improvement · Upscale small-plate narrow crops 2x before submitting them to the engine

`pipeline/FrameProcessor.kt`:385

**Что:** submitJob only ever downscales (scale = min(1f, MAX_CROP_WIDTH/cropWidth)); a narrow crop of a distant plate goes to the engine at, say, 180x90 with 30px of plate. OCR CNNs have an effective minimum character size, and ultimateALPR's own recognizer resamples internally with plain interpolation; a controlled 2x bicubic upscale in Kotlin (or Bitmap filter) before submission gives its detector/recognizer a target within its comfortable scale range and is measurably different from the engine's internal handling on some SDK builds.

**Почему:** The 26px readability floor currently discards a whole band of nearly-readable followers while busy. If 2x upscaling moves the practical floor from ~26px down to ~20px of true optical plate width, the readable-distance radius grows ~25% with zero extra engine calls per read — it only changes what the existing call sees.

**Как чинить:** For narrow crops with projected plate width under ~45px, scale the crop 2x (cap at MAX_CROP_WIDTH) before the buffer copy. This is a pure A/B: the replay corpus makes it decisive — run every captured small crop through the engine as-is and at 2x, compare read rate and scores. Ship only if the replay shows a win; the change is three lines behind a flag.

*Проверка (UNCERTAIN):* Code facts check out: submitJob line 409 only ever downscales (scale = min(1f, MAX_CROP_WIDTH/cropWidth)), so a distant narrow crop reaches the engine at native small size. But whether a pre-submission 2x bicubic upscale beats the closed engine's internal resampling is purely a property of the native SDK (its interpolation, and interactions with pyramidal_search_min_image_size_inpixels=320) that cannot be settled by reading code — the claim itself says ship only on a replay win. Also its headline benefit is misreasoned against the code path: the MIN_PLATE_PX=26 floor (lines 333-335) applies only to unanchored tracks taking WIDE crops, while the proposed change targets narrow (anchored) crops that bypass that gate entirely — the first read of a distant car is unaffected, so 'readable-distance radius grows ~25%' does not follow. Cheap A/B experiment, low severity.

## [ideas] LOW · improvement · Conditionally contrast-stretch dark/low-contrast narrow crops before submission

`pipeline/FrameProcessor.kt`:429

**Что:** Crops go to the engine exactly as captured. FocusMetric already computes luma mean and variance of the region; when the plate band is dark or flat (shade, dusk, backlit tailgate) a plain per-crop linear stretch of the luma histogram (percentile 2-98 to full range, applied to RGB jointly) costs ~1ms on a 200x100 narrow crop in a Kotlin loop and needs no new dependency. Apply only when measured contrast is below a threshold, and only to narrow crops (the make/model classifiers keep seeing untouched wide crops; klass_vcr_gamma stays theirs).

**Почему:** The FocusMetric work already established that shaded plates are the hard regime; a recognizer scores plainly higher on stretched low-contrast text in that regime. Gated application bounds the downside — normal crops are untouched.

**Как чинить:** Add the stretch between crop creation and copyPixelsToBuffer, keyed off the plate-ROI contrast from the plate-band-focus finding. Validate exclusively on the replay corpus first (same crops, stretched vs not, read rate and scores on the low-contrast subset); ship only on a demonstrated win, since the engine's internal normalization may already cover part of this.

*Проверка (UNCERTAIN):* Facts verified: crops go to the engine exactly as captured (submitJob copies pixels straight to the pooled buffer, lines 450-455), and FocusMetric computes luma mean/variance internally but returns only the ratio — the contrast value would need exposing. The gain, however, depends entirely on the closed recognizer's internal input normalization: a per-crop linear stretch is a monotone transform that standard CNN input normalization largely reproduces, klass_vcr_gamma applies to the classifiers only, and the claim itself concedes 'the engine's internal normalization may already cover part of this' and gates shipping on a replay win. That question cannot be answered by reading code; severity low is already appropriate for a validate-first experiment.

## [pipeline] LOW · defect · SpillStore.poll races offer on the newest file: torn JPEG or missing JSON sidecar

`pipeline/SpillStore.kt`:111

**Что:** offer() runs on the alpr-spill thread and writes the .jpg then the .json (lines 83-98); poll() runs on the alpr-worker thread and picks the lexically newest .jpg (line 112). At the burst/idle boundary (queue fills, spills scheduled, engine drains the queue and immediately idles into drainOneSpilled) poll can grab the file offer is still writing: decodeFile returns null but the file is deleted and queued decremented anyway (crop lost; counter decremented before offer incremented it, so count goes negative and the maxFiles gate weakens), or it decodes a complete jpg whose json does not exist yet, yielding trackId=-1 and default time/position.

**Почему:** A crop parked specifically so it would not be lost is deleted unread, and trackId=-1 results feed a consensus entry no track loss ever cleans (compounding the dead-track leak) while the follow engine receives a sighting with odometer 0 and no coordinates.

**Как чинить:** In offer(), write both files under temporary names and rename the .jpg into place last (after the .json exists); in poll(), skip any .jpg without its .json, and only decrement counters for files offer actually accounted.

*Проверка (CONFIRMED):* offer() runs on the alpr-spill thread and writes the .jpg (SpillStore.kt:83-87), then the .json (98), then increments counters (100-101); poll() on the alpr-worker thread lists .jpg files and takes the lexically newest (111-112) — which is precisely the file being written, since sequence numbers are monotonic. On a null decode poll still deletes the file and decrements queued (130-133), losing the crop and skewing the counter that gates maxFiles; a complete jpg without its json yields trackId=-1 with default time/position (119-126), feeding the never-cleaned consensus id. The burst/idle boundary is reachable (e.g., a queue of stale jobs drains at zero engine cost while the 6-deep spill writer is still busy), though the window is narrow — low severity is correct.

## [pipeline] LOW · defect · stop() shuts down spillExecutor permanently; overflow crops are lost after a restart

`pipeline/AlprWorker.kt`:205

**Что:** stop() calls spillExecutor.shutdown() but start() only recreates the worker thread. onEngineStatus stops the worker on a runtimeLimited status and starts the same instance again when the engine reports READY (ScanActivity.kt:1416/1421). After that cycle, every spillExecutor.execute throws RejectedExecutionException, runCatching swallows it, and submit() falls into the drop path: overflow crops are discarded with only a droppedJobs increment, and the eviction fallback is skipped because spill is still non-null.

**Почему:** After any stop/start cycle of the same AlprWorker, the disk overflow feature — the mechanism that exists so bursts never lose crops — is silently dead for the rest of the process, and each drop also strands its track's inFlight flag (see inflight-leak-on-discard).

**Как чинить:** Recreate the executor in start() when it is shut down, or move the shutdown into a separate terminal close() called only from onDestroy.

*Проверка (CONFIRMED):* spillExecutor is a val constructed once (AlprWorker.kt:125-132); stop() calls spillExecutor.shutdown() (205) and start() (185-192) only recreates the worker thread. The stop/start cycle on the same instance is reachable: runtimeLimited -> worker.stop() (ScanActivity.kt:1417), then a READY publish — e.g., reinitializeBlocking from the benchmark publishes READY per variant (AlprEngine.kt:163,175) — -> worker.start() (1421-1422). Afterwards runCatching{spillExecutor.execute} fails, accepted=false, and submit() takes the drop path at 250-252, returning before the eviction branch. Core defect confirmed at low. One correction to the why: 'each drop also strands its track's inFlight flag' is wrong — this path returns false from submit(), and FrameProcessor.kt:480-482 clears inFlight on a false return; crops are lost but the flag is not stranded.

## [runtime] LOW · defect · Vehicle dialog ignore button writes SQLite on main, can block behind report build

`ScanActivity.kt`:1201

**Что:** botHost.setIgnored runs `store.setIgnored(parsed.key, ignored)` (line 1201) on the caller's thread. From Telegram the caller is tg-poller (fine), but showVehicle's neutral button (lines 1565-1571) calls it on main. The same SQLiteOpenHelper connection is simultaneously used by the tg-poller (buildReport's big joins), tg-sender (vehicleCard in alert), and ioExecutor (sighting writes). The adjacent blacklist button is safe — it goes through follow.setBlacklisted which uses io.execute (FollowEngine.kt:185).

**Почему:** Tap 'Ignore' on a neighbour's car while an HTML report is being built or a batch of encounter photos is being queried: SQLite serializes on the single connection, so the main thread blocks for however long the concurrent statement runs — a visible multi-second UI freeze while driving, ANR in the worst case.

**Как чинить:** In botHost.setIgnored (and renamePlate, which has the same direct store call at line 1215), wrap the store write in ioExecutor.execute { ... }, mirroring how setBlacklist's follow-engine path already defers its write.

*Проверка (CONFIRMED):* Traced: botHost.setIgnored runs store.setIgnored on the caller's thread (ScanActivity.kt:1238) and the vehicle dialog's neutral button invokes it on main (1607-1614); the adjacent blacklist path defers via FollowEngine.setBlacklisted's io.execute (FollowEngine.kt:185). TrackingStore never enables WAL (no enableWriteAheadLogging anywhere), so all threads share one connection and the main-thread write queues behind whatever statement tg-poller/tg-sender/ioExecutor holds. Overstated, though: the connection is released between statements (report photo-embedding happens outside cursors), so the realistic block is one statement/transaction — jank, rarely multi-second, ANR unlikely. Also the renamePlate aside is wrong: /rename is only reachable from tg-poller, never main. Real defect at low severity.

## [runtime] LOW · defect · onDestroy blocks main up to ~4 s: sleep, two awaitTerminations, worker.stop

`ScanActivity.kt`:1614

**Что:** onDestroy chains Thread.sleep(1200) for clip finalize (line 1614), bot.stop() whose sender.awaitTermination waits 2 s while the farewell broadcast does network I/O (TelegramBot.kt:166-167), worker.stop() which can wait for the native ALPR engine to finish the in-flight read behind its fair lock, and analysisExecutor.awaitTermination(400 ms) — all on the main thread.

**Почему:** Pressing Back after a drive freezes the UI for 3.5-4+ seconds; if the sender is mid-upload of a clip when stop() is called, or the native engine is deep in a UHD crop, the total crosses the 5 s input-dispatch ANR threshold and the system offers to kill the process before finishTrip and the clip finalize complete.

**Как чинить:** Replace the sleep with the existing async Finalize callback (deliverClip already survives restarts via the sent_/tail_ rename protocol, so waiting is unnecessary), make bot.stop() skip or not await the farewell broadcast, and drop the awaitTermination calls — shutdown() alone is enough on the way out.

*Проверка (CONFIRMED):* onDestroy (ScanActivity.kt:1641-1680) does block main: Thread.sleep(1200) when a recording is active (1654-1657), bot.stop() whose sender.awaitTermination(2 s) burns the full 2 s whenever the sender is mid-upload (TelegramBot.kt:166-167), and analysisExecutor.awaitTermination(400 ms). But the claim misreads worker.stop(): AlprWorker.stop (AlprWorker.kt:194-206) sets running=false, interrupts, and drains the queue with non-blocking poll() — it never joins the worker thread or waits for the native engine. Worst realistic block is ~3.6 s, so the 'crosses 5 s ANR' leg does not hold; this is a multi-second exit freeze, not an ANR-and-lost-trip scenario. Real but overstated: low.



---

# Ревизия V4 — 03.08.2026

Шесть ревьюеров по диффу двух дней, каждая находка атакована отдельным проверяющим.
Выжило 42 регрессий в новом коде. План исправлений:

# LensALPR — pre-drive fix list

26 findings, 22 distinct defects. Ranked by what wrecks the next drive, not by label severity.

---

## MUST FIX BEFORE DRIVING

**1. Narrow crop locks a track onto the neighbouring car's plate** — `alpr/PlateRoi.kt:83`, `pipeline/FrameProcessor.kt:406/414`, `ScanActivity.kt:1511-1535`
The only stage-1 sanity check is a tautology (sourceRect == anchorRect, so the anchor is always in [0,1]), and once drifted it re-stamps its own TTL forever because `owesVehicleLook` is gated on `runtime.makeModel == null`. Validate the anchor against the *unpadded* `track.box` (reject a plate centre outside the inner 80%, or a width implausible against `estimatedPlatePixels`), and drop the `makeModel == null` condition so a whole-vehicle read is forced periodically. **If you cannot land both today, ship with the narrow-crop path disabled** — a confidently wrong plate is worse than a slower correct one.

**2. Camera can go blind and stay blind — three defects that chain.** Fix all three together; each one alone leaves the chain live.
 - `ScanActivity.kt:577` — the stall ladder counts watchdog *ticks*, not failed recoveries: strike 2 at t≈20 s tears down the rebind strike 1 just issued, strike 3 disarms recovery permanently. Record `lastRebindAtMs` and require `elapsedRealtime() - lastRebindAtMs >= STALL_LIMIT_MS` before the next strike; retry with backoff instead of falling into `else -> Unit`.
 - `camera/CameraController.kt:270` — a failed re-bind leaves `camera`/`boundRoute` pointing at torn-down use cases, and since the Samsung route never changes `needsRebind` is false forever after. Null `camera`/`analysis`/`preview`/`boundRoute`/`boundPhysicalId` on the failure path and call `onError`.
 - `camera/CameraController.kt:307` — the `setZoomRatio` failure listener writes `gate.updateLens(usable=false, settled=true)` with no generation or liveness guard, and nothing ever re-raises `lensUsable` in that generation. Capture `val gen = generation` and no-op when it moved, add a `closed` flag from `shutdown()`, ignore `OperationCanceledException`, and never write `settled = true` from this path.

**3. Recovered-from-disk crops are fed to the follow engine as live observations** — one root cause, four findings (`FollowEngine.kt:212`, `:252`, `:401`, `:438`)
`ScanActivity.kt:1556` hands `job.submittedAtMs` (capture time) and `job.odometerM` (capture odometer) straight into `follow.onSighting` with no freshness gate, while `SpillStore.poll()` drains **newest-first** (confirmed at `SpillStore.kt:127-131`), so a backlog delivers monotonically *decreasing* timestamps and odometer readings. Gate it: skip `follow.onSighting` entirely when `now - job.submittedAtMs > ~10 s` (let the crop still vote in plate consensus), and independently make `state.lastOdometerM = odometer` conditional on `eventMs >= state.lastSeenMs` so the pair moves together (`FollowEngine.kt:438-439`). Add a wall-clock `lastDeliveredMs` for `checkFollowerAbsence` so liveness never reads capture time. **Do not apply the throttle fix as written in that finding** — `eventMs - lastRecordedMs` under newest-first drain rejects every subsequent recovered read.

**4. One junction emits two turns (or none)** — `track/TripTracker.kt:190-240`
`MANOEUVRE_MAX_MS` force-closes mid-roundabout and resets `inManoeuvre`/`headingWindow` with no refractory period, so the tail of the same junction re-arms in ~2 s and credits a second shared turn — and `classify()` reaches TAIL at `sharedTurns >= 3`. Emit the running signed extremum of `manoeuvreTotal` rather than the instantaneous sum, and refuse to arm a new manoeuvre until `CALM_SAMPLES` calm samples have passed (apply the same guard to the `!usable` path at 176-180).

**5. Consensus pool imports every parked group under an arbitrary HashMap label** — `pipeline/VehicleRegistry.kt:311/336/347`
`putAll` transfers votes for plates that were never compared against the adopting track, and the lens plan parks groups constantly. Park only the matched group (`Orphaned(plate, counts[plate], variants[plate])`), pick the label with `maxByOrNull { it.value }`, and drop the rest.

**6. `orphanCounts` is never consumed, so one read can confirm a card** — `pipeline/VehicleRegistry.kt:256`
The tally survives `createEntry`, `trim()` and `rename()`, and because the group is keyed by `PlateSimilarity.similar` two different cars one confusable character apart share it. Remove the group when it produces a card and whenever that entry leaves `vehicles`; age entries out after a few minutes.

**7. `correctToLatvian` rebuilds the road numbers `isSignage` just rejected** — `alpr/PlateFormats.kt:70`
"P1O4" → "P-104", "R1GA25" → "RIGA25" — the O/0 flip is exactly how a distant sign is misread. Re-run `isSignage(plate.key)` on the output of `correctToLatvian` before returning.

**8. `/rename` inflates contact to the wall-clock span between two cars' histories** — `follow/FollowEngine.kt:273-291`
`maxOf` on the banked totals plus a union of both open segments turns two 30 s glimpses an hour apart into an hour of contact, and `classify()` runs immediately after. Bank both sides (`contactMsBanked += state.contactMs() + (lastSeenMs - firstSeenMs)`, same for metres), then start one fresh segment at `maxOf(lastSeenMs)` with `firstOdometerM = lastOdometerM`.

**9. Five one-line fixes — no reason to drive without them.**
 - `follow/FollowEngine.kt:515-516`: pass `state.contactMs()` / `state.contactM()`, not `lastSeenMs - firstSeenMs`. Today the DB is stamped with the current segment only and `updateEvidence` uses a plain `SET`, so the surviving evidence shrinks on every reacquisition.
 - `data/TrackingStore.kt:749`: `ORDER BY started_at DESC LIMIT ?` then `.reversed()` — three callers omit `sinceMs`, so the alert photo is currently the 50th-*oldest* encounter.
 - `report/HtmlReportBuilder.kt:157`: use `earliest` when `options.sinceMs == 0`; per-vehicle reports (the ones attached to TAIL/BLACKLIST alerts) currently render `windowFrom = 0`, i.e. a 1970 header and a timeline collapsed against the right edge.
 - `report/HtmlReportBuilder.kt:52-53`: append `nanoTime()`/a counter to the filename. Three threads now build reports and the name has minute resolution — a TAIL alert plus a tap on its own report button truncates one file into the other.
 - `telegram/TelegramBot.kt:348`: `commands.execute { runCatching { handle(active, update) } }`. The third lane was created and shut down but never used, so `/report`, `/wipe` and six `sendPhoto` uploads still block `getUpdates` — `/stop` is not even *fetched* while they run.

---

## CAN WAIT

- **`upgradePlate` collision merge de-lists the entry and lies about it** (`VehicleRegistry.kt:464`) — return the surviving entry instead of a boolean and have `touch()` write score/thumbnail/`state.confirmed` onto it. Costs one mis-attributed sighting and one stranded `FollowEvidence`; self-heals on the next read.
- **`park()` fires `onDropped` twice** (`AlprWorker.kt:360/365`) — add `release(job)` (pool + thumbnail only) and call that from the spill task. Two-line fix, but the damage is bounded: wasted engine time under saturation, not corrupted consensus (see disputes below).
- **`toggleRecording()` races `start()` on main** (`ScanActivity.kt:1286`) — post it to `mainHandler` like `setPaused`/`nextLens`, and mark `VideoRecorder.start`/`stop` `@Synchronized`. Rare (needs a `/rec` tap concurrent with a follower confirmation) but the failure mode is an unbounded clip with the absence check disabled.
- **`onDestroy` teardown** (`ScanActivity.kt:1840/1858`) — honour `awaitTermination`'s boolean before `detector.close()`/`processor.release()` (an `OrtSession.close()` during `session.run()` is a use-after-free), and stop sleeping 1.2 s on main for a Finalize event that is queued *on that same Looper*. Three seconds of main-thread stall on every exit; the clip is recovered next launch by `resendPendingClips`.
- **`RejectedExecutionException` on teardown** (`ScanActivity.kt:502`, `TelegramBot.kt:179-183`) — set a `destroyed` flag before shutting executors, null `client` *before* shutting `sender`/`media`, and wrap the remaining submissions.
- **`drainOneSpilled` has no try/finally, and the new loop is unguarded** (`AlprWorker.kt:307/387`) — wrap the body and catch `Throwable` around the drain loop so one bad crop costs one crop, not the session.
- **`closeSegment` zeroes `turnsDuringContact`** (`FollowEngine.kt:130`) — don't; it describes the relationship, not the segment. Costs one junction's worth of a wrong 8 km gate.
- **`recentSightings` not remapped on re-key** (`FollowEngine.kt:540`) — rebuild the deque in `rename()`. One lost turn per operator rename; the `canonicalKey` path self-heals.
- **`mute:on` only silences `alert()`** (`TelegramBot.kt:302`) — add `|| muted` to `sendClip`/`sendReport`/`broadcast`, with an explicit `urgent` opt-out for crash reports. The button lies, but nothing is lost.
- **`display_plate` adopts on score while the row key uses length-first `prefer`** (`TrackingStore.kt:448`) — mirror `prefer` in the CASE. Post-restart only; breaks `/list`, `/plate` lookups, report headers.
- **`supportFor` prefix/suffix credit** (`PlateFusion.kt:122`) — don't apply prefix credit on the `voters.size < 2` early return. Only the vote count is wrong, not the chosen spelling.
- **`stop()` doesn't join the worker** (`AlprWorker.kt:203`) — join, or have `loop()` exit when `thread !== currentThread()`; lock `SpillStore.poll()` regardless, since it is unsynchronised.
- **Crash note deleted before delivery** (`CrashReporter.kt:95`), **`/wipe` deletes files the media lane is reading** (`StorageCleaner.kt:77`), **orphaned `enc_*.jpg`** (`TrackingStore.kt:485`), **AE fps picks the first fixed range ≥ 30** (`CameraController.kt:336`), **no-video fallback is never retried** (`CameraController.kt:258`), **`statusText()` reads `ListAdapter.itemCount` off-thread** (`ScanActivity.kt:1109`) — all real, all cheap, none of them change what the app decides. Batch them after the first successful drive.

---

## DUPLICATES (four pairs — 26 findings, 22 defects)

- `[bitmaps] park() fires onDropped twice` ≡ `[threads] park() fires onDropped twice` — same lines, same fix.
- `[bitmaps] upgradePlate collision merge` ≡ `[consensus] upgradePlate collision merge` — same branch at `VehicleRegistry.kt:464`.
- `[threads] tg-commands executor never used` ≡ `[data] commands executor never used` — same dead executor, same one-line dispatch.
- `[follow] updateEvidence persists lastSeenMs-firstSeenMs` ≡ `[data] updateEvidence still persists last-minus-first` — same two lines.
- Additionally, the four stale-spill findings (`:212`, `:252`, `:401`, `:438`) are not duplicates of each other but share one root cause and one gate fixes all four.

## WHERE THE VERIFIER WAS TOO GENEROUS

- **`[threads] HIGH park()` should not be HIGH.** Its "one physical observation counted twice, consensus gate silently halved" is wrong, and its own duplicate's verifier says so: the extra crop is a genuinely independent frame ≥220 ms later, so it is a real vote. The defect is resource waste under overload. The `[bitmaps]` write-up of the same bug is the correct one.
- **`[follow] LOW sighting throttle` should have been marked refuted-as-written.** The verifier demolished the failure chain *and* found the proposed fix actively harmful under newest-first spill ordering — yet it is still in the list with a fix that would drop every recovered read. Keep only the residue (a stale read shadowing a live one for 2 s).
- **`[follow] MEDIUM late spilled reads lose turn credit` is not MEDIUM.** The verifier established that `missedTurns` is never read by `classify()` (uses only at 148/257/272/610) and that the credit was already lost before the change. That is a cosmetic evidence-list line, i.e. LOW, and it is subsumed by the freshness gate in item 3.
- **`[bitmaps] LOW drainOneSpilled`** survives on a hypothesised OOM after its central consequence (a zombie worker reporting `isRunning == true`) was refuted — `CrashReporter` chains to the platform handler, so the process dies loudly. Kept above only as hardening.
- Everything else the verifier confirmed traces cleanly in the code; the severity corrections it applied (critical→high on the narrow crop, the zoom gate and the bind leak) are defensible and I have kept them.

## RESIDUAL RISK ONLY A DEVICE CAN SETTLE

Even with all of the above fixed, three things stay unknown until this runs on the S25 on glass: whether a neighbouring plate actually outscores the tracked car's inside a 6% padded box at real following distances (finding 1's trigger is geometry, not code); whether `bindToLifecycle` accepts Preview + 4K Analysis + 720p VideoCapture at all on this firmware (if not, every drive silently runs without clips); and whether re-opening the camera after a thermal event fits inside any backoff you choose for finding 2.

## Регрессии

### [camera] HIGH — Failed setZoomRatio writes into FrameGate with no generation guard, nothing undoes it

`camera/CameraController.kt`:307

**Что:** applyZoom() attaches a listener to the setZoomRatio future that, on any exception, calls gate.updateLens(usable = false, settled = true). Unlike every other gate writer in the class (onVerification guards `snapshot.generation != generation` at line 342, settle() guards at line 358), this callback carries no generation and no liveness check. A failed zoom future is not rare: CameraX completes it with CameraControl.OperationCanceledException whenever the request is superseded by a newer setZoomRatio, whenever the use cases are unbound (bind() line 157 does provider.unbindAll() on the very next apply, and shutdown() line 425 does it too), and whenever the CameraControl is not yet active. Worse, once lensUsable is forced back to false there is no path that raises it again inside the same generation: FrameGate.updateLens can only ever clear `transitioning`, never re-raise it (FrameGate.kt:40-43), settle(force=true) writes `usable = gate.lensUsable` back onto itself (line 359), and LensVerifier.publish() de-duplicates on state/reason/generation/observedPhysicalId/observedFocalMm (LensVerifier.kt:275-284), so a verifier already sitting in VERIFIED with unchanged metadata never publishes again and onVerification is never re-entered.

**Почему:** Three concrete failures. (1) Cross-generation clobber: during a rotation A->B the cancelled future from A lands on mainExecutor after apply(B) has already run beginTransition(B), so B's transition is marked settled before B's optics were proven; with strictLens off (isOpen = !transitioning && !paused && true) the gate is then open for the frames captured while the HAL is still ramping to B, which is exactly what beginTransition exists to prevent. (2) Cross-controller clobber: shutdown() cannot cancel a listener already registered on mainExecutor, and haltSession()/resumeSession() in ScanActivity share one FrameGate across two CameraController instances, so the dead controller's cancelled zoom marks the new session's first transition settled. (3) Permanent lockout: if the failure lands after the verifier has published VERIFIED (the stall path keeps a zoom future pending for the whole stall, then rebind()'s unbindAll cancels it), lensUsable is stuck false; on a single-lens rotation plan LensRotationScheduler never advances (`entries.size > 1`, LensRotationScheduler.kt:113) so apply() is never called again and recognition is off for the rest of the drive while the HUD still shows a verified lens.

**Как чинить:** Capture `val gen = this.generation` in applyZoom and make the listener a no-op when `gen != this.generation`, plus a `closed` flag set by shutdown(). Distinguish CameraControl.OperationCanceledException (request superseded or camera inactive — not a lens failure, ignore it) from a genuine rejection. Do not write `settled = true` from this path at all; settle() already has the 2 s ceiling for that. If the gate must be re-closed, go through beginTransition so `transitioning` is raised too.

*Проверка (CONFIRMED):* Code trace holds. applyZoom (CameraController.kt:298-314) registers a listener that carries no generation and no liveness flag, and on any exception calls gate.updateLens(usable=false, settled=true) (line 307). Contrast onVerification (line 342: `if (snapshot.generation != generation) return`) and settle (line 358). The failure is genuinely reachable from this repo's own code paths with CameraX 1.6.1: (a) a zoom future stays pending until a capture result confirms the ratio, so during a stall it is still pending when rebind() -> apply(forceRebind=true) -> bind() -> provider.unbindAll() (line 157) drops the use-case count; Camera2CameraControlImpl/ZoomControl then complete it with OperationCanceledException, and since unbindAll runs inside the main-thread apply() the listener is dispatched on mainExecutor *after* apply() already ran gate.beginTransition(newGen) at line 129; (b) a superseded setZoomRatio is cancelled the same way. The no-recovery half also holds: FrameGate.updateLens (FrameGate.kt:40-43) never re-raises lensUsable, settle(force=true) writes gate.lensUsable back onto itself (line 359), and LensVerifier.publish de-dups on state/reason/generation/observedPhysicalId/observedFocalMm (LensVerifier.kt:273-287) while a VERIFIED stream keeps producing byte-identical snapshots (matchingFrames is not in the key), so onVerification is never re-entered inside that generation. FrameProcessor stamps lastAnalyzedAtMs before the gate (FrameProcessor.kt:137, comment 110-115), so a gate stuck closed is invisible to the stall watchdog. Severity lowered from critical to high: with the default strictLens=true (ScanConfig.kt:181) the clobber leaves the gate *closed*, not wrongly open, and with the default 3-step plan (CameraCatalog.defaultEnabledStepIds) the next apply() -> beginTransition -> VERIFIED restores it within one dwell; the permanent lockout the claim describes needs a single-entry rotation plan (LensRotationScheduler.kt:113 `entries.size > 1`), and the wrongly-open-gate variant needs strictLens turned off.

### [camera] HIGH — bind() failure keeps camera/boundRoute stale, so the dead session is never rebound

`camera/CameraController.kt`:270

**Что:** On the failure path bind() does `newAnalysis.clearAnalyzer(); return` without touching `camera`, `analysis`, `preview`, `boundRoute` or `boundPhysicalId`. provider.unbindAll() at line 157 has already torn the working session down, so those fields now point at a Camera/ImageAnalysis/Preview that are detached. needsRebind (lines 121-124) is computed as `forceRebind || camera == null || route != boundRoute || (...)`; with `camera` non-null and `boundRoute` unchanged, every subsequent apply() for the same route evaluates to false.

**Почему:** On the S25 PhysicalLensRoutingPolicy always returns LOGICAL_MULTI_CAMERA (Samsung + OUTPUT_SURFACES=2), so the route never changes. One failed bind therefore means the whole rotation runs against a session that does not exist: apply() bumps the generation, retargets the verifier and publishes a HUD state for each step, but bind() is never attempted again and no frame ever arrives. applyZoom then runs setZoomRatio on the detached CameraControl (line 289 reads the stale `camera`), which fails and feeds the gate defect above. hasFlash()/setTorch() also operate on the dead camera. The only escape is the stall watchdog's forceRebind, which gives up permanently after three strikes. Separately, bind() unbinds before it knows the new configuration will bind, so rebind() — the recovery action — destroys a merely-slow session and can leave nothing behind.

**Как чинить:** In the `bound == null` branch set camera/analysis/preview = null and boundRoute/boundPhysicalId = null so the next apply() retries the bind, and call onError so the operator learns the session is down. Better still, build and bind the new use cases before unbindAll(), or re-bind the previous group on failure.

*Проверка (CONFIRMED):* The state leak is exactly as described: on the `bound == null` branch bind() does only `newAnalysis.clearAnalyzer(); return` (CameraController.kt:270-273), leaving camera/analysis/preview/boundRoute/boundPhysicalId pointing at use cases that provider.unbindAll() (line 157) already tore down, so needsRebind (121-124) evaluates false for every later apply() on the same route — and on Samsung the route is pinned to LOGICAL_MULTI_CAMERA (PhysicalLensRoutingPolicy.kt:60-62 with OUTPUT_SURFACES=2), so it never changes. The chain into applyZoom is real too: line 289 reads the stale `camera`, and after unbindAll the adapter's control is no longer in use, so setZoomRatio fails and feeds the unguarded gate write above. Also worth noting the reviewer missed: when videoRecorder == null the first-attempt failure skips the whole 258-268 block, so onError is never called and only Log.w records the dead session. Severity lowered from critical to high on two points the claim overstates: (1) the first-ever bind failure is self-healing because `camera` is still null, so needsRebind stays true — the leak only bites on a re-bind, i.e. after a route change or a forceRebind; (2) the recovery path is not blocked by the stale fields, since rebind() passes forceRebind=true (line 152) which short-circuits needsRebind at line 121 — what actually caps recovery is the watchdog's strike ladder (separate finding). The trigger also requires bindToLifecycle to throw synchronously, which for a fixed stream configuration is deterministic rather than transient.

### [camera] HIGH — Stall watchdog escalates per tick, tearing down its own rebind and quitting after ~10 s

`ScanActivity.kt`:577

**Что:** checkCameraAlive() measures `since = elapsedRealtime() - processor.lastAnalyzedAtMs` and increments stallStrikes on every watchdog tick where `since >= STALL_LIMIT_MS`. WATCHDOG_INTERVAL_MS is 5 s, STALL_LIMIT_MS is 12 s, and rebind() does not reset lastAnalyzedAtMs (FrameProcessor.lastAnalyzedAtMs is private-set and stamped only inside analyze(), FrameProcessor.kt:117/137). So `since` keeps growing across a rebind attempt and the strike counter escalates on wall-clock ticks rather than on failed recoveries.

**Почему:** Stream dies at t=0. t=12 s: strike 1 -> rebind(), which does unbindAll plus a full re-open. t=17 s: if that fresh session has not yet delivered its first frame, `since` is 17 s (still measured from the original last frame), so strike 2 fires and calls rebind() again, destroying the session that was still coming up — re-opening a camera after a thermal event or an eviction by another app routinely takes more than 5 s. t=22 s: strike 3 broadcasts "needs an app restart" and, because the `when` falls through to `else -> Unit` and stallStrikes never decays, no further rebind is ever attempted for the rest of the drive. The recovery mechanism gets one real attempt, sabotages it with a second, then permanently disarms itself on a camera that may well have come back a few seconds later.

**Как чинить:** Record `lastRebindAtMs` when a rebind is issued and require `elapsedRealtime() - lastRebindAtMs >= STALL_LIMIT_MS` before counting another strike, so the ladder measures failed recoveries rather than ticks. Keep retrying with backoff instead of giving up at strike 3, and reset the strike counter after any successful frame (already done) or after a long quiet period.

*Проверка (CONFIRMED):* Traced and holds. checkCameraAlive computes `since = elapsedRealtime() - active.lastAnalyzedAtMs` (ScanActivity.kt:565) and increments stallStrikes on every tick where since >= STALL_LIMIT_MS (575), with WATCHDOG_INTERVAL_MS = 5_000 (1871) and STALL_LIMIT_MS = 12_000 (1877). Nothing resets the clock on a rebind attempt: FrameProcessor.lastAnalyzedAtMs is private-set and stamped only inside analyze() (FrameProcessor.kt:117, 137), CameraController.rebind() (149-154) touches nothing in the processor, and the only reset in ScanActivity is the frames-came-back branch at 566-572. So with the ticker at 5 s the ladder fires at roughly t=15/20/25 s: strike 1 rebinds, strike 2 rebinds again 5 s later — which runs provider.unbindAll() (CameraController.kt:157) on a session that may still be opening — and strike 3 falls into the `else -> Unit` arm (591) forever, since stallStrikes only decays when a frame actually arrives. Each recovery attempt therefore gets a 5 s window and the whole mechanism disarms itself ~25 s in, on a device where re-opening after a thermal event or an eviction by another app can exceed that. Severity high is right: the outcome is a permanently blind session with only a banner and one Telegram message.

### [consensus] HIGH — Narrow crop locks a track onto another car's plate; the guard against it cannot fire

`alpr/PlateRoi.kt`:83

**Что:** For a whole-vehicle read, FrameProcessor.submitJob passes `sourceRect = Rect(cropRect)` and `anchorRect = vehicleRect` — the *same* rectangle (cropRect is only overwritten when narrowRoi != null). anchorOf then computes anchor[i] = plate[i]/bitmapWidth, which is in [0,1] by construction for any box the engine returned. So the check `anchor.any { it < -0.25f || it > 1.25f }` — documented as "a plate that maps far outside the vehicle box means the tracker and the engine were looking at different cars" — is a tautology in stage 1 and can never reject anything. Meanwhile `selectReading` picks the *highest-scoring* plate in the outcome, not the one belonging to the tracked car. The 6%-padded crop of a distant car routinely contains a slice of the adjacent lane, and a nearer neighbour's plate scores higher. That reading becomes the anchor (ScanActivity:1516), the next crop is cut around the neighbour's plate at native resolution, it reads cleanly, `rememberPlatePosition` re-stamps `anchorAtMs` (ScanActivity:1535) so ANCHOR_TTL never expires, and `owesVehicleLook` stops forcing a whole-car look as soon as `runtime.makeModel` is non-null (FrameProcessor:414). The documented self-repair ("a read from the narrow crop comes back empty → anchor dropped") never triggers, because a drifted anchor that landed on a readable plate never comes back empty. (Note: the coordinate chain itself is correct when a narrow crop is scaled down — scaleX = sourceWidth/bitmapWidth with job.width being the post-scale width inverts the downscale properly.)

**Почему:** Every subsequent read of the tracked vehicle returns the neighbour's number, so consensus confirms the neighbour's plate for this track and the follower's own plate is never read at all. This is the exact failure the app cannot afford: the car actually behind you ends up filed under someone else's number, and the follow engine accumulates its contact time under that number.

**Как чинить:** Validate the anchor against something the engine did not already see: express it relative to the *unpadded* `track.box` rather than the padded `vehicleRect`, and reject a plate whose centre falls outside the inner 80% of the track box or whose width is implausible against `estimatedPlatePixels(track.box.width())`. Additionally force a periodic whole-vehicle read even after makeModel is known (drop the `runtime.makeModel == null` condition on `owesVehicleLook`), so a mis-aimed anchor is re-derived from the full car instead of re-confirming itself forever.

*Проверка (CONFIRMED):* Traced end to end. FrameProcessor.submitJob:406 copies vehicleRect from cropRect *before* the narrow override at 425, so on a stage-1 (wide) job sourceRect and anchorRect hold identical values; in PlateRoi.anchorOf that makes anchor[i] = plate[i]/bitmapWidth, always inside [0,1] for any box the engine returned in its own image coordinates (AlprWorker:404 passes job.width/job.height as the engine image size, so the box is bounded by them). The -0.25/1.25 guard at PlateRoi.kt:83 therefore cannot reject anything in stage 1 — and it is equally inert in stage 2, because PlateRoi.project clamps the ROI to basis +/- BASIS_SLACK (0.10), so a plate found anywhere in the narrow crop re-maps into [-0.1,1.1]. The self-reinforcement is real: selectReading (VehicleRegistry:533) takes the highest-scoring plate in the outcome regardless of which car it belongs to; rememberPlatePosition (ScanActivity:1511-1535) feeds that same reading into the anchor and re-stamps anchorAtMs on every success, so ANCHOR_TTL_MS never expires; the only anchor-drop path needs selectReading to return null on a narrow job (ScanActivity:1513/1536), which a cleanly readable neighbour plate never does; and owesVehicleLook (FrameProcessor:414) is gated on runtime.makeModel == null, which VehicleRegistry:122 sets permanently after the first classifier hit, so no periodic whole-car re-derivation survives. applyCropStrategy (ScanActivity:597) only alternates narrowAllowed in NARROW_EXPERIMENT mode, so in the default mode there is no rescue window. Severity lowered from critical to high only because the trigger — a second car's plate lying inside the tracked box's 6% padding and outscoring the tracked car's own plate — is device geometry that reading cannot establish as routine; the code defect itself (dead guard, no re-derivation, self-renewing TTL) holds exactly as described.

### [data] HIGH — The new commands executor is never used; commands still run on the poller thread

`telegram/TelegramBot.kt`:348

**Что:** The third lane was created (line 107) and shut down (line 181) but nothing is ever submitted to it: `grep -n commands` matches only the declaration, the shutdown and comments. `loop()` still calls `runCatching { handle(active, update) }` inline on the tg-poller thread (line 348), so every command body — `host.buildReport`, `host.wipeAll`, `sendVehicleCard`'s six `sendPhoto` uploads, `store.renamePlateManually`'s transaction — executes between two `getUpdates` calls.

**Почему:** This is the exact failure the three-lane split was written to remove, and the comment at lines 99-104 asserts it is fixed. `/report 24` on the poller thread runs the HTML build (400 vehicle queries plus base64 of up to 12 MB of JPEG, HtmlReportBuilder.kt:69/89) and then `sendDocument`, whose OkHttp callTimeout is 180 s (TelegramClient.kt:37). For that whole window `getUpdates` is not called, so `/stop`, `/pause`, `/wipe` and every inline button tap are not received — the phone is on the rear glass and the owner is driving, which is the case the comment says must never happen. Worse, `/wipe yes` blocks the poller for the whole file walk plus `VACUUM`.

**Как чинить:** Dispatch the body onto the lane that exists: `updates.forEach { update -> offset = maxOf(offset, update.updateId + 1); commands.execute { runCatching { handle(active, update) }.onFailure { … } } }`. A single-thread executor keeps commands serialized with each other, so no new interleaving is introduced. Keep the `answerCallback` ack (line 380) inside the submitted task — it is fast — and leave `offset` advancement on the poller thread as it already is.

*Проверка (CONFIRMED):* Traced exactly. TelegramBot.kt:107 declares `commands`; grep for "commands" in the file returns only line 107 (declaration), 181 (shutdown) and three comment lines — nothing is ever submitted to it. loop() at TelegramBot.kt:346-350 still does `runCatching { handle(active, update) }` inline on the tg-poller thread. handle() -> handleAction() runs the whole body synchronously: `report:` calls host.buildReport (ScanActivity.kt:1136 -> HtmlReportBuilder.build, which walks vehicles, runs store.encounters per vehicle and base64-embeds up to maxPhotoBytes=12 MB, HtmlReportBuilder.kt:69/88-89) and then active.sendDocument on the same thread (TelegramClient callTimeout 180 s, TelegramClient.kt:37). `wipe:yes` calls host.wipeAll() inline (TelegramBot.kt:575 -> ScanActivity.kt:1468 -> StorageCleaner.wipe full file walk + store.wipeDatabase + VACUUM, TrackingStore.kt:839). sendVehicleCard (609-622) does up to 6 blocking sendPhoto uploads on the poller. While any of these runs, getUpdates is not re-issued, so /stop, /pause and button taps are not fetched — precisely what the comment at 99-104 claims was fixed. Severity high is right: the mitigation exists but is dead code.

### [data] HIGH — encounters() returns the OLDEST rows; callers that omit sinceMs get ancient evidence

`data/TrackingStore.kt`:749

**Что:** `encounters(plate, sinceMs = 0L, limit = 50)` is `ORDER BY started_at LIMIT ?` — ascending. The new `sinceMs` parameter fixes the window only for callers that pass one. Three callers do not: ScanActivity.kt:777 `store.encounters(evidence.plate).lastOrNull()?.photo` (the photo attached to every alert), ScanActivity.kt:1169 `store.encounters(key)` (the `/plate` card, whose photos are then `.take(MAX_PHOTOS)` = the 6 oldest), and HtmlReportBuilder.kt:63/75 when driven by `buildVehicleReport` with sinceMs = 0 (ScanActivity.kt:1130). No caller breaks on the signature change itself — Kotlin has no implicit Int→Long widening, so an old `encounters(plate, 6)` call would not compile — the damage is the default value.

**Почему:** The KDoc added with this change (lines 742-748) states the exact failure — "a car met fifty times over a month would otherwise fill the limit with ancient rows" — and then leaves the default at 0 for the paths where it matters most. A blacklisted neighbour or a persistent tail with more than 50 encounters: `lastOrNull()` returns the 50th-oldest encounter, so the alarm photo is a picture from weeks ago; the per-vehicle report attached to a BLACKLIST/TAIL alert (TelegramBot.kt:244, 517) contains none of today's encounters, and `tripIds` (HtmlReportBuilder.kt:63) resolves to old trips, so the map draws a route from a different day than the alarm.

**Как чинить:** Select newest-first and restore chronological order in Kotlin: `... ORDER BY started_at DESC LIMIT ?` then `.reversed()` in the `buildList`. That keeps the report's oldest-first rendering while guaranteeing the limit is spent on recent rows for every caller, whatever sinceMs they pass.

*Проверка (CONFIRMED):* Traced. TrackingStore.kt:749-756 is `WHERE plate = ? AND ended_at >= ? ORDER BY started_at LIMIT ?` — ascending with default sinceMs=0L, limit 50, so callers that omit the bound get the 50 OLDEST rows. All three cited callers omit it: ScanActivity.kt:777 `store.encounters(evidence.plate).lastOrNull()?.photo` (the photo attached to every alert -> lastOrNull is the 50th-oldest encounter), ScanActivity.kt:1169 `store.encounters(key)` in vehicleCard, whose photo list is then `.take(MAX_PHOTOS)`=6 in TelegramBot.kt:239/620, and buildVehicleReport (ScanActivity.kt:1128-1130) which passes Options(sinceMs = 0L), reaching HtmlReportBuilder.kt:63 (tripIds) and :75 (per-vehicle encounters). The precondition (>50 encounters for one plate) is realistic: ENCOUNTER_GAP_MS is only 180 s (TrackingStore.kt:907) so one car dropping out of view for three minutes opens a new encounter, and prune retention is 30 days (ScanActivity.kt:1900) — i.e. it bites exactly the blacklisted regular / persistent tail the feature exists for, and the KDoc at 742-748 describes this failure while leaving the default at 0. The signature-compat note also checks out: encounters(plate, 6) would not compile against a Long parameter.

### [follow] HIGH — rename() merge spans both states, re-inflating contact across the gap

`follow/FollowEngine.kt`:273

**Что:** In the merge branch, banked contact is combined with maxOf (lines 273-274) while the OPEN segment is widened to the union of both states: firstSeenMs takes the earlier of the two (284-287) and lastSeenMs the later (288-291). contactMs() then returns max(banked) + (maxLast - minFirst), i.e. the whole wall-clock span between the two cars' histories, gaps included. Concretely: target seen 10:00:00-10:00:30 (contact 30 s), source seen 09:00:00-09:00:20 (contact 20 s); after rename the merged state reports 60.5 minutes of contact instead of 50 seconds. The maxOf on contactMsBanked/contactMBanked simultaneously throws away the loser's banked segments (target 200 s + source 300 s yields 300 s, not 500 s).

**Почему:** This is exactly the failure the segment rewrite exists to kill - the comment at 429-431 says 'otherwise two glimpses an hour apart read as an hour of company, and every regular on the route becomes a tail'. rename() is reachable from the Telegram /rename command (ScanActivity.kt:1427), so an operator correcting one misread character can push a harmless car straight to TAIL via classify()'s `sharedTurns >= 2 && contactMs >= TAIL_CONTACT_MS` branch (line 579), and the inflated contactM feeds the SUSPECT distance gate too. On the next sighting worthFilming fires and a clip starts on a car that never followed anyone.

**Как чинить:** Do not merge the two open segments. Bank both sides and start a single fresh segment: `target.contactMsBanked += state.contactMs() + (target.lastSeenMs - target.firstSeenMs).coerceAtLeast(0)` (same for metres), then set target.firstSeenMs = target.lastSeenMs = maxOf(target.lastSeenMs, state.lastSeenMs) with firstOdometerM = lastOdometerM to match. Keep firstSeenMs = min(...) only in a separate display-only field if the UI needs 'first ever seen'.

*Проверка (CONFIRMED):* Traced and holds. In the merge branch target.contactMsBanked/contactMBanked take maxOf (273-274, so the loser's banked segments are discarded, not summed) while the OPEN segment is widened to the union: firstSeenMs/firstOdometerM take the earlier pair (284-287) and lastSeenMs/lastOdometerM the later pair (288-291). contactMs() (133) = banked + (lastSeenMs - firstSeenMs), so the merged state reports the whole wall-clock span between the two histories, gap included; contactM() (135) is inflated the same way through firstOdometerM. Inflation is not an edge case: the source state's open segment is only ever closed by a later sighting of that same key (closeSegment is called only at 432), so a spelling that stopped being read an hour ago still carries an open segment stamped an hour back. classify(target) runs immediately at 294-298, and the inflated values feed both TAIL (sharedTurns>=2 && contactMs>=TAIL_CONTACT_MS, 579) and the SUSPECT distance gate (591). Reachable only via rename(), whose sole caller is the Telegram /rename handler (ScanActivity.kt:1427) - grep shows no automatic caller; canonicalKey never merges two live states because it returns early when states already contains the incoming plate (528). Severity corrected critical->high: real false-positive channel producing a wrong threat level and a spurious clip, but gated behind a deliberate operator command rather than an automatic path.

### [follow] HIGH — updateEvidence persists lastSeenMs-firstSeenMs, discarding every banked segment

`follow/FollowEngine.kt`:515

**Что:** The persistence block still uses the pre-segment formula: `contactMs = state.lastSeenMs - state.firstSeenMs` and `contactM = state.lastOdometerM - state.firstOdometerM` (lines 515-516). Since closeSegment() moves the accrued time into contactMsBanked and onSighting then resets firstSeenMs/firstOdometerM to the new segment start (432-434), these two expressions measure only the CURRENT open segment. TrackingStore.updateEvidence writes them with a plain `SET contact_ms = ?` (TrackingStore.kt:637-641) - no MAX - so the stored value is overwritten by the smaller number.

**Почему:** Immediately after any reacquisition the DB contact drops to ~0 even though the in-memory state knows about 20 minutes of company. HtmlReportBuilder.kt:206 renders `vehicle.contactMs` for the report reason list, and TrackingStore's merge path (line 579) takes MAX(contact_ms, loser.contactMs) - both consume a value that shrinks every time the car drops back and returns, i.e. precisely the behaviour of a competent tail. The evidence that survives the session is the least incriminating slice of it.

**Как чинить:** Pass the accessors that were added for exactly this: `contactMs = state.contactMs()`, `contactM = state.contactM()`. Both are already used by classify() and toEvidence().

*Проверка (CONFIRMED):* Traced and holds exactly as written. FollowEngine.kt:515-516 still passes contactMs = state.lastSeenMs - state.firstSeenMs and contactM = state.lastOdometerM - state.firstOdometerM, i.e. the CURRENT open segment only, while the accessors contactMs()/contactM() (133-135) that classify() and toEvidence() use include contactMsBanked/contactMBanked. closeSegment() (124-131) banks the finished stretch and onSighting then resets firstSeenMs/firstOdometerM to the new segment start (433-434), so straight after any reacquisition the persisted values are ~0. TrackingStore.updateEvidence writes a plain SET with no MAX (TrackingStore.kt:637-641), so the smaller number overwrites the larger. Consumers verified: HtmlReportBuilder.kt:206 renders vehicle.contactMs from the DB row (VehicleRow.contactMs, TrackingStore.kt:869), and the merge path takes MAX(contact_ms, loser) (TrackingStore.kt:579). Detection itself is unaffected (nothing reloads contact_ms into FollowEngine), but the surviving evidence artefact is the least incriminating slice, so high stands.

### [follow] HIGH — MANOEUVRE_MAX_MS force-closes mid-roundabout and re-arms, emitting 0 or 2 turns

`track/TripTracker.kt`:190

**Что:** A manoeuvre is closed unconditionally at `now - manoeuvreStartMs > MANOEUVRE_MAX_MS` (190), and manoeuvreStartMs is the OLDEST sample in the 8 s heading window at the moment the threshold was crossed (line 205), so the 15 s cap can fire barely 7 s after the swing actually began. finishManoeuvre then drops the event whenever `abs(total) < TURN_DEGREES` (220) and clears inManoeuvre plus headingWindow (216-218) with no refractory period, so the very next samples start building a brand-new window.

**Почему:** On a roundabout the heading integrates positive on entry then negative all the way round, passing through zero. If the cap fires while manoeuvreTotal is near that crossing, abs(total) < 40 and the junction emits NOTHING - a real route decision no car behind us can be scored against. Because the state is reset rather than suppressed, the remaining circling rebuilds a fresh window, crosses 40 degrees again and emits a SECOND event for the same junction. That is the multi-credit bug the rewrite was written to eliminate (doc comment 159-171: 'Every one of those events then credits every car behind us with another shared turn'). Long slip roads and cloverleaf ramps hit the same cap.

**Как чинить:** Track the running extremum rather than only the instantaneous sum: keep the largest abs(manoeuvreTotal) reached with its sign and emit that when the cap fires, so a partially-unwound roundabout still reports the turn it made. Add a refractory guard after any force-close - refuse to arm a new manoeuvre until CALM_SAMPLES consecutive calm samples have been seen - so the tail of a capped manoeuvre cannot become a second junction.

*Проверка (CONFIRMED):* Both failure modes trace. manoeuvreStartMs is the oldest sample in the <=8 s heading window at the moment of arming (205), and the cap fires at manoeuvreStartMs + 15 s (190), so a manoeuvre can be force-closed 7-15 s after it was armed. finishManoeuvre (213-240) drops the event whenever abs(total) < TURN_DEGREES (220) and clears inManoeuvre, manoeuvreTotal, calmSamples and headingWindow (215-218) with no refractory period, so the next samples immediately start a fresh window that only needs 40 degrees over <=8 s to re-arm - at roundabout yaw rates (~20 deg/s) that is ~2 s. Force-close with abs(total) >= 40 therefore emits one event and lets the rest of the same junction emit a second (two turnTimes entries at 222 and two PendingTurns, i.e. two shared turns for one junction, and classify() hits TAIL at sharedTurns >= 3, FollowEngine.kt:578); force-close near the sign change emits nothing at all. The same no-refractory reset also applies on the !usable path (176-180), so dropping below MIN_TURN_SPEED_MPS = 3 mid-junction - routine when turning across traffic - splits one junction into two events too. This is precisely the multi-credit bug the doc comment at 159-171 says the rewrite exists to eliminate, so high stands.

### [threads] HIGH — park() fires onDropped twice; the late one clears inFlight for a newer live crop

`pipeline/AlprWorker.kt`:360

**Что:** `park()` calls `onDropped(job)` synchronously on the analysis thread (line 365), and the task it queued on `alpr-spill` calls `discard(job)` (line 360), whose last statement is `runCatching { onDropped(job) }` (line 449). So every spilled crop clears `recognition.peek(trackId).inFlight` twice, the second time from the spill thread an arbitrary amount of time later. The spill executor is bounded at SPILL_QUEUE_DEPTH=6 and each task JPEG-compresses a ~1280px ARGB crop at quality 95 plus writes a JSON sidecar and renames — hundreds of milliseconds per job, over a second of backlog under the exact overload that triggered spilling in the first place.

**Почему:** The failure: queue is full, crop A for track 7 is parked, onDropped(A) clears inFlight (intended). One `ocrIntervalMs` later the analysis thread cuts crop B for track 7 and sets inFlight=true (FrameProcessor.kt:504). The spill thread then finishes writing A and calls discard(A) -> onDropped(A) -> inFlight=false while B is still queued. Next frame the scheduler sees an idle track and submits crop C. Two crops of the same car are now in the engine at once: engine time that another vehicle needed is spent twice on one car, and — worse — both near-identical reads land in `VehicleRegistry.submit` for the same track and both increment `state.counts[group]`, so `requiredMatches` is satisfied by what is physically one observation. The consensus gate that exists to stop a single bad read from being published is silently halved, and only while traffic is heavy enough to overflow the queue, which is when misreads are most likely.

**Как чинить:** Split `discard` into `release(job)` (pool.release + thumbnail.recycle) and the notification. The spill task must call `release(job)` only; `onDropped` stays where park() already calls it, exactly once. Alternatively give `discard` a `notify: Boolean = true` parameter and pass `false` from the spill task.

*Проверка (CONFIRMED):* Traced exactly. AlprWorker.park() (lines 355-367) queues a task whose last statement is discard(job) (line 360), and discard() ends with runCatching { onDropped(job) } (line 449); park() then also calls runCatching { onDropped(job) } itself on the analysis thread (line 365). So every parked crop notifies onDropped twice. onDropped is wired at ScanActivity.kt:212 to `recognition.peek(job.trackId)?.inFlight = false`, and FrameProcessor.kt:504 sets inFlight=true before worker.submit(), clearing it again only if submit returns false (line 508). The scheduler's only lockout is `if (runtime.inFlight) continue` at FrameProcessor.kt:340 plus a cooldown of config.ocrIntervalMs (default 220 ms, ScanConfig.kt:174). The spill task JPEG-compresses at quality 95 plus a JSON sidecar and a rename (SpillStore.offer, lines 89-121), so the second notification lands well after 220 ms - long enough for crop B of the same track to have been cut and submitted, and the late onDropped(A) then frees the track while B is still queued, letting crop C in. Two crops of one vehicle then occupy the serial engine at once, and both results reach VehicleRegistry.submit for the same track and both increment state.counts[group] (VehicleRegistry.kt:143-144) toward requiredMatches (line 177). Severity lowered from critical: the two crops still come from two different frames at least 220 ms apart, so the effect is a weakened consensus and wasted engine time under overload, not 'one physical observation counted twice'; no crash and no lost evidence.

### [threads] HIGH — Report builds now race across three threads onto one fixed filename

`telegram/TelegramBot.kt`:244

**Что:** `HtmlReportBuilder.build` always writes `cacheDir/lensalpr_<yyyyMMdd_HHmm>.html` (HtmlReportBuilder.kt:52-53) — minute resolution, no per-call uniqueness. Splitting one bot thread into three made three different threads able to call it at the same instant: `tg-media` via `alert()`'s `full` branch (`host.vehicleCard` then `host.buildVehicleReport`, lines 238-244), `tg-poller` via `/report` and via the `bl:` callback (`host.buildVehicleReport`, line 517), and `lensalpr-io` via `sendTripDebrief()` (ScanActivity.kt:520) and `shareReport()` (ScanActivity.kt:839).

**Почему:** A TAIL alert is the trigger: `alert()` starts the per-plate report on tg-media, and the same alert message carries a "🗺 Отчёт" button and a "⛔️ В чёрный список" button whose handler also builds a report — both handled on the poller. Two builders inside the same minute open the same `File` for writing; one truncates the other, and `sendDocument` uploads whatever is on disk at that moment. The operator receives a half-written HTML, or a per-plate report containing the whole-fleet document, at precisely the moment the evidence matters. Before the split, alerts and commands shared one executor and these builds were serialized, so this is a regression created by the fix.

**Как чинить:** Make the target unique per build — append `System.nanoTime()` or a counter to the filename — or serialize builds behind a lock in `HtmlReportBuilder` and copy the result to a per-request file before handing it to the uploader.

*Проверка (CONFIRMED):* Traced. HtmlReportBuilder.build derives its target purely from a minute-resolution stamp - FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmm") (line 514), target = File(cacheDir, "lensalpr_$stamp.html") (lines 52-53) - with no per-call uniqueness, no temp-file-and-rename and no lock; the whole document is committed by a single target.writeText(...) at line 180. Three threads can reach it concurrently: tg-media inside alert()'s `full` branch (TelegramBot.kt:237-244, host.buildVehicleReport), tg-poller via handleAction 'report:' (line 489) and via the `bl:` callback (line 517), and lensalpr-io via sendTripDebrief (ScanActivity.kt:519-522) and shareReport (ScanActivity.kt:839). The per-plate and whole-fleet paths share one filename pattern, so a TAIL alert that starts the per-plate build on tg-media while the operator taps the alert's own report or blacklist button on the poller has two builders writing one file inside the same minute, and sendDocument uploads whatever is on disk at that instant - a truncated file, or the fleet document delivered as the per-plate one. Severity high stands. One correction to the framing: the race is not created by the executor split - commands have always run on the poller thread while alerts ran on an executor - the split widened it rather than introducing it.

### [bitmaps] MEDIUM — park() fires onDropped twice; the late call un-marks a live in-flight crop

`pipeline/AlprWorker.kt`:365

**Что:** park() notifies the scheduler eagerly at line 365 (`if (accepted) runCatching { onDropped(job) }`) AND the runnable it queued at line 357 ends with `discard(job)` (line 360), whose own last statement is `runCatching { onDropped(job) }` (line 449). Every parked crop therefore clears its track's `inFlight` flag twice: once on the analysis thread at park time, and once again from the "alpr-spill" thread after the JPEG has been written.

**Почему:** The second call is not idempotent in effect, only in form: by the time it lands, the flag it clears usually belongs to a *different*, genuinely queued crop. Sequence: overflow -> park(job A) -> onDropped(A) clears inFlight for track 7 (correct) -> ~220 ms later (config.ocrIntervalMs default 220, ScanConfig.kt:174) the scheduler cuts crop B for track 7 and sets inFlight = true -> the spill task for A finally runs (a 1280x960 ARGB JPEG at quality 95, behind up to SPILL_QUEUE_DEPTH=6 other writes, so 100-400 ms later) and calls discard(A) -> onDropped(A) clears inFlight while B is still queued -> the scheduler immediately cuts crop C for the same car. This only ever happens while the queue is already overflowing, so the repair for 'a dropped crop locks a vehicle out' has been turned into a positive feedback loop that manufactures extra crops exactly when the engine is saturated, which forces more spilling, which produces more spurious clears.

**Как чинить:** Split the two responsibilities. Add a private `release(job)` that does only `pool.release(job.buffer)` + `job.thumbnail.recycle()`, keep `onDropped` in `discard()` for the paths that really abandon a crop, and have park()'s runnable call `release(job)` instead of `discard(job)` since park() has already notified. (Longer term, make inFlight a token/submission id rather than a boolean so a stale completion cannot clear a newer submission.)

*Проверка (CONFIRMED):* Traced and it holds. park() (AlprWorker.kt:355-367) queues a runnable whose last statement is discard(job) (line 360), and discard() ends with runCatching { onDropped(job) } (line 449); park() then calls onDropped(job) itself at line 365. So every parked crop notifies twice. onDropped is ScanActivity.kt:212 -> `recognition.peek(job.trackId)?.inFlight = false`, a plain per-track boolean (RecognitionState.kt:15) with no generation or submission token, so the late call from the "alpr-spill" thread clears whatever flag is currently set for that track. FrameProcessor.submitJob sets inFlight=true at line 504 and the only other gate is `now - runtime.lastSubmitMs < cooldown` with cooldown = config.ocrIntervalMs (default 220 ms, ScanConfig.kt:174), so if the JPEG write (quality 95, up to SPILL_QUEUE_DEPTH=6 ahead of it) lands more than ~220 ms after park time, the second clear un-marks a genuinely queued crop and the scheduler cuts another one for the same car. Severity lowered from high: the damage is bounded, not a runaway loop. The per-track cooldown still caps the track at one crop per 220 ms, cropBudget caps the frame at maxCropsPerFrame (default 3), and each park yields at most one spurious clear, so the worst case is one extra concurrent crop per parked crop under saturation - wasted engine time and buffer pressure, not corrupted results (the extra crop is a genuine independent frame, so it does not fake a consensus vote). The fix as written (a release() that skips onDropped on the park path) is correct.

### [bitmaps] MEDIUM — upgradePlate collision merge de-lists the entry but callers keep writing to it

`pipeline/VehicleRegistry.kt`:464

**Что:** In the merge-on-collision branch (lines 464-475) the entry is removed from `vehicles` at line 461, folded into `collision`, and `true` is returned - but `entry` itself is never re-inserted and never renamed (it keeps its old `plate`). Both callers treat that `true` as 'the card changed its plate' and keep writing to the dead object: `touch()` sets `entry.thumbnail = job.thumbnail` (line 428) and `submit()` sets `existing.thumbnail = thumbnail` (line 206). `recycleUnused` (line 488) and the guard at line 207 then both compare against that dead entry, see the thumbnail was 'adopted', and skip recycling it.

**Почему:** Reachable through the alreadyConfirmed fast path (lines 167-174): track 7 confirmed 'EN7209'; another track confirmed the true 'EM7209'; PlateFusion now returns 'EM7209' with support 3, PlateSimilarity says the two are similar, so touch() -> upgradePlate merges EN7209 into EM7209. Result: (1) the thumbnail just selected is attached to an entry that `snapshot()` will never emit again - it is neither displayed nor recycled, only reachable through the one SubmitResult; (2) `state.confirmed` and `runtime.confirmedPlate` are set to `entry.plate` (lines 172, 210), i.e. the plate that was just deleted, so the next read of that track misses the fast path entirely; (3) worst, ScanActivity:1554-1578 feeds `card.plate` straight into `follow.onSighting(...)`, so contact time and distance for this car accumulate under a plate the registry no longer holds, while `evidenceByPlate` is keyed on it and `publishVehicles()` (ScanActivity:786) looks up by the surviving plate - the threat badge never reaches the visible card. This is the vote-merging repair splitting one car's follow evidence across two plates, which is the one thing the app exists to get right.

**Как чинить:** Make the collision branch report the merge to the caller instead of pretending the entry survived - e.g. return the winning entry (or a sealed result) from upgradePlate, have submit()/touch() switch to `collision` for the thumbnail assignment, `state.confirmed`, `runtime.confirmedPlate` and the returned card, and recycle the incoming thumbnail if `collision` does not adopt it.

*Проверка (CONFIRMED):* The defect is real, but the reviewer's reachability example is wrong and one of the two cited callers cannot reach the branch. Real part: upgradePlate (VehicleRegistry.kt:444-481) removes the entry from `vehicles` at 461, folds it into `collision` at 465-473 and returns true without re-inserting or renaming `entry`, so `entry.plate` still names a key that no longer exists. touch() (425-429) then writes ocrScore/thumbnail into the de-listed object, submit() sets state.confirmed = entry.plate (line 171) and returns entry.toCard() (line 173) - a card whose plate the registry no longer holds. ScanActivity.kt:1554-1578 feeds that card.plate straight into follow.onSighting, while snapshot()/publishVehicles (ScanActivity.kt:784-797) only ever see the surviving plate, so the sighting and any FollowEvidence land under a key that evidenceByPlate lookups for the visible card will never match. Corrections: (1) the second cited caller cannot reach it - in submit()'s confirm path `existing` comes from `vehicles[winner.text] ?: firstOrNull{similar}` (195-197); if the exact lookup hit, upgradePlate returns at 445-448, so reaching 464 implies vehicles[winner.text] was null, hence collision is always null there. Only touch() (the alreadyConfirmed fast path) can hit it. (2) The stated trigger ('another track confirmed the true EM7209' while EN7209 exists) cannot happen: both createEntry call sites are guarded by the same similar-search, so two similar plates never coexist that way. The branch is still reachable, because PlateSimilarity.similar is non-transitive (M~N and N~H but M!~H, PlateSimilarity.kt:17-34) and because rename() (381-401) inserts under an arbitrary key, so cards 'EM7209' and 'EH7209' can coexist and a fused winner (PlateFusion may synthesise a string neither read produced, PlateFusion.kt:93-112) can equal the other card's plate exactly. (3) It self-heals after one read - vehicles[dead plate] is null next time, so submit() falls through to 195 and re-binds to the surviving card. Net effect is a one-shot mis-attributed sighting plus a stranded FollowEvidence entry, on a narrow path: real, but medium rather than high.

### [bitmaps] MEDIUM — onDestroy recycles frame bitmaps and closes the detector under a running analyze()

`ScanActivity.kt`:1858

**Что:** `analysisExecutor.awaitTermination(400L, MILLISECONDS)` returns a boolean saying whether the analysis thread actually stopped; it is discarded inside `runCatching` and the teardown proceeds unconditionally to `worker.close()`, `processor?.release()` (which recycles rawBitmap and rotatedBitmap, FrameProcessor.kt:213-214) and `detector?.close()` (which closes the ORT session and recycles netBitmap, YoloDetector.kt:183-185). `CameraController.shutdown()` calls `clearAnalyzer()`/`unbindAll()`, neither of which waits for an analyze() call that is already running.

**Почему:** A single frame can easily exceed 400 ms on the device this is built for - that is the premise of the whole thermal-throttle path (FrameProcessor.throttle, ScanActivity:1008). If the analysis thread is inside `detector.detect(frame)` or `Bitmap.createBitmap(frame, ...)` when the 400 ms expire, the main thread recycles the bitmap under it ('Canvas: trying to use a recycled bitmap' / IllegalStateException) or, worse, `session.close()` lands while `session.run()` is executing native ORT code, which is a SIGSEGV rather than an exception. Because crash-report delivery was just added, this surfaces as a real crash report every time the operator closes a hot session.

**Как чинить:** Honour the result: if `awaitTermination` returns false, either skip `processor.release()`/`detector.close()` (let the objects be collected) or retry with `shutdownNow()` and a second, longer await before touching any bitmap or native handle.

*Проверка (CONFIRMED):* Traced and it holds. ScanActivity.onDestroy (1856-1861): controller?.shutdown() only does clearAnalyzer()/unbindAll() (CameraController.kt:418-431), neither of which waits for an analyze() already dispatched to the single-thread "frame-analysis" executor (ScanActivity.kt:161-163); analysisExecutor.shutdown() does not interrupt; the boolean from awaitTermination(400 ms) is swallowed by runCatching (1858) and teardown proceeds unconditionally to worker.close(), processor?.release() (recycles rawBitmap/rotatedBitmap, FrameProcessor.kt:213-214) and detector?.close() (session.close() + netBitmap.recycle(), YoloDetector.kt:183-186). Exceeding 400 ms is not hypothetical: AlprWorker.submit can park the analysis thread for waitForRoomMs=200 ms per crop (AlprWorker.kt:245-252) and cropBudget allows 3 crops per frame (config.maxCropsPerFrame default 3), so a saturated frame can sit ~600 ms in submit alone before detect/crop cost is counted - and worker.close() (which drains the queue and releases that sleeper) only runs after the wait has already expired. One correction to the reasoning: the Kotlin-level failures the claim leads with are not crashes - analyze() wraps everything in catch (Throwable) (FrameProcessor.kt:179-183), so 'Canvas: trying to use a recycled bitmap' is logged, not reported. The genuine hazard is the native one: OrtSession.close() landing while session.run() (YoloDetector.kt:104) is executing on the analysis thread, which is a use-after-free, plus RejectedExecutionException from CameraX dispatching into the shut-down executor. Medium is right; 'a real crash report every time the operator closes a hot session' is overstated.

### [consensus] MEDIUM — Consensus pool transfers every parked group, labelled by an arbitrary HashMap key

`pipeline/VehicleRegistry.kt`:347

**Что:** `onTrackLost` labels the parked state with `state.counts.keys.firstOrNull()` (line 311) — HashMap iteration order, arbitrary when the track collected more than one plate group (which happens whenever a neighbouring plate outscored the tracked car's, or the plate flipped into a spelling that is not `similar` to the first one, e.g. "EM7209" and "EN720"). `adoptPooled` then matches only that one arbitrary label against the new track's first read, and on a match does `state.counts.putAll(parked.counts)` and `state.variants.putAll(parked.variants)` — importing **all** groups, including votes for plates that were never compared against the adopting track. The only cross-check is `state.car?.make != parked.car?.make`, which is skipped whenever either make is null (the common case, since a narrow plate crop yields no classifier output at all).

**Почему:** With requiredMatches defaulting to 2 (ScanConfig:173), one imported foreign vote plus a single live read confirms a plate on a track that is a different vehicle: a card is published and `follow.onSighting` is fired with this crop's time and GPS for a car that was never there. The pool exists because the lens plan resets the tracker every few seconds, so this path runs constantly, and the phantom contact it manufactures is indistinguishable from a real one.

**Как чинить:** Park and adopt only the matched group: store `Orphaned(plate, counts[plate], variants[plate], …)` instead of the whole TrackConsensus, and on adoption copy only that single group's count and variant list. Pick the label by `state.counts.maxByOrNull { it.value }` rather than `firstOrNull()`, and drop the remaining groups when a track is lost.

*Проверка (CONFIRMED):* The code does what the claim says: onTrackLost:311 labels the parked TrackConsensus with state.counts.keys.firstOrNull() (HashMap order, unrelated to vote counts), adoptPooled:336 matches only that label, and 347-348 putAll the entire counts/variants maps, with the only cross-check being the make comparison at 341-345 which is skipped when either make is null. Frequency premise verified: VehicleTracker.reset (line 130-134) fires onTrackLost for every track on each lens change, and onTrackLost only parks when state.confirmed == null, so unconfirmed groups are parked constantly. One correction to the mechanism: group keys inside one TrackConsensus are pairwise non-similar by construction (submit:141 only creates a new key when no existing key is similar), so an imported foreign group can never be picked up by the adopting read itself — the damage lands on a *later* read of this track that happens to match a foreign group, which then reaches requiredMatches=2 (ScanConfig:173) with one imported vote plus one live read and publishes CONFIRMED, firing follow.onSighting (ScanActivity:1556-1578) under this track's time and GPS. Severity lowered from high to medium: the imported votes come from a real read taken within POOL_TTL_MS (8 s) by the same camera, so this is cross-contaminated attribution and a lowered evidence bar, not the 'car that was never there' the claim describes.

### [consensus] MEDIUM — submitOrphan can confirm a card from a single read: orphanCounts is never reset

`pipeline/VehicleRegistry.kt`:256

**Что:** `orphanCounts` accumulates for the whole session and is only emptied by `clear()` (line 408) or by the wholesale wipe when it exceeds MAX_ORPHAN_GROUPS. It is not decremented when a card is created from it (createEntry, line 263), nor when that card later leaves `vehicles` via `trim()` (line 512, fires at 200 vehicles — routine in city traffic) or via `rename()` (line 382). So once orphanCounts["AB1234"] has reached requiredMatches, a *single* later orphan read of AB1234 while no card holds that key gives count = 3 >= 2 and creates a confirmed card immediately. The same happens across cars: the group is chosen by `PlateSimilarity.similar`, so two different vehicles one confusable character apart share one tally and contribute one read each, reaching the threshold together.

**Почему:** The operator asked for two matching reads before a plate is published; this path publishes on one, and a CONFIRMED result fires `follow.onSighting` with a full contact record. In the rename case it silently resurrects the card the operator just corrected. Because the reads come from crops recovered from disk — possibly from a previous run — the resurrected card can also carry a timestamp days old.

**Как чинить:** Remove the group from `orphanCounts` when it produces a card, and drop it again whenever the corresponding entry leaves `vehicles` (trim, rename). Age the tally as well — an entry that has not been touched for a few minutes is bookkeeping, not corroboration.

*Проверка (CONFIRMED):* Verified: orphanCounts is incremented in submitOrphan:256-257 and is only ever emptied by clear():408 or the wholesale reset when it exceeds MAX_ORPHAN_GROUPS=200; createEntry:263 does not consume the group, and neither trim():512-515 (maxVehicles defaults to 200) nor rename():382 touch it. While a card exists the existing-lookup at 236 short-circuits, so the stale tally is dormant — but the moment the card leaves vehicles, one further orphan read gives count = 3 >= requiredMatches (2) and republishes a CONFIRMED card, firing follow.onSighting. The cross-car variant is the sharper one and needs no removal at all: the group is chosen by PlateSimilarity.similar (line 254), so two different vehicles one confusable character apart share a single tally and reach the threshold with one read each, with no track evidence tying them together — orphan reads are frequent, since VehicleTracker.reset (line 130) drops every track on each lens change and every in-flight crop then lands in submitOrphan via recognition.peek == null. Severity medium stands. One sub-claim is wrong: createEntry stamps firstSeenMs/confirmedAtMs with nowMs, not the crop's time, so a resurrected card cannot carry a days-old timestamp.

### [consensus] MEDIUM — correctToLatvian rebuilds the road numbers isSignage was written to reject

`alpr/PlateFormats.kt`:70

**Что:** `isSignage` runs once, on the raw cleaned string (line 68), and neither `latvian()` nor `correctToLatvian()` re-checks its result. correctToLatvian exists precisely to resolve the OCR's O/0 and I/1 confusions, which is exactly how a distant road sign is misread: "P1O4" is not matched by ROAD_NUMBER (it contains a letter), passes the gate, and is then corrected to key "P104", display "P-104" — the very road number the gate rejects when read cleanly. "A1O" → "A10", "E6G" → "E67", and so on.

**Почему:** Via Baltica signs stand beside every road this phone will look at, and the O/0 flip is the engine's most common error at that distance. The gate therefore filters the easy case and lets the hard one through, generating phantom vehicles that then accumulate contact time — a stationary sign photographed repeatedly at a junction looks like a car holding station behind you.

**Как чинить:** Re-apply `isSignage(plate.key)` to the result of both `latvian()` and `correctToLatvian()` before returning, not only to the raw cleaned text.

*Проверка (CONFIRMED):* parse() calls isSignage only on the raw cleaned string (PlateFormats.kt:68) and never re-checks the result of correctToLatvian. Traced by hand: 'P1O4' contains a letter so ROAD_NUMBER ^[AEP][0-9]{1,3}$ does not match and the gate passes; correctToLatvian at split=1 gives letterPart 'P', digitPart '1O4' with O->0 at cost 1, producing key 'P104', display 'P-104' — precisely the string isSignage rejects when read cleanly. 'A1O' -> 'A10' the same way (cost 1, LATVIA matches A + 10). The hole is wider than the claim states: the place-name half is bypassed too — 'R1GA25' has letters 'R' only under takeWhile so isSignage is false, and correctToLatvian at split=4 fixes 1->I at cost 1 to yield key 'RIGA25', the exact string PlateSignageTest asserts must be rejected. latvian() and generic() both return key == input so re-checking them is a no-op; correctToLatvian is the only path that rebuilds a rejected string, and it is the one path not re-checked. Severity medium stands (the phantom-vehicle consequence still requires the sign text to fall inside a vehicle crop, but the mis-attribution of a road number onto a tracked car's card needs no such luck).

### [data] MEDIUM — updateEvidence still persists last-minus-first contact, so report contradicts alert

`follow/FollowEngine.kt`:515

**Что:** The contact rewrite introduced `State.contactMsBanked` / `contactMs()` / `contactM()` (lines 117-135) and every decision path uses them — `classify` (567-568), `toEvidence` (599-600), `companions` (203). The persistence call was not converted: it still passes `contactMs = state.lastSeenMs - state.firstSeenMs` and `contactM = state.lastOdometerM - state.firstOdometerM` (lines 515-516), and `TrackingStore.updateEvidence` writes them with a plain `SET contact_ms = ?` (TrackingStore.kt:637-641), not a MAX.

**Почему:** `closeSegment()` resets `firstSeenMs` to the new segment start (line 433), so the value written to the DB is the *current segment only* and it is overwritten downwards on every re-acquisition. A tail that drops back, disappears for two minutes and returns — the pattern the whole app exists to catch, and the reason segments were introduced — accumulates 12 minutes of `contactMs()` in memory while `vehicles.contact_ms` gets stamped back to ~20 s. HtmlReportBuilder.reasons() reads those two columns (HtmlReportBuilder.kt:206-207), so the report card for a confirmed TAIL silently drops "N мин контакта" and "N км рядом" while the Telegram alert for the same car says "держался 120 с и дольше". The operator's evidence file is weaker than the alarm that produced it.

**Как чинить:** Pass the accumulated values: `contactMs = state.contactMs(), contactM = state.contactM()`. If you want belt-and-braces against a late or out-of-order writer lowering the stored figure, change TrackingStore.updateEvidence to `contact_ms = MAX(contact_ms, ?), contact_m = MAX(contact_m, ?)`, matching what mergeInto already does (TrackingStore.kt:579).

*Проверка (CONFIRMED):* Traced. FollowEngine.kt:515-516 still passes `contactMs = state.lastSeenMs - state.firstSeenMs` / `contactM = state.lastOdometerM - state.firstOdometerM` while every in-memory consumer uses the accumulators: classify() 567-568, toEvidence() 599-600, companions() 203, becameFollower 452. closeSegment() (124-131) banks the finished stretch and onSighting resets firstSeenMs/firstOdometerM to the new segment start (433-434), so the value handed to the store is the current segment only — it drops to ~0 on every re-acquisition. TrackingStore.updateEvidence (637-641) writes it with a plain `SET contact_ms = ?`, no MAX (contrast mergeInto at 579 which does use MAX), so the stored figure is overwritten downwards. Consumer confirmed: HtmlReportBuilder.reasons() 206-207 is the only reader of vehicles.contact_ms/contact_m, so a confirmed TAIL's report card loses the "N мин контакта" / "N км рядом" badges while the Telegram alert (built from FollowEvidence.contactMs) still says the car held station. Severity lowered to medium: the wrong column never feeds classification, alerting, /who or the UI — the blast radius is two reason badges on the HTML card.

### [data] MEDIUM — display_plate CASE uses score only, disagrees with canonicalPlate, adopts truncated reads

`data/TrackingStore.kt`:448

**Что:** The UPSERT adopts `excluded.display_plate` whenever `excluded.best_score >= vehicles.best_score`. The row key, however, is chosen by `canonicalPlate` (line 353) which delegates to `PlateSimilarity.prefer` — and `prefer` ranks **length first**, score second (PlateSimilarity.kt:51-55). The two rules disagree exactly in the truncation case. (The CASE itself is valid SQLite and reads the pre-update row, so the later `best_score = MAX(...)` in the same SET list does not corrupt it.)

**Почему:** Row `EM72091` exists with best_score 60. A crop clips the last character and the engine reads `EM7209` at score 95. `PlateSimilarity.similar` says truncated → true; `prefer("EM72091", 60, "EM7209", 95)` → lengths differ → longer wins → `canonicalPlate` correctly keeps the key `EM72091`. But the UPSERT then evaluates 95 >= 60 and sets `display_plate = 'EM7209'`. From that moment the plate the driver hears (ScanActivity.kt:825 spells `displayPlate` aloud), the alert header (TelegramBot.kt:728), `/list`, `/blacklist`, the report card and the report's search key all show a plate one character short of the real one — and `/plate EM7209` normalizes to a key no row has, so the bot answers "Не встречалась" for a car it is actively tracking. PlateSimilarity's own header calls the clipped last character the canonical example, so this is the common path, not a corner.

**Как чинить:** Make the CASE mirror `prefer`, or better, do not let a redirected reading touch the display at all: have `canonicalPlate` also report whether it redirected and pass the existing display in that case. SQL-only alternative: `WHEN LENGTH(excluded.display_plate) > LENGTH(vehicles.display_plate) OR (LENGTH(excluded.display_plate) = LENGTH(vehicles.display_plate) AND excluded.best_score >= vehicles.best_score) THEN excluded.display_plate ELSE vehicles.display_plate END`.

*Проверка (CONFIRMED):* The divergence is real. Row key comes from canonicalPlate (TrackingStore.kt:505-523) which delegates to PlateSimilarity.prefer (PlateSimilarity.kt:51-55: length first, score second), while the UPSERT adopts excluded.display_plate on score alone (TrackingStore.kt:448-451). Traced the truncation case: existing 'EM72091' best_score 60, new read 'EM7209' score 95 -> similar() 42-47 -> truncated() true; prefer('EM72091',60,'EM7209',95) -> lengths differ -> keeps key 'EM72091'; the UPSERT then evaluates 95>=60 and stores display_plate='EM7209'. The parenthetical is also right: SQLite evaluates every SET expression against the pre-update row, so `best_score = MAX(...)` later in the same list does not affect the CASE. One correction to the reported blast radius: within a live session this cannot fire, because FollowEngine.canonicalKey (FollowEngine.kt:527-546) applies the same prefer() rule first and `if (key == plate) state.displayPlate = displayPlate` (442) leaves state.displayPlate on the canonical spelling, so recordSighting receives the already-canonical display and canonicalPlate returns early on the exact match. It fires after a restart / follow?.reset() (i.e. the cross-session case canonicalPlate exists for, comment at 351-352). Consequently the TTS (ScanActivity.kt:825) and the alert header (TelegramBot.kt:728) are NOT affected — both read FollowEvidence.displayPlate from memory, not the column. What genuinely breaks is every DB-backed surface: /list and /all (ScanActivity.kt:1161), /blacklist (1207), /ignored (1445), the report card plate (HtmlReportBuilder.kt:95) — all show a plate one character short — and /plate EM7209 normalizes to a key no row has (vehicleCard, ScanActivity.kt:1167-1168) so the bot answers "Не встречалась" for a car it is tracking. Severity lowered to medium: cosmetic/lookup damage only, the history and threat level stay correctly merged on the one row.

### [data] MEDIUM — Per-vehicle report passes sinceMs = 0, collapsing the timeline and the stats tiles

`report/HtmlReportBuilder.kt`:157

**Что:** `windowFrom = minOf(earliest, options.sinceMs).coerceAtMost(untilMs - 1)`. `buildVehicleReport` builds with `Options(sinceMs = 0L, plate = key)` (ScanActivity.kt:1130), so `minOf(firstEncounterStart, 0)` = 0. `/report` is safe because `currentTripStartMs()` is clamped to at most now−12 h (ScanActivity.kt:1365-1369); only the per-vehicle report hits this.

**Почему:** With `windowFrom = 0` the payload carries `windowFrom: 0`, so in the page `T0 = 0` and `SPAN ≈ 1.78e12`. Every timeline segment computes `left = (startMs - 0) / SPAN * 100` ≈ 99.99 % and `width` clamped to the 0.7 % minimum, so the whole "Кто и когда был рядом" section renders as slivers jammed against the right edge — unreadable. The header period reads "01.01 03:00 — …" (1970) and the "минут" tile shows ≈29 700 000. This is the report Telegram attaches to a BLACKLIST/TAIL alarm (TelegramBot.kt:244) and to the `bl:` button (TelegramBot.kt:517), i.e. the highest-stakes artefact the app produces.

**Как чинить:** Derive the window from the data when no lower bound was requested: `val lower = if (options.sinceMs > 0L) minOf(earliest, options.sinceMs) else earliest; val windowFrom = lower.coerceAtMost(options.untilMs - 1)`. Consider also giving `buildVehicleReport` a real bound (e.g. `System.currentTimeMillis() - RETENTION_MS`) so its `encounters()` limit is spent on recent rows.

*Проверка (CONFIRMED):* Traced. buildVehicleReport passes Options(sinceMs = 0L, plate = key) (ScanActivity.kt:1128-1130); HtmlReportBuilder.kt:157 computes `minOf(earliest, options.sinceMs)` = minOf(x, 0) = 0, and coerceAtMost(untilMs-1) does not raise it. Payload then carries windowFrom: 0 (line 170), from = TIME.format(Date(0)) (line 169) and the minutes tile = (untilMs - 0)/60_000 ~ 29.7e6 (line 165). In the page T0 = DATA.windowFrom = 0 and SPAN = windowTo (line 417), so renderTimeline's `left = (e.startMs - 0)/SPAN*100` is ~99.99 % for every segment and width falls to the 0.7 % floor (423-424) — the whole "Кто и когда был рядом" section collapses to slivers at the right edge. The scoping is correct too: /report and shareReport go through currentTripStartMs (ScanActivity.kt:1365-1368, `minOf(trip, now - DEFAULT_REPORT_WINDOW_MS)` with DEFAULT_REPORT_WINDOW_MS = 12 h at :1869) or an explicit now-N h, both real timestamps, so only the per-vehicle report — the one attached to BLACKLIST/TAIL alerts (TelegramBot.kt:244) and the bl: button (517) — hits this. Medium is right: cards and map still render, the timeline and two header figures are garbage.

### [data] MEDIUM — mute:on silences alerts only; clips, reports and broadcasts still go out

`telegram/TelegramBot.kt`:302

**Что:** `alert()` gates on `if (!config.enabled || muted) return` (line 192). The other three outbound entry points check only `enabled`: `sendReport` (line 302), `sendClip` (line 268) and `broadcast` (line 312). The `muted` flag added with the new mute:on/mute:off actions is not consulted on the media lane at all.

**Почему:** The button says "🔕 Тихо" and the reply says "Уведомления выключены", so the operator reasonably believes the bot has gone quiet. It has not: the next confirmed tail still pushes the recording-started broadcast (ScanActivity.kt:420), then a clip up to 49 MB (`sendClip`), the end-of-trip debrief and its HTML report (ScanActivity.kt:518/522), the thermal warnings (ScanActivity.kt:1018/1023/1028) and the crash dump (ScanActivity.kt:486). Muting during a meeting produces a 40 MB video notification instead of silence, and the operator has no way to stop it short of `/stop`.

**Как чинить:** Add `|| muted` to the guards in `sendClip`, `sendReport` and `broadcast`. If some traffic must survive a mute (crash reports are a defensible exception), give those callers an explicit `urgent = true` parameter rather than leaving the flag unchecked everywhere.

*Проверка (CONFIRMED):* Traced. `muted` is consulted in exactly one outbound path: alert() TelegramBot.kt:192 `if (!config.enabled || muted) return`. sendClip:268, sendReport:303 and broadcast:312 check only config.enabled. Confirmed the callers that therefore survive a mute: ScanActivity.kt:420 (recording-started broadcast), 467 -> sendClip (clip up to MAX_UPLOAD_MB=49), 518 + 522 (trip debrief broadcast and its HTML report), 1018/1023/1028 (thermal), 486 (crash dump), 570/585/662/664 (camera and parking broadcasts). The UI contradiction is real: panel() line 680 renders "🔕 Тихо" and mute:on answers "Уведомления выключены" (536-539) while the media lane keeps uploading. Medium is right — nothing is lost or misdecided, but the control the operator was given does not do what it says.

### [follow] MEDIUM — Late spilled reads lose turn credit and are charged missedTurns instead

`follow/FollowEngine.kt`:252

**Что:** sweepTurns retires a PendingTurn on the wall clock - `nowMs - pendingTurns.first().event.tMs > POST_TURN_WINDOW_MS` (252-253), driven every 5 s from the watchdog (ScanActivity.kt:619, WATCHDOG_INTERVAL_MS = 5_000). creditTurns, however, matches on capture time: it is called as `creditTurns(key, eventMs)` (448) and tests `age = nowMs - turn.event.tMs in 0..POST_TURN_WINDOW_MS` (551-552). A crop captured 30 s after the junction but recovered from disk 150 s later finds no PendingTurn left - the turn was swept at 120 s, and on the way out sweepTurns already booked the car as a miss (`if (plate !in turn.credited) states[plate]?.let { it.missedTurns += 1 }`, 257) because it was in turn.before.

**Почему:** sharedTurns is described in the file header as the strongest evidence the engine has, and classify() reaches TAIL at `sharedTurns >= 3` or `sharedTurns >= 2 && contactMs >= TAIL_CONTACT_MS` (578-581). The capturedAtMs fix converts spill latency into the exact opposite of the truth: a car that demonstrably took the same turn gets zero credit AND a permanent 'не поехал за нами N×' entry in its evidence list (610). Nothing rate-limits this - every junction taken while the queue is spilling produces another false miss for the same car. (The double-count worry itself is unfounded: removeFirst() dequeues the turn, so onTurn and the watchdog cannot both charge it.)

**Как чинить:** Retire pending turns on the same clock the credit uses, or keep them alive long enough for the spill backlog: hold PendingTurns until `nowMs - tMs > POST_TURN_WINDOW_MS + maxSpillLatency`. Alternatively defer the booking - mark the turn expired but keep it briefly so a late read can convert a miss into a credit (decrement missedTurns, increment sharedTurns) before it is discarded.

*Проверка (CONFIRMED):* Clock mismatch confirmed: sweepTurns retires on wall time (252-253, driven by System.currentTimeMillis() from the 5 s watchdog, ScanActivity.kt:619/1871) while creditTurns matches on capture time (448 creditTurns(key, eventMs); 551-552 age = nowMs - turn.event.tMs). A crop captured at T+30 s but drained at T+150 s is eligible by the capture clock yet finds no PendingTurn, and sweepTurns has already booked it as a miss at 257. Spilled reads do reach onSighting: drainOneSpilled sets submittedAtMs = crop.capturedAtMs (AlprWorker.kt:335), bypasses the loop's 5 s staleAfterMs drop (only applied at AlprWorker.kt:396 for queued jobs), and VehicleRegistry.submitOrphan returns UPDATED/CONFIRMED with a card (VehicleRegistry.kt:239-265), which ScanActivity.kt:1560-1561 forwards as capturedAtMs. Severity corrected high->medium: missedTurns is never read by classify() (grep shows uses only at 148/257/272/610), so the false miss is cosmetic in the evidence list; and the credit itself was already lost pre-change, since the read arrives outside POST_TURN_WINDOW_MS on either clock. The real cost is that capture-time-eligible reads can never be converted, not a new false-positive path.

### [follow] MEDIUM — lastOdometerM assigned unguarded while lastSeenMs is maxOf-protected

`follow/FollowEngine.kt`:438

**Что:** Line 438 carries the comment 'A late arrival must never rewind the clock' and guards time with `state.lastSeenMs = maxOf(state.lastSeenMs, eventMs)`. The very next line does the opposite for distance: `state.lastOdometerM = odometer` (439), unconditionally. For a recovered crop `odometer` comes from `job.odometerM` (ScanActivity.kt:1564), the odometer reading at CAPTURE time, which is smaller than the current one.

**Почему:** contactM() = banked + (lastOdometerM - firstOdometerM).coerceAtLeast(0.0) (135) collapses toward 0 the moment a stale read lands. At 100 km/h a 3-minute spill delay rewinds the odometer by 5 km - enough to drop a car below SUSPECT_CONTACT_M (2 500) or SUSPECT_HIGHWAY_M (8 000) in classify() line 591 and demote it out of SUSPECT. The demoted level and the shrunken contact_m are then written to the database by the updateEvidence call at 511-518. The same stale value poisons the next reacquisition test, where `gapM = odometer - state.lastOdometerM` (420) is measured against the rewound baseline and can exceed REACQUIRE_DISTANCE_M spuriously.

**Как чинить:** Mirror the time guard so the pair moves together: `if (eventMs >= state.lastSeenMs) { state.lastSeenMs = eventMs; state.lastOdometerM = odometer }`. lastSeenMs and lastOdometerM describe the same observation and must be updated together or not at all.

*Проверка (CONFIRMED):* Holds, and is worse than described. Line 438 guards time (state.lastSeenMs = maxOf(state.lastSeenMs, eventMs)) while 439 assigns state.lastOdometerM = odometer unconditionally, and odometer resolves to job.odometerM for recovered crops (ScanActivity.kt:1564 -> AlprWorker drainOneSpilled odometerM = crop.odometerM -> SpillStore meta 'odo' written at capture). Because SpillStore.poll() drains NEWEST first, a backlog delivers monotonically older odometer readings, so contactM() = banked + (lastOdometerM - firstOdometerM).coerceAtLeast(0.0) (135) collapses toward the banked value for the whole drain, and classify() can demote out of SUSPECT at 586-592 with the shrunken level and contact_m written straight to the DB by the plain-SET updateEvidence (511-518, TrackingStore.kt:637-641). The inflation direction also exists and the claim misses it: SpillStore.adopt() carries over crops from a previous run whose odometerM belongs to that run's trip odometer, so lastOdometerM can jump far ABOVE the current trip odometer and manufacture kilometres of contact past SUSPECT_HIGHWAY_M. The gapM-baseline concern at 420 is the weakest part (the reacquire branch also needs gapMs > REACQUIRE_GAP_MS, which a stale read cannot produce since lastSeenMs is max-guarded), but the contactM corruption alone justifies medium.

### [follow] MEDIUM — checkFollowerAbsence compares wall clock to capture-time lastSeenMs

`follow/FollowEngine.kt`:212

**Что:** `if (nowMs - state.lastSeenMs >= runtime.videoAbsentSeconds * 1_000L)` (212) is fed System.currentTimeMillis() from the 5 s watchdog (ScanActivity.kt:633), but lastSeenMs is now the crop's capture time, not its delivery time. DEFAULT_ABSENT is 60 s (RuntimeSettings.kt:138). Whenever the target's crops go through the spill path - and per the comment at 404-406 those arrive 'minutes late' - lastSeenMs lags wall time by more than 60 s while the car is still directly behind us.

**Почему:** The clip about a confirmed tail is terminated (videoActive = false, onFollowerLost -> ScanActivity.kt:428) even though the engine is still recognising that plate. Worse, it churns: the next delivered read passes the `worthFilming && !state.videoActive` test at 477 and calls onFollowerConfirmed, starting a new recording. The result is a stream of truncated clips of the one vehicle the feature exists for, and spilling correlates with heavy traffic - exactly when a tail is worth filming.

**Как чинить:** Keep a separate wall-clock stamp for liveness (e.g. `state.lastDeliveredMs = nowMs`, updated on every accepted sighting regardless of capturedAtMs) and test absence against that, leaving lastSeenMs to mean 'when the vehicle was actually there' for contact accounting.

*Проверка (CONFIRMED):* The clock mismatch is real: checkFollowerAbsence compares System.currentTimeMillis() (ScanActivity.kt:633) against lastSeenMs, which is now capture time (438), with DEFAULT_ABSENT = 60 s. Crops far older than 60 s definitely reach onSighting - SpillStore.adopt() picks up crops written by a previous run and drainOneSpilled forwards their original capturedAtMs (AlprWorker.kt:335), bypassing the 5 s staleAfterMs drop that only guards queued jobs (AlprWorker.kt:396). The churn is the concrete failure and it is fully traceable: onSighting starts a clip on a stale read (worthFilming && !videoActive, 475-480) while the watchdog, running 5 s later on the wall clock, sees nowMs - lastSeenMs >= 60 s and tears it down (212-215 -> ScanActivity.kt:428 recorder.stop()), and the next stale read of the same plate starts another. The cleanest instance needs no route evidence at all: loadBlacklist() marks a plate BLACKLIST (339-351), worthFilming is true for any blacklisted state, so each recovered crop of a blacklisted plate produces a ~5 s clip that is then sent. The claim's broader framing ('the engine is still recognising that plate' during live traffic) is weaker than stated, since lastSeenMs is a max over all reads and any live read refreshes it, but medium stands on the stale-read churn.

### [threads] MEDIUM — tg-commands executor is never used: every command still blocks the Telegram poller

`telegram/TelegramBot.kt`:348

**Что:** `commands` is created at line 107 and shut down at line 181, and those are its only two references in the file — there is no `commands.execute` anywhere. `loop()` still calls `handle(active, update)` inline at line 348, so every command runs on the `tg-poller` thread, exactly as before the three-lane split.

**Почему:** The consequences are concrete, not stylistic. `/photo` -> `handleAction("photo")` -> `host.snapshot()` (ScanActivity.kt:1220) parks the poller on `latch.await(4, SECONDS)` waiting for the frame-analysis thread. `/report` -> `host.buildReport()` runs the whole HTML build with embedded photos (up to 12 MB of base64) on the poller. `/wipe yes` -> `host.wipeAll()` runs DELETE-of-every-table plus VACUUM plus a recursive file walk on the poller. While any of these runs there is no outstanding `getUpdates`, so the urgent commands the lane split was created to protect — `/stop`, `/pause`, `/rec` — are not fetched at all. `ensureAlive()` cannot help either: the thread is alive, just blocked. The comment at lines 98-104 asserts a property the code does not have.

**Как чинить:** Dispatch from `loop()`: `commands.execute { runCatching { handle(active, update) }.onFailure { … } }`. Keep the executor single-threaded so commands stay serialized (which is what makes `muted`, `offset` and the host's non-atomic state safe), and keep `offset` advancement on the poller thread so the dispatch itself is not re-entrant.

*Проверка (CONFIRMED):* Verified by grep: `commands` appears only at TelegramBot.kt:107 (creation) and :181 (shutdown); there is no commands.execute anywhere in the file. loop() still calls handle(active, update) inline at line 348, on tg-poller. The consequences are traceable: handleAction 'photo' (line 472) calls host.snapshot(), which blocks on latch.await(4, SECONDS) (ScanActivity.kt:1247); 'report:' (line 481) runs host.buildReport -> HtmlReportBuilder.build with embedded base64 photos on the poller; 'wipe:yes' (line 571) runs host.wipeAll() -> StorageCleaner.wipe (recursive file walk over filesDir and cacheDir) plus store.wipeDatabase, also on the poller. While any of these runs there is no outstanding getUpdates, and ensureAlive() (lines 159-166) only checks thread.isAlive, so a blocked poller is never restarted. The comment at lines 98-104 asserts a property the code does not implement. Severity lowered from high: commands already ran on the poller before the three-lane split, so this is an unfinished fix rather than a new regression, and the harm is seconds of delay on /stop, not lost evidence or a crash.

### [threads] MEDIUM — onDestroy sleeps on main, so the clip-finalize callback it waits for can never run

`ScanActivity.kt`:1840

**Что:** `onDestroy` stops the recorder and then does `Thread.sleep(CLIP_FINALIZE_WAIT_MS)` (1200 ms) on the main thread to give the MP4 time to finalize. But `VideoRecorder` delivers its `VideoRecordEvent.Finalize` on `ContextCompat.getMainExecutor(context)` (VideoRecorder.kt:32,84) — the callback is queued on the very Looper that is sleeping. Immediately afterwards `bot?.stop()` blocks main again on `sender.awaitTermination(2, SECONDS)` (TelegramBot.kt:182) while that thread is doing network I/O.

**Почему:** The fix cannot achieve its stated purpose. The Finalize event only runs after `onDestroy` returns — i.e. after `bot.stop()` has set `client = null` (TelegramBot.kt:183) and shut the media lane down. `onClipFinished` -> `deliverClip` -> `sendClip` then hits `val active = client ?: return onDelivered(false)` and logs "clip not delivered"; the clip proving a tail is left on disk for the next session instead of being sent, which is exactly the outcome the comment says it is preventing. Meanwhile the activity blocks the UI thread for up to 3.2 s on every exit (1.2 s sleep + up to 2 s termination wait + 400 ms `analysisExecutor.awaitTermination`), an ANR window on a phone that is already thermally throttled.

**Как чинить:** Do not block main. Move the shutdown tail into a callback: stop the recorder, and have `onClipFinished` (which already runs on main) drive the remaining teardown, or hand the pending clip to an application-scoped uploader that outlives the activity. `bot.stop()`'s `awaitTermination` should run off the main thread as well.

*Проверка (CONFIRMED):* Traced exactly. ScanActivity.onDestroy runs on main and does videoRecorder?.stop() then Thread.sleep(CLIP_FINALIZE_WAIT_MS = 1200) (lines 1838-1841; constant at 1892). VideoRecorder builds its callback executor as ContextCompat.getMainExecutor(context) (line 32) and passes it to .start(executor) { ... VideoRecordEvent.Finalize ... } (lines 84-102), so the Finalize event is posted to the very Looper that is sleeping - the sleep provably cannot let the finalize run. By the time onDestroy returns it has already called bot?.stop() (line 1844), which shuts sender/media/commands down and sets client = null (TelegramBot.kt:179-183). The queued Finalize then fires onClipFinished -> deliverClip (ScanActivity.kt:454, 461-474) -> sendClip, which hits `val active = client ?: return onDelivered(false)` (TelegramBot.kt:266) and logs 'clip not delivered'. Main is additionally blocked for up to 2 s by awaitTermination (TelegramBot.kt:182) and 400 ms by analysisExecutor.awaitTermination (line 1858). Severity lowered from high because the clip is not lost: it keeps its tail_*.mp4 name and resendPendingClips() (lines 526-542) delivers it on the next launch, so the damage is a delayed clip plus a ~3 s main-thread stall.

### [threads] MEDIUM — Work submitted after shutdown throws RejectedExecutionException and kills the process

`ScanActivity.kt`:502

**Что:** `onDestroy` calls `ioExecutor.shutdown()` (line 1854), but paths that submit to it survive the activity. `bot.stop()` sets `running=false` and interrupts the poller, yet the interrupt does not abort an in-flight HTTP read and `running` is only re-tested at the top of `loop()`: the current `updates.forEach { handle(...) }` batch (TelegramBot.kt:346-350) still executes afterwards. A `/stop` in that batch -> `setSessionRunning(false)` -> `mainHandler.post { haltSession() }` -> `sendTripDebrief()` -> `ioExecutor.execute` on a shut-down executor.

**Почему:** `RejectedExecutionException` is thrown inside a Runnable running on the main Looper — uncaught, so `CrashReporter`'s handler writes a crash note and the process is killed; the next start then reports "прошлая сессия упала" for what was a clean exit. A second instance of the same shape: `bot.stop()` shuts `sender`/`media` down at lines 179-181 but only nulls `client` at line 183, up to two seconds later; a queued `onThreat` task running on `lensalpr-io` during that window passes the `client != null` check in `alert()` and calls `sender.execute` on a dead executor — an uncaught exception on a pool thread, which Android's default handler also turns into a process kill. `handle()`'s own `runCatching` hides the third variant (`setIgnored`/`setBlacklist` -> `ioExecutor.execute`), so the operator simply never gets a reply.

**Как чинить:** Set a `destroyed` flag in `onDestroy` before shutting anything down and have `haltSession`/`sendTripDebrief`/the BotHost methods return early on it; null `client` first in `TelegramBot.stop()` before shutting the executors down; and wrap the remaining submissions in `runCatching`.

*Проверка (CONFIRMED):* Both paths traced. (1) TelegramBot.stop() sets running=false and interrupts the poller (lines 169-171), but running is only re-tested at the top of the while loop (line 336): an in-flight getUpdates HTTP read is not aborted by interrupt, and the returned batch is fully processed by updates.forEach { handle(...) } (lines 346-350). A '/stop' in that batch -> host.setSessionRunning(false) (ScanActivity.kt:1266-1272) -> mainHandler.post { haltSession() }; haltSession (lines 669-682) has no destroyed guard (grep finds no such flag) and sessionRunning is still true, so it reaches sendTripDebrief(), whose ioExecutor.execute at line 502 is unwrapped. onDestroy calls ioExecutor.shutdown() at line 1854 and the posted Runnable can only run after onDestroy returns, so RejectedExecutionException (AbortPolicy is the default) is thrown on the main Looper, uncaught. The window is wide precisely because onDestroy blocks main for seconds. (2) stop() shuts sender/media down at 179-181 and only nulls client at 183, after awaitTermination(2 s); a task already queued by onThreat (ScanActivity.kt:776-781, where `active.alert(evidence, photo)` is not wrapped) passes `val active = client ?: return` (line 190) in that window and calls sender.execute at line 227 - the exception escapes the Runnable on lensalpr-io and reaches the default handler. (3) setBlacklist/setIgnored -> ioExecutor.execute (lines 1193, 1409) do throw, but loop()'s runCatching around handle (line 348) swallows it, so the operator simply gets no reply. Medium is right: real, but it needs a teardown-time coincidence.

### [threads] MEDIUM — toggleRecording() runs on the poller and races FollowEngine's start() on main

`ScanActivity.kt`:1286

**Что:** `/rec` reaches `host.toggleRecording()` on the `tg-poller` thread (TelegramBot.kt:424), while `onFollowerConfirmed` calls `recorder.start(evidence.plate)` on main (ScanActivity.kt:418) and the watchdog calls `recorder.stop()` on main (line 630). `VideoRecorder.start` is a check-then-act with no lock: `if (recording != null) return false`, then `target = plate; startedAtMs = now; recording = capture.output…start(...)` (VideoRecorder.kt:73-90).

**Почему:** Both threads can pass the `recording != null` check. CameraX then throws `IllegalStateException` for the second `start()` on the same Recorder; the `getOrElse` at VideoRecorder.kt:105-110 sets `target = null` and `startedAtMs = 0L` — but `recording` still holds the first, genuinely active Recording. The recorder is now filming with `currentTarget == null` and `elapsedMs == 0`. In the watchdog (ScanActivity.kt:626) `target != null` is false, so `checkFollowerAbsence` is never called and the `MAX_CLIP_MS` segmentation never triggers: the clip grows without bound until the session ends or the disk fills, and when it finally finalizes, `plateForClip = target ?: plate` attributes it to whichever plate lost the race.

**Как чинить:** Make `toggleRecording()` post to `mainHandler` like the other mutating BotHost methods (`setPaused`, `nextLens`, `setSessionRunning` already do), and mark `VideoRecorder.start`/`stop` `@Synchronized` so the check-then-act is atomic; on failure restore the previous `target`/`startedAtMs` rather than clearing them.

*Проверка (CONFIRMED):* Traced. '/rec' -> host.toggleRecording() runs inline on tg-poller (TelegramBot.kt:424, and the 'rec' action at 529), and ScanActivity.toggleRecording (1279-1288) calls recorder.start/stop directly - unlike setPaused (1252), nextLens (1275) and setSessionRunning (1270), which all post to mainHandler. onFollowerConfirmed calls recorder.start(evidence.plate) on main (line 418) and the watchdog calls recorder.stop() on main (line 630). VideoRecorder.start (73-111) is an unsynchronized check-then-act: `if (recording != null) return false`, then target/startedAtMs are assigned, then recording = capture.output...start(...). Two threads can both pass the null check; CameraX rejects the second prepareRecording().start() on the same Recorder, and the getOrElse at 105-110 sets target = null and startedAtMs = 0L while `recording` still holds the first, live Recording. isRecording stays true, currentTarget is null and elapsedMs is 0 (lines 52-53), so the watchdog's `if (recorder != null && target != null)` (ScanActivity.kt:626) is false: neither the MAX_CLIP_MS segmentation (627-631) nor checkFollowerAbsence (633) ever runs and the clip grows without bound. One correction: at Finalize, `plateForClip = target ?: plate` resolves to the closure plate of the start() call that actually created the Recording (the winner), not 'whichever plate lost the race'. Medium is right.

### [bitmaps] LOW — drainOneSpilled has no try/finally, and the new drain loop calls it unguarded

`pipeline/AlprWorker.kt`:307

**Что:** Between `pool.acquire` (line 307) and `pool.release` (line 349) there is no try/finally, and the only try in the method wraps `AlprEngine.process`. `Bitmap.createScaledBitmap` at line 318 is outside it. The new idle loop `while (running && queue.isEmpty() && drainOneSpilled())` (line 387) is likewise unguarded - loop() catches only InterruptedException from `queue.poll`.

**Почему:** One OutOfMemoryError (or any RuntimeException) at line 318 or 328 does three things at once: the pooled direct buffer just acquired - up to ~5 MB of native memory for a 1280x960 crop - is never returned to the pool; the decoded crop bitmap is never recycled; and the throwable escapes drainOneSpilled, escapes the while loop at line 387, escapes loop(), and kills the "alpr-worker" thread while `running` is still true. After that `isRunning` keeps reporting true, so FrameProcessor.scheduleRecognition keeps cutting crops (FrameProcessor.kt:325), submit() keeps enqueueing into a queue nobody polls until it saturates, then every crop goes to disk forever. Recognition is dead and nothing in the UI, the bot, or the stall watchdog (which only watches camera frames, ScanActivity:559) says so. Changing one drain per 120 ms poll into a back-to-back loop multiplies the exposure by the size of the backlog - `SpillStore.adopt()` can hand it 600 carried-over crops the moment a session starts, i.e. 600 unguarded decode+scale allocations in a row on a phone simultaneously running 4K capture, ONNX and MediaRecorder.

**Как чинить:** Wrap the body of drainOneSpilled in try/finally so the buffer is always released and the decoded bitmap always recycled on the failure path, and wrap the drain loop in loop() in a catch (Throwable) that logs and breaks, so a failed drain costs one crop rather than the whole worker.

*Проверка (CONFIRMED):* The code facts hold: drainOneSpilled (AlprWorker.kt:300-352) has no try/finally between pool.acquire (307) and pool.release (349); the only try wraps AlprEngine.process (311-316), leaving store.poll(), copyPixelsToBuffer and Bitmap.createScaledBitmap (318) unguarded; and the new idle loop `while (running && queue.isEmpty() && drainOneSpilled())` (387) is inside loop()'s elvis block, whose only catch is InterruptedException around queue.poll (381). A throwable therefore escapes loop() and ends the "alpr-worker" thread. But the central consequence is refuted. LensAlprApp.kt:11 installs CrashReporter, and CrashReporter.install chains to the platform handler (`previous?.uncaughtException(thread, error)`, CrashReporter.kt:44), and Android's default handler kills the process for an uncaught exception on ANY thread. So there is no zombie worker reporting isRunning==true, no queue that saturates unnoticed, and no 'nothing in the UI or the bot says so' - the session dies loudly, writes last_crash.txt and is delivered to Telegram by reportPreviousCrash (ScanActivity.kt:482-488). The unreleased pooled buffer and the unrecycled decoded bitmap are likewise moot: the process is gone, and even without the crash the buffer merely misses the pool cache rather than leaking natively. What remains is worth fixing - one OOM in a 600-deep back-to-back drain ends the whole session instead of costing one crop - but that is a hardening item on a hypothesised allocation failure, so low rather than medium.

### [bitmaps] LOW — stop() does not join the worker; a later start() leaves two loops draining one file

`pipeline/AlprWorker.kt`:203

**Что:** `stop()` sets `running = false`, interrupts and immediately nulls `thread` (lines 204-206) without joining. If `start()` runs before the old thread has come back from its current job, `start()` sees `running == false`, sets it true and spawns a second "alpr-worker"; the old thread then re-tests `while (running)` at line 378, finds true again, and never exits.

**Почему:** onEngineStatus toggles exactly this pair - `worker.stop()` on runtimeLimited and `worker.start()` on READY (ScanActivity:1596/1601) - and every `AlprEngine.reinitializeBlocking` from the benchmark paths republishes a READY status. With two live loops, both idle branches can enter drainOneSpilled concurrently: `SpillStore.poll()` (SpillStore.kt:129-161) is not synchronised, so both threads list the directory, both pick the same `maxByOrNull { it.name }` file, both decode it, both `bytes.addAndGet(-length)` and `queued.decrementAndGet()` for one file, and both delete it. The same photograph is then submitted twice, so one crop casts two identical votes in the consensus - the corroboration counter, which is the entire basis for trusting a plate, is silently inflated, and `spilledCount` drifts negative.

**Как чинить:** Join the previous thread in stop() (or keep a reference and have start() wait for it), and/or make loop() capture its own thread identity and exit when `thread !== Thread.currentThread()`. Guarding SpillStore.poll() with a lock removes the double-drain independently.

*Проверка (CONFIRMED):* Both halves of the mechanism check out, but the trigger is much narrower than described. stop() (AlprWorker.kt:203-214) sets running=false, interrupts and nulls `thread` with no join; start() (194-201) only guards on `if (running) return`, so a start() that lands before the old thread re-tests `while (running)` at 378 leaves two live loops, and the old one never exits. SpillStore.poll (SpillStore.kt:129-161) is genuinely unsynchronised - both threads can listFiles, pick the same maxByOrNull{name}, both decode (the delete only happens at 157-159, after decode and meta read), and both bytes.addAndGet(-length)/queued.decrementAndGet() for one file - so with two loops one photograph really is submitted twice, and for a carriedOver crop that means two increments of the same orphanCounts key (VehicleRegistry.kt:254-262), i.e. a plate confirmed from a single image. Correction to the trigger: worker.stop() and worker.start() (ScanActivity.kt:1596/1601) are not adjacent in practice. markRuntimeLimited publishes from inside process() (AlprEngine.kt:209/228-230) and thereafter status.isReady is false, so the worker's process() returns immediately without taking engineLock and it re-tests `running` within milliseconds; the next READY publish requires a full reinitializeBlocking (AlprEngine.kt:144-177), seconds later. The overlap needs both status runnables to be sitting in the main queue at once while the old thread is mid-job. So the hazard is a missing invariant (nothing enforces one loop per worker) rather than a demonstrable in-service failure - correctly filed as low.

### [camera] LOW — A rebind that hits the no-video fallback loses clips for the rest of the session

`camera/CameraController.kt`:258

**Что:** When bindToLifecycle with the video use case throws, bind() calls videoRecorder.detach() — which stops any live Recording and sets VideoRecorder.videoCapture = null (VideoRecorder.kt:67-70) — and re-binds without it. buildUseCase() is only reached again on the next bind(), and needsRebind (lines 121-124) is false for every subsequent rotation step because the route is unchanged and `camera` is non-null.

**Почему:** On the S25 the route is always LOGICAL_MULTI_CAMERA, so after the first fallback the only thing that can ever call bind() again is the stall watchdog's forceRebind — which is capped at two attempts (see the watchdog finding). VideoRecorder.start() returns false while videoCapture is null, so every confirmed tail from then on takes the `follow?.noteVideoFinished(plate)` branch in ScanActivity.onFollowerConfirmed and is never filmed. A transient bind failure during a stall recovery therefore silently disables the video evidence for the whole drive, and the only signal is the preflight string "камера отказала" printed once at startup. A rebind issued while a clip is live also unbinds the Recording's VideoCapture and immediately replaces VideoRecorder.videoCapture with a fresh instance inside group(true), before the old recording's Finalize event has landed.

**Как чинить:** Retry the with-video group on a later apply — e.g. keep a `videoRefused` counter and clear it after N successful frames, or attempt the video use case again on every forced rebind regardless of route. Stop any live recording explicitly before unbindAll() so the Finalize is ordered before buildUseCase() swaps the capture out, and report the loss of clips through onError rather than only in the preflight.

*Проверка (CONFIRMED):* The mechanism holds. The fallback at CameraController.kt:258-268 calls videoRecorder.detach(), which stops the recording and nulls videoCapture (VideoRecorder.kt:67-70); buildUseCase() — the only thing that ever re-creates it (VideoRecorder.kt:56-65) — is reached solely from group(true) inside bind() (line 248), and needsRebind (121-124) is false for every later rotation step because the Samsung route never changes. With videoCapture null, start() returns false at VideoRecorder.kt:74, so onFollowerConfirmed takes the noteVideoFinished branch (ScanActivity.kt:423-425) for every confirmed tail. The notification gap is real and slightly worse than claimed: onError is only invoked if the *second* bind also fails (line 265), so a successful no-video fallback produces nothing but a Log.w and the on-demand preflight line at ScanActivity.kt:952-955. The live-clip ordering point also checks out — unbindAll (157) runs before group(true)/buildUseCase (248), so the old Recording's Finalize lands after videoCapture has been swapped, though the handler captures `file`/`plateForClip` locally and ERROR_SOURCE_INACTIVE is in RECOVERABLE_ERRORS (VideoRecorder.kt:126-131), so the clip is kept; the only cost is a short window where isRecording is still true and a new tail cannot be filmed. Severity lowered from medium to low: bindToLifecycle refusing a Preview+Analysis+VideoCapture combination is a deterministic stream-configuration property, not a transient event, so the 'a rebind hits it and clips are lost for the drive' framing collapses into the startup case that the code deliberately accepts ('recognition matters more than the clip'). What is genuinely defective is only that the degrade is never retried outside a forced rebind and never reported.

### [camera] LOW — AE fps probe takes the first fixed range >= 30, so it can pin 60 fps instead of 30

`camera/CameraController.kt`:336

**Что:** readCapabilities() selects the pinned frame rate with `firstOrNull { it.lower == it.upper && it.upper >= STEADY_FPS }` over CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. That array has no guaranteed ordering, and Samsung firmwares commonly list [60,60] alongside [30,30]. The intent stated in the comment at lines 213-215 is to pin ~30 ("at most a thirtieth of a second", "the cost is a noisier frame").

**Почему:** Whether the session is pinned at 30 or 60 fps is decided by the order of an unordered characteristics array, so it can flip between firmware revisions with no code change. At [60,60] the ISP runs at double rate on a device whose pipeline is already thermally throttled (FrameProcessor.throttle, ScanActivity's thermal listener) while sharing the session with a 720p VideoCapture, and the exposure ceiling halves — at night that is half the light per frame, not "a noisier frame". Since the same session also carries a 1440p/4K ImageAnalysis stream whose minimum frame duration may not support 60 fps, a fixed 60 fps range is also the request most likely to be silently unmet by the HAL.

**Как чинить:** Select the range explicitly: prefer `Range(STEADY_FPS, STEADY_FPS)` if present, otherwise the fixed range with the smallest upper bound that is >= STEADY_FPS, e.g. `.filter { it.lower == it.upper && it.upper >= STEADY_FPS }.minByOrNull { it.upper }`.

*Проверка (CONFIRMED):* Code reading is exact: readCapabilities picks the AE range with `firstOrNull { it.lower == it.upper && it.upper >= STEADY_FPS }` (CameraController.kt:335-336) over CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, whose ordering is not specified by the platform, and STEADY_FPS is 30 (line 435). Any device that advertises a fixed [60,60] ahead of [30,30] gets pinned at 60, and the result is then applied unconditionally as CONTROL_AE_TARGET_FPS_RANGE (216-218). So which frame rate the session runs at is decided by array order rather than by the code's intent, and can change between firmware revisions with no source change — that much is traceable. Low is the right severity, and one part of the rationale is wrong: pinning [60,60] still satisfies the stated goal in the comment at 213-215 ('exposure at most a thirtieth of a second'), it just halves the light and doubles the sensor rate on a thermally constrained pipeline; the 'HAL silently fails the request' part is speculation, since [60,60] is by definition a member of the advertised list.

### [consensus] LOW — upgradePlate's collision merge leaves the caller publishing a card that was just deleted

`pipeline/VehicleRegistry.kt`:464

**Что:** In the collision branch upgradePlate does `vehicles.remove(entry.plate)`, folds `entry` into `collision`, and returns true — but it never updates `entry.plate`, and `entry` is now detached from `vehicles`. The reachable caller is `touch()` (line 425, called from the already-confirmed path at line 170): there `entry = vehicles[alreadyConfirmed]` while `winner.text` may be a different spelling that already owns a card. After touch returns, submit() does `state.confirmed = entry.plate` (line 171) — a key that was just deleted — and returns `SubmitResult(UPDATED, entry.toCard())`, a card whose `plate` no longer exists in the registry. touch() also writes `entry.ocrScore`/`entry.thumbnail` after the merge, so the fresher score and thumbnail land on the discarded object instead of the surviving card. (In the confirm path at line 195 the collision is always null, because `existing` is only reached by similarity after `vehicles[winner.text]` returned null, so this is specific to touch().)

**Почему:** ScanActivity:1554-1578 feeds `card.plate` straight into `follow.onSighting`, and FollowEngine.canonicalKey returns `plate` unchanged when a state for it already exists (line 528) — which it does, since that plate was confirmed earlier. So the same physical car keeps two follow states, and its contact time, shared turns and sighting count are split between the merged-away spelling and the surviving one. A follower that stays just under the tail threshold on each half is never flagged — the precise failure the merge was written to prevent. `state.confirmed` also points at a dead key, so the next read re-runs the whole confirmation path.

**Как чинить:** Have upgradePlate return the entry that survived (or null) rather than a boolean, and make touch()/submit() operate on that entry for the score, thumbnail, attributes, `state.confirmed`, `runtime.confirmedPlate` and the returned card. Apply the score/thumbnail update *before* folding into the collision so the better frame is not lost.

*Проверка (CONFIRMED):* The code path is exactly as described: upgradePlate:461-475 removes entry.plate from vehicles, folds into collision and returns true without updating entry.plate, leaving entry detached; touch:426-430 then writes ocrScore/thumbnail/attributes onto the discarded object; submit:171 sets state.confirmed = entry.plate (a key just removed) and 173 returns UPDATED with entry.toCard(), a card whose plate is absent from vehicles. The claim's own scoping is right — the line-195 branch and submitOrphan:243 can never hit the collision arm, because existing is only reached by similarity after vehicles[winner.text] returned null, so collision is null there; only touch() can reach it. But the 'why' is refuted: FollowEngine.canonicalKey:527-538 merges a new plate into an existing *similar* state (or re-keys that state onto the new plate), so the two spellings do not accumulate two follow states and contact time is not split — that merge already happened when the second spelling was first published. The residual harm is real but small: the better score/thumbnail are lost, one sighting is recorded under a plate the registry no longer lists, runtime.confirmedPlate/state.confirmed dangle, and the dangling state.confirmed self-heals on the next read (vehicles[dead key] is null, so submit falls through to the confirm path at 195, finds the surviving card and resets state.confirmed). Severity high -> low.

### [consensus] LOW — supportFor hands a lone read the whole group's weight via prefix/suffix counting

`alpr/PlateFusion.kt`:122

**Что:** supportFor counts any shorter read that is a prefix or suffix of the spelling as backing it. Combined with the early return at line 55, one read can claim the whole group: readings ["AB123"@70, "AB123"@72, "AB1234"@75] give cohorts {5:2, 6:1}; the longest cohort has size 1 < MIN_LENGTH_SUPPORT and the shorter cohort has size 2 < STRONG_SUPPORT, so `voters = longestCohort`, `voters.size < 2`, and the function returns `Fused("AB1234", supportFor("AB1234", readings)) = 3`. The two truncated reads never contained the trailing '4' — they cannot corroborate it — yet they hand their weight to the read that invented it. This directly contradicts the method's own doc ("one read must never be able to claim the weight of the whole group").

**Почему:** The inflated support becomes the card's `votes` (VehicleRegistry:154, 216), and `PlateSimilarity.prefer` breaks ties in favour of the longer string, so the hallucinated spelling both wins the card and arrives pre-armed with a vote count that later tracks cannot beat — feeding directly into the freeze above. The published plate carries a character no read ever agreed on.

**Как чинить:** Count only reads that actually contain the disputed positions: for a shorter read, credit support only when it aligns (via alignmentOffset) *and* the fused string's extra characters are not the ones in dispute — i.e. cap support at the number of reads whose length equals the winning spelling's, plus aligned reads that cover every position. At minimum, do not apply prefix/suffix credit on the `voters.size < 2` early-return path.

*Проверка (CONFIRMED):* The arithmetic checks out. For ['AB123'@70,'AB123'@72,'AB1234'@75] (all one group: PlateSimilarity.truncated accepts the 5-char prefix since MIN_LENGTH=5), cohorts are {5:2, 6:1}; longestCohort.size 1 < MIN_LENGTH_SUPPORT 2 but bestShorter.size 2 < STRONG_SUPPORT 3, so voters = longestCohort, voters.size < 2, and PlateFusion.kt:55 returns Fused(leader, supportFor('AB1234', readings)) = 3 — a single read collecting the whole group's weight through the prefix credit at line 122-124, directly against the invariant its own KDoc states ('one read must never be able to claim the weight of the whole group'). PlateFusionTest's 'a truncated read still supports the complete plate' only covers the benign case (two full reads plus one truncated), so nothing pins this down. Severity lowered from medium to low: the winning spelling is chosen by the cohort rule, not by supportFor, so only the vote count is wrong, and the downstream consequence the claim leans on (the permanent freeze in votes-freeze-wrong-spelling) does not hold — the effect is that a challenger needs one or two extra reads to displace a one-read spelling.

### [data] LOW — /wipe on the poller thread deletes cacheDir files the media lane is still using

`data/StorageCleaner.kt`:77

**Что:** `handleAction("wipe:yes")` calls `host.wipeAll()` inline on tg-poller (TelegramBot.kt:575), which calls `StorageCleaner.wipe`. `walk` deletes every file in `context.cacheDir` root (line 77) — that is exactly where HtmlReportBuilder writes `lensalpr_<stamp>.html` (HtmlReportBuilder.kt:53) and where the snapshot lands (ScanActivity.kt:1226) — plus every evidence JPEG under `filesDir/evidence`. Nothing serializes this against the tg-media executor or the lensalpr-io thread.

**Почему:** `sendReport` checks `file.exists()` on the *calling* thread (line 302) and then hands the file to the media lane; `sendDocument` re-checks and returns `false` (TelegramClient.kt:91) with no message to anyone. So `/wipe yes` issued while the end-of-trip debrief is uploading makes the report disappear with zero feedback — the operator sees the wipe confirmation and assumes the debrief is still coming. The same walk deletes `filesDir/last_crash.txt` (CrashReporter.kt:33/56), destroying an undelivered crash report, and deletes evidence JPEGs while `embedPhoto` (HtmlReportBuilder.kt:212) is mid-read, yielding a report whose photos are silently absent. Note the two commands themselves cannot interleave (both run on the poller thread) — the damage is against the other two lanes.

**Как чинить:** Run `wipeAll()` on the same single-thread lane as report building (`ioExecutor`) and have the bot await its result, or guard file deletion and report build/upload with one lock in ScanActivity. At minimum make `sendReport`/`sendClip` report a vanished file to the operator instead of returning false silently, so a wipe-induced loss is visible.

*Проверка (CONFIRMED):* The mechanism holds. handleAction("wipe:yes") calls host.wipeAll() inline on tg-poller (TelegramBot.kt:571-577) -> ScanActivity.kt:1469 -> StorageCleaner.wipe, whose walk() deletes every file in cacheDir root (StorageCleaner.kt:77 — where HtmlReportBuilder writes lensalpr_<stamp>.html, HtmlReportBuilder.kt:53, and where snapshot() writes frame.jpg, ScanActivity.kt:1226), every non-.onnx file in filesDir root (78-80 — including CrashReporter's last_crash.txt, CrashReporter.kt:33/56/92) and everything under filesDir/evidence (65-66). Nothing serializes that against tg-media or ioExecutor. sendReport does check exists() on the caller's thread and then hands the File to media (TelegramBot.kt:303-305); sendDocument re-checks and returns false with no user-visible message (TelegramClient.kt:91), so a debrief upload killed by a wipe is silent. embedPhoto (HtmlReportBuilder.kt:212-217) likewise degrades to a photoless card via runCatching. One correction to the claim's supporting detail: prune() is NOT a concurrent deleter — it runs on ioExecutor (ScanActivity.kt:381/387), the same single thread as recordSighting (FollowEngine io = ioExecutor, ScanActivity.kt:372); only the wipe crosses threads. Severity lowered to low: every artefact destroyed here is one the operator just explicitly asked to destroy, and the residual defect is the missing acknowledgement plus a narrow window on an undelivered crash note.

### [data] LOW — Photo written after commit can outlive its row, and prune can never reclaim it

`data/TrackingStore.kt`:485

**Что:** After the transaction the JPEG is written to `enc_<id>.jpg` and only then does `UPDATE encounters SET photo = ?` run, inside a `runCatching` that swallows failure (lines 486-495). A rolled-back transaction cannot leave a file — any throw inside the try propagates past `finally` and never reaches line 485 — but the reverse gap is open: the file exists on disk before any row references it.

**Почему:** `prune()` reclaims photos only by reading the `photo` column (lines 795-801), so any file whose UPDATE was swallowed, or whose row was deleted by a concurrent `prune`/`wipeDatabase` between COMMIT and the UPDATE, is invisible to every cleanup path except a full `/wipe`. `writeJpeg` also opens the `FileOutputStream` before `compress` can fail (FollowEngine.kt:644-650), so a bitmap recycled between the `isRecycled` check and `compress` leaves a 0-byte `enc_<id>.jpg` while the row keeps `photo` NULL. On a phone that scans for a month these accumulate in `filesDir/evidence` and are counted as evidence by `/storage`.

**Как чинить:** Write the JPEG to a temp name and rename only after the UPDATE succeeds, or have `prune()` additionally sweep `photoDir` for `enc_*.jpg` whose id is absent from `SELECT id FROM encounters`. Also log the swallowed `execSQL` failure instead of discarding it — a photo that never reaches its row is currently indistinguishable from one that was never wanted.

*Проверка (CONFIRMED):* Traced. TrackingStore.kt:485-496: the JPEG is written by photoWriter after endTransaction and only then does `UPDATE encounters SET photo = ?` run inside a runCatching that discards the failure, so the file can exist with no row pointing at it. The claim's own exculpation is correct — a throw inside the try propagates out of recordSighting and never reaches 485, so a rolled-back transaction leaves no file. prune() (794-801) reclaims photos only via `SELECT photo FROM encounters`, and no other path sweeps photoDir (StorageCleaner only touches it on a full wipe, StorageCleaner.kt:65-66), so an orphan is permanent and is still counted by /storage (ScanActivity.kt:1453-1462 via StorageCleaner.usage). writeJpeg (FollowEngine.kt:644-650) does open FileOutputStream before compress, so a compress failure leaves a 0-byte enc_<id>.jpg with photo NULL; the recycle race is plausible (card.thumbnail is handed to io at ScanActivity.kt:1577 while VehicleRegistry.recycle runs on main, VehicleRegistry.kt:539-541) though I could not pin a specific interleaving. The clearest concurrent row-deleter is wipeDatabase from tg-poller, not prune (prune shares ioExecutor with recordSighting, so those two cannot interleave). Low is correct — slow disk leak, no evidence lost.

### [follow] LOW — Sighting throttle uses arrival time while segments use capture time

`follow/FollowEngine.kt`:401

**Что:** The repeat-read throttle is stamped on the wall clock - `if (nowMs - state.lastRecordedMs < config.minSightingIntervalMs) return; state.lastRecordedMs = nowMs` (401-402) - but everything downstream now runs on `eventMs = minOf(capturedAtMs, nowMs)` (407). AlprWorker's idle loop drains parked crops back-to-back (`while (running && queue.isEmpty() && drainOneSpilled())`, AlprWorker.kt:387) and each recovered job carries `submittedAtMs = crop.capturedAtMs` (AlprWorker.kt:335), forwarded as capturedAtMs by ScanActivity.kt:1561. A burst of hundreds of recovered crops therefore reaches onSighting within a fraction of a second of wall time: for any one plate exactly ONE read survives the 2 s throttle, no matter how many minutes of capture time the burst covered.

**Почему:** One surviving read per burst means firstSeenMs == lastSeenMs for that segment, and closeSegment() banks nothing at all because its guard is `lastSeenMs > firstSeenMs` (line 125). If bursts are more than REACQUIRE_GAP_MS apart the cycle repeats forever: contactMs() stays 0, contactM() stays 0, `odometer - firstOdometerM > MOVING_CONTACT_M` never trips so movedWithUs stays false, and classify()'s first line (`!movedWithUs && contactM < MOVING_CONTACT_M -> IGNORE`, 569) pins the car at IGNORE permanently. segmentSightings also never reaches MIN_FOLLOWER_SIGHTINGS = 4, so `follower` is never set and the returnedAfterFollowing -> TAIL rule (573) can never fire. A car physically behind us for the entire trip stays invisible - and spilling happens precisely when traffic is heavy, i.e. when a tail is easiest to hide.

**Как чинить:** Throttle on the same clock the evidence uses: move the eventMs computation above the throttle and use `if (eventMs - state.lastRecordedMs < config.minSightingIntervalMs) return; state.lastRecordedMs = maxOf(state.lastRecordedMs, eventMs)`. Separately, make closeSegment bank single-sighting segments as a nominal duration (or weight them by segmentSightings) so a real stretch of company is never worth literally zero.

*Проверка (CONFIRMED):* The mechanism is real - the throttle is stamped on arrival (401-402, nowMs) while everything downstream runs on eventMs = minOf(capturedAtMs, nowMs) (407) - and dropped reads cost creditTurns (448), segmentSightings and a recentSightings entry. But the failure chain as written does not hold. (1) 'A burst of hundreds of recovered crops reaches onSighting within a fraction of a second': drainOneSpilled (AlprWorker.kt:300-348) decodes a JPEG, runs AlprEngine.process synchronously and builds a scaled thumbnail per crop, and onRecognition is posted to the main thread; hundreds of crops take tens of seconds of wall time, so roughly one read per plate per 2 s survives, not one per burst. (2) The claimed 'contactMs stays 0' for spill-fed plates is real but has a different cause the reviewer missed: SpillStore.poll() returns NEWEST first (maxByOrNull { it.name }, SpillStore.kt), so eventMs decreases through a drain; firstSeenMs is set from the first (newest) delivered read, every later one is older, lastSeenMs = maxOf never advances and gapMs is negative so closeSegment never runs. Note the proposed fix (eventMs - lastRecordedMs) would, under that LIFO ordering, make every subsequent recovered read negative-aged and drop ALL of them. Residual real defect: a stale spilled read shadows a live read of the same plate for 2 s of wall time, discarding the fresher evidence - hence low, not high.

### [follow] LOW — Manoeuvre-start tMs lets 'before' absorb sightings taken during the turn

`follow/FollowEngine.kt`:360

**Что:** onTurn builds the candidate set from `recentSightings.forEach { (plate, tMs) -> if (tMs >= since) pending.before += plate }` with `since = event.tMs - PRE_TURN_WINDOW_MS` (359-360). There is no upper bound. event.tMs is now the manoeuvre START (TripTracker.kt:231-232 passes manoeuvreStartMs) and onTurn only runs when finishManoeuvre fires - up to MANOEUVRE_MAX_MS = 15 s later, on top of the up-to-8 s of pre-crossing window already baked into that start stamp. Every sighting in [tMs, tMs+~16 s] is therefore already in recentSightings when 'before' is assembled and lands in it.

**Почему:** 'Before' is supposed to mean 'was behind us while we had not yet turned'. A car recognised at tMs+1 s now enters before, and a second recognition at tMs+3 s satisfies creditTurns (`age in 0..POST_TURN_WINDOW_MS`, 552) - both while we are still physically mid-manoeuvre and the car behind has had no chance to make the same route decision. Two reads 2 s apart (the minSightingIntervalMs floor) suffice to earn a shared turn. classify() returns TAIL outright at `sharedTurns >= 3` (578), so this is a direct false-positive channel that the pre-rewrite code, which stamped the event at the crossing moment, did not have.

**Как чинить:** Cap the candidate window at the event time: `if (tMs in since..event.tMs) pending.before += plate`. Better, carry both stamps on TurnEvent - keep tMs = manoeuvre start for turnsSince/history, and add endedAtMs so 'before' closes at the start while the post-turn credit window opens at the end.

*Проверка (CONFIRMED):* The code reads as claimed: since = event.tMs - PRE_TURN_WINDOW_MS with no upper bound (359-360), event.tMs is the manoeuvre START (TripTracker.kt:231-232 passes manoeuvreStartMs, which is itself up to TURN_WINDOW_MS = 8 s before the threshold crossing), and onTurn only runs from finishManoeuvre, up to MANOEUVRE_MAX_MS = 15 s after that start - so sightings taken during our own manoeuvre do land in pending.before, and a second read at age 0..POST_TURN_WINDOW_MS credits a shared turn (552-556). Severity corrected medium->low: the pre-rewrite code stamped the event at the crossing, which was already mid-swing, so the added contamination is only the crossing-to-settle interval (typically 4-8 s), and any car reaching classify() at all must first clear the movedWithUs/MOVING_CONTACT_M gate at 569, which a vehicle merely stationary at the junction does not. The claim that this is a channel 'the pre-rewrite code did not have' is overstated; the semantic breakage of 'before' is real.

### [follow] LOW — closeSegment zeroes turnsDuringContact, forcing the 8 km gate after reacquisition

`follow/FollowEngine.kt`:130

**Что:** closeSegment() resets `turnsDuringContact = 0` (130) alongside segmentSightings. classify() reads it as `val straightRoad = state.turnsDuringContact == 0` and picks `distanceGate = if (straightRoad) SUSPECT_HIGHWAY_M else SUSPECT_CONTACT_M` (586-587) - 8 000 m versus 2 500 m. turnsDuringContact is only re-incremented by onTurn for states whose lastSeenMs falls inside the pre-turn window (363-365).

**Почему:** A car that drops back for more than REACQUIRE_GAP_MS and rejoins - the textbook tail behaviour this engine is built to catch, and the reason the consensus pool was added - is reset to 'straight road' on every return, so it must now cover 8 km of urban driving instead of 2.5 km to reach SUSPECT. The signal is inverted: repeated drop-and-rejoin, which should raise suspicion, mechanically raises the bar. Compounding it, the increment at 364 tests `state.lastSeenMs >= since`, and lastSeenMs is now capture time, so a car whose reads are lagging is skipped even when it was present at the junction.

**Как чинить:** Do not reset turnsDuringContact in closeSegment - it is a property of the whole relationship with the vehicle, not of one stretch of company (banked contact is preserved across segments for the same reason). If a per-segment count is genuinely wanted, keep both and let classify()'s distance gate use the cumulative one.

*Проверка (CONFIRMED):* Code reads as claimed: closeSegment() zeroes turnsDuringContact (130) alongside segmentSightings, classify() derives straightRoad from it and swings the distance gate between SUSPECT_CONTACT_M = 2500 and SUSPECT_HIGHWAY_M = 8000 (586-587), and closeSegment runs on any read gap over REACQUIRE_GAP_MS (432), even when no reacquisition is credited. The inconsistency is genuine: contactM() is cumulative across segments while turnsDuringContact is per-segment, so a cumulative distance is judged against a gate derived from one stretch. Severity corrected medium->low: turnsDuringContact only affects the last OR-term of the SUSPECT test (588-591) - a car that repeatedly drops back and returns hits reacquisitions >= 2 and becomes SUSPECT regardless - and the counter is restored to 1 by the next turn taken while the car is present (363-365), so in city driving the demotion window is one junction. The secondary point (the 364 test now uses capture-time lastSeenMs) is true but marginal.

### [follow] LOW — rename/canonicalKey remap pendingTurns but not recentSightings

`follow/FollowEngine.kt`:540

**Что:** Both re-keying paths carefully rewrite the pending turn sets - canonicalKey 540-543 ('Turn credit is keyed by plate as well; without this the renamed car loses its evidence') and rename 306-309 - but neither touches recentSightings, which is keyed by plate too (458) and retains entries for SIGHTING_LOG_MS = 180 s. After a re-key the deque still holds only the dead spelling.

**Почему:** For the next three minutes any onTurn builds `pending.before` from those stale keys (360), so the vehicle's new key is absent from before and creditTurns can never satisfy `plate in turn.before` (553) - the shared turn is silently lost. sweepTurns then evaluates `states[plate]?.let { it.missedTurns += 1 }` (257) on a key that no longer exists, so not even the miss is booked and nothing signals that evidence went missing. canonicalKey re-keys automatically whenever a better-scored spelling arrives, and the lens rotation resets the tracker every few seconds, so this fires routinely rather than only on operator /rename - a steady under-count of the engine's strongest signal.

**Как чинить:** Rewrite recentSightings in both places. ArrayDeque has no in-place set, so rebuild it: `val remapped = recentSightings.map { if (it.first == from) to to it.second else it }; recentSightings.clear(); recentSightings.addAll(remapped)`. Note also that appending late reads with old eventMs (458) leaves the deque unsorted, so the prune loop at 459-463 stops at the first fresh entry and can strand stale ones behind it.

*Проверка (CONFIRMED):* The omission is real: pendingTurns are remapped in both re-key paths (306-309 and 540-543) but recentSightings, keyed by plate at 458 and retained for SIGHTING_LOG_MS = 180 s, is never rewritten (its only mutations are addLast/removeFirst/clear). The claimed consequence, however, is mostly self-healing on the canonicalKey path the reviewer says makes it routine: canonicalKey re-keys during a sighting, and that same sighting appends (newKey to eventMs) at 458, so the new key is in recentSightings from the instant of the re-key and any turn within PRE_TURN_WINDOW_MS finds it in before; entries under the dead spelling age out of the 60 s pre-window at the same rate as the live ones. The residual real hole is rename() (263-311), which re-keys with no sighting: a turn falling between the operator's /rename (ScanActivity.kt:1427) and the car's next read builds before from the dead key only, so creditTurns can never satisfy plate in turn.before (553) and that one turn's credit is lost silently (sweepTurns' states[plate]?.let at 257 no-ops on the removed key). Severity corrected medium->low: one lost turn per operator rename, not a steady under-count. The appended-out-of-order note is correct but harmless - the head-only prune at 459-463 merely lets a few entries linger.

### [threads] LOW — The crash note is deleted before it is delivered, so a disabled bot loses it

`CrashReporter.kt`:95

**Что:** `CrashReporter.consume` reads `last_crash.txt` and deletes it unconditionally (line 95), returning the text. `reportPreviousCrash()` (ScanActivity.kt:482-488) consumes it on `lensalpr-io` and hands it to `bot?.broadcast(...)`, which returns immediately without sending when `bot` is null (no token configured), when `config.telegramEnabled` is false (TelegramBot.kt:312), or when the send later fails on the `tg-sender` thread.

**Почему:** The stack trace from a session that died unattended on the rear window is the only evidence of why the drive was not watched, and it exists in exactly one copy. With the Telegram switch off — the documented, supported configuration, since the code elsewhere insists "that switch mutes alerts" — the note is destroyed on the next start and never seen by anyone. The same happens if the process crashes again in the seconds between the delete and the sender thread actually reaching Telegram, which is likely if the crash is a reproducible startup fault.

**Как чинить:** Delete only after delivery is confirmed: have `consume` rename the file to `last_crash.sent` (or return the `File` and let the caller delete it in the send callback), and leave it in place when no bot is configured so a later run can still deliver it.

*Проверка (CONFIRMED):* Traced. CrashReporter.consume (lines 91-97) reads last_crash.txt and calls file.delete() unconditionally before returning the text - no rename, no callback, no second copy. reportPreviousCrash (ScanActivity.kt:482-488) consumes it on lensalpr-io and passes it to bot?.broadcast, which drops it silently in three cases: bot is null when config.telegramToken is blank (startBot, line 1075); `val active = client ?: return` when start() never created a client (TelegramBot.kt:310 with 131-140); and `if (!config.enabled) return` (line 312) - the alerts-off configuration the code elsewhere explicitly supports ('that switch mutes alerts', TelegramBot.kt:126-130 and ScanActivity.kt:1070-1073). A later failure on tg-sender is likewise unreported. Severity lowered from medium: the same text is written to logcat by record() (CrashReporter.kt:57) and again by reportPreviousCrash (line 485), and the loss is diagnostic only - no plate, encounter or clip is affected.

### [threads] LOW — statusText() reads ListAdapter.itemCount from the Telegram poller thread

`ScanActivity.kt`:1109

**Что:** `botHost.statusText()` is invoked on the `tg-poller` thread (TelegramBot.kt:468 and the `start()` banner on `tg-sender`) and reads `adapter.itemCount`. `VehicleAdapter` extends `ListAdapter`, whose `getItemCount()` delegates to `AsyncListDiffer.getCurrentList().size`; that list reference is published from `submitList` on the main thread (`publishVehicles`, line 795) with no synchronization. `panel()` likewise reads `host.isPaused()` -> the plain (non-volatile) `userPaused` field from the poller thread.

**Почему:** No crash, but the numbers the operator is shown are not guaranteed to be the current ones: `AsyncListDiffer` is documented as main-thread-only, and the reader can observe the pre-`submitList` list or a stale `userPaused` indefinitely on ARM, so `/status` can report "подтверждено: 0" and an unpaused-looking keyboard while cars are on the list and scanning is paused. That is the single message the operator uses to decide whether the phone is doing its job.

**Как чинить:** Keep a `@Volatile private var confirmedCount` updated in `publishVehicles` and read that instead of `adapter.itemCount`; mark `userPaused` (and `parkedPause`) `@Volatile` since they are already written on main and read from bot threads.

*Проверка (CONFIRMED):* Traced. statusText() reads adapter.itemCount at ScanActivity.kt:1109 and is invoked from tg-poller (handleAction 'status', TelegramBot.kt:468) and from tg-sender in the start() banner (line 147). VehicleAdapter is `ListAdapter<VehicleCard, ...>` (VehicleAdapter.kt:19), so getItemCount() delegates to AsyncListDiffer.getCurrentList().size, and that list is published only from submitList on main (publishVehicles, ScanActivity.kt:795); AsyncListDiffer is documented main-thread-only and its current-list field carries no synchronization. panel() (TelegramBot.kt:672, 676) reads host.isPaused() from the same bot threads, and userPaused is declared `private var userPaused = false` at ScanActivity.kt:178 with no @Volatile (parkedPause likewise at 1719), while sessionRunning at 1722 is @Volatile. No crash is possible (plain field reads), but there is no happens-before between the main-thread write and the bot-thread read, so /status can show a stale confirmed count and a stale pause keyboard. Low is correct.

