# 3.5.3 View Assessment History

## Function Trigger

The function is triggered when an authenticated User opens `/assessments` and reaches **“Các lần đánh giá gần đây”**, or selects **“Xem tất cả lịch sử”** to request the complete history view. The target system must validate the User's authenticated session and derive the history owner from trusted server-side session data before returning any records.

## Function Description

- **Actors / Roles:** Authenticated User
- **Purpose:** Allows a User to review a chronological list of their own completed psychological assessments and select an entry for the separate View Assessment Results function.
- **Interface:** Recent-history heading, non-diagnostic notice, history table/list, completion date, instrument, score, screening-level badge, result action, **“Xem tất cả lịch sử”** action, loading state, empty state, error state, and pagination or progressive loading for the complete history.
- **Data Processing:**
  - Identify the current User from the trusted authenticated session.
  - Retrieve only assessment submissions visible in that User's personal history.
  - Order entries by authoritative submission time from newest to oldest, using a stable tie-breaker.
  - Return a bounded recent subset for the `/assessments` summary and a server-paginated result set for the complete history view.
  - Format dates according to the selected locale/timezone and display instrument-specific score maxima and screening labels.
  - Exclude raw answers, safety details, internal scoring logic, consent data, and other sensitive system fields from the history list.
  - Transfer the selected result ID to the separate View Assessment Results function, where ownership must be checked again.

> **Historical snapshot — superseded by Sprint 2 runtime evidence.** The note
> and flow below describe the pre-Sprint-2 frontend baseline and are not a
> current-runtime claim. Current behavior is defined by the Care OpenAPI
> contract, Care/frontend implementation, and Sprint 2 runbook: owned history
> is retrieved through the frontend boundary, has explicit states, and reopens
> a server-owned result.

## Screen Layout

The supplied reference screenshot defines the assessment-history area:

- The section label displays **“LỊCH SỬ”** and the heading **“Các lần đánh giá gần đây”**.
- A notice states **“Điểm số giúp theo dõi xu hướng, không phải chẩn đoán.”**
- The table displays **“NGÀY LÀM”**, **“BÀI TEST”**, **“ĐIỂM SỐ”**, and **“KẾT QUẢ”** columns plus a result action.
- Each row displays the localized completion date/day, instrument name, score with the correct instrument maximum, and screening-level badge.
- Selecting the arrow transfers control to the separate View Assessment Results function.
- **“Xem tất cả lịch sử”** expands or navigates to the complete paginated history without changing any assessment data.
- When no records exist, the history container displays MSG-038 instead of an empty table.
- Loading and generic error states preserve the page structure and disclose no other User's assessment information.

## Function Details

### Data

| Field | Type | Source | Display Rule |
|---|---|---|---|
| Assessment result ID | opaque identifier | Stored assessment submission | Do not display the identifier; use it only to open the separately authorized result view. |
| Completion date/time | timestamp | Stored assessment submission | Format using the User's selected locale and timezone; order by the authoritative stored instant. |
| Instrument | enum/string | Stored questionnaire definition | Display the reviewed short instrument name, such as PHQ-9 or GAD-7. |
| Total score | non-negative integer | Stored server-computed result | Display the immutable authoritative score; do not calculate or alter it in the browser. |
| Maximum score | non-negative integer | Stored questionnaire/scoring definition | Display the maximum for the exact instrument/version; PHQ-9 uses 27 and GAD-7 uses 21 in the current approved baseline. |
| Screening level | enum | Stored server-computed result | Display the approved localized label and badge; do not present it as diagnosis. |
| Submission visibility/status | enum | Stored assessment submission and approved visibility policy | Include only records eligible for User history; do not show voided or otherwise excluded records as authoritative current results. |
| Page size | positive bounded integer | Server configuration/request limit | Use a server-approved limit; do not allow an unbounded history query. |
| Pagination cursor | opaque string or unavailable | Server-generated history response | Treat as opaque, scope it to the session User/query, and do not infer ownership from it. |
| Has more results | boolean | Server-derived | Control pagination or progressive loading without guessing from the current row count. |

### System Data

- Authenticated User ID obtained from the trusted server session.
- Effective authorization result.
- Assessment result IDs and immutable submission timestamps.
- Questionnaire definition/version, instrument, scoring version, authoritative score, maximum score, and screening level.
- Approved history visibility/status decision.
- Server-approved page size, opaque pagination cursor, stable sort/tie-breaker values, and has-more indicator.
- Non-sensitive correlation and access-log metadata; raw answers, safety details, and sensitive assessment content are excluded from logs and list responses.

### Validation & Business Rules

| Code | Rule Definition |
|---|---|
| BR-06 | Assessment scores and levels are screening information and must not be presented as diagnosis. |
| BR-07 | A completed PHQ-9 result is immutable. |
| BR-23 | Mock assessment history must not appear in production as real User data. |
| BR-25 | Effective roles and permissions must come from trusted server-side authorization data. |
| BR-41 | History scores and maxima must correspond to the approved questionnaire/scoring version and authoritative server result. |
| BR-42 | Opening a history entry must transfer to a result view that rechecks ownership against the trusted session. |
| BR-44 | An authenticated User may view only their own assessment history, scoped by the trusted server session identity. |

