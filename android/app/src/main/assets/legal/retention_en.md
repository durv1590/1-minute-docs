# Data Retention — DRAFT

| Data | Where | Kept for |
|---|---|---|
| Your documents | On your device | Until you delete them or uninstall |
| Drafts (editor text) | On your device | Until you delete them or uninstall; drafts not changed for 30 days are deleted automatically |
| Temporary processing files | On your device | Deleted after use; leftovers older than 6 hours are removed by a cleanup that runs about every 12 hours |
| Uploaded inputs (cloud) | Our server | Deleted when processing ends; if a job is never processed, when it expires (about 75 minutes after upload) |
| Text sent to AI features | Our server | Not stored; passed to the AI provider and the result returned |
| Cloud results | Our server | Deleted on download, or 60 minutes after processing finishes |
| Job records (file names, types, sizes, checksums, status — no content) | Our server | About 7 days after the job ends |
| AI usage records (task, model, sizes, token counts, status — no content) | Our server | 30 days |
| Audit events (cancellation/deletion/expiry, no content) | Our server | 90 days |
| Fair-use counters (anonymous app identifier; IP address for identifier requests) | Our server | About 1 hour (hourly limits) or 1 day (daily limits) |
| Server logs (no content, file names or tokens) | Our server | [LOG RETENTION PERIOD] |
| Database backups (job metadata only, no content) | [BACKUP LOCATION] | [BACKUP RETENTION PERIOD] |

Server durations are the default settings of the current server software.

Content sent to the AI provider (Anthropic) is subject to that provider's API data terms; it is not used for training.
