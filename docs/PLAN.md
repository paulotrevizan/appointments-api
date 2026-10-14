# Implementation Plan

> **Status: complete.** Steps 0–8 are implemented and merged. This file keeps each step as it was
> planned; changes made during or after a step are recorded in [`DECISIONS.md`](../DECISIONS.md),
> and the current design and configuration are in [`HLD.md`](HLD.md) and the
> [README](../README.md).

Each step is one branch and one Pull Request, squash-merged into `main` after review. Steps
are done **in order**, one at a time, following
[`CLAUDE.md`](../CLAUDE.md). Acceptance criteria are behaviours that must be proven by tests
(unless marked *manual*). References point to [`HLD.md`](HLD.md) and
[`DECISIONS.md`](../DECISIONS.md).

**Prompt to start a step:**

> Read `CLAUDE.md`, `docs/HLD.md`, `DECISIONS.md` and `docs/PLAN.md`. Implement **Step N**
> only, on its own branch from an up-to-date `main`. Restate its scope and acceptance criteria
> first and ask if anything is unclear. When `./mvnw spotless:apply verify` is green, commit,
> push and open the Pull Request as described in `CLAUDE.md`, then stop and send me the link.
> Do not merge.

## Default configuration values

Defaults agreed before implementation. The slot duration is fixed by the schema; the rest are
settings in `application.yaml`. The current list, with every setting and environment variable, is
in the README, section *Configuration*.

| Setting | Default |
|---------|---------|
| Slot duration | 30 min (grid `:00` / `:30`, UTC) — fixed by the schema (D-002, D-007) |
| Allocation attempts on conflict | 3 (`appointments.booking.max-retries: 2`) |
| Outbox poll interval / batch size / lease | 2 s / 20 / 30 s |
| Outbox backoff | 5 s × 2 per attempt, capped at 10 min, ± 20 % jitter |
| Outbox max attempts | 10 |
| HTTP adapter timeouts | connect 2 s / read 5 s |
| Admin page `limit` | default 20, max 100 |
| Local seeder (profile `local`) | 3–5 doctors per specialty, 10 rooms, 200 patients + 3 fixed demo patients |

---

## Step 0 — Scaffold and tooling

**Scope**
- Generate the project from start.spring.io: latest stable Spring Boot 4.1.x, Java 25, Maven,
  dependencies: Web, Data JPA, Validation, Security, Mail, Actuator, Flyway (+ PostgreSQL
  module), PostgreSQL driver, Lombok, Testcontainers.
- Spotless with google-java-format bound to `verify`.
- `.gitattributes` (LF line endings, including `mvnw`), `.gitignore`.
- `application.yaml` baseline: virtual threads on, `open-in-view: false`, `ddl-auto: validate`,
  graceful shutdown. Profiles `local` and `test`.
- Multi-stage `Dockerfile` (JDK build → JRE runtime, non-root user).
- `docker-compose.yml` with PostgreSQL 18 only (the full stack comes in Step 7).
- GitHub Actions workflow running `./mvnw verify` on pushes and Pull Requests.
- `.github/pull_request_template.md` with the sections listed in `CLAUDE.md` (Summary,
  Decisions and invariants, Acceptance criteria, Review notes).
- README skeleton: prerequisites (Java 25, Docker Desktop), how to run tests on Windows
  (`mvnw.cmd`) and macOS/Linux (`./mvnw`).

**Acceptance criteria**
- `./mvnw verify` is green on a clean clone; the application context starts against a
  Testcontainers PostgreSQL 18.
