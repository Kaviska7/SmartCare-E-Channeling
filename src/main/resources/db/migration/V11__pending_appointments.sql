ALTER TABLE appointment DROP CONSTRAINT ck_appointment_status;
ALTER TABLE appointment ADD CONSTRAINT ck_appointment_status CHECK(status IN ('PENDING','CONFIRMED','CHECKED_IN','CALLED','COMPLETED','CANCELLED','NO_SHOW'));
