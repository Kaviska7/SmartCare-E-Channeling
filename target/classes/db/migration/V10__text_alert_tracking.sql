ALTER TABLE notification ADD text_handled_at DATETIME2 NULL;
GO
-- Do not export old, already-visible inbox history when this feature is installed.
UPDATE notification SET text_handled_at=SYSUTCDATETIME() WHERE due_at IS NULL;
