# Decisions Log

Every significant decision, the options considered and the trade-offs accepted.
Decided by Paulo Trevizan; AI was used to explore options and challenge trade-offs.
Context: [docs/HLD.md](docs/HLD.md) · Diagrams: [docs/excalidraw-diagrams.md](docs/excalidraw-diagrams.md).

| ID | Decision | Status | Date |
|----|----------|--------|------|
| D-001 | Our service is the source of truth; return 201 and propagate via outbox | Accepted | 2026-10-12 |
| D-002 | Prevent overbooking with PostgreSQL unique indexes on fixed slots | Accepted | 2026-10-12 |
| D-003 | Optimistic allocation with bounded retry on conflict | Accepted | 2026-10-12 |
| D-004 | Transactional outbox, one task per post-booking action | Accepted | 2026-10-12 |
| D-005 | Retry lives in the outbox; adapters use timeouts; no circuit breaker | Accepted | 2026-10-12 |
| D-006 | External systems: ports + production-style adapters, simulated locally | Accepted | 2026-10-12 |
| D-007 | Fixed 30-minute slots aligned to the grid | Accepted | 2026-10-12 |
| D-008 | Availability = no overlapping appointment; no opening hours or schedules | Accepted | 2026-10-12 |
| D-009 | Doctor selection: least booked that day; room: first free | Accepted | 2026-10-12 |
| D-010 | Patient referenced by `patientId` (local read copy) | Accepted | 2026-10-12 |
| D-011 | Duplicate protection via patient overlap constraint (I5) | Accepted | 2026-10-12 |
| D-012 | Service-to-service authentication with per-endpoint roles | Accepted | 2026-10-12 |
| D-013 | PostgreSQL as the data layer | Accepted | 2026-10-12 |
| D-014 | Single deployable (modular monolith), no message broker | Accepted | 2026-10-12 |
| D-015 | API surface limited to the stated FRs | Accepted | 2026-10-12 |
| D-016 | Keyset (cursor) pagination for the admin listing | Accepted | 2026-10-12 |
| D-017 | Classify post-booking failures: retryable vs non-retryable | Accepted | 2026-10-12 |
| D-018 | Java 25 with virtual threads enabled | Accepted | 2026-10-12 |
| D-019 | Maven, Spring Boot 4.1, PostgreSQL 18 | Accepted | 2026-10-12 |
| D-020 | Spring Data JPA, native SQL only where PostgreSQL features are needed | Accepted | 2026-10-12 |
| D-021 | Layered architecture, packaged by feature; interfaces only at external boundaries | Accepted | 2026-10-12 |
| D-022 | Behaviour-based tests against real infrastructure | Accepted | 2026-10-12 |
| D-023 | Tooling: Spotless, GitHub Actions, WireMock, Mailpit | Accepted | 2026-10-12 |
| D-024 | Randomized local sample data generated at startup, never in production or tests | Accepted | 2026-10-12 |
| D-025 | UUIDs assigned by the application, with a `BaseEntity` (`Persistable`) | Accepted | 2026-10-13 |
| D-026 | OpenAPI document and Swagger UI with springdoc; open in the local profile, authenticated elsewhere | Accepted | 2026-10-13 |
| D-027 | The doctor calendar receives the patient id, never patient details | Accepted | 2026-10-13 |

---

## D-001 — Our service is the source of truth; return 201 and propagate via outbox

- **Context:** After booking, the doctor's calendar and room system "must be updated". If they are down, what happens to the patient's request?
- **Options:**
  - A) Our DB is the source of truth; confirm immediately (201) and update externals asynchronously via outbox.
  - B) Saga: appointment `PENDING` (202) until externals confirm; compensate on failure.
  - C) Call externals synchronously inside the request.
- **Decision:** A.
- **Trade-offs:** ➕ simple, strong consistency for I1/I2 in one place, booking available even if all externals are down, fast responses. ➖ if an external system also accepts bookings from other sources, it may reject ours later → needs reconciliation.
- **Why not B:** compensation logic and a non-final answer to the patient for a problem statement that only asks to *update* externals. **Why not C:** couples our availability to three external systems and needs undo of partial calls.
- **Revisit when:** external calendar/room systems become shared sources of truth.

## D-002 — Prevent overbooking with PostgreSQL unique indexes on fixed slots

