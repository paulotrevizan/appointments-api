CREATE TABLE patient (
    id         uuid         NOT NULL,
    name       varchar(200) NOT NULL,
    email      varchar(254) NOT NULL,
    phone      varchar(30)  NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    CONSTRAINT patient_pkey PRIMARY KEY (id),
    CONSTRAINT patient_email_unique UNIQUE (email)
);

CREATE TABLE specialty (
    id   uuid         NOT NULL,
    code varchar(50)  NOT NULL,
    name varchar(100) NOT NULL,
    CONSTRAINT specialty_pkey PRIMARY KEY (id),
    CONSTRAINT specialty_code_unique UNIQUE (code)
);

CREATE TABLE doctor (
    id           uuid         NOT NULL,
    name         varchar(200) NOT NULL,
    email        varchar(254) NOT NULL,
    specialty_id uuid         NOT NULL,
    active       boolean      NOT NULL,
    CONSTRAINT doctor_pkey PRIMARY KEY (id),
    CONSTRAINT doctor_specialty_fkey FOREIGN KEY (specialty_id) REFERENCES specialty (id)
);

CREATE INDEX doctor_specialty_idx ON doctor (specialty_id);

CREATE TABLE room (
    id     uuid         NOT NULL,
    name   varchar(100) NOT NULL,
    active boolean      NOT NULL,
    CONSTRAINT room_pkey PRIMARY KEY (id),
    CONSTRAINT room_name_unique UNIQUE (name)
);

CREATE TABLE appointment (
    id           uuid        NOT NULL,
    patient_id   uuid        NOT NULL,
    specialty_id uuid        NOT NULL,
    doctor_id    uuid        NOT NULL,
    room_id      uuid        NOT NULL,
    start_at     timestamptz NOT NULL,
    end_at       timestamptz NOT NULL,
    status       varchar(20) NOT NULL,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT appointment_pkey PRIMARY KEY (id),
    CONSTRAINT appointment_patient_fkey FOREIGN KEY (patient_id) REFERENCES patient (id),
    CONSTRAINT appointment_specialty_fkey FOREIGN KEY (specialty_id) REFERENCES specialty (id),
    CONSTRAINT appointment_doctor_fkey FOREIGN KEY (doctor_id) REFERENCES doctor (id),
    CONSTRAINT appointment_room_fkey FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT appointment_status_check CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    CONSTRAINT appointment_end_after_start_check CHECK (end_at > start_at),
    -- D-007: fixed 30-minute slots on the :00 / :30 grid.
    CONSTRAINT appointment_fixed_duration_check CHECK (end_at - start_at = interval '30 minutes'),
    CONSTRAINT appointment_grid_check CHECK (extract(epoch FROM start_at) % 1800 = 0)
);

-- D-002: with fixed slots on the grid, two bookings overlap only when they start at the same
-- instant, so partial unique indexes enforce I1, I2 and I5. Unlike exclusion constraints they check
-- before inserting: concurrent conflicting inserts fail cleanly instead of deadlocking.
-- I1
CREATE UNIQUE INDEX appointment_doctor_no_overlap
    ON appointment (doctor_id, start_at) WHERE status = 'CONFIRMED';
-- I2
CREATE UNIQUE INDEX appointment_room_no_overlap
    ON appointment (room_id, start_at) WHERE status = 'CONFIRMED';
-- I5
CREATE UNIQUE INDEX appointment_patient_no_overlap
    ON appointment (patient_id, start_at) WHERE status = 'CONFIRMED';

CREATE INDEX appointment_start_at_id_idx ON appointment (start_at, id);

-- Timestamps use the database clock: the outbox worker claims and leases tasks with it (HLD 6.2).
CREATE TABLE outbox_task (
    id              uuid        NOT NULL,
    appointment_id  uuid        NOT NULL,
    type            varchar(30) NOT NULL,
    status          varchar(20) NOT NULL,
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_until    timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT outbox_task_pkey PRIMARY KEY (id),
    CONSTRAINT outbox_task_appointment_fkey FOREIGN KEY (appointment_id) REFERENCES appointment (id),
    CONSTRAINT outbox_task_type_check CHECK (type IN ('DOCTOR_CALENDAR', 'ROOM_RESERVATION', 'EMAIL')),
    CONSTRAINT outbox_task_status_check CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT outbox_task_appointment_type_unique UNIQUE (appointment_id, type)
);

CREATE INDEX outbox_task_status_next_attempt_idx ON outbox_task (status, next_attempt_at);
