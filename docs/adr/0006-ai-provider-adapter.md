# ADR-0006: Provider-independent AI adapter
Status: Accepted · 2026-10-10

Decision: Backend `AiProvider` interface with `anthropic` (default when ANTHROPIC_API_KEY is set) and `openai-compatible`
implementations; provider chosen by `AI_PROVIDER`. Prompts are built server-side from structured requests (task, doc type,
language, tone, fields) with hard input/output caps. Handwriting recognition uses the provider's vision capability.
Consequences: No keys on device; switching vendors is configuration. With no provider configured the API returns
`AI_NOT_CONFIGURED` and the app shows a configuration error.
