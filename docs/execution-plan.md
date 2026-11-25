# SmartCare E-Channeling — corrected execution plan

## Stack and local setup

Use Java 17, Spring Boot, Maven, React, and **Microsoft SQL Server on localhost:1433**. The application database is `smartcare`; the local connection is configured in the ignored `config/application-local.properties` file. Flyway manages the SQL Server schema. Open the Maven project in IntelliJ and run the **SmartCare** configuration. PostgreSQL is not part of this project.

## Access and date rules

- Visitors can choose a hospital branch and view its active doctors, specialties, and remaining available times without an account. Booking requires patient login.
- Patients can view only their own profile, appointments, and messages. An appointment whose start time has passed remains in read-only history. Its reschedule, cancel, and demo-payment controls disappear, and the Java API rejects those changes even if called directly.
- A doctor has a separate staff login. Their dashboard loads only their own profile, recurring schedules, leave dates, assigned appointment history and basic patient details, queue, messages, and account settings. The appointment page combines all their visits with today's queue. They can reschedule or cancel their own future confirmed visits and complete or mark a called token no-show only in their own queue. They cannot edit another doctor's appointments, profile, schedule, or leave.
- Reception can search registered patients by name, NIC, or phone and book an available slot for them. They can view today's appointments, find a booking by ID, name, or NIC, check in, issue tokens, call the next token, and complete or mark a called consultation no-show.
- Managers can monitor the read-only queue and view or export operational reports. Administrators can see every appointment, including future patient bookings and payment updates, and can create patient, staff, and doctor logins, see staff grouped by role, manage branches and specialties, perform reception actions, and see reports.
- Patient sign-in and staff/doctor sign-in are separate. The login handler rejects an account submitted through the wrong portal, and all API actions still enforce their role and record owner.
- All signed-in users can edit their own account details and change their password. Passwords stay hashed in the database.

## Delivery order

1. Keep the six feature folders and their named owners: patient, doctor, appointment, queue, combined feedback and notification, and report. Maintain shared authentication and database code separately. Feedback controllers, services, repositories, and tests belong to the notification feature folder; appointment booking and status workflows remain in the appointment folder.
2. Maintain SQL Server migrations and a single IntelliJ run configuration. Verify the connection with `scripts/check-db.ps1` and the app with `scripts/check-app.ps1`.
3. Complete public doctor discovery, patient registration, separate staff logins, and role-specific screens.
4. Keep booking and rescheduling transactional so one slot cannot be claimed twice. Save the displayed consultation fee on each appointment when booked, so later doctor fee changes do not alter its history or demo receipt. Preserve past and cancelled appointments as history; show edit actions only for future confirmed appointments.
5. Complete patient feedback, administrator moderation, in-app notifications, reception queue, and manager reports. Save the payment method at booking. The demo-card screen displays prefilled test details and records the payment only after Submit; no card details are sent to Java or stored, and no real money is collected. Show method and status in the admin panel.
6. Test every role and the full patient-to-reception journey. Use `scripts/check-role-flows.ps1` and `scripts/check-queue-flow.ps1` with explicit local dummy accounts. Rebuild the React assets and Maven application before the IntelliJ demonstration.

## Still to decide before final submission

Patients can leave one 1–5 star review after each completed visit. The public doctor list shows the average and review count; cancelled and no-show visits cannot be reviewed. Doctors can record a future leave date if no appointments are booked that day; open times are blocked and new slots are not generated for it. The pasted proposal plan also asks for a 24-hour cancellation cutoff and versioned `/api/v1` endpoints. Those are **not claimed as implemented** by the current app. The current app uses Spring Security sessions and a labelled demo-payment choice, consistent with the simpler project build already selected.
