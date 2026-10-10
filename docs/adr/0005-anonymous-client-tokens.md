# ADR-0005: Anonymous client tokens instead of accounts
Status: Accepted · 2026-10-10

Context: The app must work without accounts, but uploaded files must not be reachable by other clients and resource use must be limited.
Decision: The app obtains an HMAC-signed token bound to a random client ID (stored in DataStore). Jobs are owned by that ID;
every job query filters by owner. Token issuance is IP rate-limited. Play Integrity attestation can be added later.
Consequences: No personal data is collected. A token holder can only see their own jobs. Abuse is limited by quotas, not identity.
