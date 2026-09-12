# 3.5.2 View Assessment Results

## Function Trigger

The function is triggered when an authenticated User selects the result action for one of their assessment-history entries, or when the separate Take Psychological Assessment function transfers control after a confirmed submission. The result identifier may select a record, but the target system must derive the User identity from the trusted authenticated session and verify ownership before returning any assessment data.

## Function Description

- **Actors / Roles:** Authenticated User
- **Purpose:** Allows a User to view one authoritative, immutable psychological assessment result, including its instrument, completion date, score, screening level, safe explanation, recommendations, and an eligible comparison with the User's previous result.
- **Interface:** Result modal or dedicated result view, close action, assessment summary card, score and screening-level badge, prior-result comparison, result explanation, recommendations, non-diagnostic notice, and immediate safety guidance when required.
- **Data Processing:**
  - Identify the current User from the trusted authenticated session.
  - Retrieve only the selected assessment submission owned by that User.
  - Retrieve the exact questionnaire and scoring versions recorded with the immutable submission.
  - Display the authoritative server-computed score and screening level without recalculating them in the browser.
  - Find the immediately previous eligible result for the same instrument and calculate a comparison only when the scoring versions are compatible.
  - Select reviewed explanation, recommendation, disclaimer, and safety-guidance content associated with the stored result and policy versions.
  - Return no raw answers unless a separate, explicitly authorized function requires them.

> **Historical snapshot — superseded by Sprint 2 runtime evidence.** The note
> and flow below describe the pre-Sprint-2 frontend baseline and are not a
> current-runtime claim. Current behavior is defined by the Care OpenAPI
> contract, Care/frontend implementation, and Sprint 2 runbook: results are
> server-owned, access is ownership-checked, and the browser does not score
> PHQ-9 or publish an obsolete hotline.

## Screen Layout

The supplied reference screenshot defines the target modal layout:

- The modal header displays **“Kết quả đánh giá”**, the completion date, and a close action.
- The summary card displays the instrument name, full questionnaire name, authoritative score, maximum score, and screening-level badge such as **“NHẸ”**.
- The comparison banner displays a safe same-instrument trend such as **“↓ Giảm 2 điểm so với lần trước”** only when an eligible previous result exists.
- **“Giải thích kết quả”** displays reviewed, non-diagnostic wording appropriate to the stored screening level.
- **“Khuyến nghị”** displays reviewed self-care or support recommendations associated with the result.
- When the stored safety outcome requires escalation, immediate safety guidance and verified support resources take priority over general recommendations.
- Closing the modal returns the User to the existing assessment catalogue/history state and does not change the result.
- Loading, unavailable-comparison, authorization-denied, not-found, and generic failure states must not expose another User's assessment information.

## Function Details

### Data

| Field | Type | Source | Display Rule |
|---|---|---|---|
| Assessment result ID | opaque identifier | Selected history entry or confirmed submission response | Use only to select a result; authorize ownership against the session User before returning data. |
| Instrument | enum/string | Stored assessment submission and questionnaire definition | Display the authoritative supported instrument name, such as PHQ-9 or GAD-7. |
| Questionnaire full name | localized string | Stored questionnaire definition | Display reviewed wording for the result's recorded locale/version, with an approved locale fallback when necessary. |
| Completion date/time | timestamp | Stored assessment submission | Format according to the User's selected locale and timezone; do not alter the stored instant. |
| Total score | non-negative integer | Stored server-computed result | Display the immutable authoritative score; never replace it with a browser calculation. |
| Maximum score | non-negative integer | Recorded questionnaire/scoring definition | Display the maximum applicable to the exact instrument/version; do not hard-code `/27` for every instrument. |
| Screening level | enum | Stored server-computed result | Display the reviewed localized label and styling; always describe it as screening, not diagnosis. |
| Result explanation | localized reviewed text | Approved content mapped to instrument, scoring version, and screening level | Display the approved explanation associated with the historical result; do not generate a clinical conclusion. |
| Recommendations | ordered list of reviewed items | Approved recommendation content/version | Display only recommendations valid for the stored screening/safety outcome and locale. |
| Previous eligible score | non-negative integer or unavailable | Previous owned result for the same compatible instrument/scoring basis | Do not use a result belonging to another instrument, User, or incompatible scoring version. |
| Score change | signed integer or unavailable | Server-derived comparison | Display decrease/increase and absolute difference without claiming causation or clinical improvement. Missing comparison data is not zero. |
| Safety outcome | internal enum/boolean and guidance reference | Stored deterministic safety evaluation | When active, display approved immediate guidance independently of the total score or trend. |
| Disclaimer | localized reviewed text | Approved platform content | State that the result supports screening and does not replace professional assessment or diagnosis. |

