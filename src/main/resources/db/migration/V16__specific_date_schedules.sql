ALTER TABLE schedule_template ADD schedule_date DATE NULL;
GO

UPDATE schedule_template SET active=0 WHERE schedule_date IS NULL;

UPDATE slot
SET status='BLOCKED',retired=1
WHERE starts_at>SYSDATETIME() AND status='AVAILABLE';

CREATE INDEX ix_schedule_specific_date
ON schedule_template(doctor_id,schedule_date,start_time,active);
