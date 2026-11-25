DECLARE @unique_name sysname;
DECLARE @sql nvarchar(500);
SELECT TOP 1 @unique_name=name FROM sys.key_constraints WHERE parent_object_id=OBJECT_ID(N'dbo.doctor') AND type='UQ';
IF @unique_name IS NOT NULL
BEGIN
    SET @sql = N'ALTER TABLE dbo.doctor DROP CONSTRAINT ' + QUOTENAME(@unique_name);
    EXEC sp_executesql @sql;
END;
GO
CREATE UNIQUE INDEX uq_doctor_user_not_null ON doctor(user_id) WHERE user_id IS NOT NULL;