### System Data

- Authenticated User ID obtained from the trusted server session.
- Effective authorization result and selected assessment result ID.
- Immutable assessment submission, questionnaire definition/version, scoring version, score, screening level, and submission timestamp.
- Stored deterministic safety outcome and approved safety-guidance reference.
- Previous eligible assessment result ID, score, and compatibility decision when a comparison is available.
- Reviewed explanation, recommendation, disclaimer, and support-resource content versions.
- Non-sensitive correlation and access-audit metadata; raw assessment answers and sensitive safety content are excluded from logs.

### Validation & Business Rules

| Code | Rule Definition |
|---|---|
| BR-06 | Assessment results are screening information and must not be presented as diagnosis. |
| BR-07 | A completed PHQ-9 result is immutable. |
| BR-08 | A self-harm signal must activate the approved safety process independently of total score. |
| BR-21 | Hotline and support resources must be verified before publication. |
| BR-23 | Mock results must not appear in production as real User data. |
| BR-25 | Effective roles and permissions must come from trusted server-side authorization data. |
| BR-41 | The displayed score must be the authoritative score calculated for the approved questionnaire/scoring version. |
| BR-42 | An authenticated User may view only their own full assessment result, with ownership checked against the trusted session identity. |
| BR-43 | Score comparison requires the same User, instrument, and compatible questionnaire/scoring versions; missing comparison data is not zero. |

### Validation

| Situation | Message | Handling |
|---|---|---|
| No valid authenticated session | — | Redirect to Sign In and return no authenticated assessment result. |
| Result ID is missing or malformed | MSG-030 | Do not request or display a result; show the safe generic error state and allow return to history. |
| Result does not exist or is not owned by the session User | — | Return a non-disclosing not-found/denied response and display no assessment data. |
| Result API or required content cannot be loaded | MSG-030 | Show the generic error state and allow a safe retry or close action. |
| Questionnaire metadata is partially unavailable | MSG-030 | Do not guess the instrument maximum, screening label, or explanation; show the result as unavailable until authoritative metadata can be loaded. |
| No compatible previous result exists | — | Display an approved unavailable/no-comparison state and keep the current result visible. |
| Previous-result comparison service fails independently | — | Hide the numeric comparison or display it as unavailable; do not present stale comparison data as current. |
| Safety guidance is required but a non-critical recommendation dependency fails | — | Continue to show the authoritative result and approved local safety guidance; omit unavailable non-critical recommendations. |
| Support resource is unverified or unavailable | MSG-030 | Do not publish the contact as active; show the approved safe fallback guidance. |

## Functionalities

### Normal Flow

1. The authenticated User selects the result action for an assessment-history entry or arrives from a confirmed assessment submission.
2. The system validates the User's session and effective authorization under BR-25.
3. The system derives the User identity from the trusted session and treats the supplied result ID only as a resource selector under BR-42.
4. The system retrieves the immutable assessment submission only when it belongs to the current User.
5. The system retrieves the recorded questionnaire definition, scoring version, localized instrument metadata, and authoritative score under BR-41.
6. The system retrieves the stored screening level, deterministic safety outcome, and reviewed explanation/recommendation content.
7. The system searches for the immediately previous eligible result belonging to the same User and instrument.
8. The system verifies questionnaire/scoring compatibility and calculates the signed score difference under BR-43.
9. The interface displays **“Kết quả đánh giá”**, completion date, instrument, full name, score, maximum score, and screening-level badge.
10. When an eligible previous result exists, the interface displays the neutral score comparison without claiming diagnosis, causation, or clinical improvement.
11. The interface displays the reviewed explanation, recommendations, and non-diagnostic disclaimer required by BR-06.
12. When the stored safety outcome requires action, the interface prioritizes the approved synchronous safety guidance under BR-08 and shows only verified resources under BR-21.
13. The User selects the close action, and the system returns to the assessment history without changing any assessment data.

