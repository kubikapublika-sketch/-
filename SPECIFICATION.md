# ProControl: agreed functional specification

## Daily records
- One record per calendar date, with clean product tonnes, scrap tonnes and optional note.
- Dates may be entered retroactively, including dates in closed periods when archive editing is enabled.
- A duplicate date must be rejected with a clear warning; never silently overwrite.
- Existing records are edited/deleted from a three-dot menu.
- Days without records remain calendar days; a zero-valued record is distinct from a missing record.

## Calculation periods
- Periods are arbitrary date ranges, not calendar months.
- A period is closed manually on the current date, even if no production records exist.
- Closing stores the period's exact start/end dates and its records, then the next period starts the following calendar day.
- Missing days are included in calendar-day averages.
- Editing an archived record changes only that record inside its original period. Period boundaries and later periods never move.

## Archive and rates
- Archive shows periods, records, clean tonnes, scrap tonnes, earnings and calendar-day averages.
- Earnings are always recalculated locally from the phone's current tariff settings.
- Tariffs and earnings are never included in export/import payloads.
- Archive editing is disabled by default and enabled/disabled in Settings.

## Transfer
- Export is a compact text code, copy/paste friendly for SMS.
- Payload includes dates, clean/scrap tonnes, notes and period boundaries only.
- Compressed payload has version marker and checksum.
- Import previews new record count, merges without overwriting existing dates, and rejects corrupt/unsupported codes.

## Charts
- Combined time-series chart with money, clean tonnes and scrap tonnes on a left-to-right date axis.
- Separate charts and period comparisons; average tonnes and earnings per calendar day.
- Independent scales for unlike units; pinch zoom, horizontal pan, reset and tap-to-inspect date values.
