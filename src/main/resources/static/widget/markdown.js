/**
 * CRM Chat Widget - Markdown Parser & URL Utilities
 */

export function escapeHtml(text) {
    if (text === null || text === undefined) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export function parseMarkdown(text, apiBase) {
    if (!text) return '';
    let html = escapeHtml(String(text));

    // Code blocks & inline code
    html = html.replace(/```([\s\S]*?)```/g, '<pre class="md-pre"><code>$1</code></pre>');
    html = html.replace(/`(.*?)`/g, '<code class="md-code">$1</code>');

    // Images: ![alt](url)
    html = html.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, (match, alt, url) => {
        const resolved = resolveImageUrl(url, apiBase);
        return `<img src="${resolved || url}" alt="${escapeHtml(alt || '')}" class="chat-md-image" loading="lazy" onerror="this.style.display='none'" />`;
    });

    // Links: [label](url)
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, (match, label, url) => {
        const cleanUrl = escapeHtml(url);
        if (cleanUrl.startsWith('javascript:') || cleanUrl.startsWith('vbscript:')) return label;
        return `<a href="${cleanUrl}" target="_blank" rel="noopener noreferrer" class="chat-md-link">${label}</a>`;
    });

    html = html.replace(/^### (.*$)/gim, '<strong class="md-h3">$1</strong>');
    html = html.replace(/^## (.*$)/gim, '<strong class="md-h2">$1</strong>');
    html = html.replace(/^# (.*$)/gim, '<strong class="md-h1">$1</strong>');
    html = html.replace(/^\s*&gt; (.*$)/gim, '<blockquote class="md-quote">$1</blockquote>');
    html = html.replace(/^\s*[-*]\s+(.*$)/gim, '• $1');
    html = html.replace(/\*\*(.*?)\*\*/g, '<b>$1</b>');
    html = html.replace(/\*([^\n*]+?)\*/g, '<i>$1</i>');
    html = html.replace(/\_([^\n_]+?)\_/g, '<i>$1</i>');
    html = html.replace(/~~(.*?)~~/g, '<del>$1</del>');
    html = html.replace(/\n/g, '<br>');

    return html;
}

export function resolveImageUrl(url, apiBase) {
    if (!url) return null;
    const raw = String(url).trim();
    if (raw.startsWith('http://') || raw.startsWith('https://') || raw.startsWith('data:') || raw.startsWith('blob:')) {
        try {
            const parsed = new URL(raw);
            if (parsed.protocol === 'http:' || parsed.protocol === 'https:' || parsed.protocol === 'data:' || parsed.protocol === 'blob:') {
                return raw;
            }
        } catch (e) {
            return null;
        }
    }
    if (raw.startsWith('javascript:') || raw.startsWith('vbscript:')) return null;
    const base = apiBase || '';
    const serverOrigin = base
        .replace(/\/api\/v1\/public\/?$/, '')
        .replace(/\/api\/v1\/?$/, '')
        .replace(/\/$/, '');
    return `${serverOrigin}${raw.startsWith('/') ? '' : '/'}${raw}`;
}

export function getLocalDateString() {
    const d = new Date();
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
}
