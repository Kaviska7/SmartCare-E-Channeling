CREATE TABLE branch (
    id BIGINT IDENTITY PRIMARY KEY,
    name NVARCHAR(120) NOT NULL UNIQUE,
    address NVARCHAR(300),
    active BIT NOT NULL DEFAULT 1
);
INSERT INTO branch(name) VALUES(N'Main Branch');
GO
ALTER TABLE doctor ADD branch_id BIGINT NULL REFERENCES branch(id);
GO
UPDATE doctor SET branch_id=(SELECT TOP 1 id FROM branch ORDER BY id) WHERE branch_id IS NULL;
GO
ALTER TABLE doctor ALTER COLUMN branch_id BIGINT NOT NULL;
GO
ALTER TABLE appointment ADD branch_id BIGINT NULL REFERENCES branch(id);
GO
UPDATE a SET branch_id=d.branch_id FROM appointment a JOIN slot s ON s.id=a.slot_id JOIN doctor d ON d.id=s.doctor_id;
GO
ALTER TABLE appointment ALTER COLUMN branch_id BIGINT NOT NULL;
