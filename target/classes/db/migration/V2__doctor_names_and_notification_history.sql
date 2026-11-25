ALTER TABLE doctor ADD display_name NVARCHAR(160) NOT NULL CONSTRAINT df_doctor_display_name DEFAULT 'Doctor';
GO
UPDATE d SET display_name=u.full_name FROM doctor d JOIN app_user u ON u.id=d.user_id;
GO
ALTER TABLE notification DROP CONSTRAINT uq_notification_due;
