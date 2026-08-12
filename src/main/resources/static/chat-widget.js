/**
 * CRM Chat Widget - Dynamic Module Loader
 * Automatically resolves businessId, API_BASE, and loads the modular widget core.
 */
(function () {
    const script = document.currentScript || document.querySelector('script[data-business-id]');
    const businessId = script
        ? script.getAttribute('data-business-id')
        : new URLSearchParams(window.location.search).get('businessId');

    if (!businessId) return;

    let API_BASE = 'http://localhost:8080/api/v1/public';
    let widgetBaseUrl = '/widget';

    if (script && script.src) {
        try {
            const url = new URL(script.src, window.location.href);
            API_BASE = `${url.origin}/api/v1/public`;
            widgetBaseUrl = `${url.origin}/widget`;
        } catch (e) {
            console.warn('CRM Chat: Could not determine API origin, defaulting to localhost');
        }
    }

    import(`${widgetBaseUrl}/index.js`)
        .then(({ initWidget }) => {
            initWidget({ businessId, apiBase: API_BASE });
        })
        .catch(err => {
            console.error('CRM Chat: Failed to load widget modules:', err);
        });
})();
