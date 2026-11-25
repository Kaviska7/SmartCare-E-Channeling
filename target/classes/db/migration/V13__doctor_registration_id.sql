ALTER TABLE doctor ADD registration_id NVARCHAR(50) NULL;

EXEC(N'CREATE UNIQUE INDEX uq_doctor_registration_id
    ON doctor(registration_id)
    WHERE registration_id IS NOT NULL');
