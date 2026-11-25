ALTER TABLE appointment_review ADD updated_at DATETIME2 NOT NULL CONSTRAINT df_appointment_review_updated DEFAULT SYSUTCDATETIME();
ALTER TABLE appointment_review ADD deleted_at DATETIME2 NULL;
ALTER TABLE appointment_review ADD deleted_by_user_id BIGINT NULL REFERENCES app_user(id);