- **Context:** Invariants I1, I2 and I5 must hold under concurrency and with multiple instances.
- **Options:**
  - A) `EXCLUDE USING gist` on (resource, time range).
  - B) Partial unique indexes on (resource, `start_at`) `WHERE status = 'CONFIRMED'`, plus `CHECK` constraints that fix the duration (30 min) and the grid (:00 / :30).
  - C) Application locks (`SELECT … FOR UPDATE`, advisory or Redis locks).
- **Decision:** B: `appointment_doctor_no_overlap`, `appointment_room_no_overlap`, `appointment_patient_no_overlap`, `appointment_fixed_duration_check`, `appointment_grid_check`. With fixed, grid-aligned slots (D-007) two appointments overlap exactly when they start at the same instant, so the unique indexes are as strong as the exclusion constraints.
- **Trade-offs:** ➕ the guarantee lives in the database, independent of app instances; concurrent conflicting inserts fail fast with a unique violation, so optimistic retries (D-003) work; plain B-tree indexes, no extension. ➖ only valid for one fixed duration: variable durations need A again; tests need a real PostgreSQL.
- **Why not A:** an exclusion constraint inserts its index entry and checks for conflicts afterwards, so two concurrent conflicting inserts see each other's uncommitted entry and wait for each other until the deadlock detector aborts one (after `deadlock_timeout`, 1 s). Measured with 20 parallel requests for one slot: intermittent `deadlock detected` and attempts running out. A unique B-tree index checks before inserting: the second insert waits for the first transaction to finish and then fails with a clean violation. **Why not C:** contention, harder to reason about and to defend, not needed at this load.
- **Revisit when:** appointments get variable durations.

## D-003 — Optimistic allocation with bounded retry on conflict

- **Context:** Two requests can pick the same free doctor/room at the same time.
- **Options:** A) Pick candidates, insert, on a slot-constraint violation retry with fresh candidates (max 3 attempts). B) Lock candidate rows first (`FOR UPDATE SKIP LOCKED`).
- **Decision:** A. Each attempt is one transaction (`TransactionTemplate`); the retry wraps it from outside with Spring Framework's built-in `@Retryable` (no extra library), retrying only violations of the three slot indexes (D-002), with a few milliseconds of delay and jitter. Attempts are configured with `appointments.booking.max-retries` (2 retries = 3 attempts). When they run out, the last violation becomes `NO_DOCTOR_AVAILABLE` / `NO_ROOM_AVAILABLE`.
- **Trade-offs:** ➕ no locks held, cost only when a real race happens (rare at our load), retry policy declared in one place. ➖ under extreme contention (more than 3 free candidates and many simultaneous requests for the same slot) some requests may get 409 after 3 attempts even if a candidate is still free.

## D-004 — Transactional outbox, one task per post-booking action

- **Context:** Invariant I4 — a confirmed booking must never lose its post-booking actions.
- **Options:** A) Outbox table written in the same transaction, polled by a worker. B) In-memory async events after commit. C) Message broker (Kafka) + CDC.
- **Decision:** A, with **three independent tasks** (calendar, room, email) per appointment.
- **Trade-offs:** ➕ atomic with the booking, survives restarts, no extra infrastructure, per-action status visible. ➖ polling latency (seconds), table needs cleanup over time.
- **Why not B:** events are lost if the instance crashes. **Why not C:** extra infrastructure not justified by the load; a natural evolution when other services need the events.
- **Detail:** claim with `FOR UPDATE SKIP LOCKED` + lease (`locked_until`) so multiple instances never process the same task and no transaction is held during external calls. The claim is one statement that also returns the appointment data the calendar and room calls need. Every time (`next_attempt_at`, `locked_until`, `updated_at`) comes from the database clock. A claimed batch runs in parallel, one virtual thread per task, so one slow system does not delay the others. Only task types with a handler are claimed. The worker can be switched off with `appointments.outbox.worker.enabled` (off in the `test` profile, so booking tests never call external systems).

## D-005 — Retry lives in the outbox; adapters use timeouts; no circuit breaker

