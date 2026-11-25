CREATE TABLE doctor_leave (
    id BIGINT IDENTITY PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor(id),
    leave_date DATE NOT NULL,
    reason NVARCHAR(200),
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT uq_doctor_leave_day UNIQUE(doctor_id,leave_date)
);