### Historical Frontend Flow (pre-Sprint-2; superseded)

1. `/assessments` renders three hard-coded history rows containing fixed dates, instrument names, scores, and screening labels.
2. Every history-row arrow is a client button with an accessibility label, but it has no `onClick` handler, link, modal state, or navigation behavior.
3. The supplied **“Kết quả đánh giá”** modal, score-comparison banner, explanation section, and recommendation list are not present in the current source.
4. `/assessment/anonymous` displays a separate PHQ-9 result immediately after the ninth answer and calculates the score and severity entirely in the browser.
5. The anonymous result screen always labels the instrument as PHQ-9, does not load an authenticated persisted submission, and does not display completion date or a compatible prior-result comparison.
6. Its hotline is a fixed demonstration value shown only when the client total exceeds 9; it does not use the stored independent safety outcome required by BR-08.
7. No result API, trusted ownership check, persistent result retrieval, reviewed explanation/recommendation source, or result access audit is implemented in the frontend flow.

### Abnormal Cases

- Missing or expired session → redirect to Sign In and return no authenticated result data.
- Client-supplied or mismatched User ID → ignore it and authorize ownership using the trusted session under BR-42.
- Result ID belongs to another User or does not exist → return a non-disclosing denial/not-found response and show no result details.
- Result API failure → display MSG-030 and provide safe retry and close behavior.
- Stored questionnaire/scoring metadata is unavailable → do not guess the maximum score, level, explanation, or recommendations.
- No compatible prior result → display no-comparison state instead of a zero-point difference under BR-43.
- Prior-result lookup failure → keep the current result visible and mark comparison unavailable.
- Safety-sensitive result → show approved safety guidance regardless of total score or favorable trend under BR-08.
- Unverified hotline/support resource → do not display it as active; use approved fallback guidance under BR-21.
- Close action → dismiss the result view and preserve the current assessment-history state without changing data.

## Post-Conditions

- **PC-01:** The authenticated User has viewed only an assessment result that belongs to their own account.
- **PC-02:** The displayed score, screening level, questionnaire/scoring provenance, and safety outcome match the immutable authoritative submission.
- **PC-03:** Any displayed comparison uses an eligible same-instrument result with compatible scoring provenance, or is explicitly shown as unavailable.
- **PC-04:** No assessment, answer, score, recommendation, safety outcome, or account data is changed by this view-only function.
- **PC-05:** On authentication, authorization, ownership, or loading failure, no protected assessment result is disclosed.

## BR and MSG Registry Reference

All codes below are defined once in the MentalBridge Master Registry:

| Code | Type | Usage in this function |
|---|---|---|
| BR-06 | Business Rule | Requires non-diagnostic result wording. |
| BR-07 | Business Rule | Preserves completed PHQ-9 result immutability. |
| BR-08 | Business Rule | Requires safety guidance independent of score and trend. |
| BR-21 | Business Rule | Prevents publication of unverified support resources. |
| BR-23 | Business Rule | Prevents static prototype results from appearing as production truth. |
| BR-25 | Business Rule | Requires trusted server-side authorization. |
| BR-41 | Business Rule | Requires display of the authoritative server-scored result. |
| BR-42 | Business Rule | Restricts full result viewing to the owning authenticated User. |
| BR-43 | Business Rule | Controls valid same-instrument score comparison and missing-data handling. |
| MSG-030 | Error | Handles generic result-loading, metadata, and support-resource failures. |