- **Context:** Are retry and circuit breaker enough to deal with unavailable external systems?
- **Decision:** No — durability comes from the outbox (D-004). Retries are scheduled by the outbox with exponential backoff + jitter; after N attempts the task is `FAILED` and alerts. Adapters apply timeouts only (`spring.http.clients.connect-timeout` / `read-timeout`) and speak HTTP/1.1. On plain `http://` the JDK client would otherwise try an h2c upgrade, which many servers and proxies cancel.
  - The delay must survive restarts, so it is persisted as `next_attempt_at` instead of an in-memory `@Retryable`. The rules are a Spring `RetryPolicy` (`OutboxRetrySchedule`): retry only retryable `IntegrationException`s, at most `max-attempts`, with an exponential backoff of 5 s × 2 per attempt capped at 10 min. Spring's jitter grows with the interval, which gives the ±20 %. The policy's backoff returns `STOP` once the attempts are used up, and the task becomes `FAILED`.
- **Why no circuit breaker:** the booking path never calls externals, so there is no user-facing latency to protect; the outbox backoff already stops hammering a failing system. At ~1 req/s a breaker adds a dependency and configuration without measurable benefit.
- **Trade-offs:** ➕ a single place that controls retries (no retry storms from stacked layers), fewer moving parts, predictable behaviour. ➖ during a long outage each due task still makes one (timed-out) call per backoff step.
- **Revisit when:** post-booking volume grows enough that failing calls consume worker capacity.

## D-006 — External systems: ports + production-style adapters, simulated locally

- **Context:** Should we build the external systems or just interfaces?
- **Decision:** Domain ports (`DoctorCalendarGateway`, `RoomReservationGateway`, `EmailSender`) with real HTTP/SMTP adapters; the external systems are simulated (HTTP mock and fake SMTP) in local and test environments.
  - Naming: a *Gateway* is the port the application depends on (Fowler's Gateway pattern); a *Client* is the HTTP contract behind it. The HTTP side uses Spring's declarative HTTP Service Clients: `@PostExchange` interfaces (`DoctorCalendarClient`, `RoomReservationClient`) registered with `@ImportHttpServices` and configured per group (`spring.http.serviceclient.calendar.base-url`, `…room.base-url`). The gateway adapter only maps HTTP failures to D-017 outcomes.
- **Trade-offs:** ➕ integration points are real and swappable, behaviour is testable end-to-end. ➖ outbound contracts are assumed by us and must be aligned with the real systems.

## D-007 — Fixed 30-minute slots aligned to the grid

- **Context:** The input is a "desired timeslot".
- **Decision:** Client sends `startAt`; duration is a fixed 30 min; `startAt` must be on `:00`/`:30` (UTC). The duration and the grid are enforced by the schema (D-002), so they are not configuration.
- **Trade-offs:** ➕ simple validation, predictable availability, overlap reduces to "same start". ➖ no variable durations; changing the duration is a migration.

## D-008 — Availability = no overlapping appointment; no opening hours or schedules

- **Context:** "Available doctor" is not defined, and no opening hours or doctor schedules are given.
- **Options:** A) No overlapping appointment only. B) Plus global clinic opening hours (configured). C) Plus per-doctor working hours.
- **Decision:** A — any future slot aligned to the 30-min grid is bookable, with no booking horizon and no calendar rules (weekends and holidays included).
- **Why not B or C:** both require business data we do not have, so we would be inventing policy (e.g. "Mon–Fri 08:00–18:00") that readers would mistake for a real rule. Schedules, leave and holidays belong to the doctor calendar / clinic systems. Without opening hours there is no need for a clinic time zone: everything stays UTC, with no DST edge cases.
- **Trade-offs:** ➕ no invented rules, no time-zone handling. ➖ the API accepts a slot at 03:00 — the booking channel is expected to offer sensible slots.
- **Revisit when:** schedules become available (e.g. from the calendar system) — it is a validation rule, not a design change.

## D-009 — Doctor selection: least booked that day; room: first free

- **Decision:** Among free doctors of the specialty, choose the one with fewest appointments that day (tie → id). Room: first free. A single concrete class (no strategy interface until a second policy exists).
- **Trade-offs:** ➕ spreads workload, deterministic, simple to replace. ➖ one extra count per request (cheap with an index).

## D-010 — Patient referenced by `patientId` (local read copy)

- **Context:** Input is "patient information". The statement also says "*our healthcare system* allows patients to book", i.e. patients already exist in the platform.
- **Options:**
  - A) Inline patient data (name, email, phone) stored as a snapshot on each appointment.
  - B) `patientId` resolved by calling an external patient service on each booking.
  - C) `patientId` resolved against a **local read copy** of patients (same treatment as doctors/rooms); the response returns the full patient details.
