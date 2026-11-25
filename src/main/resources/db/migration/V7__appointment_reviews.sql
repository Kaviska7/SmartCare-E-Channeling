CREATE TABLE appointment_review (
    id BIGINT IDENTITY PRIMARY KEY,
    appointment_id BIGINT NOT NULL UNIQUE REFERENCES appointment(id),
    patient_id BIGINT NOT NULL REFERENCES patient(id),
    rating INT NOT NULL CONSTRAINT ck_review_rating CHECK(rating BETWEEN 1 AND 5),
    comment NVARCHAR(500),
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
);
