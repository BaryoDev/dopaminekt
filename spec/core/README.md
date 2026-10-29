# Conformance fixtures

Language-neutral cases that pin the progression core: saved-state migration,
merging saves, streaks, score and XP, level thresholds and achievements. They
describe what the JS core does today. Any port runs the same files and must
match.

`spec/VERSION` holds the contract number (`contract: 3`). It is the same
number as `v` in a saved document. `spec/schema.json` describes that document.

The JS runner is `packages/dopaminejs/tests/conformance.test.js`. The `dopaminejs`
package ships this folder as `spec/`, pinned to the package version, with a
`dopaminejs/spec/*` export. The build copies it from here, so the repo root is
the one place to edit.

A rule change means changing a fixture in the same commit. A port that has not
caught up goes red.

## Files

`spec/fixtures/*.json`, each `{ "suite", "description", "cases": [...] }`.

## A case

| Key | Meaning |
|---|---|
| `name` | Unique within the file. |
| `note` | Optional. Why the case exists, or a quirk it pins. |
| `clock` | Required. Start time, local `YYYY-MM-DDTHH:MM:SS`. No offset. |
| `config` | Optional. See below. |
| `device` | Optional. A device id stored before `init`, so the document has known keys. Without it a random id is made. |
| `storage` | Optional. `{ "failWrites": true }` makes every storage write throw, to exercise `save_failed`. Reads still work and `savedJson`/`savedRaw` are seeded first. |
| `savedJson` | Optional. Any JSON value, stored as `JSON.stringify(value)`. |
| `savedRaw` | Optional. A string stored as is, for corrupt data. |
| `afterInit` | Optional. Expectation checked after the first `init`. |
| `steps` | Optional. Ordered list of steps. |

With neither `savedJson` nor `savedRaw`, storage is empty. A case needs
`afterInit`, or at least one step, or it checks nothing and fails.

Storage is an in-memory string store. The player document lives under the key
`dopamine_player` (prefix `dopamine_` plus `player`). The device id lives under
`dopamine_device`, outside the document.

A document with no `v` is a 2.x save. It is migrated on load and on `import`
(see `migrate.json`). Any other `v` than 3, including a string, `null` or `NaN`,
is refused with `unsupported_version`.

### Clock

The core takes a clock: `{ now(), localDate(ms?) }`. `now` is the time in
milliseconds and `localDate` is the local calendar day as `YYYY-MM-DD`, never
UTC. A runner passes a clock it controls. It does not fake the system time.

Streak dates are local calendar dates, so the case clock is a local date and
time, not an instant. The core runs in a non-UTC zone in CI (`Asia/Manila`). A
port sets its clock to the local wall time given, and must not convert through
UTC.

### Config

- `builtinAchievements`: `false` removes the five built-ins (`first_game`,
  `level_5`, `level_10`, `streak_3`, `streak_7`). Default `true`. The JS core
  has no such option; the runner emulates it by replacing the achievement table
  after construction. A port must do the same.
- `streakDays`: `"any"` (default) or `"record"`. With `any`, `record` and `addXP` mark today as a play day. With `record`, only `record` does. XP from an achievement reward never marks a day.
- `achievements`: extra achievements by id, `{ name, description?, icon?, xp?, when }`.

`when` replaces the check function. One of:

| Condition | True when |
|---|---|
| `{ "always": true }` | Always. |
| `{ "never": true }` | Never. |
| `{ "throws": true }` | The check raises an error. Must not stop other achievements. |
| `{ "player": "totalGamesPlayed", "gte": 2 }` | The dotted field of the snapshot is `>=` the number. |
| `{ "activity": "arcade" }` | The recorded activity name equals the string. |
| `{ "score_gte": 100 }` | The result has a score and it is `>=` the number. |

