/**
 * CRM Chat Widget - API Client Layer
 */

export function createApiClient(apiBase, businessId) {
    const base = apiBase.replace(/\/+$/, '');

    return {
        getApiBase() {
            return base;
        },

        getBusinessId() {
            return businessId;
        },

        async fetchBootstrapData() {
            const [configRes, flowRes, triggersRes, supportRes] = await Promise.all([
                fetch(`${base}/config/${businessId}`),
                fetch(`${base}/flow/${businessId}`),
                fetch(`${base}/triggers/${businessId}`),
                fetch(`${base}/support/config/${businessId}`)
            ]);

            return {
                theme: configRes.ok ? await configRes.json() : null,
                flow: flowRes.ok ? await flowRes.json() : null,
                triggers: triggersRes.ok ? await triggersRes.json() : null,
                supportConfig: supportRes.ok ? await supportRes.json() : null
            };
        },

        async sendChatMessage(message, isReturning, sessionId) {
            const payload = {
                message,
                isReturning: String(isReturning)
            };
            if (sessionId) {
                payload.sessionId = sessionId;
            }
            const res = await fetch(`${base}/chat/${businessId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            if (!res.ok) {
                throw new Error(`Chat API error: ${res.status}`);
            }
            return await res.json();
        },

        async fetchFlow(flowType) {
            let typeParam = (flowType || '').toLowerCase();
            if (typeParam === 'lead_capture') typeParam = 'lead';

            const res = await fetch(`${base}/flow/${businessId}?type=${typeParam}`);
            if (!res.ok) {
                throw new Error(`Flow API error: ${res.status}`);
            }
            return await res.json();
        },

        async fetchCatalog() {
            const res = await fetch(`${base}/catalog/${businessId}`);
            if (!res.ok) {
                throw new Error(`Catalog API error: ${res.status}`);
            }
            return await res.json();
        },

        async fetchServices() {
            const res = await fetch(`${base}/services/${businessId}`);
            if (!res.ok) {
                throw new Error(`Services API error: ${res.status}`);
            }
            return await res.json();
        },

        async fetchSupportConfig() {
            const res = await fetch(`${base}/support/config/${businessId}`);
            if (!res.ok) {
                throw new Error(`Support Config API error: ${res.status}`);
            }
            return await res.json();
        },

        async submitFlow(endpoint, data, sessionId) {
            const payloadData = { ...(data || {}) };
            if (sessionId) {
                payloadData.sessionId = sessionId;
            }
            const res = await fetch(`${base}/${endpoint}/${businessId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ data: payloadData })
            });
            const result = await res.json().catch(() => ({}));
            return {
                ok: res.ok,
                status: res.status,
                data: result
            };
        },

        async submitSupportTicket(payload) {
            const data = { ...payload };
            if (!data.message && data.description) {
                data.message = data.description;
            }
            if (!data.description && data.message) {
                data.description = data.message;
            }
            const res = await fetch(`${base}/support/${businessId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            const result = await res.json().catch(() => ({}));
            return {
                ok: res.ok || res.status === 201,
                status: res.status,
                data: result
            };
        },

        async requestLiveSupport(payload) {
            const res = await fetch(`${base}/livechat/request/${businessId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const result = await res.json().catch(() => ({}));
            return {
                ok: res.ok,
                status: res.status,
                data: result
            };
        }
    };
}