### Validation

| Situation | Message | Handling |
|---|---|---|
| No valid authenticated session | — | Redirect to Sign In and return no assessment-history data. |
| User has no eligible assessment results | MSG-038 | Display the approved empty state and keep the assessment catalogue available. |
| Recent-history or full-history API fails | MSG-030 | Show the generic error state and allow a safe retry. |
| Pagination cursor is missing, malformed, expired, or belongs to another query/User | MSG-030 | Reject the cursor, disclose no records, and allow restart from the first page. |
| One history entry has incomplete authoritative metadata | MSG-030 | Do not guess score maximum, instrument, or screening label; omit the invalid entry or mark the section unavailable according to the approved response contract. |
| Duplicate records are returned across pages | — | Deduplicate by assessment result ID in the display and treat the server pagination response as inconsistent; do not show duplicate history entries. |
| User selects an entry that was removed or became unavailable | MSG-030 | Keep the history visible, do not open stale details, and allow a safe refresh. |
| Authorization or ownership check fails | — | Return no history data and do not reveal whether another User has assessment records. |

## Functionalities

### Normal Flow

1. The authenticated User opens `/assessments`.
2. The system validates the User's session and effective authorization under BR-25.
3. The system derives the history owner from the trusted server session under BR-44.
4. The system requests a bounded recent set of visible assessment submissions for that User.
5. The history owner orders results by authoritative submission time from newest to oldest with a stable tie-breaker.
6. The system returns result IDs, completion timestamps, instruments, authoritative scores, instrument-specific maxima, screening levels, and pagination metadata under BR-41.
7. The interface formats the dates and labels for the selected locale/timezone and displays **“Các lần đánh giá gần đây”** with the non-diagnostic notice required by BR-06.
8. When records exist, the interface displays each result once in the history table.
9. When no records exist, the interface displays MSG-038 and keeps assessment-start actions available.
10. The User may select **“Xem tất cả lịch sử”** to request the first page of the complete history.
11. The system uses server-approved pagination to load additional pages without mixing another User's records or duplicating entries.
12. When the User selects a row action, the system transfers the selected result ID to the separate View Assessment Results function, which revalidates ownership under BR-42.
13. The User may leave the history view; no assessment data is changed.

### Historical Frontend Flow (pre-Sprint-2; superseded)

1. `/assessments` defines a local `history` array containing exactly three demonstration records.
2. The page maps the array directly into three rows with fixed dates, instruments, scores, levels, and presentation tones.
3. Every score denominator is hard-coded as `/27`, including the GAD-7 row, although the current approved GAD-7 maximum is 21.
4. The row arrow is a button with an accessibility label but no result navigation or click behavior.
5. **“Xem tất cả lịch sử”** is a button without a handler, route, pagination state, or expanded-history behavior.
6. No authenticated session guard, backend request, ownership filter, stable server ordering, loading state, empty state, retry state, or persistent history source is implemented.
7. The three demonstration records are always shown and do not change after the client-side anonymous PHQ-9 flow completes.

### Abnormal Cases

- Missing or expired session → redirect to Sign In and return no history data.
- Client-supplied or mismatched User ID → ignore it and scope the query with the trusted session under BR-44.
- No eligible history entries → display MSG-038 without treating the condition as an error.
- History API failure → display MSG-030 and provide safe retry behavior.
- Invalid or cross-User pagination cursor → reject it, reveal no records, and allow restart from the first page.
- Duplicate entry across pages → show the entry once and require a safe refresh when the response is inconsistent.
- Missing score maximum or questionnaire metadata → do not hard-code or guess values; omit or mark the affected data unavailable.
- Selected result became unavailable → display MSG-030, preserve the history list, and allow refresh.
- Authorization failure → disclose no history records or record-count information.

## Post-Conditions

- **PC-01:** The authenticated User can view only assessment-history entries belonging to their own account.
- **PC-02:** Displayed dates, instruments, scores, maxima, and screening levels match authoritative stored submissions and questionnaire/scoring versions.
- **PC-03:** The recent subset and complete paginated history use stable newest-first ordering without duplicate display entries.
- **PC-04:** Viewing, paging, retrying, or leaving assessment history changes no assessment, answer, score, safety, preference, or account data.
- **PC-05:** On authentication, authorization, ownership, or loading failure, no protected history data or record-count information is disclosed.

## BR and MSG Registry Reference

All codes below are defined once in the MentalBridge Master Registry:

| Code | Type | Usage in this function |
|---|---|---|
| BR-06 | Business Rule | Requires non-diagnostic history wording. |
| BR-07 | Business Rule | Preserves completed PHQ-9 result immutability. |
| BR-23 | Business Rule | Prevents static prototype history from appearing as production truth. |
| BR-25 | Business Rule | Requires trusted server-side authorization. |
| BR-41 | Business Rule | Requires authoritative instrument-specific score and maximum display. |
| BR-42 | Business Rule | Requires ownership revalidation when opening a result from history. |
| BR-44 | Business Rule | Restricts history retrieval to the owning authenticated User. |
| MSG-030 | Error | Handles generic history-loading, pagination, and stale-entry failures. |
| MSG-038 | Empty state | Displays the approved no-history state. |
