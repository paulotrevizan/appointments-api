# Diagrams for Excalidraw

Paste each block into Excalidraw → **More tools → Mermaid to Excalidraw**.
Flowcharts become editable shapes; sequence and class diagrams are converted as far as
Excalidraw supports them — adjust styling by hand afterwards.

## 1. Architecture (components + external systems)

```mermaid
flowchart LR
  Patient["Patient app backend / Front-desk system"] -->|"POST /appointments (BOOKING_CLIENT)"| API
  Admin["Admin panel"] -->|"GET /admin/appointments (ADMIN)"| API

  subgraph SVC["Appointments Service (Spring Boot)"]
    API["REST API"] --> BS["Booking Service"]
    BS --> AL["Allocation Policy"]
    W["Outbox Worker"] --> CA["Calendar Adapter"]
    W --> RA["Room Adapter"]
    W --> EA["Email Adapter"]
  end

  BS -->|"1 tx: appointment + 3 outbox tasks"| DB[("PostgreSQL")]
  W -->|"claim PENDING tasks"| DB
  CA --> CAL["Doctor Calendar System (external)"]
  RA --> ROOM["Room Reservation System (external)"]
  EA --> SMTP["SMTP / Email provider (external)"]
```

## 2. Create appointment (synchronous)

```mermaid
sequenceDiagram
  actor C as Booking client
  participant API as REST API
  participant BS as Booking Service
  participant DB as PostgreSQL

  C->>API: POST /api/v1/appointments
  API->>BS: book(patientId, specialty, startAt)
  BS->>DB: load patient and specialty (404 if unknown)
  BS->>DB: patient already booked at this slot?
  alt same slot and same specialty
    BS-->>API: existing appointment
    API-->>C: 200 OK (retry)
  else same slot, other specialty
    API-->>C: 409 PATIENT_ALREADY_BOOKED
  else not booked
    BS->>DB: find free doctors of specialty and free rooms
    loop up to 3 attempts
      BS->>DB: one tx - insert appointment and 3 outbox tasks
      alt exclusion constraint violated
        DB-->>BS: conflict on doctor, room or patient
        Note over BS: doctor or room - try next candidate. patient - concurrent retry, re-check
      end
    end
    alt booked
      BS-->>API: confirmed appointment
      API-->>C: 201 Created (doctor, room and patient details)
    else no candidates left
      API-->>C: 409 NO_DOCTOR_AVAILABLE or NO_ROOM_AVAILABLE
    end
  end
```

## 3. Post-booking actions (asynchronous)

```mermaid
sequenceDiagram
  participant W as Outbox Worker
  participant DB as PostgreSQL
  participant AD as Adapter
  participant EXT as External System

  loop every 2 seconds
    W->>DB: lock due PENDING tasks (FOR UPDATE SKIP LOCKED, one transaction)
    W->>AD: execute task (calendar, room or email)
    AD->>EXT: HTTP or SMTP call with timeout
    alt success
      W->>DB: status DONE
    else failure
      W->>DB: attempts + 1 and next_attempt_at with backoff
      Note over W,DB: after N attempts status FAILED and alert
    end
  end
```

## 4. List appointments — admin (cursor pagination)

```mermaid
sequenceDiagram
  actor A as Admin panel
  participant API as REST API
  participant Q as Admin Query Service
  participant DB as PostgreSQL

  A->>API: GET /api/v1/admin/appointments?limit=20 (and cursor from previous page)
  API->>Q: list(limit, cursor)
  alt cursor malformed
    API-->>A: 400 INVALID_CURSOR
  else valid or absent
    Q->>DB: appointments where (start_at, id) after cursor, order by start_at, id, limit 21
    Q->>DB: outbox statuses for the returned ids (one query)
    Note over Q: 21 rows - drop last, nextCursor from item 20. Less - nextCursor null
    API-->>A: 200 items + nextCursor
  end
  Note over A,API: repeat with cursor = nextCursor until it is null
```

## 5. Data model

```mermaid
classDiagram
  class Patient {
    uuid id
    string name
    string email
    string phone
    timestamptz created_at
    timestamptz updated_at
  }
  class Specialty {
    uuid id
    string code
    string name
  }
  class Doctor {
    uuid id
    string name
    string email
    uuid specialty_id
    boolean active
  }
  class Room {
    uuid id
    string name
    boolean active
  }
  class Appointment {
    uuid id
    uuid patient_id
    uuid specialty_id
    uuid doctor_id
    uuid room_id
    timestamptz start_at
    timestamptz end_at
    tstzrange slot
    string status
    timestamptz created_at
    timestamptz updated_at
  }
  class OutboxTask {
    uuid id
    uuid appointment_id
    string type
    string status
    int attempts
    timestamptz next_attempt_at
    string last_error
    timestamptz created_at
    timestamptz updated_at
  }

  Patient "1" --> "*" Appointment
  Specialty "1" --> "*" Doctor
  Specialty "1" --> "*" Appointment
  Doctor "1" --> "*" Appointment
  Room "1" --> "*" Appointment
  Appointment "1" --> "3" OutboxTask
```

Constraints to annotate on the drawing:
- `Appointment`: no overlap of (`doctor_id`, `slot`) for CONFIRMED — **I1**
- `Appointment`: no overlap of (`room_id`, `slot`) for CONFIRMED — **I2**
- `Appointment`: no overlap of (`patient_id`, `slot`) for CONFIRMED — **I5**
- `OutboxTask`: unique (`appointment_id`, `type`)