- **Decision:** C.
- **Trade-offs:** ➕ consistent with the rest of the reference data; patient PII stored once (easier to protect and erase — GDPR); smaller request; unknown patient is rejected (404); the 201 already returns patient details, so no follow-up call; lookup is in the same DB (no network round trip). ➖ requires seeded patients to test; in production the copy must be kept in sync with the patient registry (same assumption as doctors/rooms).
- **Why not A:** duplicates PII on every appointment and has no notion of patient identity. **Why not B:** adds a synchronous external dependency to the booking path, against D-001.

## D-011 — Duplicate protection via patient overlap constraint (I5)

- **Context:** I1/I2 protect doctors and rooms, not patients. A retried request (double click, timeout, new tab) would simply pick *another* free doctor and room, booking the same patient N times at the same slot.
- **Options:**
  - A) `Idempotency-Key` header: same key → replay the original response.
  - B) Invariant I5: unique (`patient_id`, `start_at`) for confirmed appointments (D-002); the natural key *patient + slot* identifies a retry.
  - C) Both.
- **Decision:** B.
  - Same patient + slot + specialty → it is a retry: **201** with the existing appointment, the same body as the original response.
  - Same patient + slot, different specialty → **409** `PATIENT_ALREADY_BOOKED`.
  - Each booking attempt first looks for the patient's confirmed appointment at that slot. Two identical requests in parallel: one inserts, the other hits a slot index, is retried (D-003) and finds the first appointment.
- **Trade-offs:** ➕ protection independent of client behaviour (a new key per click, two tabs or two devices would defeat option A); no client contract; one line of SQL using the same mechanism as I1/I2; a sound business rule on its own (nobody attends two consultations at once). ➖ adds a rule the statement does not state explicitly; does not cover *different* slots (not a duplicate — see rate limiting, out of scope).
- **Why 201 on retry:** from the client's point of view an idempotent operation returns the same response however many times it is sent (the replay model of `Idempotency-Key` APIs). Clients need no special case, and nothing about the response depends on which copy of the request arrived first. Repeated requests are visible in the logs ("Repeated request for appointment …") and, in Step 7, in metrics, instead of in the status code.

## D-012 — Service-to-service authentication with per-endpoint roles

- **Context:** Bookings arrive from other platform components (patient app backend, front-desk system), and the admin listing exposes every patient's appointments — the most sensitive data this service holds. End-user authentication (patients, staff) is not part of this problem.
- **Options:**
  - A) No authentication; rely on network isolation.
  - B) Authenticate calling services; authorize per endpoint by role: `BOOKING_CLIENT` → create, `ADMIN` → list all.
  - C) B plus end-user authentication and per-patient authorization (a patient may only book for themselves, staff for anyone).
- **Decision:** B. Locally: HTTP Basic with two clients (`booking-client`, `admin`). In production: the same roles mapped from JWTs issued by the platform's identity provider (OAuth2 resource server) — the authorization rules do not change.
- **Trade-offs:** ➕ least privilege per endpoint; patient data in the listing is protected; standard service-to-service pattern; no simulated identity provider. ➖ no per-patient check here: a `BOOKING_CLIENT` can book for any `patientId`. That check belongs to the caller, which holds the patient's session (HLD A6).
- **Why not A:** network isolation alone is not enough for health data. **Why not C:** requires end-user identity this service does not have (patient tokens, user ↔ patient mapping); building it would mean simulating an identity provider.
- **Revisit when:** end users call the API directly — then C is required: `patientId` must match the patient in the token.

## D-013 — PostgreSQL as the data layer

- **Context:** "The choice of data layer is up to you."
- **Decision:** PostgreSQL.
- **Trade-offs:** ➕ partial unique indexes and `CHECK` constraints for the slot invariants (D-002), `SKIP LOCKED` for the outbox (D-004), transactions spanning booking + outbox, mature and widely known. ➖ vendor-specific features reduce DB portability (accepted).

## D-014 — Single deployable (modular monolith), no message broker

- **Context:** "Thousands of requests per hour" ≈ 1 req/s.
- **Decision:** One Spring Boot service + PostgreSQL, organised in modules (booking, allocation, catalog, post-booking, integration, admin).
- **Trade-offs:** ➕ simple to run, test and operate; scales horizontally as-is. ➖ no event stream for other services yet (see D-004 evolution).

## D-015 — API surface limited to the stated FRs

