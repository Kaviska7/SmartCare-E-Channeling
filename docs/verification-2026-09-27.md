# SmartCare verification — 27–28 September 2026

The tested core workflows and report regressions pass. Confirmed defects in account deactivation, inactive doctor availability, report customization, and report scheduling were corrected. Coverage limits below remain; this is not a guarantee that every possible edge case is bug-free.

## Checks completed

| Area | Result |
| --- | --- |
| Backend automated tests | 87 passed; zero failures, errors, or skipped tests |
| React production build | Passed |
| Application startup | Passed with Java 17 and the local SQL Server profile |
| Database | All 17 Flyway migrations validated; all 20 application tables present |
| Current API workflow suite | 46 core assertions and 23 report regression assertions passed against the running application |
| Browser | Public doctor browsing, staff login, administrator appointments and reports rendered; no warning/error console messages observed during those checks |

The API suite exercises role restrictions, incorrect login portals, doctor and patient login, dated schedules, overlap rejection, leave and restoration, emergency contacts, pending bookings, administrator confirmation, duplicate booking/payment rejection, simulated payment/refund, doctor rescheduling, appointment alerts, read status, reminder cancellation, reception booking/check-in/call-next, consultation completion, review creation/update/removal, all four report types, PDF/XLSX/CSV exports, template ownership, paused delivery schedules, manual simulated delivery, retained file download/purge, report history, account deactivation, and inactive doctor availability.

Run the current suite with `./scripts/check-current-flows.ps1` after starting the local app. It deliberately creates local QA records. It does not send email/SMS or process real payments. The notification unit test prints an intentional ERROR when testing recovery from an alert-storage failure; the test passes.

## Fixed defects

1. **Deactivated accounts retained access through existing sessions.** Reproduced a staff account returning HTTP 200 from the queue after administrator deactivation. `CurrentUser` now checks `active=1` on each identity/role lookup and returns HTTP 401 for disabled or missing accounts. Added three regression tests. Live checks verify both staff and patient sessions lose access immediately.
2. **Inactive doctors still exposed available slots.** The public slots query did not join the doctor's active state. It now requires an active doctor. The live regression verifies zero returned slots after deactivation.
3. **Incomplete database health check.** The script only checked the original 15 tables and ignored multi-branch/report tables. It now verifies all 20 application tables.
4. **PowerShell startup documentation.** The Maven profile argument needed quoting; the unquoted README command failed with “Unknown lifecycle phase”. The quoted command was verified.

## Additional report fixes verified on 28 September

- **Grouping, ordering, columns, and metrics:** browser previews, generated history, saved-template reports, and configured PDF/XLSX/CSV exports now share one server-generated projection. Doctor grouping combines the doctor's branches; branch grouping combines its doctors. Specialty and department groups are supported. Group totals and previous-period growth are calculated per group. Zero net revenue is kept as zero instead of falling back to gross revenue. Excel exports use numeric cells for metrics.
- **Timezone scheduling:** schedule validation and due-time comparisons use each schedule's selected timezone. Recurrence advances in that same timezone. API responses preserve the entered wall time, and the editor displays the timezone explicitly. A UTC time one hour in the future is accepted and survives a save/read round trip. A regression test verifies future UTC schedules are not executed early.
- **Live preview:** report edits clear the previously generated selection so stale data is not shown as a current preview; superseded preview responses are ignored. Template previews now use their own grouping, settings, and dynamic dates. Line and pie selections have corresponding renderers. Prior stored reports retain a legacy preview fallback.
- **Export verification:** automated tests read generated PDF text and Excel cells, and inspect CSV contents, to confirm the selected grouped data is present. The live suite exercises all three configured export routes and removes its temporary report files afterward.

Run `./scripts/check-report-regressions.ps1` for the 23 focused live report checks. No real email/SMS is sent by these tests.
## Coverage limits and local test data

- Older `check-role-flows.ps1`, `check-new-role-flows.ps1`, and related scripts contain superseded assumptions such as weekly schedules, single-branch doctor fields, non-Gmail account creation, and immediately confirmed bookings. Use the new current-flow suite; the older scripts have not all been rewritten.
- Browser coverage on 27 September was a smoke test, not a click-through of every form on every role/device. On 28 September the browser connection timed out; reconnecting was rejected by the browser security policy. The final report UI build succeeded, but its visual layout and interaction changes could not be rechecked in the browser. There is no frontend automated test script in `frontend/package.json`.
- Automatic execution at an actual future schedule time, multi-user concurrency/load, all PDF/Excel formatting, and real external delivery were not verified. Report delivery and card payments are intentionally simulated.
- QA runs `1790519355079`, `1790519386095`, `1790519570671`, and `1790519688105` created isolated doctors, accounts, branches, and visits. All those accounts, doctor profiles, branches, and schedules were deactivated afterward; available QA slots were blocked. Audit/visit history was retained and can appear in historical reports. Existing non-QA accounts and appointments were not edited by the workflow suite.
- This workspace is not a Git repository, so no Git diff or commit was available.

## Changed files

- `src/main/java/lk/sunshine/smartcare/shared/CurrentUser.java`
- `src/main/java/lk/sunshine/smartcare/features/doctor/DoctorRepository.java`
- `src/test/java/lk/sunshine/smartcare/shared/CurrentUserTest.java`
- `scripts/check-current-flows.ps1`
- `scripts/check-db.ps1`
- `README.md`
- This verification report

The report frontend was updated and rebuilt. Additional changed files include `frontend/src/features/Report.jsx`, report service/repository/exporter/controller classes, the new `ReportPresentation.java`, report regression tests, and `scripts/check-report-regressions.ps1`.

