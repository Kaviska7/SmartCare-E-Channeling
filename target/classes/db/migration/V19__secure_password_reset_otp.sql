UPDATE password_reset_token SET used = 1 WHERE used = 0;

DECLARE @token_constraint NVARCHAR(128);
DECLARE @drop_constraint_sql NVARCHAR(MAX);
SELECT TOP 1 @token_constraint = kc.name
FROM sys.key_constraints kc
JOIN sys.tables t ON t.object_id = kc.parent_object_id
JOIN sys.index_columns ic ON ic.object_id = t.object_id AND ic.index_id = kc.unique_index_id
JOIN sys.columns c ON c.object_id = t.object_id AND c.column_id = ic.column_id
WHERE t.name = 'password_reset_token' AND c.name = 'token' AND kc.type = 'UQ';

IF @token_constraint IS NOT NULL
BEGIN
    SET @drop_constraint_sql = N'ALTER TABLE password_reset_token DROP CONSTRAINT '
        + QUOTENAME(@token_constraint) + N';';
    EXEC sys.sp_executesql @drop_constraint_sql;
END;

ALTER TABLE password_reset_token DROP COLUMN token;
ALTER TABLE password_reset_token ADD hashed_code NVARCHAR(100) NULL;
ALTER TABLE password_reset_token ADD attempt_count INT NOT NULL CONSTRAINT df_password_reset_attempt_count DEFAULT 0;

GO

UPDATE password_reset_token SET hashed_code = 'invalidated-migrated-token';
ALTER TABLE password_reset_token ALTER COLUMN hashed_code NVARCHAR(100) NOT NULL;

CREATE INDEX ix_password_reset_rate_limit ON password_reset_token(user_id, created_at);
CREATE INDEX ix_password_reset_active ON password_reset_token(user_id, used, expires_at, created_at DESC);
