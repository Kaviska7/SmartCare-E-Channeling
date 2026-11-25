# UC-06: Manage Revenue Reports & Automated Schedules

## Use case header

| Field | Description |
|---|---|
| **System** | Smart E-Channeling System |
| **Number** | UC-06 |
| **Name** | Manage Revenue Reports & Automated Schedules |
| **Summary** | Hospital administrators and operations managers query financial and operational data, save reusable report criteria, maintain automated delivery schedules, view charts, export reports, and purge temporary generated files. |
| **Priority** | Medium |
| **Primary actors** | Hospital Administrator; Operations Manager |
| **Supporting actors** | System / Database; Email/SMS Service |
| **Trigger** | An authorised actor opens **Reports** to generate a report, manage a saved template, or manage an automated delivery schedule. |
| **Preconditions** | The actor is authenticated as an administrator or manager. Appointment and financial records exist or the system can return a valid zero-result report. Supporting reference data is available. The delivery service is configured when real external delivery is enabled. |
| **Success postconditions** | Requested results are displayed or exported; valid template and schedule changes are persisted; scheduled executions are recorded; generated files have a seven-day expiry. Reporting does not change appointments or payments. |
| **Failure postconditions** | Invalid or unauthorised changes are not persisted. Existing templates and schedules remain unchanged. Execution and delivery failures are recorded without changing financial source data. |

## Main success scenario

1. The actor opens **Revenue report management**.
2. The system verifies that the actor has the Administrator or Manager role.
3. The system displays reporting filters, saved templates, delivery schedules, and unexpired generated files owned by the actor.
4. The actor selects a report type and enters a date range, doctor, specialty/department, branch, and fee structure as required.
5. The system confirms that the start date is not after the end date and that the range does not exceed five years.
6. The actor optionally enters a non-empty template name and chooses fixed dates or a rolling previous-period date mode.
7. The system confirms that the template name is unique within the actor's account and that all selected reference records exist.
8. The system saves the reusable report template.
9. The actor optionally creates an automated schedule by selecting the template, a daily, weekly, or monthly frequency, PDF or Excel format, delivery channel, recipient list, and first execution time.
10. The system validates every email address or international SMS number, checks that execution is not in the past, and verifies that the actor has fewer than ten other active schedules.
11. The system saves and activates the delivery schedule.
12. For an immediate request, the system queries appointments, doctors, branches, queue records, and demonstration-payment records using the selected criteria.
13. For a due schedule, the system resolves a rolling template to the previous day, week, or month before querying the same source data.
14. The system aggregates bookings, earnings, refunded amounts, net simulated revenue, fee totals, cancellation/refund rates, doctor revenue, and utilisation values required by the selected report.
15. The system displays the report using metrics, tables, and a revenue comparison chart.
16. The actor exports the displayed report as PDF or Excel, or generates a stored export from a saved template.
17. For a scheduled execution, the system creates the selected export, passes it to the Email/SMS delivery adapter, and records the execution result and next execution time.
18. The system stores generated files securely for repeat download and assigns an expiry seven days after generation.
19. A daily cleanup process removes expired files and their storage records.

## CRUD coverage

| Operation | Required behaviour |
|---|---|
| **Create** | Create a reusable report template; create an automated delivery schedule; generate a temporary PDF or Excel file. |
| **Read** | Query and view report results and charts; list or retrieve owned templates, schedules, and generated files; download PDF/Excel exports. |
| **Update** | Rename a template or change its criteria; change a schedule's template, recipients, frequency, channel, format, next run, or active state. All changed values are revalidated. |
| **Delete** | Delete an unused template; cancel an active schedule; manually purge a generated file; automatically purge expired files after seven days. |

## Extensions and alternate flows