- **Context:** REST convention suggests extras such as `GET /appointments/{id}` (and a `Location` header) or filters on the admin listing. No FR asks for them.
- **Decision:** Expose only `POST /api/v1/appointments` (FR 1) and `GET /api/v1/admin/appointments` (FR 3). The admin listing has **pagination only** (D-016) — an unbounded "list all" is a production defect. The 201 response carries the complete appointment, so clients need no read-back. No `Location` header, since it would point to a non-existent resource.
- **Trade-offs:** ➕ smaller surface to secure, test and maintain; every endpoint traces to a requirement. ➖ clients cannot re-fetch a single appointment, and admins cannot filter — both straightforward extensions when a FR appears.

## D-016 — Keyset (cursor) pagination for the admin listing

- **Context:** "List all scheduled appointments" over a table that only grows (thousands of requests/hour, never deleted). Bookings are inserted **anywhere** in the `startAt` order (any future slot), not only at the end.
- **Options:**
  - A) Offset: `?page=N&size=20` → `OFFSET N*20 LIMIT 20` + `COUNT(*)`.
  - B) Keyset / cursor: `?cursor=…&limit=20` → `WHERE (start_at, id) > (:startAt, :id) ORDER BY start_at, id LIMIT 21`.
- **Decision:** B.
- **Trade-offs:**

  | | Offset | Keyset (cursor) |
  |---|---|---|
  | Cost per page | Grows with depth (DB reads and discards all skipped rows) + `COUNT(*)` on every call | Constant: index seek on (`start_at`, `id`) |
  | Consistency while data changes | A booking inserted before the current page shifts everything → **skipped or repeated rows** (likely here, since inserts land in the middle of the order) | Stable: never skips or repeats |
  | Jump to page N / total count | Yes | No |
  | Client complexity | Trivial | Pass back an opaque cursor |

- **Why:** correctness under concurrent inserts and constant cost on an ever-growing table matter more for an operational list than "go to page 437", which has no meaning without filters. Implementation is one query with a composite index.
- **Details:** `id` as tie-breaker (many appointments share a `start_at`); cursor is opaque base64url of `{startAt, id}`; fetch `limit + 1` rows to know if there is a next page without counting.
- **Revisit when:** a UI needs numbered pages / totals — add a separate count endpoint or filters that make offset ranges small.

## D-017 — Classify post-booking failures: retryable vs non-retryable

- **Context:** Retrying every failure treats "the calendar is down" and "the room system rejected the reservation" the same way. The second will never succeed and only delays the alert.
- **Decision:**
  - Retryable: timeouts, connection errors, HTTP 5xx and 429, transient SMTP errors → backoff, `FAILED` after N attempts.
  - Non-retryable: any other HTTP 4xx, permanent SMTP errors → `FAILED` immediately + alert.
- **Trade-offs:** ➕ fast, clear signal for business rejections; no wasted calls. ➖ a misclassified error (e.g. an external returning 400 during an outage) fails early — re-drive is a one-line runbook step.

## D-018 — Java 25 with virtual threads enabled

- **Context:** The service does blocking I/O (JDBC, HTTP, SMTP) and relies on virtual threads for it.
- **Decision:** Java 25 (LTS) with `spring.threads.virtual.enabled: true`.
- **Trade-offs:** ➕ records, pattern matching, sequenced collections; blocking I/O no longer ties up platform threads, so request handling and the outbox worker scale without thread-pool tuning; since JDK 24 (JEP 491) a virtual thread that blocks inside `synchronized` no longer pins its carrier thread, so libraries that use monitors (JDBC driver, Hibernate, Spring internals) need no special care. ➖ the DB connection pool, not threads, becomes the concurrency limit — which is the desired back-pressure.
- **Why not Java 21:** on 21, blocking inside `synchronized` pins the carrier thread. Under the concurrency tests of Step 3 this showed up in Spring internals (a cache filled with `ConcurrentHashMap.computeIfAbsent`), and the diagnostic flag `-Djdk.tracePinnedThreads` hung the test JVM intermittently. Java 25 removes the problem instead of working around it.

## D-019 — Maven, Spring Boot 4.1, PostgreSQL 18

- **Decision:** Maven with the Maven Wrapper (no local Maven install); the latest stable Spring Boot 4.1.x at scaffolding time; PostgreSQL 18 in Docker Compose and Testcontainers. Library versions come from the Spring Boot BOM; anything outside it is pinned explicitly.
- **Trade-offs:** ➕ the most widely known build tool, reproducible builds, same database version everywhere. ➖ Maven XML is more verbose than Gradle.

