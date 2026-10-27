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
            const visitorToken = localStorage.getItem('crm_visitor_token');
            const headers = visitorToken ? { 'X-Visitor-Token': visitorToken } : {};

            const [configRes, flowRes, triggersRes, supportRes] = await Promise.all([
                fetch(`${base}/config/${businessId}`, { headers }),
                fetch(`${base}/flow/${businessId}`),
                fetch(`${base}/triggers/${businessId}`),
                fetch(`${base}/support/config/${businessId}`)
            ]);

            const theme = configRes.ok ? await configRes.json() : null;
            if (theme && theme.visitorToken) {
                localStorage.setItem('crm_visitor_token', theme.visitorToken);
            }

            return {
                theme,
                flow: flowRes.ok ? await flowRes.json() : null,
                triggers: triggersRes.ok ? await triggersRes.json() : null,
                supportConfig: supportRes.ok ? await supportRes.json() : null
            };
        },

        async sendChatMessage(message, isReturning, sessionId) {
            const visitorToken = localStorage.getItem('crm_visitor_token');
            const payload = {
                message,
                isReturning: String(isReturning),
                visitorToken: visitorToken || undefined
            };
            if (sessionId) {
                payload.sessionId = sessionId;
            }
            const headers = { 'Content-Type': 'application/json' };
            if (visitorToken) headers['X-Visitor-Token'] = visitorToken;

            const res = await fetch(`${base}/chat/${businessId}`, {
                method: 'POST',
                headers,
                body: JSON.stringify(payload)
            });
            if (!res.ok) {
                throw new Error(`Chat API error: ${res.status}`);
            }
            return await res.json();
        },

        async streamChatMessage(message, isReturning, sessionId, { onChunk, onDone, onFallback, onError, signal } = {}) {
            const visitorToken = localStorage.getItem('crm_visitor_token');
            const payload = {
                message,
                isReturning: String(isReturning),
                visitorToken: visitorToken || undefined
            };
            if (sessionId) {
                payload.sessionId = sessionId;
            }

            const headers = {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream'
            };
            if (visitorToken) headers['X-Visitor-Token'] = visitorToken;

            try {
                const res = await fetch(`${base}/chat/${businessId}/stream`, {
                    method: 'POST',
                    headers,
                    body: JSON.stringify(payload),
                    signal
                });

                if (!res.ok) {
                    throw new Error(`Streaming API returned ${res.status}`);
                }

                const reader = res.body.getReader();
                const decoder = new TextDecoder('utf-8');
                let buffer = '';

                while (true) {
                    const { value, done } = await reader.read();
                    if (done) break;

                    buffer += decoder.decode(value, { stream: true });
                    const lines = buffer.split('\n');
                    buffer = lines.pop(); // keep partial line

                    let currentEvent = 'message';
                    for (const line of lines) {
                        const trimmed = line.trim();
                        if (!trimmed) continue;

                        if (trimmed.startsWith('event:')) {
                            currentEvent = trimmed.replace('event:', '').trim();
                        } else if (trimmed.startsWith('data:')) {
                            const dataStr = trimmed.replace('data:', '').trim();
                            try {
                                const data = JSON.parse(dataStr);
                                if (currentEvent === 'chunk' && onChunk) {
                                    onChunk(data.text || '');
                                } else if (currentEvent === 'done' && onDone) {
                                    onDone(data);
                                } else if ((currentEvent === 'visitor_quota_exhausted' || currentEvent === 'tenant_quota_exhausted') && onFallback) {
                                    onFallback(currentEvent, data);
                                } else if (currentEvent === 'error' && onError) {
                                    onError(new Error(data.message || 'Stream error'));
                                }
                            } catch (parseEx) {
                                if (currentEvent === 'chunk' && onChunk) {
                                    onChunk(dataStr);
                                }
                            }
                        }
                    }
                }
            } catch (err) {
                if (onError) onError(err);
                else throw err;
            }
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
        },

        async sendVoiceTurn(audioBlob, sessionId, visitorId, transcript, preferredLang) {
            const formData = new FormData();
            if (audioBlob) {
                formData.append('audio', audioBlob, 'recording.webm');
            }
            if (sessionId) {
                formData.append('sessionId', sessionId);
            }
            if (visitorId) {
                formData.append('visitorId', visitorId);
            }
            if (transcript) {
                formData.append('transcript', transcript);
            }
            if (preferredLang) {
                formData.append('preferredLang', preferredLang);
            }

            const res = await fetch(`${base}/voice/${businessId}`, {
                method: 'POST',
                body: formData
            });

            if (!res.ok) {
                const errJson = await res.json().catch(() => ({}));
                const err = new Error(errJson.error || `Voice API error: ${res.status}`);
                err.status = res.status;
                err.data = errJson;
                throw err;
            }
            return await res.json();
        },

        async sendBargeIn(sessionId, turnNumber) {
            try {
                await fetch(`${base}/voice/${businessId}/barge-in`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessionId, turnNumber })
                });
            } catch (e) {
                // Non-blocking barge-in acknowledgment
            }
        },

        async fetchVoiceConfig() {
            try {
                const res = await fetch(`${base}/voice/config/${businessId}`);
                return res.ok ? await res.json() : null;
            } catch (e) {
                return null;
            }
        },

        // DPDP Right to Erasure
        async requestDataErasure(sessionId) {
            try {
                const res = await fetch(`${base}/chat/${businessId}/erasure`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessionId })
                });
                return res.ok;
            } catch (e) {
                console.warn('CRM Chat: Erasure request failed', e);
                return false;
            }
        }
    };
}