Achievements are checked in the order: built-ins in the order listed above,
then `config.achievements` in file order. Two limits come from JS objects:
integer-like ids (`"1"`, `"2"`) sort first regardless of file order, and a
config entry that reuses a built-in id replaces its definition but keeps the
built-in's position. Fixtures avoid both; do not rely on either.

`gte` and `score_gte` must be numbers, and a `player` path must name a numeric
field on a new player's snapshot, or the runner rejects the case.

## Steps

Every step is `{ "op": ..., ...args, "expect": {...} }`. `expect` is required
except for `setClock`, where it is accepted and checked if present.

| op | args | Does |
|---|---|---|
| `setClock` | `at` | Moves the clock. |
| `reload` | `device?` | Builds a new reward system on the same storage and runs `init`, like a page load. `device` stores a new device id first, as a second device sharing the save. |
| `addXP` | `amount`, `reason?` | `addXP(amount, reason)`. |
| `record` | `activity`, `result?` | `record(activity, result)`. A missing `result` means the default. `null` is passed as `null`. Returns `true`. |
| `unlockAchievement` | `id` | `unlockAchievement(id)`. |
| `markAchievementsSeen` | `ids` | `markAchievementsSeen(ids)`. |
| `getXPForNextLevel` | none | Reads `{ total, needed, progress }`. |
| `export` | `as` | `export()`. Keeps the document under the name `as`. |
| `import` | `doc` or `from`, `silent?` | `import(doc, { silent })`. `from` names a kept document. Returns the snapshot. |
| `merge` | `a`, `b`, `as?` | `merge(a, b)`. Each side is a document or the name of a kept one. Returns the document. |
| `validate` | `doc`, `as?` | `validate(doc)`. Returns the cleaned document. |

`merge` and `validate` are pure and need no reward system. The cases run them
as steps only because the runner has one.

Events are collected from the start of each step (and from construction for
`afterInit` and `reload`) and compared after it.

## Expectations

`expect` and `afterInit` take these keys. Unknown keys are an error.

| Key | Checks |
|---|---|
| `player` | Subset match against `snapshot()`. Every listed key must exist as an own key and match. Objects match by listed keys, arrays by length and element, numbers and strings exactly. |
| `playerExact` | Map of dotted path to value. The value at that path in the snapshot must equal it strictly, no extra keys. |
| `doc` | Subset match against `export()`, the saved document. |
| `docExact` | Like `playerExact`, against `export()`. |
| `absent` | Dotted paths under the snapshot that must not exist as own keys. |
| `achievements` | Ids in `player.achievements`, as a set. |
| `events` | The complete ordered list of events since the step began. Each is `{ "event": name, ...payload fields }`, matched as a subset. The list length must match. |
| `returns` | Subset match against the operation's return value. |
| `returnsExact` | Like `playerExact`, against the return value. |
| `same` | List of pairs of kept document names. Each pair must be equal. |
| `throws` | An error with this name (`TypeError`, `DopamineError`) is raised. State checks still run afterwards. |
| `code` | With `throws`: the `code` of the `DopamineError`. |

An empty `player`, `playerExact`, `doc`, `docExact`, `returns`, `returnsExact` or `absent`, or an empty `expect`,
is an error because it checks nothing. An empty `events` or `achievements`
list is a real check (nothing fired, nothing unlocked).