## D-020 — Spring Data JPA, native SQL only where PostgreSQL features are needed

- **Context:** The design relies on PostgreSQL features (partial unique indexes, `FOR UPDATE SKIP LOCKED`).
- **Decision:** Spring Data JPA (Hibernate) for entities and simple queries; explicit `@Query` (JPQL, or native SQL for PostgreSQL-specific operations such as the outbox claim). Flyway owns the schema (`ddl-auto: validate`). Rules: `open-in-view: false`, `LAZY` associations, fetch joins where a response needs related data, no bidirectional associations, constraint names mapped explicitly to domain errors.
- **Trade-offs:** ➕ familiar to most Java teams, less boilerplate for CRUD and pagination. ➖ JPA hides SQL and invites N+1 and lazy-loading surprises — contained by the rules above; PostgreSQL-specific parts are native SQL anyway.
- **Why not `JdbcClient`:** fully explicit, but more hand-written mapping code for a team used to JPA.
- **Exception — the outbox queue:** `OutboxTaskQueue` uses `JdbcClient`. Its operations are PostgreSQL statements with no JPA equivalent: a CTE with `FOR UPDATE SKIP LOCKED`, `UPDATE … RETURNING` into a non-entity record, and database-clock arithmetic (`now() + interval`). In Spring Data they would need native `UPDATE … RETURNING` through `getResultList`, overriding the repositories' read-only transactions, an extra fetch-join query and a projection. Creating tasks stays in JPA (`OutboxTask`, `OutboxTaskRepository`).

## D-021 — Layered architecture, packaged by feature; interfaces only at external boundaries

- **Decision:** Packages by feature (`catalog`, `booking`, `admin`, `postbooking`, `integration`, `common`); inside each, Controller → Service → Repository. `catalog` holds the reference data (patients, doctors, rooms, specialties) and never depends on `booking`. Every feature uses the same layers, only the ones it needs: `api` (controller, DTOs), `application` (services, policies), `domain` (entities, domain exceptions) and `persistence` (repositories), with dependencies pointing from `api` inwards. `common` is cross-cutting and is split by concern instead (`web`, `security`, `persistence`, `config`). Controllers are thin and never touch repositories; business rules live in stateless `@Service` classes. DTOs, request validation and the HTTP error mapping stay with the controllers. Interfaces exist only for the three external systems (calendar, rooms, email), which have real and simulated implementations.
- **Trade-offs:** ➕ a feature can be read in one package; no interface-per-class ceremony; simple to test. ➖ less isolation than hexagonal/DDD — not needed at this size.

## D-022 — Behaviour-based tests against real infrastructure

- **Decision:**
  - Unit tests cover pure business logic through its public behaviour (slot validation, doctor selection, failure classification, backoff, cursor encoding).
  - Integration tests go through the HTTP API with a real PostgreSQL (Testcontainers), external HTTP systems simulated with WireMock and SMTP with GreenMail; asynchronous outcomes asserted with Awaitility.
  - Mocks only at external boundaries. Tests named after behaviour (`givenX_whenY_thenZ` + `@DisplayName`).
  - A concurrency test proves I1, I2 and I5 under parallel requests.
- **Trade-offs:** ➕ tests survive refactoring and prove the invariants, not the implementation. ➖ slower suite (containers); Docker is required to run it.

## D-023 — Tooling: Spotless, GitHub Actions, WireMock, Mailpit

- **Decision:** Spotless with google-java-format (build fails on unformatted code); GitHub Actions runs `./mvnw verify` on every push; Docker Compose runs the app with PostgreSQL, WireMock (calendar and room systems) and Mailpit (fake SMTP with a web UI). The API is documented in the README.
- **Trade-offs:** ➕ consistent style without discussion, every commit verified, the full flow observable locally (including the email). ➖ the build fails until formatting is fixed (`spotless:apply`); more containers to run locally.

## D-024 — Randomized local sample data generated at startup, never in production or tests

- **Context:** Patients, doctors, rooms and specialties are read copies synced from other systems in production (HLD A5). Locally, the service must start with realistic data so it can be exercised immediately.
- **Options:**
  - A) Fixed sample data in Flyway SQL files.
  - B) A seeder that runs at application startup in the `local` profile and generates randomized data (Datafaker), only when the reference tables are empty.
  - C) B, but wiping and regenerating on every start.
