# Runbook: AI Outage

**Severity:** P2 (Degraded Experience)
**Component:** Google Gemini API / RagRetrievalService
**On-Call:** AI Platform Engineer

## Symptoms
- `Gemini API quota exceeded` or `503 Service Unavailable` in logs.
- `RagRetrievalService` fallback to "low_signal_fallback" (Menu mode).
- AI cost charts in Grafana show flatline.
- Circuit breaker `gemini` in OPEN state.

## Investigation Steps
1. **Check Gemini Health:**
   - Verify status at [Google Cloud Status Dashboard](https://status.cloud.google.com/).
2. **Quota Check:**
   - Check `AIQuotaService` logs for `QuotaExceededException`.
3. **Latency Spike:**
   - Check `ai.execution.time` metric for timeouts (> 60s).

## Recovery Actions
- **Fail-Open to Menu:** Ensure the system is in "Shadow Mode" or "Menu Only" mode by updating `RagGuardrailService` configuration.
- **Switch API Key:** Rotate `GEMINI_API_KEY` if the current one is rate-limited.
- **Semantic Cache:** Verify `SemanticCacheService` is still serving hits (this mitigates outage impact for repeated queries).

## Rollback Procedures
- Close circuit breaker manually once upstream service is confirmed healthy.
