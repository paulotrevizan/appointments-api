# Appointments API — High Level Design

> Status: **Accepted** · Owner: Paulo Trevizan · Decisions log: [DECISIONS.md](../DECISIONS.md) · Implementation plan: [PLAN.md](PLAN.md)

## 1. Problem in one paragraph

Patients book a consultation by giving their identity, a desired timeslot and a specialty.
The system automatically picks a free doctor of that specialty and a free room, confirms
the booking, and then (1) updates the doctor's calendar, (2) reserves the room — both in
external systems — and (3) emails the patient with doctor name + room. Admins can list all
appointments. Overbooking of doctors or rooms is never allowed.

## 2. Invariants

These are the properties the solution must **never** violate. Every design choice below
exists to protect one of them.

| # | Invariant | Enforced by |
|---|-----------|-------------|
| **I1** | A doctor never has two active appointments overlapping in time | PostgreSQL partial unique index on fixed, grid-aligned slots (D-002) |
| **I2** | A room never has two active appointments overlapping in time | PostgreSQL partial unique index on fixed, grid-aligned slots (D-002) |
| **I3** | The assigned doctor always has the requested specialty | Allocation query: only doctors of the requested specialty are candidates |
| **I4** | A confirmed appointment never loses its post-booking actions (they may be delayed, never dropped) | Transactional outbox (same DB transaction) |
| **I5** | A patient never has two active appointments overlapping in time — which also makes client retries (double click, timeout, new tab) safe | PostgreSQL partial unique index on fixed, grid-aligned slots (D-002) |

> I1, I2 and I5 are guaranteed by the database, not by the application or the client. It
> does not matter how many instances run, how many concurrent requests arrive or how the
> client retries: PostgreSQL rejects the overlap. The application only decides *who to try*.

## 3. Assumptions

| # | Assumption | Why it is reasonable |
|---|------------|----------------------|
| A1 | This service is the **source of truth** for appointments. External calendar/room systems are downstream and only need to be *updated* | The statement says they "must be updated", not consulted |
| A2 | Fixed consultation duration of **30 min**; `startAt` must be aligned to the grid (`:00` / `:30`) | Patient chooses a timeslot, not a duration |
| A3 | Any **future** slot aligned to the 30-min grid is bookable, with **no booking horizon** (a slot in 2099 is accepted) and no calendar rules: weekends, holidays and night hours are bookable. A doctor is available if they have **no overlapping appointment**. Opening hours, doctor schedules, holidays and how far ahead patients may book are not modelled — they belong to the doctor calendar / clinic systems and the booking channel. Everything is UTC | No such data is given; inventing values would create business rules nobody asked for. Adding them later is a validation rule, not a design change (D-008) |
| A4 | Each doctor has exactly **one** specialty; any room can host any specialty | Simplification; no data given about room types |
| A5 | Patients **already exist** in the healthcare system ("our healthcare system allows patients to book"). The request references a `patientId`; this service keeps a local read copy of patients, like doctors and rooms (seeded locally, synced from the patient registry in production). Patient management (CRUD) is not owned here | Same treatment as all other reference data; no FR asks to register patients |
| A6 | End users (patients, staff) authenticate upstream, in the platform's identity provider. This service authenticates its **callers** — platform components such as the patient app backend or the front-desk system — and holds no end-user identity | Standard platform setup; the FR passes patient information as input, so the caller is the one that knows who the patient is |
| A7 | External systems accept an idempotency key (our appointment id) | Standard for at-least-once integrations; makes outbox retries safe |

## 4. Answers to the open questions