| Step | Alternate or exception flow |
|---|---|
| **2a** | The actor is not an administrator or manager. The system denies access and makes no change. |
| **5a** | The start date is after the end date. The system rejects the request and asks the actor to correct the dates. |
| **5b** | The range exceeds five years. The system rejects the request to protect database and memory capacity. |
| **7a** | The template name is blank. The system requests a non-empty name. |
| **7b** | The actor already owns a template with the same name, ignoring letter case. The system rejects the duplicate and requests another name. |
| **7c** | A selected doctor, specialty/department, or branch no longer exists. The system rejects the template change and reloads valid choices. |
| **10a** | An email address is malformed or an SMS number is not in international `+` format. The system identifies the invalid recipient and does not save the schedule. |
| **10b** | The actor already owns ten active schedules. The system rejects activation until another schedule is cancelled. |
| **10c** | The first execution time is in the past. The system requests a future time. |
| **12a** | No records match the criteria. The system displays zero totals or an empty table without treating the result as an error. |
| **12b** | The database query or aggregation fails. The system reports the error and logs the incident; no template or source record is changed. |
| **16a** | PDF or Excel generation fails. The system displays an error, does not create a file record, and preserves the template for retry. |
| **17a** | Scheduled querying, export, or delivery fails. The system records `FAILED`, saves a concise error, advances the next run, and keeps the schedule available for correction. |
| **U1** | The actor edits a template. The system repeats steps 5 and 7 before persisting the update. Existing generated files are not rewritten. |
| **U2** | The actor edits recipients, frequency, channel, format, activation, or execution time. The system repeats step 10 before saving. |
| **D1** | The actor requests template deletion while an active schedule references it. The system blocks deletion and requires cancellation or reassignment first. |
| **D2** | The actor confirms deletion of a template with no active schedules. The system removes its inactive schedule definitions, preserves generated files until expiry, and deletes the template. |
| **D3** | The actor cancels an active schedule. The system marks it inactive, prevents future execution, and retains its last-run history. |
| **D4** | The actor confirms manual file purge. The system deletes the owned file and its metadata. An expired or missing file cannot be downloaded and may be regenerated. |

## Report definitions

- **Operations overview:** booking status totals, estimated fees, average waiting time, doctor utilisation, net simulated revenue, and cancellations found by booking date.
- **Doctor revenue by branch:** booking count, gross simulated demo-card payments, simulated refunds, and net simulated revenue for each doctor and appointment branch.
- **Total gross revenue:** gross submitted demo-card payments, refunded amount, net amount, and demo-payment count.
- **Refund and cancellation rates:** cancellations divided by matching bookings and refunds divided by submitted demo payments. A zero denominator produces 0%.
- Refund/cancellation reporting uses booking dates. Other report types use scheduled visit dates.
- Pay-at-hospital selections can be filtered and counted as booking/payment choices but do not contribute collected simulated revenue.

## Business rules

1. Template names are required and unique per owner account.
2. A fixed start date must be on or before its end date; a query may cover no more than five years.
3. Each account may own at most ten active automated schedules.
4. Supported frequencies are daily, weekly, and monthly; supported export formats are PDF and Excel.
5. Email recipients must use valid email syntax. SMS recipients must use international `+` format.
6. Templates, schedules, and files are owner-scoped. Only administrators and managers can manage them.
7. A template referenced by an active schedule cannot be deleted.
8. Temporary generated files expire after seven days and may be purged earlier by their owner.
9. Reports are read-only with respect to appointment, payment, doctor, and queue source records.

## Open issues and notes

1. The current application uses a simulated delivery adapter because no production SMTP or SMS provider credentials are configured. A real provider can implement the same delivery interface without changing UC-06.
2. Decide whether production email should include the file as an attachment or use an authenticated, time-limited download link.
3. Decide whether failed external deliveries should retry before the next scheduled period and when staff should be alerted.
4. Confirm whether hospital service fees and doctor consultation fees require separate persisted values rather than the current appointment fee snapshot and demonstration-payment totals.
5. Confirm whether executive recipients may be external addresses or must belong to registered staff accounts.

**Implementation references:** `Report.jsx` provides filtering, charts, template/schedule CRUD, and file management. `ReportController`, `ReportManagementService`, and `ReportManagementRepository` enforce role, ownership, validation, scheduling, storage, and audit rules. `ReportService` and `ReportRepository` calculate report results. PDF and XLSX exporters use the same saved criteria.