- An unformatted Java file makes `./mvnw verify` fail.
- *Manual:* `docker compose up -d postgres` + `./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
  → `GET /actuator/health` returns `UP`.

**Commit:** `build: scaffold Spring Boot 4 project with tooling and CI`

---

## Step 1 — Schema, database invariants and local data seeder

**Scope**
- `db/migration/V1__schema.sql`: `btree_gist` extension; tables `patient`, `specialty`,
  `doctor`, `room`, `appointment`, `outbox_task` exactly as HLD §8.
  - `appointment.slot` generated as `tstzrange(start_at, end_at, '[)')`.
  - Named exclusion constraints `appointment_doctor_no_overlap`, `appointment_room_no_overlap`,
    `appointment_patient_no_overlap`, all `WHERE (status = 'CONFIRMED')`.
  - Check constraints on status/type values and `end_at > start_at`.
  - Indexes: `appointment (start_at, id)`, `outbox_task (status, next_attempt_at)`,
    unique `outbox_task (appointment_id, type)`.
- JPA entities mapped to the schema (`slot` not mapped).
- `LocalDataSeeder` (D-024): `@Profile("local")`, runs at startup, only when the reference
  tables are empty. Fixed specialties (`CARDIOLOGY`, `DERMATOLOGY`, `GENERAL_PRACTICE`,
  `PEDIATRICS`, `ORTHOPEDICS`, `NEUROLOGY`); randomized doctors, rooms and patients with
  Datafaker; three demo patients with fixed ids. Counts from configuration. Logs counts only
  (no PII).

**Acceptance criteria** (integration tests against PostgreSQL)
- **I1:** two `CONFIRMED` overlapping appointments for the same doctor are rejected with
  `appointment_doctor_no_overlap`.
- **I2:** same for the same room (`appointment_room_no_overlap`).
- **I5:** same for the same patient (`appointment_patient_no_overlap`).
- Back-to-back slots (14:00–14:30 and 14:30–15:00) are accepted.
- `CANCELLED` appointments do not block a slot.
- Hibernate schema validation passes.
- Seeder, profile `local`, empty database → configured numbers of doctors per specialty, rooms
  and patients exist, plus the three demo patients.
- Seeder runs a second time → nothing is duplicated.
- Seeder is not active outside the `local` profile (test profile starts with empty reference tables).

**Commit:** `feat: database schema with overlap constraints and local data seeder`

---

## Step 2 — Book an appointment (happy path, validation, security, error model)

**Scope**
- `POST /api/v1/appointments` per HLD §7.1 (request/response records, manual mapping).
- `ProblemDetail` error handling with stable `code` values (HLD §7.1 table).
- Slot validation with injected `Clock`: future, aligned to the grid.
- Patient and specialty lookup; `AllocationPolicy` (D-009): least-booked doctor that day
  (tie → id), first free room.
- One transaction: insert the appointment + three `PENDING` outbox tasks (D-004). No worker yet.
- Security (D-012): HTTP Basic, roles `BOOKING_CLIENT` and `ADMIN`, credentials from
  configuration (env vars; local defaults in `application-local.yaml` / test config).

**Acceptance criteria**
- 201 with a doctor **of the requested specialty** (I3), a room, full patient details and
  `endAt = startAt + 30 min`.
- The new appointment has exactly three `PENDING` outbox tasks: calendar, room, email (I4).
- With two free doctors, the one with fewer appointments that day is chosen.
- 400 `SLOT_IN_PAST`, `SLOT_NOT_ALIGNED`, `VALIDATION_ERROR` (missing/invalid fields).
- 404 `PATIENT_NOT_FOUND`, `SPECIALTY_NOT_FOUND`.
- 409 `NO_DOCTOR_AVAILABLE` when all doctors of the specialty are booked;
  409 `NO_ROOM_AVAILABLE` when all rooms are booked.
- 401 without credentials; 403 with `ADMIN` credentials.
- Error bodies follow RFC 9457 with `code`.

**Commit:** `feat: book appointments with automatic doctor and room allocation`

---

## Step 3 — Concurrency and duplicate protection

**Scope**
- `V2` migration (D-002): partial unique indexes `appointment_doctor_no_overlap`,
  `appointment_room_no_overlap`, `appointment_patient_no_overlap` on (resource, `start_at`)
  `WHERE status = 'CONFIRMED'` replace the exclusion constraints; `CHECK` constraints fix the
  duration (30 min) and the grid.
- Optimistic allocation retry (D-003): one `TransactionTemplate` transaction per attempt,
  `saveAndFlush`, retried from outside with Spring Framework's `@Retryable` on slot-index
  violations only; index name → outcome mapping when attempts run out.
- Duplicate handling (D-011): each attempt first looks for the patient's appointment at that
  slot; a repeated request gets the existing appointment with **201**.

**Acceptance criteria**
- Same patient + slot + specialty again → **201** with the same appointment body; still one
  appointment and three outbox tasks.
- Same patient + slot, different specialty → 409 `PATIENT_ALREADY_BOOKED`.
- **Concurrency (I1, I2):** given K doctors of a specialty and R rooms (min(K, R) ≤ 3), 20
  parallel requests from different patients for the same slot → exactly `min(K, R)` × 201, the
  rest 409, and no double bookings in the database.
- **Concurrency (I5):** 10 parallel identical requests from one patient → exactly one
  appointment; every response is 201 with the same body.
- The database rejects a start off the grid and a duration other than 30 minutes.

**Commit:** `feat: concurrency-safe allocation and duplicate booking protection`

---

## Step 4 — Outbox worker, calendar and room adapters

**Scope**
- `OutboxWorker` (`@Scheduled`, fixed delay): claim due tasks with `FOR UPDATE SKIP LOCKED`
  and a lease, using the database clock (HLD §6.2); dispatch by task type.
- `RetryPolicy`: exponential backoff with cap and jitter; max attempts; failure classification
  (D-017).
- Ports `DoctorCalendarGateway`, `RoomReservationGateway` with `RestClient` adapters (timeouts,
  `Idempotency-Key: {appointmentId}`, contracts in HLD §7.3). *Implemented, after review, as
  Spring Boot HTTP service clients (`@HttpExchange` interfaces), which use `RestClient`.*

**Acceptance criteria** (WireMock for externals, Awaitility for async)
- After a booking, the calendar and room systems each receive exactly one request with the
  correct body and `Idempotency-Key` = appointment id; both tasks become `DONE`.
- Booking still returns 201 while both external systems are down (D-001).
- External 503 / timeout → task stays `PENDING`, `attempts` incremented, `next_attempt_at`
  in the future; once the system recovers → `DONE`.
- External 4xx → task `FAILED` immediately with `last_error` (D-017).
- Max attempts reached → `FAILED`.
- Two concurrent claims never return the same task; a task with an expired lease is claimed again.
- *Unit:* backoff grows exponentially, respects the cap and jitter bounds; classification table.

**Commit:** `feat: transactional outbox worker with calendar and room integrations`

---

## Step 5 — Confirmation email

**Scope**
- `EmailSender` port + SMTP adapter (`JavaMailSender`). Plain-text body: date/time (UTC),
  doctor name, room. **No specialty** (health data, HLD §8).
- Email task handler loads appointment, patient, doctor and room at execution time.
- SMTP transient vs permanent failure classification.

**Acceptance criteria** (GreenMail)
- After a booking, exactly one email reaches the patient's address with doctor name, room and
  time; it does not contain the specialty. Task becomes `DONE`.
- SMTP unavailable → task retried, then `DONE` when it recovers.
- Permanent SMTP rejection → `FAILED`.

**Commit:** `feat: send booking confirmation email via outbox`

---

## Step 6 — Admin listing with cursor pagination

**Scope**
- `GET /api/v1/admin/appointments` per HLD §6.3 / §7.2 (D-015, D-016).
- `CursorCodec` (opaque base64url of `{startAt, id}`).
- Keyset query ordered by `(startAt, id)`, fetching `limit + 1`, with fetch joins; outbox
  statuses loaded in one extra query.

**Acceptance criteria**
- Walking all pages returns every appointment exactly once, in `(startAt, id)` order —
  including many appointments sharing the same `startAt`.
- An appointment inserted *before* the current cursor while paging causes no duplicates or
  skips in the following pages.
- Last page has `nextCursor: null`; no appointments → `items: []`, `nextCursor: null`.
- `postBooking` shows the status of each task.
- 400 `INVALID_CURSOR`; 400 `VALIDATION_ERROR` for `limit` outside 1–100.
- 401 without credentials; 403 with `BOOKING_CLIENT` credentials.
- The number of SQL statements per page is constant regardless of page size (no N+1).

**Commit:** `feat: admin appointment listing with keyset pagination`

---

## Step 7 — Operability and full local stack

**Scope**
- Actuator: liveness/readiness (readiness includes the database).
- Metrics (HLD §13): booking outcomes and latency, age of oldest `PENDING` task, `FAILED`
  task count, allocation conflict retries.
- Structured JSON logs (Spring Boot built-in) in the container; request id via `X-Request-Id`
  in MDC; no PII in logs.
- `docker-compose.yml`: app (profile `local`, so the seeder runs) + PostgreSQL (no volume:
  `down` + `up` gives a fresh random dataset) + WireMock (calendar and room mappings) + Mailpit.

**Acceptance criteria**
- Metrics are exposed under `/actuator/metrics` with the expected names.
- Responses echo `X-Request-Id` (generated when absent) and logs carry it.
- *Manual:* `docker compose up --build` → a booking via `curl` returns 201, the email appears in
  the Mailpit UI and the WireMock request journal shows the calendar and room calls.

**Commit:** `feat: observability and full docker compose stack`

---

## Step 8 — Documentation and conformance review

**Scope**
- README: purpose, architecture summary (links to HLD/DECISIONS), prerequisites (Java 25,
  Docker Desktop), run tests, run with Docker Compose and from the IDE (IntelliJ run/debug
  configuration with profile `local`), demo patient ids and credentials,
  configuration reference, runbook (inspect and re-drive `FAILED` tasks), project structure,
  how to extend, how AI was used.
- README request examples for every endpoint and error case.
- Conformance table in the README: each invariant (I1–I5) and key decision → the tests that
  prove it. Fix any gap found.

**Acceptance criteria**
- *Manual:* from a fresh clone, following only the README, a reviewer runs the tests and makes
  a successful booking.
- Every invariant has at least one named test listed in the conformance table.

**Commit:** `docs: README, API examples and conformance table`