| Question | Answer |
|----------|--------|
| Is a security layer required? | **Yes, minimal (D-012).** Calling services authenticate, and each endpoint requires a role: `BOOKING_CLIENT` to book, `ADMIN` to list. The listing exposes every patient's appointments, so it is the main asset to protect. Per-patient authorization is the caller's responsibility (A6). |
| Implement the external systems or only interfaces? | **Interface + real-style adapter.** Domain ports (`DoctorCalendarGateway`, `RoomReservationGateway`, `EmailSender`), HTTP/SMTP adapters written as production code, and the external systems **simulated** (HTTP mock + fake SMTP). We do not build the external systems. |
| Are Retry + Circuit Breaker enough? | **No.** They handle *transient* failures but do not *remember* pending work if the process restarts or the external system is down for an hour. **Durability comes from the outbox.** Retries are done by the outbox with exponential backoff, which already stops hammering a failing system, so a circuit breaker adds little at this load and is left out (D-005). Adapters use timeouts. |
| How do we know the calendar/room were updated? | Each post-booking action is a row in `outbox_task` with a status (`PENDING → DONE / FAILED`). The admin listing exposes it; `FAILED` raises an alert/metric. |
| Block the user or return 201 + outbox? | **201 + outbox** (D-001). Booking works even if all externals are down; consistency lives in our DB. |

## 5. Architecture

Single deployable Spring Boot service (modular monolith) + PostgreSQL. No message broker.

```
[Patient app backend / Front-desk system]     [Admin panel]
          │ POST /appointments                  │ GET /admin/appointments
          ▼                                     ▼
┌────────────────── Appointments Service (Spring Boot) ──────────────────┐
│  REST API                                                              │
│     │                                                                  │
│  Booking Service ──▶ Allocation Policy (choose doctor + room)          │
│     │                                                                  │
│     ▼ 1 transaction: appointment + 3 outbox tasks                      │
│  ─────────────────────────────────────────────▶ [PostgreSQL]           │
│                                                     ▲                  │
│  Outbox Worker (scheduled) ── claims PENDING ───────┘                  │
│     ├─▶ CalendarAdapter ──▶ [Doctor Calendar System]  (external)       │
│     ├─▶ RoomAdapter     ──▶ [Room Reservation System] (external)       │
│     └─▶ EmailAdapter    ──▶ [SMTP / Email provider]   (external)       │
└────────────────────────────────────────────────────────────────────────┘
```

### Components

| Component | Responsibility |
|-----------|----------------|
| REST API | Validation, auth (roles), HTTP ↔ domain mapping, problem+json errors |
| Booking Service | Patient/specialty lookup, duplicate check (patient + slot), orchestrates allocation, persists appointment + outbox in one transaction, retries on constraint violation |
| Allocation Policy | Chooses candidates. Doctor: least booked that day (tie → id). Room: first free. A single concrete class — replaced, not abstracted, if a new policy is needed |
| Outbox Worker | Polls due tasks, claims them safely across instances, dispatches to the right adapter, applies backoff, marks `DONE` / `FAILED` |
| Adapters | One per external system, behind a domain port. Timeouts. Send appointment id as idempotency key |
| PostgreSQL | Owned exclusively by this service. Enforces I1, I2, I5 |

## 6. Flows

### 6.1 Create appointment (synchronous)

1. Validate: future time, aligned to the 30-min grid, patient exists,
   specialty exists (local lookups, same database — no extra network round trip).
2. **Attempt**, in one transaction:
   1. Duplicate check — does the patient already have a confirmed appointment at that slot?
      - same specialty → it is a retry: return the existing appointment (**201**, the same
        body as the original response);
      - different specialty → **409** `PATIENT_ALREADY_BOOKED`.
   2. Query candidate doctors (specialty, active, no appointment at that slot) ordered by load
      that day, and candidate rooms (active, free at that slot).
   3. `INSERT appointment` (first doctor, first room) + `INSERT` 3 `outbox_task` rows → `COMMIT`.
3. If PostgreSQL rejects the insert on a slot index (another request committed first), the
   attempt is retried with fresh candidates (**max 3 attempts**, D-003). An identical request
   that lost the race finds the winner's appointment in step 2.1.
4. No candidate left, or attempts used up → **409** (`NO_DOCTOR_AVAILABLE` / `NO_ROOM_AVAILABLE`).
   Success → **201** with the full appointment: doctor, room **and patient details**, so the
   client never needs a follow-up call.

### 6.2 Post-booking actions (asynchronous)

