-- Preserve queue history when a visit moves to another consultation time.
ALTER TABLE queue_token ADD branch_id BIGINT NULL REFERENCES branch(id);
ALTER TABLE queue_token ADD appointment_starts_at DATETIME2 NULL;
GO
UPDATE q SET branch_id=a.branch_id,appointment_starts_at=s.starts_at
FROM queue_token q JOIN appointment a ON a.id=q.appointment_id JOIN slot s ON s.id=a.slot_id;
ALTER TABLE queue_token ALTER COLUMN branch_id BIGINT NOT NULL;
ALTER TABLE queue_token ALTER COLUMN appointment_starts_at DATETIME2 NOT NULL;

DECLARE @constraint_name NVARCHAR(128),@statement NVARCHAR(MAX);
SELECT @constraint_name=kc.name FROM sys.key_constraints kc
JOIN sys.index_columns ic ON ic.object_id=kc.parent_object_id AND ic.index_id=kc.unique_index_id
JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id
WHERE kc.parent_object_id=OBJECT_ID('queue_token') AND kc.type='UQ' AND c.name='appointment_id';
IF @constraint_name IS NOT NULL
BEGIN
    SET @statement=N'ALTER TABLE queue_token DROP CONSTRAINT '+QUOTENAME(@constraint_name);
    EXEC sys.sp_executesql @statement;
END;
CREATE UNIQUE INDEX uq_queue_active_appointment ON queue_token(appointment_id) WHERE status IN ('WAITING','CALLED');
CREATE INDEX ix_queue_branch_date ON queue_token(queue_date,branch_id,doctor_id,status);
ALTER TABLE queue_token ADD CONSTRAINT ck_queue_status CHECK(status IN ('WAITING','CALLED','COMPLETED','SKIPPED','CANCELLED','RESCHEDULED'));
