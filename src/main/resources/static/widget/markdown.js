/**
 * CRM Chat Widget - Markdown Parser & URL Utilities
 */

export function parseMarkdown(text) {
    if (!text) return '';
    let html = String(text);

    // Code blocks
    html = html.replace(/```([\s\S]*?)```/g, '<pre style="background:#f1f5f9;color:#0f172a;padding:8px;border-radius:4px;overflow-x:auto;margin:4px 0;font-family:monospace;font-size:12px;"><code>$1</code></pre>');
    
    // Inline code
    html = html.replace(/`(.*?)`/g, '<code style="background:#f1f5f9;color:#0f172a;padding:2px 4px;border-radius:3px;font-family:monospace;font-size:12px;">$1</code>');

    // Headings
    html = html.replace(/^### (.*$)/gim, '<strong style="display:block;margin-top:8px;font-size:1.1em;">$1</strong>');
    html = html.replace(/^## (.*$)/gim, '<strong style="display:block;margin-top:8px;font-size:1.2em;">$1</strong>');
    html = html.replace(/^# (.*$)/gim, '<strong style="display:block;margin-top:8px;font-size:1.3em;">$1</strong>');

    // Blockquotes
    html = html.replace(/^\s*> (.*$)/gim, '<blockquote style="border-left:3px solid #cbd5e1;padding-left:8px;margin:4px 0;color:#64748b;font-style:italic;">$1</blockquote>');

    // Bullet lists (dash or asterisk with optional leading spaces)
    html = html.replace(/^\s*[-*]\s+(.*$)/gim, '• $1');

    // Bold, Italic, Strikethrough
    html = html.replace(/\*\*(.*?)\*\*/g, '<b>$1</b>');
    html = html.replace(/\*([^\n*]+?)\*/g, '<i>$1</i>');
    html = html.replace(/\_([^\n_]+?)\_/g, '<i>$1</i>');
    html = html.replace(/~~(.*?)~~/g, '<del>$1</del>');

    // Line breaks
    html = html.replace(/\n/g, '<br>');

    return html;
}

export function resolveImageUrl(url, apiBase) {
    if (!url) return null;
    if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
        return url;
    }
    const base = apiBase || '';
    const serverOrigin = base.replace(/\/api\/v1\/public\/?$/, '');
    return `${serverOrigin}${url.startsWith('/') ? '' : '/'}${url}`;
}