1. Every ~2 s the worker claims due tasks in a short transaction:
   `status = PENDING AND next_attempt_at <= now() AND (locked_until IS NULL OR locked_until < now())`
   using `FOR UPDATE SKIP LOCKED`, and sets a lease (`locked_until = now() + 30s`).
   No DB transaction is held during external calls; a crashed instance's tasks become
   claimable again when the lease expires.
2. Call the adapter (with timeout).
3. Success → `DONE`. Failure is classified (D-017):
   - **retryable** (timeout, connection error, HTTP 5xx / 429, SMTP transient) →
     `attempts + 1`, `next_attempt_at = now + exponential backoff (with jitter)`;
     after **N attempts** → `FAILED`;
   - **non-retryable** (other HTTP 4xx, e.g. the external system rejects the reservation,
     SMTP permanent failure) → `FAILED` immediately — retrying cannot fix it.

   `FAILED` → metric + log alert, visible in the admin listing.
   All times (`next_attempt_at`, `locked_until`) use the **database clock**, never the
   instances' clocks. The lease (30 s) is longer than the adapter timeout; if it still
   expires mid-call the task may run twice — harmless thanks to A7.
4. The three tasks are **independent**: the email does not wait for the calendar, because
   doctor and room are already known locally (consequence of A1).

Delivery semantics: **at-least-once**. Duplicates are made harmless by sending the
appointment id as idempotency key to external systems (A7).

### 6.3 List appointments — admin (keyset / cursor pagination, D-016)

1. Validate `limit` (default 20, max 100). If a `cursor` is present, decode it into
   (`startAt`, `id`); malformed → **400** `INVALID_CURSOR`.
2. One query, served by the index on (`start_at`, `id`):
   ```sql
   SELECT ... FROM appointment a
     JOIN patient p ... JOIN doctor d ... JOIN room r ... JOIN specialty s ...
   WHERE (:cursorStartAt IS NULL OR (a.start_at, a.id) > (:cursorStartAt, :cursorId))
   ORDER BY a.start_at, a.id
   LIMIT :limit + 1
   ```
3. Load the post-booking statuses for the returned ids in **one** extra query
   (`outbox_task WHERE appointment_id IN (...)`) — no N+1.
4. Got `limit + 1` rows → drop the extra one and build `nextCursor` from the last returned
   item (`startAt`, `id`). Otherwise `nextCursor = null` (last page).
5. **200** with `items` + `nextCursor`. The client calls again with `cursor=nextCursor`
   until it is `null`.

`id` is the tie-breaker: many appointments share the same `start_at` (one per doctor/room),
so `start_at` alone is not a unique sort key and pages would skip or repeat rows.

## 7. API contracts

Two endpoints, one per FR that needs an API (D-015). Versioned under `/api/v1`.
Timestamps are ISO-8601 UTC. Errors use **RFC 9457 Problem Details**
(`application/problem+json`) with a stable `code`. Clients identify the error by `code`; `type` is
omitted (`about:blank`) and `title` is the HTTP status phrase, as RFC 9457 recommends when there is
no dedicated documentation page per problem type.

### 7.1 `POST /api/v1/appointments` — role `BOOKING_CLIENT` (FR 1)

```json
{
  "patientId": "0b7e4c1d-...",
  "specialty": "CARDIOLOGY",
  "startAt": "2026-10-20T14:00:00Z"
}
```

`201 Created` — the body is the complete appointment (no `Location` header: there is no
read-by-id endpoint).

```json
{
  "id": "6f1c2a9e-...",
  "status": "CONFIRMED",
  "specialty": "CARDIOLOGY",
  "startAt": "2026-10-20T14:00:00Z",
  "endAt": "2026-10-20T14:30:00Z",
  "doctor": { "id": "d1...", "name": "Dr. João Lima" },
  "room": { "id": "r3...", "name": "Room 3" },
  "patient": { "id": "0b7e4c1d-...", "name": "Ana Souza", "email": "ana@mail.com", "phone": "+351912345678" },
  "createdAt": "2026-10-12T10:15:02Z"
}
```

