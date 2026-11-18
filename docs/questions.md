# Open questions for Jacob

Decisions the docs don't cover. Each entry: date, issue, the question, options with a recommendation, and what
was done meanwhile (work continues on other issues). Jacob answers here or in chat; answered items move to
`docs/decisions.md`.

| # | Date | Issue | Question | Options (recommended first) | Meanwhile |
|---|---|---|---|---|---|
| 1 | 2026-11-18 | #16 | How is the sign-in token stored "encrypted"? | **(Recommended, done)** AES-256-GCM with a non-exportable Android Keystore key, no extra library (`data/AuthStore.kt`) · AndroidX `security-crypto` EncryptedSharedPreferences (deprecated by Google) | Implemented the recommended option. If the key is lost (e.g. data restored to another device) the user just signs in again. |
| 2 | 2026-11-18 | #16 | On a `401` mid-session, what happens to the open screen? | **(Recommended, done)** Return to sign-in with a cleared back stack; after signing in the user starts at Home. Queued uploads (when they exist) stay on the device and are sent after sign-in · Show sign-in as a dialog over the current screen | Implemented the recommended option. Nothing is queued yet in A2; the upload queue (later issue) must not be tied to the session. |
