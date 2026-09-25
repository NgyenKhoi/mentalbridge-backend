# ADR 0024: Global anonymous crisis support and reviewed national emergency contacts

- Status: Accepted
- Date: 2026-09-25
- Decision ID: `MB-GLOBAL-CRISIS-SUPPORT-001`
- Amends: [Reviewed Vietnam safety directory](0018-reviewed-vietnam-safety-directory.md)
- Policy: [Vietnam safety directory policy v1](../policies/vietnam-safety-directory-policy-v1.md)

## Context

The existing safety-directory flow requires deliberate coarse-area input before
it can return a reviewed directory record. That flow remains useful for
area-specific facilities, but it does not satisfy the product requirement for
immediate, anonymous access to nationwide emergency contacts from every web
page.

The earlier decisions correctly prohibited unverified numbers. They did not
approve the newly operational national emergency number `112`, and they kept
`115` outside the application until a separate content decision was made.

## Decision

The web root layout exposes a reusable crisis-support action on every route.
Opening it does not navigate, require authentication, request location, submit
a form, or call an API. It shows the following reviewed fixed contacts before
any optional area or self-help link:

| Service | Number | Approved wording | Official source reviewed 2026-09-25 |
| --- | --- | --- | --- |
| National emergency switchboard | `112` | `Tổng đài khẩn cấp quốc gia` and `24/7` | [Government announcement](https://xaydungchinhsach.chinhphu.vn/tong-dai-so-112-tiep-nhan-24-7-cac-thong-tin-ve-su-co-thien-tai-tham-hoa-119250902150528929.htm) |
| Medical emergency service | `115` | `Cấp cứu y tế` | [National telecommunications numbering plan](https://congbaocdn.chinhphu.vn/CongBaoCP/VanBan/2022/3/36965/40179-1-2022289-29004-vbhn-btttt.pdf) |

These numbers are nationally assigned emergency-service numbers, not
area-directory records and not a restored hotline catalogue. The reviewed web
content release is `mb-crisis-support-vi-vn-v1`. It keeps its source references
with the executable content and requires a new reviewed version if a number,
service label, or availability claim changes. The content owner must recheck
the official sources at least every 90 days while this release is active.

The `24/7` statement describes the government `112` service only. It does not
claim that MentalBridge or a MentalBridge specialist monitors or responds at
all times. A `tel:` link may open only after the user deliberately selects its
corresponding `Gọi` action; the application never starts a call automatically.

The panel may link to the existing public safety-directory flow and reviewed
self-help resources. It does not offer “Nhắn tin với chuyên gia trực” until the
product can truthfully establish on-duty availability. “Tìm cơ sở gần bạn” is
a navigation goal label only; the destination continues to require deliberate
coarse-area input and must not claim nearest-distance results.

## Accessibility and availability

- The global action and panel have explicit accessible names and visible focus.
- The panel traps focus while open, closes with `Escape`, restores focus to the
  trigger, and prevents background scrolling.
- Desktop uses an anchored drawer and mobile uses a full-width bottom sheet.
- The reviewed contacts are bundled with the web release so authentication,
  AI, messaging, Care, Content/Notification, Kafka, Redis, and WebSocket
  availability cannot suppress them.

## Compatibility and consequences

- ADR 0018 continues to govern all area-specific directory records, their
  provenance, administrator review, 90-day freshness, and Care composition.
- This decision narrowly supersedes ADR 0018's statement that no specific
  number is published and the PHQ-9 policy's prospective exclusion of `115`.
- No database, REST contract, event, or persistence change is introduced.
- Any additional real contact still requires its own reviewed content decision
  or a current eligible safety-directory record.

## Rejected alternatives

- Use the illustrative `1900 599 830`: rejected because the product request
  identifies the image as illustrative and no official national-service source
  was established for that number.
- Fetch default contacts from the area directory: rejected because it adds a
  dependency and still requires area input before the user can see a number.
- Link directly to the safety-directory page: rejected because it navigates
  away and delays the required default contacts.
- Claim live specialist chat: rejected because the current product cannot
  guarantee on-duty or emergency response availability.