| Status | `code` | When |
|--------|--------|------|
| 201 | — | Booked |
| 201 | — | Retry: the patient already has this appointment (same slot + specialty); same body as the original response |
| 400 | `VALIDATION_ERROR`, `SLOT_IN_PAST`, `SLOT_NOT_ALIGNED` | Invalid input |
| 401 / 403 | — | Not authenticated / wrong role |
| 404 | `PATIENT_NOT_FOUND`, `SPECIALTY_NOT_FOUND` | Unknown patient or specialty |
| 409 | `PATIENT_ALREADY_BOOKED` | Patient already has another appointment at that slot |
| 409 | `NO_DOCTOR_AVAILABLE`, `NO_ROOM_AVAILABLE` | Nothing free at that slot |

Error example:

```json
{
  "title": "Conflict",
  "status": 409,
  "code": "NO_DOCTOR_AVAILABLE",
  "detail": "No CARDIOLOGY doctor is free at 2026-10-20T14:00:00Z",
  "instance": "/api/v1/appointments"
}
```

### 7.2 `GET /api/v1/admin/appointments` — role `ADMIN` (FR 3)

Query params: `limit` (default 20, max 100), `cursor` (optional, opaque — taken from the
previous response). Sorted by `startAt`, then `id`. Keyset pagination (D-016); filters are an
extension (D-015).

First page: `GET /api/v1/admin/appointments?limit=20`
Next page: `GET /api/v1/admin/appointments?limit=20&cursor=eyJzdGFydEF0Ijoi...`

```json
{
  "items": [
    {
      "id": "6f1c2a9e-...",
      "status": "CONFIRMED",
      "specialty": "CARDIOLOGY",
      "startAt": "2026-10-20T14:00:00Z",
      "endAt": "2026-10-20T14:30:00Z",
      "doctor": { "id": "d1...", "name": "Dr. João Lima" },
      "room": { "id": "r3...", "name": "Room 3" },
      "patient": { "id": "0b7e4c1d-...", "name": "Ana Souza", "email": "ana@mail.com" },
      "postBooking": { "doctorCalendar": "DONE", "roomReservation": "PENDING", "email": "DONE" }
    }
  ],
  "nextCursor": "eyJzdGFydEF0IjoiMjAyNi0xMC0yMFQxNDowMDowMFoiLCJpZCI6IjZmMWMyYTllLi4uIn0"
}
```

- `nextCursor` is `null` on the last page.
- The cursor is **opaque** to clients (base64url of `{startAt, id}`), so its format can change
  without breaking them. It is not signed: tampering only reaches rows an admin may already see.
- No `totalItems` and no "jump to page N" (see D-016).

| Status | `code` | When |
|--------|--------|------|
| 200 | — | Page returned (possibly empty) |
| 400 | `INVALID_CURSOR`, `VALIDATION_ERROR` | Malformed cursor, `limit` out of range |
| 401 / 403 | — | Not authenticated / not `ADMIN` |

### 7.3 Outbound contracts (defined by us, implemented by adapters — FR 2)

| Port | Adapter call | Idempotency |
|------|--------------|-------------|
| `DoctorCalendarGateway.addEvent(...)` | `POST {calendar-base-url}/doctors/{doctorId}/events` body `{ appointmentId, startAt, endAt, title }` | `Idempotency-Key: {appointmentId}` |
| `RoomReservationGateway.reserve(...)` | `POST {rooms-base-url}/rooms/{roomId}/reservations` body `{ appointmentId, startAt, endAt }` | `Idempotency-Key: {appointmentId}` |
| `EmailSender.send(to, subject, body)` | SMTP (fake SMTP server locally) | Task status prevents re-send after `DONE` |

## 8. Data model (PostgreSQL)

```
PATIENT   1 ──── N APPOINTMENT
SPECIALTY 1 ──── N DOCTOR
SPECIALTY 1 ──── N APPOINTMENT
DOCTOR    1 ──── N APPOINTMENT
ROOM      1 ──── N APPOINTMENT
APPOINTMENT 1 ── 3 OUTBOX_TASK
```

