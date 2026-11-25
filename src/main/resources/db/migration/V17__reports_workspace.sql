ALTER TABLE report_template ADD
    description NVARCHAR(500) NULL,
    visibility NVARCHAR(20) NOT NULL CONSTRAINT df_report_template_visibility DEFAULT 'PRIVATE',
    configuration_json NVARCHAR(MAX) NOT NULL CONSTRAINT df_report_template_configuration DEFAULT '{}';
GO

ALTER TABLE report_template ADD CONSTRAINT ck_report_template_visibility
    CHECK(visibility IN ('PRIVATE','TEAM','PUBLIC'));
ALTER TABLE report_template ADD CONSTRAINT ck_report_template_configuration
    CHECK(ISJSON(configuration_json)=1);

ALTER TABLE report_template DROP CONSTRAINT ck_report_template_fee;
DECLARE @fee_default_constraint SYSNAME;
DECLARE @drop_fee_default_sql NVARCHAR(500);
SELECT @fee_default_constraint=dc.name
FROM sys.default_constraints dc
JOIN sys.columns c
  ON c.object_id=dc.parent_object_id
 AND c.column_id=dc.parent_column_id
WHERE dc.parent_object_id=OBJECT_ID(N'dbo.report_template')
  AND c.name=N'fee_structure';
IF @fee_default_constraint IS NOT NULL
BEGIN
    SET @drop_fee_default_sql=N'ALTER TABLE dbo.report_template DROP CONSTRAINT '+QUOTENAME(@fee_default_constraint);
    EXEC sys.sp_executesql @drop_fee_default_sql;
END;
ALTER TABLE report_template DROP COLUMN fee_structure;

ALTER TABLE report_schedule ADD
    schedule_name NVARCHAR(120) NULL,
    timezone NVARCHAR(80) NOT NULL CONSTRAINT df_report_schedule_timezone DEFAULT 'Asia/Colombo',
    recurrence_json NVARCHAR(MAX) NOT NULL CONSTRAINT df_report_schedule_recurrence DEFAULT '{}',
    formats_json NVARCHAR(200) NOT NULL CONSTRAINT df_report_schedule_formats DEFAULT '["pdf"]',
    email_subject NVARCHAR(200) NULL,
    email_message NVARCHAR(1000) NULL,
    is_template_default BIT NOT NULL CONSTRAINT df_report_schedule_default DEFAULT 0;
GO

ALTER TABLE report_schedule DROP CONSTRAINT ck_report_schedule_format;
ALTER TABLE report_schedule ADD CONSTRAINT ck_report_schedule_format CHECK(export_format IN ('pdf','xlsx','csv'));
ALTER TABLE generated_report_file DROP CONSTRAINT ck_generated_report_format;
ALTER TABLE generated_report_file ADD CONSTRAINT ck_generated_report_format CHECK(export_format IN ('pdf','xlsx','csv'));

UPDATE report_schedule
SET schedule_name=CONCAT('Delivery for ',(SELECT name FROM report_template WHERE id=report_schedule.template_id)),
    formats_json=CONCAT('["',export_format,'"]')
WHERE schedule_name IS NULL;

ALTER TABLE report_schedule ALTER COLUMN schedule_name NVARCHAR(120) NOT NULL;
ALTER TABLE report_schedule ADD CONSTRAINT ck_report_schedule_recurrence CHECK(ISJSON(recurrence_json)=1);
ALTER TABLE report_schedule ADD CONSTRAINT ck_report_schedule_formats CHECK(ISJSON(formats_json)=1);
CREATE UNIQUE INDEX ux_report_schedule_template_default
    ON report_schedule(template_id,is_template_default) WHERE is_template_default=1;

CREATE TABLE generated_report (
    id BIGINT IDENTITY PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES app_user(id),
    template_id BIGINT NULL REFERENCES report_template(id),
    schedule_id BIGINT NULL REFERENCES report_schedule(id),
    report_name NVARCHAR(160) NOT NULL,
    source NVARCHAR(20) NOT NULL,
    criteria_json NVARCHAR(MAX) NOT NULL,
    result_json NVARCHAR(MAX) NOT NULL,
    status NVARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    created_at DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT ck_generated_report_source CHECK(source IN ('MANUAL','TEMPLATE','SCHEDULE')),
    CONSTRAINT ck_generated_report_status CHECK(status IN ('COMPLETED','FAILED')),
    CONSTRAINT ck_generated_report_criteria CHECK(ISJSON(criteria_json)=1),
    CONSTRAINT ck_generated_report_result CHECK(ISJSON(result_json)=1)
);
CREATE INDEX ix_generated_report_owner_created ON generated_report(owner_user_id,created_at DESC);

ALTER TABLE generated_report_file ADD report_id BIGINT NULL REFERENCES generated_report(id);
