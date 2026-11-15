# MB-849 Android push setup and device test

Decision record: [ADR 0044 amendment 2026-11-15](../adr/0044-secure-native-push-delivery.md).
Push is off unless `CONTENT_PUSH_DELIVERY_ENABLED=true`. This runbook holds no secret values.

## FCM and Expo setup

1. Create an Expo account and run `eas init` in `mentalbridge-frontend/mobile/`; keep the
   resulting `extra.eas.projectId` (the app needs it for the Expo push token).
2. Create a Firebase project, add the Android app `com.mentalbridge.mobile` and download
   `google-services.json`. Keep it out of git (the mobile build reads it from a gitignored path
   or `GOOGLE_SERVICES_JSON`).
3. Create a Firebase service account key for FCM HTTP v1 and upload it to EAS
   (`eas credentials`, Android, FCM V1).
4. Create an Expo access token and a 32-byte key (`openssl rand -base64 32`). Put them in the
   target environment only (staging first), never in git.

## Environment (content-notification)

| Name | Purpose | Default |
| --- | --- | --- |
| `CONTENT_PUSH_DELIVERY_ENABLED` | Starts the push worker | `false` |
| `EXPO_ACCESS_TOKEN` | Expo push authorization | empty |
| `CONTENT_PUSH_TOKEN_ENCRYPTION_KEY` | Base64 32-byte AES key for stored device tokens | empty |
| `CONTENT_PUSH_DAILY_REMINDER_TIME` | Local `HH:MM` from which daily reminders push | `20:00` |
| `CONTENT_PUSH_DELIVERY_INTERVAL_MS`, `_BATCH_SIZE`, `_PROVIDER_TIMEOUT_MS`, `_RECEIPT_*`, `_DEVICE_RATE_*`, `EXPO_PUSH_BASE_URL` | Worker tuning | see `compose.yml` |

Enabling needs both `EXPO_ACCESS_TOKEN` and `CONTENT_PUSH_TOKEN_ENCRYPTION_KEY`; the service
refuses to start without them. Empty values are fine while the flag is `false`.

## Make a reminder due for a device test

1. Register the phone from the app (preferences, "Thông báo đẩy") so a device row exists.
2. Set `CONTENT_PUSH_DAILY_REMINDER_TIME` a few minutes ahead of the phone's local time and
   restart content-notification. A device registered earlier the same local day still receives
   that day's 00:00 reminder; a device registered after that day ends does not.
3. The reminder must still exist and the activity must still be undone: no journal entry or emotion
   check-in today. Keep quiet hours off or not covering the test time.
4. Close the app, wait for the time. Expect the MentalBridge chime and the reminder title.
   Tapping opens the matching screen and marks it read.
5. Restore the setting to `20:00`.

A reminder not sent by the owner's local midnight is dropped (`REMINDER_EXPIRED`) and never sent the
next morning. Failure codes are on `push_delivery_attempt.failure_code`.

## Turn push off

Set `CONTENT_PUSH_DELIVERY_ENABLED=false` and restart content-notification. Inbox notifications and
device registrations are unaffected; the app keeps working. While it is false,
registration (PUT /api/v1/push-devices/{installationId}) returns 503 so the app never reports push
as on; revoke (DELETE) keeps working.
