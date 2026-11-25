CREATE TABLE report_template (
    id BIGINT IDENTITY PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
    name NVARCHAR(120) NOT NULL,
    report_type NVARCHAR(40) NOT NULL,
    date_mode NVARCHAR(20) NOT NULL DEFAULT 'FIXED',
    from_date DATE NULL,
    to_date DATE NULL,
    doctor_id BIGINT NULL REFERENCES doctor(id),
    specialty_id BIGINT NULL REFERENCES specialty(id),
    department NVARCHAR(100) NULL,
    branch_id BIGINT NULL REFERENCES branch(id),
    fee_structure NVARCHAR(30) NOT NULL DEFAULT 'ALL',
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT uq_report_template_owner_name UNIQUE(owner_user_id,name),
    CONSTRAINT ck_report_template_type CHECK(report_type IN ('summary','doctor_revenue','gross_revenue','refund_cancellation')),
    CONSTRAINT ck_report_template_date_mode CHECK(date_mode IN ('FIXED','PREVIOUS_PERIOD')),
    CONSTRAINT ck_report_template_dates CHECK((date_mode='FIXED' AND from_date IS NOT NULL AND to_date IS NOT NULL) OR date_mode='PREVIOUS_PERIOD'),
    CONSTRAINT ck_report_template_fee CHECK(fee_structure IN ('ALL','DEMO_CARD','PAY_AT_HOSPITAL'))
);

CREATE TABLE report_schedule (
    id BIGINT IDENTITY PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES report_template(id),
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
    frequency NVARCHAR(20) NOT NULL,
    delivery_channel NVARCHAR(10) NOT NULL DEFAULT 'EMAIL',
    recipients NVARCHAR(2000) NOT NULL,
    export_format NVARCHAR(10) NOT NULL DEFAULT 'pdf',
    active BIT NOT NULL DEFAULT 1,
    next_run_at DATETIME2 NOT NULL,
    last_run_at DATETIME2 NULL,
    last_status NVARCHAR(20) NULL,
    last_error NVARCHAR(500) NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_report_schedule_frequency CHECK(frequency IN ('DAILY','WEEKLY','MONTHLY')),
    CONSTRAINT ck_report_schedule_channel CHECK(delivery_channel IN ('EMAIL','SMS')),
    CONSTRAINT ck_report_schedule_format CHECK(export_format IN ('pdf','xlsx'))
);
CREATE INDEX ix_report_schedule_due ON report_schedule(active,next_run_at);

CREATE TABLE generated_report_file (
    id BIGINT IDENTITY PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
    template_id BIGINT NULL REFERENCES report_template(id),
    schedule_id BIGINT NULL REFERENCES report_schedule(id),
    report_type NVARCHAR(40) NOT NULL,
    export_format NVARCHAR(10) NOT NULL,
    file_name NVARCHAR(255) NOT NULL,
    storage_path NVARCHAR(700) NOT NULL,
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    expires_at DATETIME2 NOT NULL,
    CONSTRAINT ck_generated_report_format CHECK(export_format IN ('pdf','xlsx'))
);
CREATE INDEX ix_generated_report_expiry ON generated_report_file(expires_at);
