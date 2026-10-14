# Appointments API

Patients book medical appointments; the service assigns a doctor and a room without
overbooking, then updates external systems and emails the patient.

Design: [docs/HLD.md](docs/HLD.md) · Decisions: [DECISIONS.md](DECISIONS.md) ·
Implementation plan: [docs/PLAN.md](docs/PLAN.md)

> Work in progress — this README is completed in Step 8 of the plan.

## Prerequisites

- Java 25 (`JAVA_HOME` pointing to a JDK 25)
- Docker Desktop (running) — the tests use Testcontainers, and the local database runs in Docker

Maven is not required: the project ships with the Maven Wrapper.

## Run the tests

macOS / Linux:

```bash
./mvnw verify
```

Windows (PowerShell or cmd):

```powershell
.\mvnw.cmd verify
```

`verify` also checks formatting (Spotless + google-java-format). To fix formatting:
`./mvnw spotless:apply` (or `.\mvnw.cmd spotless:apply`).

## Run locally

1. Start PostgreSQL:

   ```bash
   docker compose up -d postgres
   ```

2. Start the application with the `local` profile, either from the command line:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

   or from **IntelliJ IDEA**:

   1. Open the project as a Maven project and make sure the project SDK is JDK 25
      (File → Project Structure → Project).
   2. Open `AppointmentsApiApplication` and click the green arrow next to `main` → *Modify Run
      Configuration…* (or Run → Edit Configurations → + → Spring Boot).
   3. Set **Active profiles** to `local` (if the field is hidden: Modify options → Add VM
      options → `-Dspring.profiles.active=local`, or Environment variables →
      `SPRING_PROFILES_ACTIVE=local`).
   4. Run or Debug.

The `local` profile connects to the Docker database, creates random sample data on the first start
(doctors, rooms, patients — see D-024) and defines the credentials below. Health check:
`GET http://localhost:8080/actuator/health`.

## Calling the API

### Authentication: there is no token

Callers authenticate with **HTTP Basic** (D-012), so there is nothing to generate: send the user and
password on every request. The role comes from the user:

| User | Password (`local` profile) | Role | Can call |
|------|----------------------------|------|----------|
| `booking-client` | `booking-local` | `BOOKING_CLIENT` | `POST /api/v1/appointments` |
| `admin` | `admin-local` | `ADMIN` | `GET /api/v1/admin/appointments` |

Outside the `local` profile the passwords come from the environment variables
`BOOKING_CLIENT_PASSWORD` and `ADMIN_PASSWORD`. In production the same roles will be mapped from JWTs
issued by the platform identity provider (D-012).

### Sample data

The `local` profile seeds, on an empty database: the six specialties (`CARDIOLOGY`, `DERMATOLOGY`,
`GENERAL_PRACTICE`, `PEDIATRICS`, `ORTHOPEDICS`, `NEUROLOGY`), 3–5 doctors per specialty, 10 rooms,
200 random patients and three demo patients with fixed ids:

| Patient | Id |
|---------|----|
| Demo Patient 1 | `00000000-0000-0000-0000-000000000001` |
| Demo Patient 2 | `00000000-0000-0000-0000-000000000002` |
| Demo Patient 3 | `00000000-0000-0000-0000-000000000003` |

To start again from a fresh random dataset: `docker compose down` then `up` (the database has no
volume).

### Swagger UI

Open <http://localhost:8080/swagger-ui.html>. In the `local` profile the page opens without login:
click **Authorize**, enter `booking-client` / `booking-local`, then open *POST /api/v1/appointments* →
*Try it out* → *Execute*. To list appointments, authorize as `admin` / `admin-local` and use
*GET /api/v1/admin/appointments*. The OpenAPI document is at `http://localhost:8080/v3/api-docs` (D-026).
In other profiles both require HTTP Basic credentials, and the browser asks for them.

### Book an appointment

`startAt` must be in the future, in UTC, on the :00 / :30 grid. There is no booking horizon and no
calendar: weekends, holidays and night hours are accepted (HLD A3, D-008). Replace the date if it has passed.

```bash
curl -i -u booking-client:booking-local \
  -H 'Content-Type: application/json' \
  -d '{"patientId":"00000000-0000-0000-0000-000000000001","specialty":"CARDIOLOGY","startAt":"2030-01-07T14:00:00Z"}' \
  http://localhost:8080/api/v1/appointments
```

On Windows PowerShell use `curl.exe` (plain `curl` is an alias of another command) and escape the
quotes, or use Swagger UI instead.

`201 Created`:

```json
{
  "id": "b5204e3c-07ab-41eb-a05e-7121b4717d7e",
  "status": "CONFIRMED",
  "specialty": "CARDIOLOGY",
  "startAt": "2030-01-07T14:00:00Z",
  "endAt": "2030-01-07T14:30:00Z",
  "doctor": { "id": "11eae05e-3535-41b0-b51d-db4abb17023f", "name": "Dr. Marguerita Sporer" },
  "room": { "id": "0c5e5eec-5c3c-40e5-af76-7cb3c438967c", "name": "Room 2" },
  "patient": {
    "id": "00000000-0000-0000-0000-000000000001",
    "name": "Demo Patient 1",
    "email": "demo.patient1@example.com",
    "phone": "+351910000001"
  },
  "createdAt": "2026-10-13T14:42:21.889883Z"
}
```

Errors are RFC 9457 problem details (`application/problem+json`). Identify them by `code`; `title` is
the HTTP status phrase and `detail` explains the case:

```json
{
  "title": "Bad Request",
  "status": 400,
  "code": "SLOT_IN_PAST",
  "detail": "Slot 2020-01-07T14:00:00Z is not in the future",
  "instance": "/api/v1/appointments"
}
```

| Status | `code` | When |
|--------|--------|------|
| 400 | `VALIDATION_ERROR` | Missing or malformed field |
| 400 | `SLOT_IN_PAST` | `startAt` is not in the future |
| 400 | `SLOT_NOT_ALIGNED` | `startAt` is not on :00 or :30 UTC |
| 401 / 403 | — | No or wrong credentials / wrong role |
| 404 | `PATIENT_NOT_FOUND`, `SPECIALTY_NOT_FOUND` | Unknown patient or specialty code |
| 409 | `PATIENT_ALREADY_BOOKED` | The patient already has another appointment at that slot |
| 409 | `NO_DOCTOR_AVAILABLE`, `NO_ROOM_AVAILABLE` | Nothing free at that slot |

Sending the same request again (same patient, slot and specialty) is safe: it returns `201` with
the appointment created the first time, and nothing new is booked (D-011).

### List appointments (admin)

All appointments, sorted by `startAt` and then `id`, one page at a time (D-016). Query parameters:

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `limit` | `20` | Page size, 1–100 |
| `cursor` | — | `nextCursor` from the previous page; omit it for the first page |

```bash
curl -i -u admin:admin-local 'http://localhost:8080/api/v1/admin/appointments?limit=20'
```

`200 OK`:

```json
{
  "items": [
    {
      "id": "b5204e3c-07ab-41eb-a05e-7121b4717d7e",
      "status": "CONFIRMED",
      "specialty": "CARDIOLOGY",
      "startAt": "2030-01-07T14:00:00Z",
      "endAt": "2030-01-07T14:30:00Z",
      "doctor": { "id": "11eae05e-3535-41b0-b51d-db4abb17023f", "name": "Dr. Marguerita Sporer" },
      "room": { "id": "0c5e5eec-5c3c-40e5-af76-7cb3c438967c", "name": "Room 2" },
      "patient": {
        "id": "00000000-0000-0000-0000-000000000001",
        "name": "Demo Patient 1",
        "email": "demo.patient1@example.com"
      },
      "postBooking": { "doctorCalendar": "DONE", "roomReservation": "DONE", "email": "PENDING" }
    }
  ],
  "nextCursor": "eyJzdGFydEF0IjoiMjAzMC0wMS0wN1QxNDowMDowMFoiLCJpZCI6ImI1MjA0ZTNjLTA3YWItNDFlYi1hMDVlLTcxMjFiNDcxN2Q3ZSJ9"
}
```

For the next page, send `nextCursor` back as `cursor` (`limit` may change between pages). Stop when
`nextCursor` is `null`: that was the last page. With no appointments, `items` is `[]`.

```bash
curl -i -u admin:admin-local 'http://localhost:8080/api/v1/admin/appointments?limit=20&cursor=eyJzdGFydEF0IjoiMjAzMC0wMS0wN1QxNDowMDowMFoiLCJpZCI6ImI1MjA0ZTNjLTA3YWItNDFlYi1hMDVlLTcxMjFiNDcxN2Q3ZSJ9'
```

- Treat the cursor as opaque: do not build or edit it. Its format may change.
- Pages stay consistent while new appointments are booked: walking every page returns each appointment
  exactly once. There is no total count and no "jump to page N" (D-016).
- `postBooking` shows each post-booking action: `PENDING` (not done yet, or being retried), `DONE`
  or `FAILED` (needs a person; see the runbook in HLD §13).

| Status | `code` | When |
|--------|--------|------|
| 400 | `INVALID_CURSOR` | The cursor is malformed (not a `nextCursor` returned by this API) |
| 400 | `VALIDATION_ERROR` | `limit` is not a number between 1 and 100 |
| 401 / 403 | — | No or wrong credentials / not `ADMIN` |
