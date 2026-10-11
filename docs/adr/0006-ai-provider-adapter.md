# ADR-0006: Provider-independent AI adapter
Status: Accepted · 2026-10-10

Decision: Backend `AiProvider` interface (`complete`, `transcribeImage`) with one implementation, `anthropic`, using the
official Anthropic SDK (default model `claude-opus-5-5`, explicit `output_config.effort`, server-side refusal fallback).
Provider chosen by `AI_PROVIDER` (`anthropic` | `none`). Another vendor is added by implementing the interface; callers
(prompts, quotas, accounting, job processing) do not change. Prompts are built server-side from structured requests (task, doc type,
language, tone, fields) with hard input/output caps. Handwriting recognition uses the provider's vision capability.
Consequences: No keys on device; switching vendors is configuration. With no provider configured the API returns
`AI_NOT_CONFIGURED` and the app shows a configuration error.
