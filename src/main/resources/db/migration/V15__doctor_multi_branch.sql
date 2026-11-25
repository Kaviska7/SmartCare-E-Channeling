CREATE TABLE doctor_branch (
    id BIGINT IDENTITY PRIMARY KEY,
    doctor_id BIGINT NOT NULL REFERENCES doctor(id),
    branch_id BIGINT NOT NULL REFERENCES branch(id),
    room NVARCHAR(30) NULL,
    fee DECIMAL(12,2) NOT NULL,
    active BIT NOT NULL CONSTRAINT df_doctor_branch_active DEFAULT 1,
    CONSTRAINT uq_doctor_branch UNIQUE(doctor_id,branch_id)
);

INSERT INTO doctor_branch(doctor_id,branch_id,room,fee)
SELECT id,branch_id,room,fee FROM doctor;

ALTER TABLE schedule_template ADD branch_id BIGINT NULL REFERENCES branch(id);
GO
UPDATE st SET branch_id=d.branch_id FROM schedule_template st JOIN doctor d ON d.id=st.doctor_id;
GO
ALTER TABLE schedule_template ALTER COLUMN branch_id BIGINT NOT NULL;

ALTER TABLE slot ADD branch_id BIGINT NULL REFERENCES branch(id);
GO
UPDATE sl SET branch_id=d.branch_id FROM slot sl JOIN doctor d ON d.id=sl.doctor_id;
GO
ALTER TABLE slot ALTER COLUMN branch_id BIGINT NOT NULL;

DECLARE @doctor_branch_fk sysname;
DECLARE @drop_doctor_branch_fk_sql nvarchar(500);
SELECT @doctor_branch_fk=fk.name
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id=fk.object_id
JOIN sys.columns c ON c.object_id=fkc.parent_object_id AND c.column_id=fkc.parent_column_id
WHERE fk.parent_object_id=OBJECT_ID(N'dbo.doctor') AND c.name=N'branch_id';
IF @doctor_branch_fk IS NOT NULL
BEGIN
    SET @drop_doctor_branch_fk_sql=N'ALTER TABLE doctor DROP CONSTRAINT '+QUOTENAME(@doctor_branch_fk);
    EXEC sp_executesql @drop_doctor_branch_fk_sql;
END;
GO
ALTER TABLE doctor DROP COLUMN branch_id,room,fee;

CREATE INDEX ix_doctor_branch_branch ON doctor_branch(branch_id,doctor_id);
CREATE INDEX ix_schedule_branch ON schedule_template(branch_id,doctor_id);
CREATE INDEX ix_slot_branch_time ON slot(branch_id,starts_at,status);