| Table | Columns | Constraints / indexes |
|-------|---------|-----------------------|
| `patient` | `id`, `name`, `email`, `phone`, `created_at`, `updated_at` | `email` unique · read copy of the patient registry |
| `specialty` | `id`, `code`, `name` | `code` unique |
| `doctor` | `id`, `name`, `email`, `specialty_id`, `active` | FK specialty |
| `room` | `id`, `name`, `active` | `name` unique |
| `appointment` | `id`, `patient_id`, `specialty_id`, `doctor_id`, `room_id`, `start_at`, `end_at`, `status`, `created_at`, `updated_at` | **I1:** unique (`doctor_id`, `start_at`) `WHERE status = 'CONFIRMED'` · **I2:** same for `room_id` · **I5:** same for `patient_id` · `CHECK` `end_at - start_at = 30 min` and `start_at` on the :00 / :30 grid (D-002, D-007) · index on (`start_at`, `id`) for the admin listing |
| `outbox_task` | `id`, `appointment_id`, `type` (`DOCTOR_CALENDAR` / `ROOM_RESERVATION` / `EMAIL`), `status` (`PENDING` / `DONE` / `FAILED`), `attempts`, `next_attempt_at`, `locked_until`, `last_error`, `created_at`, `updated_at` | FK appointment · unique (`appointment_id`, `type`) · index on (`status`, `next_attempt_at`) |

Notes:
- `appointment.status` is `CONFIRMED` today; `CANCELLED` is reserved so the constraints
  already ignore cancelled rows when cancellation is added.
- Booking state (`appointment.status`) and integration state (`outbox_task.status`) are
  deliberately separate: an appointment is confirmed even if the calendar is not yet updated.
- Reference data (patients, specialties, doctors, rooms) is generated randomly at startup in
  the `local` profile (D-024); in production it is synchronised from the patient registry and
  HR / facilities systems. Flyway migrations contain schema only.
- Patient PII lives in **one** table, not copied into every appointment: easier to protect,
  update and erase (GDPR right to erasure).
- The email task reads the patient's email at execution time (join), so it always uses the
  current address.
- Appointment data is **health data** (a specialty can reveal a condition — GDPR special
  category). The email carries only what the FR requires (date/time, doctor name, room), not
  the specialty; logs carry ids only, never names, emails or specialties.

## 9. Non-functional view

| Concern | Approach |
|---------|----------|
| Load | "Thousands/hour" ≈ 1 req/s. One instance + one PostgreSQL has large headroom. The real challenge is **contention on the same slot**, solved by I1/I2/I5 |
| Scalability | Stateless app → N replicas without code change; constraints + `SKIP LOCKED` make concurrency safe |
| Resilience | Booking path never calls externals. Post-booking: outbox + exponential backoff + timeouts |
| Security | Calling services authenticate (HTTP Basic locally, JWT from the platform identity provider in production); per-endpoint roles `BOOKING_CLIENT` / `ADMIN` (D-012). No PII in logs |
| Observability | Health (liveness/readiness), metrics (bookings, 409s, outbox lag, failed tasks), structured logs with correlation id and appointment id |
| Deployability | Container image, configuration via environment variables, one-command local stack |

## 10. Out of scope (extension points)

- Cancellation / rescheduling (status and constraints already prepared)
- Availability search endpoint ("which slots are free?")
- Opening hours, doctor schedules, holidays (owned by the calendar / clinic systems) and room types/equipment
- Patient management (create/update patients) — owned by the patient registry
- Read endpoints not requested by the FRs: get appointment by id, list appointments of a patient
- Admin listing filters (date range, doctor, room, patient), total count, previous-page navigation
- Circuit breaker on adapters (worth adding if post-booking volume grows a lot)
- Limit of future appointments per patient / rate limiting (abuse, not idempotency — gateway concern)
- Admin endpoint to re-run `FAILED` tasks
- Publishing domain events to a broker (Kafka) for other services

## 11. Main trade-offs (what we chose and what it costs)

