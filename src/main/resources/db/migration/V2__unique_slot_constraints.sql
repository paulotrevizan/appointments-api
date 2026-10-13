-- D-002: fixed 30-minute slots on the grid, so two bookings overlap only when they start at the
-- same instant. Partial unique indexes enforce I1, I2 and I5; unlike exclusion constraints they
-- check before inserting, so concurrent conflicting inserts never deadlock.
ALTER TABLE appointment
    DROP CONSTRAINT appointment_doctor_no_overlap,
    DROP CONSTRAINT appointment_room_no_overlap,
    DROP CONSTRAINT appointment_patient_no_overlap,
    DROP COLUMN slot,
    ADD CONSTRAINT appointment_fixed_duration_check CHECK (end_at - start_at = interval '30 minutes'),
    ADD CONSTRAINT appointment_grid_check CHECK (extract(epoch FROM start_at) % 1800 = 0);

-- I1
CREATE UNIQUE INDEX appointment_doctor_no_overlap
    ON appointment (doctor_id, start_at) WHERE status = 'CONFIRMED';
-- I2
CREATE UNIQUE INDEX appointment_room_no_overlap
    ON appointment (room_id, start_at) WHERE status = 'CONFIRMED';
-- I5
CREATE UNIQUE INDEX appointment_patient_no_overlap
    ON appointment (patient_id, start_at) WHERE status = 'CONFIRMED';

DROP EXTENSION btree_gist;