- **Decision:** B.
  - Specialties are a fixed list (their codes are part of the API contract); doctors (per specialty), rooms and patients are randomized; counts are configurable.
  - Three demo patients with fixed ids are always created, so the README and Swagger examples have stable ids to use.
  - Appointments are not seeded: they are created through the API so the full post-booking flow runs.
  - Docker Compose keeps no database volume, so `docker compose down` + `up` gives a fresh random dataset.
- **Trade-offs:** ➕ realistic, varied data with zero manual setup; Flyway migrations contain only schema, so production never receives sample data; restarting never duplicates data. ➖ one extra dependency (Datafaker, local profile only); data differs between runs — anything that needs stable ids uses the demo patients.
- **Tests:** never rely on seeded data; each test creates exactly the data it needs (deterministic).

## D-025 — UUIDs assigned by the application, with a `BaseEntity` (`Persistable`)

- **Context:** The demo patients need fixed ids (D-024), and Hibernate-generated ids cannot be chosen by the caller. The appointment id is also the idempotency key sent to external systems (HLD 7.3), so knowing it before the insert is useful.
- **Options:**
  - A) UUID assigned in the entity constructor, no `@GeneratedValue`; a `BaseEntity` implements `Persistable<UUID>`.
  - B) Hibernate-generated UUIDs; demo patients inserted with raw SQL in the seeder; no `BaseEntity`.
- **Decision:** A. Every table uses a `uuid` primary key assigned by the application. `BaseEntity` (in `common/`) tracks whether an entity is new, so Spring Data issues a plain `INSERT` instead of a `SELECT` followed by a merge on every `save` (which is what it does when the id is already set).
- **Trade-offs:** ➕ stable ids for demo data and tests, id known before the insert, no extra `SELECT` per save. ➖ one base class; random UUIDs (v4) are 16 bytes and insert at random positions of the primary key index, which hurts locality on very large tables — irrelevant at ~1 req/s.
- **Why not B:** it removes `BaseEntity` but moves the demo patients out of the entity model into hand-written SQL.
- **Revisit when:** insert rate or table size makes index locality matter — switch to time-ordered UUID v7; the column type does not change.

## D-026 — OpenAPI document and Swagger UI with springdoc; open in the local profile, authenticated elsewhere

- **Context:** Trying the endpoints by hand is easier with an interactive UI, and the contract (request, responses, error codes) is then generated from the code instead of only written in prose.
- **Options:**
  - A) README only, no UI.
  - B) Add `springdoc-openapi` (Swagger UI + `/v3/api-docs`) and keep the README examples.
- **Decision:** B. `springdoc-openapi-starter-webmvc-ui` 3.1.1 (built against Spring Boot 4.1; outside the Boot BOM, so pinned). The document and the UI exist in every profile. They require authentication like any other endpoint (any role) because they describe a health-data API, except in the `local` profile (`appointments.security.public-api-docs: true`), where they open without login so the credentials are entered only in the *Authorize* button. The endpoints themselves always require credentials. The document is generated from the code; the only hand-written parts are the Basic scheme and a request example, in `OpenApiConfig`.
- **Trade-offs:** ➕ interactive testing with the *Authorize* (Basic) button, contract generated from the code, no annotations in controllers or DTOs. ➖ one more dependency to keep compatible with Spring Boot upgrades; error codes are not in the generated document (they are in the README).
- **Revisit when:** the API needs richer documentation (error responses, descriptions) — add annotations or a separate OpenAPI file.

## D-027 — The doctor calendar receives the patient id, never patient details

- **Context:** The doctor has to know who the appointment is for, yet the calendar system is outside this service, and an appointment linked to a person is health data (GDPR / LGPD special category, HLD 8).
- **Options:**
  - A) Send nothing about the patient (only the appointment).
  - B) Send the `patientId`, a pseudonymous reference to the patient registry.
  - C) Send name and contact details.
- **Decision:** B. `POST /doctors/{doctorId}/events` carries `patientId`; the clinic systems resolve it in the patient registry, which already holds the details (HLD A5). The room system gets no patient data, and the event title stays generic ("Medical appointment", no specialty).
- **Trade-offs:** ➕ the doctor can see who is coming; no names, emails or phones leave this service; a patient's data is changed or erased in one place. ➖ the calendar system needs access to the patient registry to show a name.
- **Why not A:** the calendar would be useless for its main purpose. **Why not C:** copies PII into another system for no gain over B.
