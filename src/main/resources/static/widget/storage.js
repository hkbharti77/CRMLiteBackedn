/**
 * CRM Chat Widget - Storage & Persistence
 */

export function createStorageManager(businessId) {
    const HISTORY_KEY = `crm_chat_history_${businessId}`;
    const SESSION_KEY = `crm_chat_session_${businessId}`;

    return {
        loadHistory() {
            try {
                const raw = localStorage.getItem(HISTORY_KEY);
                let history = JSON.parse(raw || '[]');
                // Cleanup stale {{business}} placeholder from old cached sessions
                if (Array.isArray(history)) {
                    return history.filter(m => m && m.text && !m.text.includes('{{business}}'));
                }
                return [];
            } catch (e) {
                console.warn('CRM Chat: Could not load history', e);
                return [];
            }
        },

        saveHistory(messages) {
            try {
                if (Array.isArray(messages)) {
                    localStorage.setItem(HISTORY_KEY, JSON.stringify(messages.slice(-50)));
                }
            } catch (e) {
                console.warn('CRM Chat: Could not save history', e);
            }
        },

        clearHistory() {
            try {
                localStorage.removeItem(HISTORY_KEY);
            } catch (e) {
                console.warn('CRM Chat: Could not clear history', e);
            }
        },

        getSessionId() {
            try {
                let sessionId = localStorage.getItem(SESSION_KEY);
                if (!sessionId) {
                    sessionId = 'web_' + Math.random().toString(36).substring(2, 9) + Date.now().toString(36);
                    localStorage.setItem(SESSION_KEY, sessionId);
                }
                return sessionId;
            } catch (e) {
                return 'web_' + Math.random().toString(36).substring(2, 9) + Date.now().toString(36);
            }
        },

        setSessionId(sessionId) {
            try {
                localStorage.setItem(SESSION_KEY, sessionId);
            } catch (e) {
                console.warn('CRM Chat: Could not set session ID', e);
            }
        }
    };
}
