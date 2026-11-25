-- One-time cleanup for this workspace's seeded and smoke-test records.
-- Refuse to run if a non-demo account, doctor, or specialty exists.
SET XACT_ABORT ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
IF EXISTS (
    SELECT 1 FROM app_user
    WHERE email NOT IN (
        N'admin@smartcare.local', N'reception@smartcare.local',
        N'manager@smartcare.local', N'doctor@smartcare.local'
    ) AND email NOT LIKE N'smoke-%@example.test'
) THROW 51000, 'Non-demo account found; cleanup cancelled.', 1;
IF EXISTS (
    SELECT 1 FROM doctor
    WHERE display_name <> N'Dr. Demo Perera'
      AND display_name NOT LIKE N'Smoke Test Doctor %'
) THROW 51001, 'Non-demo doctor found; cleanup cancelled.', 1;
IF EXISTS (
    SELECT 1 FROM specialty
    WHERE name NOT IN (N'Cardiology', N'General Medicine', N'Dermatology', N'Paediatrics')
) THROW 51002, 'Non-demo specialty found; cleanup cancelled.', 1;
IF EXISTS (SELECT 1 FROM audit_event WHERE actor_user_id IS NULL)
    THROW 51003, 'Unattributed audit records found; cleanup cancelled.', 1;

BEGIN TRANSACTION;
DELETE FROM notification;
DELETE FROM audit_event;
DELETE FROM demo_payment;
DELETE FROM queue_token;
DELETE FROM appointment_review;
DELETE FROM appointment;
DELETE FROM slot;
DELETE FROM schedule_template;
DELETE FROM emergency_contact;
DELETE FROM patient;
DELETE FROM doctor_leave;
DELETE FROM doctor;
DELETE FROM specialty;
DELETE FROM app_user;
COMMIT TRANSACTION;

SELECT 'Demo data removed; application schema retained.' AS result;