| # | We chose | We gave up | Why it is acceptable |
|---|----------|------------|----------------------|
| T1 | Our DB as source of truth + async propagation (D-001) | Immediate consistency with calendar/room systems: the patient can be confirmed for a room the external system later rejects | Booking never depends on 3 external systems; rejections are visible (`FAILED`) and rare under A1 |
| T2 | Invariants in PostgreSQL (partial unique indexes, `CHECK` constraints, `SKIP LOCKED`) | Database portability; tests need a real PostgreSQL | Correctness of the core rule beats portability; PostgreSQL is a safe long-term choice |
| T3 | Polling outbox, no broker (D-004, D-014) | Seconds of latency on post-booking actions; constant light polling load | No extra infrastructure to run; broker is a clear evolution path |
| T4 | Local read copies of patients/doctors/rooms (A5) | Freshness: a change upstream is only seen after sync; the sync itself is assumed, not built | Keeps booking fast and self-contained; same pattern for all reference data |
| T5 | Greedy allocation, one request at a time (D-009) | Global optimality (e.g. packing doctors to leave whole days free) | Rooms are generic and doctors interchangeable within a specialty |
| T6 | Minimal API surface (D-015) | Get-by-id, filters, cancellation | Every endpoint traces to a FR; extensions are cheap |
| T7 | Service-level authorization with per-endpoint roles (D-012) | Per-patient checks: a booking client can book for any `patientId` | The caller holds the patient's session (A6). If end users ever call the API directly, the check moves here: `patientId` must match the token |

## 12. Failure modes

| What fails | When | What happens | Invariant kept |
|------------|------|--------------|----------------|
| Instance crashes | Before commit | Nothing persisted; client retry books normally | I1, I2, I4, I5 |
| Instance crashes | After commit, before response | Booking + outbox exist; client retry gets **201** with the same appointment | I5, I4 |
| Two identical requests | Concurrently | One inserts; the other hits the slot index, is retried and finds the first appointment → both get 201 with the same body | I5 |
| Many requests, same slot | Concurrently | The slot index rejects losers → retried with fresh candidates (max 3 attempts) → 201 or 409 | I1, I2 |
| Worker crashes | During an external call | Lease expires → task claimed again → external dedups by appointment id | I4 |
| External system down | For hours | Tasks back off; booking keeps working; after N attempts `FAILED` + alert | I4 (nothing lost) |
| External rejects (4xx) | Any time | Non-retryable → `FAILED` immediately + alert → manual reconciliation | I4 (visible, not silent) |
| PostgreSQL down | Any time | Booking returns 503; readiness probe fails; worker pauses; nothing half-written | All |
| SMTP accepts, email never arrives | After `DONE` | Out of our control; `DONE` means "handed to the provider" | — |

## 13. Operational readiness

Signals the service exposes (metrics) and what each one is for. Targets and alert
thresholds are set once real traffic is observed — not invented upfront.

| Signal | Use |
|--------|-----|
| Booking requests by outcome (201 / 4xx / 5xx, repeated requests) and latency | Health of the booking API |
| Age of oldest `PENDING` outbox task | Post-booking freshness — the main alert candidate |
| Count of `FAILED` tasks | Something needs a human — alert candidate |
| `NO_DOCTOR_AVAILABLE` / `NO_ROOM_AVAILABLE` rate | Capacity signal for the business, not an incident |
| Constraint-conflict retries | Contention signal |

Runbook (README): how to inspect `FAILED` tasks, re-drive one after fixing the cause
(`status = 'PENDING', attempts = 0, next_attempt_at = now()`), and check external system
health. Deploys: migrations are backward compatible (expand → migrate → contract) so a
rolling deploy can run old and new versions side by side; on shutdown the worker stops
claiming and in-flight tasks finish or are recovered by lease expiry.

## 14. Other risks

| Risk | Mitigation / position |
|------|-----------------------|
| Email sent but room reservation still failing | Tasks are independent by design; the patient's booking is valid in our system of record |
| Patient books many *different* slots | Not a duplicate; legitimate or abuse → business limit / rate limiting (out of scope) |
| Outbox table growth | Periodic cleanup/archival of `DONE` tasks (extension) |
| Admin access to patient data is not audited | Audit log of admin reads is an extension; access is restricted to `ADMIN` |
