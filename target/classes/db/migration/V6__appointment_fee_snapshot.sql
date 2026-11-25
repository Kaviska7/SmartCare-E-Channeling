ALTER TABLE appointment ADD fee_at_booking DECIMAL(12,2) NULL;
GO

UPDATE a SET fee_at_booking=d.fee
FROM appointment a
JOIN slot s ON s.id=a.slot_id
JOIN doctor d ON d.id=s.doctor_id;
GO

ALTER TABLE appointment ALTER COLUMN fee_at_booking DECIMAL(12,2) NOT NULL;
