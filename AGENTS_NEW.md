# AGENTS — Android Project Handbook

Rules distilled from a finished, fully E2E-verified Android port. Every rule
cost something: a bug, a wasted session, or a restart that had to re-derive
settled reasoning. Keep them.

## 0. Continuity (the most important rule)

A future agent instance must be able to resume **without re-reasoning**.

- `REASONING.md` is the running chronological log: decisions, dead ends,
  wrong assumptions, broken tools — written **as they are discovered**,
  never batched at the end, never conclusions-only.
- Every mistake recorded as three lines: **what was believed / what was
  actually true / the rule that prevents the repeat**.
- `docs/current-task.md` is updated **while the task is in progress**:
  in-progress state, what was found, how to verify right now, the exact
  next step. Honest mid-task snapshots mark work **[in progress]** and list
  known failures.
- Doc entries (decisions, bugs, status) land the moment they are final,
  not when the task finishes.
- Docs are simple, short, and structured: prefer tables over prose;
  cap list items at 5 per group.
- Docs never point at online services. The repo **is** the entire build
  input: source is here, build happens here, nothing is fetched. Vendored
  code is recorded in `VENDORED.md` with exact version + hash so the copy
  is verifiable by diff.

## 1. Repo invariants

- Every program and script in the repo answers `--help` properly (usage
  line, flags, truthful defaults) **and** is self-contained: the text a
  user sees (help and runtime reports) must not reference decision numbers,
  idea numbers, or other files.
- Every script must run in a **bare shell**: no reliance on a login
  profile's `JAVA_HOME`/`PATH`. Fall back to known toolchain paths
  (e.g. glob `~/toolchain/jdk-17*`) instead of hardcoding one user's home
  or one exact patch version.
- No third-party runtime dependencies without a recorded decision and a
  license-attribution entry. Prefer stdlib; vendor proven code (source in
  repo, as-is) rather than writing crypto/codec primitives from scratch.
- No background processes unless the user explicitly allows them.
- Ask before doing anything the user must physically do (moving a webcam,
  plugging hardware). Camera checks are ~2-minute asks; batch them, never
  improvise one mid-task.

## 2. Android specifics (hard-won)

- **Build env is per-shell**: export `JAVA_HOME` and adb `PATH` in every
  command that needs them; a `gradlew` that worked last session is not
  guaranteed this one. Prefer `--offline` for speed + reproducibility.
- **Stale APKs are a top-3 bug source.** After any install, md5-compare
  the pulled device APK against the build output before debugging "code
  that doesn't match the screen". Clean device debug artifacts after
  repeated reinstalls.
- **Drive the UI, don't trust the log**: `adb exec-out uiautomator dump`
  to read text and find tap coordinates; `adb exec-out screencap -p`
  for a single-shot visual check. Scripted taps (`adb shell input tap`)
  are fine for long flows.
- **Async callbacks outlive screens**: a camera permission dialog or an
  in-flight decode can complete after the user left that screen. Every
  async callback must check "am I still the current view?" before acting —
  a synchronous reference implementation can't have this race, a port to
  callback-based Android can.
- **Permissions**: request at use-site, handle deny as BACK, guard the
  callback against view staleness (see above).
- **QR display**: ≥ 3-module quiet zone (upstream `qrcode`/`qrencode`
  conventions), error-correction level chosen deliberately (check what the
  reference actually pins — library defaults drift), test the *displayed*
  QR by decoding it, not by re-encoding the bytes.
- **Camera QR capture**: the webcam's field of view crops large QRs —
  keep rendered QRs small enough for the canvas (measure once, record the
  limit). Byte-mode QR payloads: ZXing's `result.text` applies charset
  guessing and **corrupts binary data**; read the raw segments.
- **Test invariants**: if a registry test instantiates every view/class,
  add every new class to it — a class missing from the registry is a test
  failure, not a warning.

## 3. Porting / parity work (when a reference implementation exists)

- Map files 1:1 (one reference view = one port view) so both sides can be
  audited side by side.
- **Differential crosschecks over eyeballing**: a generator (reference
  side) writes deterministic cases (fixed RNG seed) to a TSV; a test
  runner in the port's own test suite consumes the TSV; a CLI `--check`
  compares. Byte-identical output is the acceptance criterion.
- When a crosscheck fails, **prove which side is wrong before changing
  code**: verify against an independent implementation or a known-good
  fixture (e.g. verify a BIP143 signature with third-party tooling before
  debugging the signer). A failed test is often a broken *fixture* —
  regenerate fixtures, don't patch the code around them.
- Keep reference-side bugs in mind: if the reference has a typo or
  quirk, match it or record the deliberate deviation with the reason.
- Record every divergence (fixed or intentional) in a findings table in
  `REASONING.md`: number, where, finding, fix.

## 4. Verification gate (never claim done before all of these)

1. Compiles clean; relevant tests pass; linter (clippy/ktlint) clean when
   applicable.
2. The full test suite re-run **after the last change** — not before.
3. Every doc claim that changed with the code is re-checked (numbers,
   statuses, "pending" markers).
4. Every script touched (or added) answers `--help` in a bare shell.
5. For app code: built, installed, md5-verified on device, exercised
   through the real UI path — not just unit tests.
6. Docs updated *during* the work (rule 0), so the commit that lands is
   the one whose docs already describe its state.

## 5. Verification numbers: keep them current

Maintain one block (in `docs/current-task.md`) listing every test suite
and its count, re-run green after every change: unit tests per module,
per-variant app tests, each crosscheck suite. Stale numbers are how a
restart wastes an hour re-establishing ground truth.

## 6. Agent behavior

- Work as a senior engineer of the platform (Rust/Kotlin/Java per
  project). Small coherent changes; no unrelated rewrites; fix the
  underlying cause, never suppress the error.
- Before non-trivial changes: inspect existing implementation, search the
  repo for existing patterns before inventing new ones.
- When uncertain about an architectural decision, inspect the repo and
  existing APIs before guessing; ask the user when the request is
  incoherent or two facts contradict.
- Safety first: destructive actions (force push, migrations, `rm -rf`)
  get confirmation before execution.
- Keep the working tree clean; commit with a message that names the
  findings or fixes, not "misc updates".
