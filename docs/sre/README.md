# SRE Toolkit Summary

This directory contains the operational framework for maintaining the reliability of the CRMLite platform.

## 📁 [Runbooks](./runbooks/)
Detailed playbooks for incident response:
- **[WebhookFailure.md](./runbooks/WebhookFailure.md)**: WhatsApp ingress troubleshooting.
- **[QueueOverload.md](./runbooks/QueueOverload.md)**: Async worker scaling and Redis stream management.
- **[DBExhaustion.md](./runbooks/DBExhaustion.md)**: Database connection and storage management.
- **[AIOutage.md](./runbooks/AIOutage.md)**: Gemini API fail-open and quota management.
- **[RedisFailure.md](./runbooks/RedisFailure.md)**: Distributed state and cache recovery.
- **[WebSocketFailure.md](./runbooks/WebSocketFailure.md)**: Real-time UI synchronization issues.

## 📊 [Dashboards](./dashboards/)
Grafana JSON templates to be imported into your monitoring instance:
- **API_Health.json**: Tracks 5xx rates and request latency.

## 🔔 [Alerts](./alerts/)
Prometheus alerting rules:
- **critical_alerts.yml**: P0/P1/P2 thresholds for automated notification via Alertmanager.

## 🚀 Operational Best Practices
1. **Shadow Mode**: Use the `shadowMode` flag in `RagGuardrailService` when testing new guardrails in production.
2. **Horizontal Scaling**: Scale the backend nodes if `QueueOverload` alerts persist.
3. **Database Retention**: Ensure the `DatabaseRetentionService` is running weekly to prevent hot-table bloat.