Each event is matched as `{ ...payload, "event": name }`. The `event` key is
the event name, so it wins over a payload field called `event`; the
`achievement_unlocked` payload has its own `name` (the achievement's name),
which is why the event name is not under `name`.

CI also runs the conformance file once in `America/Los_Angeles`
(`DOPAMINE_TEST_TZ`), so the daylight saving case can fail.

Event names: `xp_gained`, `level_up`, `achievement_unlocked`, `personal_best`
(payload `{ activity, score }`), `streak_updated`, `save_failed` and
`load_failed`. `load_failed` is covered for a corrupt value and for a document
the core refuses (`reason` is `corrupt`, `unsupported_version` or
`too_many_devices`). A read that fails in storage cannot be made by a fixture.

Payloads are frozen copies, and `snapshot()` is a frozen deep copy.

Timestamps (`createdAt`, `lastPlayedAt`, `unlockedAt`) are not checked.
Fixture paths use `.` as the separator, so keys in fixtures contain no dots.

## Running them from a port

1. Load every `spec/fixtures/*.json`.
2. For each case, build a clock at the local time given, seed storage,
   construct the system with the config and the clock, subscribe to the seven
   events, run `init`.
3. Check `afterInit`, then run each step and check its `expect`.
4. Fail on any unknown op, step key, expectation key or condition. A fixture
   must never pass by checking nothing.

Some JSON files contain keys such as `__proto__`. Parse them into a plain map,
not into a structure that treats those names specially.

## The saved document

`export()` returns a version 3 document (`schema.json`). Every progress number
is kept per device, and a device only changes its own entries, so two copies of
a save join by taking the larger number per key. That is what `merge` does.

| Field | Merge rule |
|---|---|
| `xpBy`, `xpSpentBy`, `gamesBy`, `playsBy`, `sumsBy` | Max per device. |
| `best` | Max. |
| `level` | Max. The shown level is never lower than the curve gives for the XP. |
| `longest` | Max. |
| `playDates` | Union. When `today` is known (load, record, import), days older than 400 days before today (inclusive) and days more than 1 day after today are dropped. `merge` called without `today` drops nothing. A future date does not move the window. |
| `achievements` | Union. The earliest `unlockedAt` decides the entry and its stored `reward`. `seen` is true if either side has it true. |
| `name` | From the side with the later `lastPlayedAt`, ties take the larger string. |
| `createdAt`, `lastPlayedAt` | Min of the set values, max. |

XP is derived: the sum of `xpBy`, plus the stored `reward` of every unlocked
achievement, minus the sum of `xpSpentBy`, and not below 0. An achievement's
reward is never part of `xpBy`, so it counts once however many devices unlock
it. The current streak is derived from `playDates` as of today, so `merge` reads
no clock.

More than 20 devices across the per-device maps is refused with a
`DopamineError` (`too_many_devices`). The `legacy` key counts as a device, so
`legacy` plus 19 real devices is full. A device that would be the 21st does not
write: `addXP` and `record` leave the stored save alone and publish `load_failed`
with reason `too_many_devices`. A document with a `v` other than 3 is refused
with `unsupported_version`. A value that is not an object is `invalid_document`. Counters and sums stop at
`Number.MAX_SAFE_INTEGER`.

### From 2.x

A save with no `v` moves under the fixed device key `legacy`, never a real
device id. The old `xp` already holds the rewards of the achievements it
unlocked, so the legacy entry is the old `xp` minus those rewards, not below 0.
Two devices that read the same 2.x save get the same entries and merge without
doubling. 2.x progress made separately on two devices before migration merges
by max, not by sum. `playDates` is rebuilt from `streak.current` and
`streak.lastPlayDate`. A level above the curve is kept.

## Quirks pinned on purpose

Cases with a `note` pin behaviour that may look odd. Changing any of these is a
rule change and needs a fixture change.

- A new player has no play dates: `streak.current` is 0 and there is no
  `lastPlayDate`. The streak starts with the first `record`, which fires no
  `streak_updated` for a player with no earlier play. Loading a save counts no
  day.
- Only `record` marks a day as played. `addXP` does not.
- Numbers must be finite numbers. A numeric string is dropped to the default.
- Dates must be real calendar days. `9999-99-99` and `2026-02-30` are dropped.
- A play date after today is kept and ignored for the current streak.
- Fields the core does not know are not carried.
- `__proto__`, `constructor` and `prototype` are never kept as a key.
- Achievement ids the core does not define are kept.
- Extra numbers in a `record` result add up per device. A value that is not a
  positive finite number adds 0.
