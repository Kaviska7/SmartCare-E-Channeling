ALTER TABLE appointment DROP CONSTRAINT ck_appointment_status;
ALTER TABLE appointment ADD CONSTRAINT ck_appointment_status CHECK(status IN ('CONFIRMED','CHECKED_IN','CALLED','COMPLETED','CANCELLED','NO_SHOW'));
