# MB-596 product journey metrics

The ADMIN-only `GET /api/v1/admin/product-journey-metrics` projection accepts an explicit half-open UTC window capped at 366 days. Identity composes its own registration cohort with bounded aggregate responses from Care and Consultation over REST. Every source reports a version and as-of instant; a failed dependency produces `UNAVAILABLE` stages with null counts rather than inferred zeroes.

The projection contains platform-wide counts only. It has no cohort breakdown, account identifier, PHQ-9/GAD-7 score or band, assessment answer, Journal or emotion content, AI analysis, consultation participant, chat, private note, or user-level outcome. Rates are limited to facts with a defensible denominator: current-active status within the registered cohort and confirmed/completed state within the requested-appointment cohort. The response explicitly labels all metrics as descriptive product activity, not clinical effectiveness or causation.

Care owns completed screening submissions, generated Support Guides, and paid SupportPlan activations. No authoritative Support Guide open fact exists, so that stage remains explicitly unavailable. Consultation counts requests created in the selected window and uses immutable status history to determine whether the same request cohort ever reached confirmed or completed.

Focused verification covers owner aggregate queries, ADMIN authorization, bounded windows, dependency degradation, source/contract versions, null unavailable counts, and absence of sensitive response fields. The frontend consumes the same Identity OpenAPI projection through an ADMIN-authenticated same-origin BFF.
