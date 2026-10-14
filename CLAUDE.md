# CLAUDE.md — working rules for this repository

Appointments API: patients book medical appointments; the service assigns a doctor and a
room without overbooking, then updates external systems and emails the patient.

**Read before any change:**
- [`docs/HLD.md`](docs/HLD.md) — invariants (I1–I5), assumptions, flows, API contracts, data model.
- [`DECISIONS.md`](DECISIONS.md) — every accepted decision (D-001…). Code must follow them.
- [`docs/PLAN.md`](docs/PLAN.md) — implementation steps and their acceptance criteria.

The invariants in `docs/HLD.md` §2 are non-negotiable. If a task seems to require breaking
one, or changing a decision, **stop and ask** — never change a decision silently. New
decisions are proposed as a new `DECISIONS.md` entry and wait for approval.

## Workflow (one branch and one Pull Request per step)

1. Work on **one step of `docs/PLAN.md` at a time**, only when asked.
2. Start from an up-to-date `main`: `git switch main && git pull`, then create the step branch
   `step-<N>-<short-slug>` (e.g. `step-1-schema`).
3. Before coding, restate the step's scope and acceptance criteria in a few lines. If anything
   is ambiguous, ask before writing code.
4. Implement, then run `./mvnw spotless:apply verify` until it is green.
5. Commit with Conventional Commits (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`, `build:`,
   `chore:`) — the commit message given in the plan for that step. Push the branch and open a
   Pull Request with `gh pr create`, filling `.github/pull_request_template.md`:
   - **Summary** — what the step adds, in a few lines.
   - **Decisions and invariants** — which `D-…` / `I…` this step implements or touches.
   - **Acceptance criteria** — each criterion → the test(s) that prove it.
   - **Review notes** — anything deferred, questionable or worth a closer look.
6. Stop and report the PR link. **The user reviews and merges** (squash). Review feedback is
   addressed with new commits on the same branch.
7. **Never** push to `main`, merge a PR, force-push or start the next step on your own.

## Stack

- Java 25 (virtual threads enabled), Maven Wrapper, Spring Boot 4.1.x (latest stable).
- PostgreSQL 18, Flyway, Spring Data JPA (Hibernate).
- Spring Web MVC, Spring Security (HTTP Basic), Spring Mail, `RestClient`, Actuator + Micrometer.
- Lombok (restricted, see below). Datafaker for the local data seeder only (D-024).
- springdoc-openapi for the OpenAPI document and Swagger UI (D-026).
- Tests: JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock, GreenMail, Awaitility.
- Spotless + google-java-format. GitHub Actions CI.
- Use versions managed by the Spring Boot BOM; pin anything outside it explicitly.
- **No other library without asking first.**

## Architecture

Layered, packaged by feature (D-021). Maven coordinates `pt.paulotrevizan:appointments-api`; base package `pt.paulotrevizan.appointments`.

```
Every feature uses the same layers (only the ones it needs):
api/ (controller, DTOs) → application/ (services, policies, transactions) →
domain/ (entities, domain exceptions) and persistence/ (repositories).

catalog/        reference data (read copies, HLD A5)
  domain/       Patient, Doctor, Room, Specialty
  persistence/  their repositories
  seed/         LocalDataSeeder, SeedProperties (profile local)
booking/
  api/          BookingController + request/response records
  application/  BookingService, AllocationPolicy, SlotPolicy
  domain/       Appointment, AppointmentStatus, domain exceptions
  persistence/  AppointmentRepository (including the availability queries)
admin/          same layers: AppointmentListingController + DTOs, AppointmentListingService, CursorCodec
postbooking/
  application/  PostBookingTasks, OutboxWorker, task handlers, RetryPolicy
  domain/       OutboxTask, OutboxTaskStatus, OutboxTaskType
  persistence/  OutboxTaskRepository
integration/    calendar/, room/, email/ — one port interface + one adapter each
common/         cross-cutting, split by concern: web/ (ProblemDetail handler, ApiException,
                OpenAPI), security/, persistence/ (BaseEntity), config/ (Clock)
```

- Controller → Service → Repository. **Controllers never call repositories.**
- Dependencies point inwards: `api` → `application` → `domain` / `persistence`. `catalog` never depends on
  `booking`; queries that combine reference data with appointments live in `booking/persistence`.
- Controllers are thin: validate the request shape, call one service method, map the result.
- Business rules live in `@Service` classes: stateless, small focused methods, unit-testable.
- Repositories are simple: queries only, no business logic.
- Interfaces **only** for the three external systems (`DoctorCalendarGateway`,
  `RoomReservationGateway`, `EmailSender`). No interface-per-service.
- DTOs are Java `records`, mapped manually (static `from(...)` factory on the response record
  or a small mapper class next to the controller). No MapStruct, no Spring Data projections.

## Coding rules

- Constructor injection for every dependency, fields `private final`. **Never `@Autowired`**
  on fields or setters. Use Lombok `@RequiredArgsConstructor`.
- Lombok allowed: `@RequiredArgsConstructor`, `@Slf4j`, and on JPA entities only `@Getter` +
  `@NoArgsConstructor(access = AccessLevel.PROTECTED)`. **Not allowed:** `@Data`, `@Setter`,
  `@Builder`, `@EqualsAndHashCode`, `@ToString` on entities.
- Entities change state through intention-revealing methods (`markDone()`, `scheduleRetry(...)`),
  not setters.
- KISS first: short, readable code over clever abstractions. DRY with restraint — a little
  duplication beats the wrong abstraction.
- Small focused functions, clear names, no comments that restate the code.
- Explicit error handling: one domain exception per error `code` in the HLD contract, mapped to
  RFC 9457 `ProblemDetail` in a single `@RestControllerAdvice`. Never swallow exceptions.
- Time: inject `java.time.Clock` (UTC); never call `Instant.now()` / `LocalDateTime.now()`
  directly. All timestamps are `Instant` / `timestamptz` (UTC).
- Configuration in `application.yaml` (+ `application-local.yaml`, `application-test.yaml`);
  no `.properties`. Typed `@ConfigurationProperties` records for custom settings. Secrets via
  environment variables; local defaults only in `application-local.yaml`.
- Logging with SLF4J only. **No `System.out` / `printStackTrace`.** Log ids only — never patient
  names, emails, phones or specialties (health data).

## Persistence rules (D-020)

- Flyway owns the schema (`db/migration`, schema only — no sample data).
  `spring.jpa.hibernate.ddl-auto: validate`. Never edit an applied migration.
- Local sample data comes from `LocalDataSeeder` (`@Profile("local")`, runs at startup, only when
  the reference tables are empty, D-024).
- `spring.jpa.open-in-view: false`. Associations `LAZY`, unidirectional, no cascades unless
  required. Use fetch joins (`JOIN FETCH`) when a response needs related data — no N+1.
- Queries are always parameterized: Spring Data methods, `@Query` with named parameters, or
  `NamedParameterJdbcTemplate`. **Never build SQL by string concatenation.**
- Native SQL only for PostgreSQL-specific needs (outbox claim with `FOR UPDATE SKIP LOCKED`).
- Constraint names are explicit in migrations (`appointment_doctor_no_overlap`,
  `appointment_room_no_overlap`, `appointment_patient_no_overlap`) and mapped to domain outcomes
  by name.
- Retries use Spring Framework's `@Retryable` / `RetryTemplate`, never hand-written loops. They run
  **outside** the transaction: one transaction per attempt via `TransactionTemplate`; use
  `saveAndFlush` so constraint violations surface inside the attempt (D-003). Never rely on
  calling a `@Transactional` method through `this`.
- No pessimistic, advisory or `SERIALIZABLE` locking for bookings: the slot invariants are unique
  indexes and conflicts are retried (D-002, D-003).

## Testing rules (D-022)

- Test **behaviour, not implementation**: assert on HTTP responses, database state, outgoing
  calls and emails — not on private methods, getters or mocks of our own classes.
- Unit tests for pure logic; integration tests through the HTTP API with Testcontainers
  PostgreSQL, WireMock and GreenMail. Mock only external boundaries.
- Naming: `givenX_whenY_thenZ` plus `@DisplayName` in plain English. Reference the invariant or
  decision when a test proves one (e.g. `@DisplayName("I1: a doctor is never double-booked")`).
- Async behaviour with Awaitility — never `Thread.sleep`.
- Each test sets up its own data; never rely on seeded data; no ordering dependencies between tests.

## Never

- Change an invariant, contract or decision without asking.
- Add endpoints, fields or features that are not in `docs/HLD.md`.
- Add dependencies without asking.
- Push to `main`, merge a Pull Request or force-push.
- Use `@Autowired` fields, `System.out`, string-concatenated SQL, `@Data` on entities.
