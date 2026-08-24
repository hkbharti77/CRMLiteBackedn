/**
 * CRM Chat Widget - UI Controller & DOM Component Renderer
 */

import { escapeHtml, getLocalDateString } from './markdown.js';
import { validateFieldInput } from './validator.js';
import { COUNTRY_CODES } from './constants.js';

let fieldIdCounter = 0;

function darkenHex(hex, amount = 0.15) {
    if (!hex || typeof hex !== 'string') return hex;
    const raw = hex.replace('#', '').trim();
    if (!/^[0-9a-fA-F]{6}$/.test(raw)) return hex;
    const num = parseInt(raw, 16);
    let r = (num >> 16) & 255;
    let g = (num >> 8) & 255;
    let b = num & 255;
    r = Math.max(0, Math.round(r * (1 - amount)));
    g = Math.max(0, Math.round(g * (1 - amount)));
    b = Math.max(0, Math.round(b * (1 - amount)));
    return `#${((1 << 24) + (r << 16) + (g << 8) + b).toString(16).slice(1)}`;
}

function hexToRgba(hex, alpha) {
    if (!hex || typeof hex !== 'string') return `rgba(37, 99, 235, ${alpha})`;
    const raw = hex.replace('#', '').trim();
    if (!/^[0-9a-fA-F]{6}$/.test(raw)) return `rgba(37, 99, 235, ${alpha})`;
    const num = parseInt(raw, 16);
    const r = (num >> 16) & 255;
    const g = (num >> 8) & 255;
    const b = num & 255;
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function businessInitials(name) {
    const parts = String(name || 'A').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return 'A';
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[1][0]).toUpperCase();
}

export function createUIController({
    theme,
    apiBase,
    icons,
    parseMarkdown,
    resolveImageUrl,
    onSendMessage,
    onRestartConfirm,
    onZoomToggle,
    onMenuCardClick,
    onCtaAction
}) {
    let elements = {};
    let isZoomed = false;
    let activeTheme = theme || {};
    let apiBaseRef = apiBase || '';
    let botLogoUrl = resolveImageUrl ? (resolveImageUrl(activeTheme.logoUrl || activeTheme.widgetIconUrl, apiBaseRef) || '') : '';

    const USER_AVATAR_SVG = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="8" r="4"/></svg>`;

    function createMsgAvatar(sender) {
        const avatar = document.createElement('div');
        avatar.className = `msg-avatar msg-avatar--${sender}`;
        avatar.setAttribute('aria-hidden', 'true');

        if (sender === 'bot') {
            const initials = businessInitials(activeTheme.businessName);
            if (botLogoUrl) {
                avatar.classList.add('has-logo');
                avatar.innerHTML = `<img src="${botLogoUrl}" alt="" onerror="this.onerror=null;this.parentElement.classList.remove('has-logo');this.parentElement.innerHTML='<span>${initials}</span>';">`;
            } else {
                avatar.innerHTML = `<span>${initials}</span>`;
            }
        } else {
            avatar.innerHTML = USER_AVATAR_SVG;
        }
        return avatar;
    }

    function wrapMessageRow(sender, bubbleEl) {
        const row = document.createElement('div');
        row.className = `message-row message-row--${sender}`;
        const avatar = createMsgAvatar(sender);
        if (sender === 'bot') {
            row.appendChild(avatar);
            row.appendChild(bubbleEl);
        } else {
            row.appendChild(bubbleEl);
            row.appendChild(avatar);
        }
        return row;
    }

    function appendMessageRow(container, sender, bubbleEl) {
        const row = wrapMessageRow(sender, bubbleEl);
        container.appendChild(row);
        container.scrollTop = container.scrollHeight;
        setTimeout(() => { container.scrollTop = container.scrollHeight; }, 30);
        return row;
    }

    function refreshTypingAvatar() {
        const typing = elements.typing;
        if (!typing) return;
        let avatar = typing.querySelector('.msg-avatar');
        if (!avatar) {
            avatar = createMsgAvatar('bot');
            typing.insertBefore(avatar, typing.firstChild);
        } else {
            const fresh = createMsgAvatar('bot');
            avatar.replaceWith(fresh);
        }
    }

    function createBotRow(contentClass) {
        const row = document.createElement('div');
        row.className = 'message-row message-row--bot';
        const avatar = createMsgAvatar('bot');
        const content = document.createElement('div');
        content.className = contentClass || 'message-row-content';
        row.appendChild(avatar);
        row.appendChild(content);
        return row;
    }

    function removeLastFlowButtons() {
        const { messages } = elements;
        if (!messages) return;
        const groups = messages.querySelectorAll('.flow-buttons-row');
        if (groups.length === 0) return;
        groups[groups.length - 1].remove();
    }

    function getFocusable(container) {
        if (!container) return [];
        return Array.from(container.querySelectorAll(
            'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
        ));
    }

    function trapFocus(container, e) {
        if (e.key !== 'Tab') return;
        const focusable = getFocusable(container);
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }

    return {
        getElements() {
            return elements;
        },

        buildWidgetDOM() {
            const existingWidget = document.getElementById('crm-chat-widget');
            if (existingWidget) {
                existingWidget.remove();
            }

            const widgetWrap = document.createElement('div');
            widgetWrap.id = 'crm-chat-widget';
            document.body.appendChild(widgetWrap);

            const initials = businessInitials(activeTheme.businessName);
            const iconImage = resolveImageUrl ? (resolveImageUrl(activeTheme.widgetIconUrl || activeTheme.logoUrl, apiBaseRef) || '') : '';
            botLogoUrl = resolveImageUrl ? (resolveImageUrl(activeTheme.logoUrl || activeTheme.widgetIconUrl, apiBaseRef) || '') : '';

            // Apply active theme colors & fonts immediately to prevent style flashing on reload
            const root = document.documentElement;
            const primary = activeTheme.primaryColor || '#2563eb';
            const secondary = activeTheme.secondaryColor || '#0f172a';
            const accent = activeTheme.accentColor || primary;
            const fontFamily = activeTheme.fontFamily || "'Plus Jakarta Sans', 'Inter', sans-serif";

            root.style.setProperty('--primary-color', primary);
            root.style.setProperty('--primary-hover', darkenHex(primary, 0.1));
            root.style.setProperty('--primary-light', hexToRgba(primary, 0.1));
            root.style.setProperty('--secondary-color', secondary);
            root.style.setProperty('--accent-color', accent);
            root.style.setProperty('--font-family', fontFamily);
            root.style.setProperty('--header-bg', secondary);
            root.style.setProperty('--bubble-user-bg', primary);
            root.style.setProperty('--bubble-user-gradient', primary);

            const launcherHtml = iconImage
                ? `<button type="button" class="chat-button has-custom-icon" id="chat-toggle" title="Chat with us" aria-label="Open chat">
                    <img src="${iconImage}" alt="Open chat" onerror="this.parentElement.classList.remove('has-custom-icon');this.parentElement.innerHTML=\`${icons.chat}\`;">
                </button>`
                : `<button type="button" class="chat-button" id="chat-toggle" title="Chat with us" aria-label="Open chat">
                    ${icons.chat}
                </button>`;

            const headerAvatarHtml = botLogoUrl
                ? `<div class="niche-icon-wrap has-logo" id="chat-header-icon"><img src="${botLogoUrl}" alt="" class="avatar-logo" onerror="this.onerror=null;this.parentElement.classList.remove('has-logo');this.parentElement.innerHTML='<span class=\\'avatar-initials\\'>${initials}</span>';"></div>`
                : `<div class="niche-icon-wrap" id="chat-header-icon"><span class="avatar-initials">${initials}</span></div>`;

            const menuAvatarHtml = botLogoUrl
                ? `<div class="niche-icon-wrap has-logo"><img src="${botLogoUrl}" alt="" class="avatar-logo" onerror="this.onerror=null;this.parentElement.classList.remove('has-logo');this.parentElement.innerHTML='<span class=\\'avatar-initials\\'>${initials}</span>';"></div>`
                : `<div class="niche-icon-wrap"><span class="avatar-initials">${initials}</span></div>`;

            widgetWrap.innerHTML = `
                <div class="chat-tooltip" id="chat-tooltip">
                    <span class="chat-tooltip-text">Chat with us</span>
                </div>
                ${launcherHtml}
                <div class="chat-panel" id="chat-panel" role="dialog" aria-modal="true" aria-label="Chat">
                    <div class="chat-header">
                        <div class="chat-header-info">
                            ${headerAvatarHtml}
                            <div class="chat-header-text">
                                <span class="chat-header-title">${escapeHtml(activeTheme.businessName || 'Assistant')}</span>
                                <div class="chat-header-status">
                                    <span class="status-dot-pulse" aria-hidden="true"></span>
                                    <span class="status-text">Available</span>
                                </div>
                            </div>
                        </div>
                        <div class="chat-header-actions">
                            <div class="header-actions-group" role="group" aria-label="Chat controls">
                                <button type="button" id="chat-zoom" class="header-action-btn" title="Expand view" aria-label="Expand view">${icons.zoomIn}</button>
                                <button type="button" id="chat-clear" class="header-action-btn" title="New conversation" aria-label="New conversation">${icons.clear}</button>
                                <span class="header-actions-divider" aria-hidden="true"></span>
                                <button type="button" id="chat-close" class="header-action-btn header-action-btn--close" title="Close chat" aria-label="Close chat">${icons.close}</button>
                            </div>
                        </div>
                    </div>
                    <div class="chat-messages" id="chat-messages" role="log" aria-live="polite" aria-relevant="additions"></div>
                    <div id="typing" class="typing-wrap" style="display:none;" aria-live="polite" aria-label="Assistant is typing">
                        <div class="typing-dots">
                            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
                        </div>
                    </div>
                    <div class="chat-input-container">
                        <button type="button" id="chat-menu" class="chat-menu-btn" title="Menu" aria-label="Open menu">${icons.menu}</button>
                        <div class="chat-composer">
                            <textarea id="chat-input" class="chat-input" rows="1" placeholder="Loading..." autocomplete="off" disabled aria-label="Message"></textarea>
                            <button type="button" id="chat-send" class="send-btn" title="Send message" disabled aria-label="Send">${icons.send}</button>
                        </div>
                    </div>
                    <div class="chat-confirm-overlay" id="chat-confirm-overlay">
                        <div class="chat-confirm-box" role="alertdialog" aria-labelledby="chat-confirm-title" aria-describedby="chat-confirm-desc">
                            <h4 id="chat-confirm-title">Restart conversation</h4>
                            <p id="chat-confirm-desc">Are you sure you want to start a new conversation? This will clear current messages.</p>
                            <div class="chat-confirm-actions">
                                <button type="button" id="chat-confirm-cancel" class="flow-btn flow-btn--muted">Cancel</button>
                                <button type="button" id="chat-confirm-ok" class="flow-btn flow-btn--danger">Start new</button>
                            </div>
                        </div>
                    </div>
                    <div class="chat-menu-overlay" id="chat-menu-overlay">
                        <div class="chat-header chat-header--menu">
                            <div class="chat-header-info">
                                ${menuAvatarHtml}
                                <div class="chat-header-text">
                                    <span class="chat-header-title">${escapeHtml(activeTheme.businessName || 'Assistant')}</span>
                                    <div class="chat-header-status">
                                        <span class="status-text">Select an option</span>
                                    </div>
                                </div>
                            </div>
                            <button type="button" id="chat-menu-close" class="header-action-btn" title="Back to chat" aria-label="Back to chat">${icons.close}</button>
                        </div>
                        <div class="chat-menu-body" id="chat-menu-body"></div>
                    </div>
                </div>
            `;

            elements = {
                widgetWrap,
                panel: document.getElementById('chat-panel'),
                toggle: document.getElementById('chat-toggle'),
                input: document.getElementById('chat-input'),
                sendBtn: document.getElementById('chat-send'),
                messages: document.getElementById('chat-messages'),
                typing: document.getElementById('typing'),
                zoomBtn: document.getElementById('chat-zoom'),
                clearBtn: document.getElementById('chat-clear'),
                closeBtn: document.getElementById('chat-close'),
                menuBtn: document.getElementById('chat-menu'),
                menuOverlay: document.getElementById('chat-menu-overlay'),
                menuBody: document.getElementById('chat-menu-body'),
                menuCloseBtn: document.getElementById('chat-menu-close'),
                confirmOverlay: document.getElementById('chat-confirm-overlay'),
                confirmCancelBtn: document.getElementById('chat-confirm-cancel'),
                confirmOkBtn: document.getElementById('chat-confirm-ok')
            };

            this.bindEvents();
            return elements;
        },

        bindEvents() {
            const {
                panel, toggle, input, sendBtn, zoomBtn, clearBtn, closeBtn,
                menuBtn, menuOverlay, menuCloseBtn, confirmOverlay,
                confirmCancelBtn, confirmOkBtn, messages
            } = elements;

            let lastFocusedBeforeOpen = null;

            const autoResizeComposer = () => {
                if (!input || input.tagName !== 'TEXTAREA') return;
                input.style.height = 'auto';
                input.style.height = Math.min(input.scrollHeight, 120) + 'px';
            };

            const openPanel = () => {
                lastFocusedBeforeOpen = document.activeElement;
                panel.classList.add('open');
                const tooltip = document.getElementById('chat-tooltip');
                if (tooltip) {
                    tooltip.style.display = 'none';
                    tooltip.classList.add('dismissed');
                }
                messages.scrollTop = messages.scrollHeight;
                setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
                if (!input.disabled) input.focus();
                toggle.classList.add('is-hidden');
            };

            const closePanel = () => {
                panel.classList.remove('open');
                menuOverlay.classList.remove('active');
                confirmOverlay.classList.remove('active');
                toggle.classList.remove('is-hidden');
                if (lastFocusedBeforeOpen && typeof lastFocusedBeforeOpen.focus === 'function') {
                    lastFocusedBeforeOpen.focus();
                }
            };

            toggle.onclick = openPanel;

            closeBtn.onclick = closePanel;

            clearBtn.onclick = () => {
                confirmOverlay.classList.add('active');
                confirmCancelBtn.focus();
            };

            confirmCancelBtn.onclick = () => {
                confirmOverlay.classList.remove('active');
            };

            confirmOkBtn.onclick = () => {
                confirmOverlay.classList.remove('active');
                if (onRestartConfirm) onRestartConfirm();
            };

            confirmOverlay.onclick = (e) => {
                if (e.target === confirmOverlay) confirmOverlay.classList.remove('active');
            };

            zoomBtn.onclick = () => {
                isZoomed = !isZoomed;
                panel.classList.toggle('zoomed', isZoomed);
                zoomBtn.innerHTML = isZoomed ? icons.zoomOut : icons.zoomIn;
                const label = isZoomed ? 'Standard view' : 'Expand view';
                zoomBtn.title = label;
                zoomBtn.setAttribute('aria-label', label);
                messages.scrollTop = messages.scrollHeight;
                if (onZoomToggle) onZoomToggle(isZoomed);
            };

            menuBtn.onclick = () => {
                menuOverlay.classList.add('active');
                menuCloseBtn.focus();
            };

            menuCloseBtn.onclick = () => {
                menuOverlay.classList.remove('active');
                if (!input.disabled) input.focus();
            };

            menuOverlay.onclick = (e) => {
                if (e.target === menuOverlay) menuOverlay.classList.remove('active');
            };

            panel.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    if (confirmOverlay.classList.contains('active')) {
                        confirmOverlay.classList.remove('active');
                    } else if (menuOverlay.classList.contains('active')) {
                        menuOverlay.classList.remove('active');
                    } else {
                        closePanel();
                    }
                    return;
                }
                if (menuOverlay.classList.contains('active')) {
                    trapFocus(menuOverlay, e);
                } else if (confirmOverlay.classList.contains('active')) {
                    trapFocus(confirmOverlay, e);
                } else if (panel.classList.contains('open')) {
                    trapFocus(panel, e);
                }
            });

            if (onSendMessage) {
                sendBtn.onclick = onSendMessage;
                input.addEventListener('input', autoResizeComposer);
                input.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        onSendMessage();
                    }
                });
            }
        },

        renderMenuOverlay(currentTheme) {
            const { menuBody, menuOverlay } = elements;
            if (!menuBody) return;

            menuBody.innerHTML = '';
            const t = currentTheme || theme;

            if (t.menuSections && t.menuSections.length > 0) {
                t.menuSections.forEach(section => {
                    const sectionEl = document.createElement('div');
                    sectionEl.className = 'menu-section';

                    const titleEl = document.createElement('div');
                    titleEl.className = 'menu-section-title';
                    titleEl.textContent = section.title;
                    sectionEl.appendChild(titleEl);

                    const gridEl = document.createElement('div');
                    gridEl.className = 'menu-cards-grid';

                    (section.cards || []).forEach(card => {
                        const cardEl = document.createElement('button');
                        cardEl.type = 'button';
                        cardEl.className = 'menu-card';
                        cardEl.innerHTML = `
                            <div class="menu-card-icon">${icons[card.icon] || icons.briefcase}</div>
                            <div class="menu-card-title">${escapeHtml(card.title || '')}</div>
                            <div class="menu-card-subtitle">${escapeHtml(card.subtitle || '')}</div>
                        `;

                        cardEl.onclick = () => {
                            menuOverlay.classList.remove('active');
                            if (onMenuCardClick) onMenuCardClick(card);
                        };

                        gridEl.appendChild(cardEl);
                    });

                    sectionEl.appendChild(gridEl);
                    menuBody.appendChild(sectionEl);
                });
            } else {
                menuBody.innerHTML = '<div class="menu-empty">No additional options available.</div>';
            }
        },

        applyTheme(nextTheme, apiBase) {
            const t = nextTheme || theme;
            activeTheme = t;
            apiBaseRef = apiBase || '';
            const { widgetWrap, toggle } = elements;
            const root = widgetWrap || document.documentElement;
            const primary = t.primaryColor || '#2563eb';
            const secondary = t.secondaryColor || '#0f172a';
            const accent = t.accentColor || primary;
            // Chat UI always uses a clean enterprise sans — never monospace/code niche fonts
            const UI_FONT = "'Plus Jakarta Sans', 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
            const rawFont = String(t.fontFamily || '').toLowerCase();
            const isCodeOrMono = /fira|mono|code|courier|consolas|jetbrains|source code/.test(rawFont);
            const fontFamily = (!t.fontFamily || isCodeOrMono) ? UI_FONT : `${t.fontFamily}, ${UI_FONT}`;

            root.style.setProperty('--primary-color', primary);
            root.style.setProperty('--primary-hover', darkenHex(primary, 0.12));
            root.style.setProperty('--primary-light', hexToRgba(primary, 0.1));
            root.style.setProperty('--secondary-color', secondary);
            root.style.setProperty('--accent-color', accent);
            root.style.setProperty('--font-family', fontFamily);
            root.style.setProperty('--header-bg', secondary);
            root.style.setProperty('--bubble-user-bg', primary);
            root.style.setProperty('--bubble-user-gradient', primary);

            // Load decorative niche fonts only when safe for UI (not mono/code)
            if (t.fontFamily && !isCodeOrMono && typeof document !== 'undefined') {
                const fontName = String(t.fontFamily).split(',')[0].replace(/['"]/g, '').trim();
                const skip = ['Plus Jakarta Sans', 'Inter', 'system-ui', 'sans-serif', 'Segoe UI', 'Roboto', 'Arial'];
                if (fontName && !skip.includes(fontName)) {
                    const id = 'crm-widget-font-' + fontName.replace(/\s+/g, '-').toLowerCase();
                    if (!document.getElementById(id)) {
                        const link = document.createElement('link');
                        link.id = id;
                        link.rel = 'stylesheet';
                        link.href = `https://fonts.googleapis.com/css2?family=${encodeURIComponent(fontName).replace(/%20/g, '+')}:wght@400;500;600;700&display=swap`;
                        document.head.appendChild(link);
                    }
                }
            }

            document.querySelectorAll('.chat-header-title').forEach(el => {
                el.textContent = t.businessName || 'Assistant';
            });

            const initials = businessInitials(t.businessName);
            botLogoUrl = resolveImageUrl(t.logoUrl || t.widgetIconUrl, apiBase) || '';

            document.querySelectorAll('.niche-icon-wrap').forEach(iconWrap => {
                if (botLogoUrl) {
                    iconWrap.innerHTML = `<img src="${botLogoUrl}" alt="" class="avatar-logo" onerror="this.onerror=null;this.parentElement.innerHTML='<span class=\\'avatar-initials\\'>${initials}</span>';">`;
                    iconWrap.classList.add('has-logo');
                } else {
                    iconWrap.classList.remove('has-logo');
                    iconWrap.innerHTML = `<span class="avatar-initials">${initials}</span>`;
                }
            });

            refreshTypingAvatar();

            if (toggle) {
                const iconImage = resolveImageUrl(t.widgetIconUrl || t.logoUrl, apiBase);
                if (iconImage) {
                    toggle.classList.add('has-custom-icon');
                    toggle.innerHTML = '';
                    const img = document.createElement('img');
                    img.src = iconImage;
                    img.alt = 'Open chat';
                    img.onerror = function () {
                        toggle.classList.remove('has-custom-icon');
                        toggle.innerHTML = icons.chat;
                    };
                    toggle.appendChild(img);
                } else {
                    toggle.classList.remove('has-custom-icon');
                    toggle.innerHTML = icons.chat;
                }
            }

            this.renderMenuOverlay(t);

            if (t.showWatermark && widgetWrap) {
                const existing = widgetWrap.querySelector('.crm-watermark');
                if (!existing) {
                    const watermark = document.createElement('div');
                    watermark.className = 'crm-watermark';
                    watermark.textContent = 'Powered by CRMLite';
                    const inputContainer = widgetWrap.querySelector('.chat-input-container');
                    if (inputContainer) {
                        inputContainer.insertAdjacentElement('afterend', watermark);
                    }
                }
            }
        },

        renderMessageBubble(entry, containerEl) {
            const container = containerEl || elements.messages;
            if (!container || !entry || !entry.text) return;

            const sender = entry.sender === 'user' ? 'user' : 'bot';
            const msgDiv = document.createElement('div');
            msgDiv.className = `message ${sender}`;

            const textSpan = document.createElement('span');
            textSpan.className = 'message-text';
            textSpan.innerHTML = parseMarkdown(entry.text);

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date(entry.time || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

            msgDiv.appendChild(textSpan);
            msgDiv.appendChild(timeSpan);
            return appendMessageRow(container, sender, msgDiv);
        },

        renderBotBubbleWithCTAs(text, ctaButtons, onCtaSelect, ctasUsed = false) {
            const { messages } = elements;
            if (!messages) return;

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas';

            const textSpan = document.createElement('span');
            textSpan.className = 'message-text';
            textSpan.innerHTML = parseMarkdown(text);
            msgDiv.appendChild(textSpan);

            if (ctaButtons && ctaButtons.length > 0) {
                const ctaWrap = document.createElement('div');
                ctaWrap.className = 'cta-card-wrap';
                ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';
                    if (ctasUsed) btn.disabled = true;
                    const labelSpan = document.createElement('span');
                    labelSpan.className = 'btn-text';
                    labelSpan.textContent = btnConfig.label || '';
                    btn.appendChild(labelSpan);
                    if (!ctasUsed) {
                        const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                        arrow.setAttribute('class', 'btn-arrow');
                        arrow.setAttribute('viewBox', '0 0 24 24');
                        arrow.setAttribute('fill', 'none');
                        arrow.setAttribute('stroke', 'currentColor');
                        arrow.setAttribute('stroke-width', '2.5');
                        arrow.innerHTML = '<path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path>';
                        btn.appendChild(arrow);
                    }
                    if (!ctasUsed) {
                        btn.onclick = () => {
                            btn.classList.add('selected');
                            ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                            if (onCtaSelect) onCtaSelect(btnConfig);
                        };
                    }
                    ctaWrap.appendChild(btn);
                });
                msgDiv.appendChild(ctaWrap);
            }

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            msgDiv.appendChild(timeSpan);

            appendMessageRow(messages, 'bot', msgDiv);
        },

        renderCustomMenu(jsonString, defaultMessage, overrideBodyText, currentTheme, onActionSelect) {
            const { messages } = elements;
            if (!messages) return;

            let bodyMsg = defaultMessage || 'Please select an option:';
            let parsed = null;
            let buttonList = [];

            try {
                if (jsonString) {
                    parsed = typeof jsonString === 'string' ? JSON.parse(jsonString) : jsonString;
                    if (parsed) {
                        if (parsed.bodyText && !overrideBodyText) {
                            bodyMsg = parsed.bodyText;
                        } else if (parsed.message && !overrideBodyText) {
                            bodyMsg = parsed.message;
                        }

                        if (parsed.enabled !== false) {
                            if (Array.isArray(parsed)) {
                                buttonList = parsed;
                            } else if (Array.isArray(parsed.buttons)) {
                                buttonList = parsed.buttons;
                            } else if (parsed.sections && parsed.sections.length > 0 && parsed.sections[0].rows) {
                                buttonList = parsed.sections[0].rows;
                            }
                        }
                    }
                }
            } catch (e) {
                console.warn('Failed to parse custom menu JSON', e);
            }

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas';

            const textSpan = document.createElement('span');
            textSpan.className = 'message-text';
            textSpan.innerHTML = parseMarkdown(bodyMsg, apiBaseRef);
            msgDiv.appendChild(textSpan);

            const t = currentTheme || activeTheme || theme;

            if (buttonList && buttonList.length > 0) {
                const btnWrap = document.createElement('div');
                btnWrap.className = 'cta-card-wrap';

                buttonList.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';

                    const label = btnConfig.label || btnConfig.title || 'Action';
                    const linkType = btnConfig.linkType || btnConfig.action || btnConfig.id;

                    const labelSpan = document.createElement('span');
                    labelSpan.className = 'btn-text';
                    labelSpan.textContent = label;
                    btn.appendChild(labelSpan);

                    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                    arrow.setAttribute('class', 'btn-arrow');
                    arrow.setAttribute('viewBox', '0 0 24 24');
                    arrow.setAttribute('fill', 'none');
                    arrow.setAttribute('stroke', 'currentColor');
                    arrow.setAttribute('stroke-width', '2.5');
                    arrow.innerHTML = '<path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path>';
                    btn.appendChild(arrow);

                    btn.onclick = () => {
                        btn.classList.add('selected');
                        btnWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (onActionSelect) {
                            onActionSelect(btnConfig.id || linkType, label, linkType);
                        }
                    };
                    btnWrap.appendChild(btn);
                });
                msgDiv.appendChild(btnWrap);
            } else if (t.ctaButtons && t.ctaButtons.length > 0 && (!parsed || parsed.enabled !== false)) {
                const ctaWrap = document.createElement('div');
                ctaWrap.className = 'cta-card-wrap';
                t.ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';
                    const labelSpan = document.createElement('span');
                    labelSpan.className = 'btn-text';
                    labelSpan.textContent = btnConfig.label || '';
                    btn.appendChild(labelSpan);
                    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                    arrow.setAttribute('class', 'btn-arrow');
                    arrow.setAttribute('viewBox', '0 0 24 24');
                    arrow.setAttribute('fill', 'none');
                    arrow.setAttribute('stroke', 'currentColor');
                    arrow.setAttribute('stroke-width', '2.5');
                    arrow.innerHTML = '<path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path>';
                    btn.appendChild(arrow);
                    btn.onclick = () => {
                        btn.classList.add('selected');
                        ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (onActionSelect) {
                            onActionSelect(btnConfig.action, btnConfig.label, btnConfig.action);
                        } else if (onCtaAction) {
                            onCtaAction(btnConfig);
                        }
                    };
                    ctaWrap.appendChild(btn);
                });
                msgDiv.appendChild(ctaWrap);
            }

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            msgDiv.appendChild(timeSpan);

            appendMessageRow(messages, 'bot', msgDiv);
            return { text: bodyMsg, sender: 'bot', time: Date.now() };
        },

        renderButtons(options, onSelect, showTypeInstead = false, onTypeInstead = null, opts = {}) {
            const { messages } = elements;
            if (!messages) return;

            const row = createBotRow('message-row-content message-row-content--buttons');
            const container = document.createElement('div');
            container.className = `flow-buttons${opts.outline ? ' flow-buttons--outline' : ''}`;

            options.forEach(opt => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = opt === 'Cancel' ? 'flow-btn flow-btn--danger' : (opts.outline ? 'flow-btn flow-btn--outline' : 'flow-btn');
                const labelSpan = document.createElement('span');
                labelSpan.className = 'btn-text';
                labelSpan.textContent = opt;
                btn.appendChild(labelSpan);
                if (opt !== 'Cancel' && opt !== 'Skip') {
                    const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                    arrow.setAttribute('class', 'btn-arrow');
                    arrow.setAttribute('viewBox', '0 0 24 24');
                    arrow.setAttribute('fill', 'none');
                    arrow.setAttribute('stroke', 'currentColor');
                    arrow.setAttribute('stroke-width', '2.5');
                    arrow.innerHTML = '<path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path>';
                    btn.appendChild(arrow);
                }
                btn.onclick = () => {
                    btn.classList.add('selected');
                    container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                    onSelect(opt);
                };
                container.appendChild(btn);
            });

            row.classList.add('flow-buttons-row');
            const content = row.querySelector('.message-row-content');
            content.appendChild(container);

            if (showTypeInstead) {
                const skipLink = document.createElement('button');
                skipLink.type = 'button';
                skipLink.className = 'flow-skip-link';
                skipLink.textContent = 'Type response instead';
                skipLink.onclick = () => {
                    skipLink.remove();
                    container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                    if (onTypeInstead) onTypeInstead();
                };
                content.appendChild(skipLink);
            }

            messages.appendChild(row);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
        },

        removeLastFlowButtons() {
            removeLastFlowButtons();
        },

        createBotRow(contentClass) {
            return createBotRow(contentClass);
        },

        setInputEnabled(enabled, placeholder = null) {
            const { input, sendBtn } = elements;
            if (input) {
                input.disabled = !enabled;
                if (placeholder !== null) input.placeholder = placeholder;
            }
            if (sendBtn) {
                sendBtn.disabled = !enabled;
                sendBtn.classList.toggle('is-disabled', !enabled);
            }
        },

        setTyping(isTyping) {
            const { typing, messages } = elements;
            if (!typing) return;
            if (isTyping) {
                refreshTypingAvatar();
                typing.style.display = 'flex';
                if (messages) messages.scrollTop = messages.scrollHeight;
            } else {
                typing.style.display = 'none';
            }
        },

        showValidationError(msg) {
            const { messages } = elements;
            if (!messages) return;
            const el = document.createElement('div');
            el.className = 'validation-msg';
            el.setAttribute('role', 'alert');
            el.textContent = msg;
            messages.appendChild(el);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => el.remove(), 3500);
        },

        renderWebFlowInline({ title, subtitle, badgeText, iconSvg, steps, onSubmit, onCancel }) {
            const { messages } = elements;
            if (!messages) return;

            messages.querySelectorAll('.crm-webflow-inline-form').forEach(el => el.remove());

            const row = document.createElement('div');
            row.className = 'message-row message-row--bot crm-form-row';

            const avatar = createMsgAvatar('bot');
            row.appendChild(avatar);

            const card = document.createElement('div');
            card.className = 'crm-webflow-cta-card has-inline-form';

            const bText = badgeText || 'Form';
            const tText = title || 'Complete Your Request';
            const dText = subtitle || '';
            const iconHtml = iconSvg || '';

            card.innerHTML = `
                <div class="crm-webflow-cta-header">
                    ${iconHtml ? `<div class="crm-webflow-cta-icon">${iconHtml}</div>` : ''}
                    <div class="crm-webflow-cta-header-text">
                        <span class="crm-webflow-cta-badge">${escapeHtml(bText)}</span>
                        <div class="crm-webflow-cta-title">${escapeHtml(tText)}</div>
                    </div>
                </div>
                ${dText ? `<div class="crm-webflow-cta-desc">${escapeHtml(dText)}</div>` : ''}
            `;

            row.appendChild(card);
            messages.appendChild(row);

            this.renderWebFlowModal({
                title: tText,
                subtitle: subtitle,
                steps: steps || [],
                anchorCard: card,
                anchorRow: row,
                onSubmit,
                onCancel
            });

            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => row.scrollIntoView({ behavior: 'smooth', block: 'nearest' }), 60);
        },

        renderWebFlowCtaCard({ title, subtitle, badgeText, buttonText, iconSvg, onOpen }) {
            const { messages } = elements;
            if (!messages) return null;

            const row = document.createElement('div');
            row.className = 'message-row message-row--bot crm-form-row';

            const avatar = createMsgAvatar('bot');
            row.appendChild(avatar);

            const card = document.createElement('div');
            card.className = 'crm-webflow-cta-card';

            const bText = badgeText || 'Form';
            const tText = title || 'Complete Your Request';
            const dText = subtitle || 'Fill in the form below to submit your details securely.';
            const btnLabel = buttonText || 'Open Form';
            const iconHtml = iconSvg || `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>`;

            card.innerHTML = `
                <div class="crm-webflow-cta-header">
                    <div class="crm-webflow-cta-icon">${iconHtml}</div>
                    <div class="crm-webflow-cta-header-text">
                        <span class="crm-webflow-cta-badge">${escapeHtml(bText)}</span>
                        <div class="crm-webflow-cta-title">${escapeHtml(tText)}</div>
                    </div>
                </div>
                <div class="crm-webflow-cta-desc">${escapeHtml(dText)}</div>
                <button type="button" class="crm-webflow-cta-btn">
                    <span>${escapeHtml(btnLabel)}</span>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>
                </button>
            `;

            const btn = card.querySelector('.crm-webflow-cta-btn');
            btn.onclick = () => {
                btn.disabled = true;
                btn.classList.add('is-opened');
                if (onOpen) onOpen(card, row);
            };

            row.appendChild(card);
            messages.appendChild(row);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => {
                messages.scrollTop = messages.scrollHeight;
                row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 60);
            return { card, row };
        },

        renderWebFlowSuccessCard({ title, details }) {
            const { messages } = elements;
            if (!messages) return;

            const row = document.createElement('div');
            row.className = 'message-row message-row--bot crm-form-row';

            const avatar = createMsgAvatar('bot');
            row.appendChild(avatar);

            const card = document.createElement('div');
            card.className = 'crm-webflow-success-card';

            const headerText = title || 'Form submitted successfully';
            const detailText = details || 'Thank you. Your information has been recorded and a confirmation will be sent shortly.';

            card.innerHTML = `
                <div class="crm-webflow-success-header">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
                    <span>${escapeHtml(headerText)}</span>
                </div>
                <div class="crm-webflow-success-details">${escapeHtml(detailText)}</div>
            `;

            row.appendChild(card);
            messages.appendChild(row);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => {
                messages.scrollTop = messages.scrollHeight;
                row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 60);
        },

        renderWebFlowModal({ title, subtitle, steps = [], initialData = {}, onSubmit, onCancel, anchorCard, anchorRow }) {
            const { messages } = elements;
            if (!messages) return;

            const isCompact = !!anchorCard;

            // Remove any existing inline form in this thread
            messages.querySelectorAll('.crm-webflow-inline-form').forEach(el => el.remove());

            const formWrap = document.createElement('div');
            formWrap.className = 'crm-webflow-inline-form';

            // Show all fields on one screen (full form — no multi-step Continue clicks)
            const screens = steps.length > 0 ? [steps] : [[]];

            let currentScreenIdx = 0;
            const formData = { ...initialData };

            function getFieldType(step) {
                const ft = (step.fieldType || '').toUpperCase();
                const dk = (step.dataKey || '').toLowerCase();
                if (ft === 'EMAIL' || dk.includes('email')) return 'EMAIL';
                if (ft === 'PHONE' || dk.includes('phone') || dk.includes('mobile')) return 'PHONE';
                if (ft === 'DATE' || dk.includes('date')) return 'DATE';
                if (ft === 'TIME' || dk.includes('time') || dk.includes('slot')) return 'TIME';
                if (ft === 'NUMBER' || dk.includes('budget') || dk.includes('count') || dk.includes('guest')) return 'NUMBER';
                if (ft === 'TEXTAREA' || dk.includes('notes') || dk.includes('message') || dk.includes('desc') || dk.includes('problem')) return 'TEXTAREA';
                if (step.options && step.options.length > 0) return 'OPTIONS';
                return 'TEXT';
            }

            const dismissForm = () => {
                formWrap.remove();
                if (anchorCard) {
                    anchorCard.classList.remove('has-inline-form');
                    const ctaBtn = anchorCard.querySelector('.crm-webflow-cta-btn');
                    if (ctaBtn) {
                        ctaBtn.disabled = false;
                        ctaBtn.classList.remove('is-opened');
                        ctaBtn.style.display = '';
                    }
                }
                if (onCancel) onCancel();
            };

            function renderScreen(screenIdx) {
                const screenSteps = screens[screenIdx] || [];
                const totalScreens = screens.length;
                const progressPct = Math.round(((screenIdx + 1) / totalScreens) * 100);

                const isSingleScreen = totalScreens === 1;

                formWrap.innerHTML = `
                    <div class="crm-webflow-sheet crm-webflow-sheet--inline${isCompact ? ' crm-webflow-sheet--compact' : ''}">
                        ${isCompact ? `
                            <div class="crm-webflow-inline-toolbar">
                                <button type="button" class="crm-webflow-close-btn" title="Close" aria-label="Close form">
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
                                </button>
                            </div>
                        ` : `
                            <div class="crm-webflow-header">
                                <div class="crm-webflow-header-info">
                                    <div class="crm-webflow-header-title">${escapeHtml(title || 'Form')}</div>
                                    <div class="crm-webflow-header-subtitle">
                                        ${escapeHtml(subtitle || (isSingleScreen ? 'Complete all fields below' : `Step ${screenIdx + 1} of ${totalScreens}`))}
                                    </div>
                                </div>
                                <button type="button" class="crm-webflow-close-btn" title="Close" aria-label="Close form">
                                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></svg>
                                </button>
                            </div>
                        `}
                        ${!isCompact && !isSingleScreen ? `<div class="crm-webflow-progress-bar"><div class="crm-webflow-progress-fill" style="width: ${progressPct}%"></div></div>` : ''}
                        <div class="crm-webflow-body"></div>
                        <div class="crm-webflow-footer">
                            <button type="button" class="crm-webflow-btn crm-webflow-btn-secondary btn-back-cancel">
                                ${screenIdx === 0 ? 'Cancel' : 'Back'}
                            </button>
                            <button type="button" class="crm-webflow-btn crm-webflow-btn-primary btn-next-submit">
                                <span>${screenIdx === totalScreens - 1 ? 'Submit' : 'Continue'}</span>
                                ${screenIdx === totalScreens - 1 ? '' : '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>'}
                            </button>
                        </div>
                    </div>
                `;

                const closeBtn = formWrap.querySelector('.crm-webflow-close-btn');
                const backCancelBtn = formWrap.querySelector('.btn-back-cancel');
                const nextSubmitBtn = formWrap.querySelector('.btn-next-submit');
                const bodyContainer = formWrap.querySelector('.crm-webflow-body');

                closeBtn.onclick = dismissForm;
                backCancelBtn.onclick = () => {
                    if (screenIdx === 0) {
                        dismissForm();
                    } else {
                        currentScreenIdx--;
                        renderScreen(currentScreenIdx);
                    }
                };

                screenSteps.forEach(step => {
                    const fieldType = getFieldType(step);
                    const fieldWrap = document.createElement('div');
                    fieldWrap.className = 'crm-webflow-field';
                    fieldWrap.dataset.dataKey = step.dataKey;

                    const isReq = step.required !== false;
                    const val = formData[step.dataKey] || '';
                    const fieldId = `crm-field-${++fieldIdCounter}`;

                    const labelEl = document.createElement('label');
                    labelEl.className = 'crm-webflow-label';
                    labelEl.setAttribute('for', fieldType === 'OPTIONS' ? '' : fieldId);
                    const labelText = document.createElement('span');
                    labelText.textContent = step.question || step.dataKey;
                    labelEl.appendChild(labelText);
                    if (isReq) {
                        const star = document.createElement('span');
                        star.className = 'required-star';
                        star.textContent = ' *';
                        labelEl.appendChild(star);
                    }
                    fieldWrap.appendChild(labelEl);

                    if (fieldType === 'OPTIONS') {
                        const radioGroup = document.createElement('div');
                        radioGroup.className = 'crm-webflow-radio-group';
                        radioGroup.setAttribute('role', 'radiogroup');
                        radioGroup.setAttribute('aria-label', step.question || step.dataKey);
                        step.options.forEach((opt, optIdx) => {
                            const radioId = `${fieldId}-${optIdx}`;
                            const radioCard = document.createElement('label');
                            radioCard.className = `crm-webflow-radio-card ${val === opt ? 'selected' : ''}`;
                            radioCard.setAttribute('for', radioId);
                            const radioInput = document.createElement('input');
                            radioInput.type = 'radio';
                            radioInput.className = 'crm-webflow-radio-input';
                            radioInput.name = fieldId;
                            radioInput.id = radioId;
                            radioInput.value = opt;
                            radioInput.checked = val === opt;
                            radioInput.onchange = () => {
                                radioGroup.querySelectorAll('.crm-webflow-radio-card').forEach(c => c.classList.remove('selected'));
                                radioCard.classList.add('selected');
                                formData[step.dataKey] = opt;
                                const errEl = fieldWrap.querySelector('.crm-webflow-field-error');
                                if (errEl) errEl.remove();
                            };
                            const dot = document.createElement('div');
                            dot.className = 'crm-webflow-radio-dot';
                            const optLabel = document.createElement('span');
                            optLabel.className = 'crm-webflow-radio-label';
                            optLabel.textContent = opt;
                            radioCard.appendChild(radioInput);
                            radioCard.appendChild(dot);
                            radioCard.appendChild(optLabel);
                            radioGroup.appendChild(radioCard);
                        });
                        fieldWrap.appendChild(radioGroup);
                    } else if (fieldType === 'TEXTAREA') {
                        const ta = document.createElement('textarea');
                        ta.id = fieldId;
                        ta.className = 'crm-webflow-textarea';
                        ta.rows = 3;
                        ta.placeholder = `Enter details...`;
                        ta.value = val;
                        ta.oninput = (e) => {
                            formData[step.dataKey] = e.target.value;
                            const oldErr = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (oldErr) oldErr.remove();
                            const rawVal = e.target.value.trim();
                            const vErr = rawVal ? validateFieldInput(fieldType, step.dataKey, rawVal) : null;
                            if (vErr) {
                                ta.classList.add('input-error');
                                ta.classList.remove('input-success');
                                const errDiv = document.createElement('div');
                                errDiv.className = 'crm-webflow-field-error';
                                errDiv.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg><span>${vErr}</span>`;
                                fieldWrap.appendChild(errDiv);
                            } else {
                                ta.classList.remove('input-error');
                                if (rawVal) ta.classList.add('input-success');
                                else ta.classList.remove('input-success');
                            }
                        };
                        fieldWrap.appendChild(ta);
                    } else {
                        const input = document.createElement('input');
                        input.id = fieldId;
                        input.className = 'crm-webflow-input';
                        input.value = val;

                        if (fieldType === 'EMAIL') {
                            input.type = 'email';
                            input.placeholder = 'yourname@example.com';
                            input.maxLength = 156;
                        } else if (fieldType === 'PHONE') {
                            // --- Country Code Phone Widget ---
                            const phoneWrapper = document.createElement('div');
                            phoneWrapper.className = 'crm-phone-wrapper';

                            // Default to India (index 0)
                            let selectedCountry = COUNTRY_CODES[0];
                            input.type = 'tel';
                            input.placeholder = 'Enter phone number';
                            input.className = 'crm-webflow-input crm-phone-number-input';
                            input.maxLength = 15;

                            // Country code button
                            const codeBtn = document.createElement('button');
                            codeBtn.type = 'button';
                            codeBtn.className = 'crm-phone-code-btn';
                            codeBtn.innerHTML = `<span class="crm-phone-flag">${selectedCountry.flag}</span><span class="crm-phone-dial">${selectedCountry.dial}</span><svg class="crm-phone-chevron" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>`;

                            // Dropdown panel
                            const dropdown = document.createElement('div');
                            dropdown.className = 'crm-phone-dropdown';
                            dropdown.style.display = 'none';

                            // Search input
                            const searchWrap = document.createElement('div');
                            searchWrap.className = 'crm-phone-search-wrap';
                            const searchInput = document.createElement('input');
                            searchInput.type = 'text';
                            searchInput.className = 'crm-phone-search';
                            searchInput.placeholder = 'Search country...';
                            searchInput.autocomplete = 'off';
                            searchWrap.appendChild(searchInput);
                            dropdown.appendChild(searchWrap);

                            // Scrollable list
                            const listEl = document.createElement('div');
                            listEl.className = 'crm-phone-list';

                            function renderCountryList(filter) {
                                listEl.innerHTML = '';
                                const query = (filter || '').toLowerCase();
                                const filtered = COUNTRY_CODES.filter(c =>
                                    c.name.toLowerCase().includes(query) ||
                                    c.dial.includes(query) ||
                                    c.code.toLowerCase().includes(query)
                                );
                                if (filtered.length === 0) {
                                    const empty = document.createElement('div');
                                    empty.className = 'crm-phone-empty';
                                    empty.textContent = 'No countries found';
                                    listEl.appendChild(empty);
                                    return;
                                }
                                filtered.forEach(country => {
                                    const opt = document.createElement('div');
                                    opt.className = 'crm-phone-option' + (country.code === selectedCountry.code ? ' selected' : '');
                                    opt.innerHTML = `<span class="crm-phone-opt-flag">${country.flag}</span><span class="crm-phone-opt-name">${escapeHtml(country.name)}</span><span class="crm-phone-opt-dial">${country.dial}</span>`;
                                    opt.onclick = (e) => {
                                        e.stopPropagation();
                                        selectedCountry = country;
                                        codeBtn.innerHTML = `<span class="crm-phone-flag">${country.flag}</span><span class="crm-phone-dial">${country.dial}</span><svg class="crm-phone-chevron" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>`;
                                        dropdown.style.display = 'none';
                                        codeBtn.classList.remove('open');
                                        searchInput.value = '';
                                        // Update formData with new country code
                                        const num = input.value.replace(/\D/g, '');
                                        formData[step.dataKey] = num ? selectedCountry.dial + num : '';
                                        input.focus();
                                    };
                                    listEl.appendChild(opt);
                                });
                            }

                            renderCountryList('');
                            dropdown.appendChild(listEl);

                            searchInput.oninput = () => renderCountryList(searchInput.value);
                            searchInput.onclick = (e) => e.stopPropagation();

                            // Toggle dropdown
                            codeBtn.onclick = (e) => {
                                e.stopPropagation();
                                const isOpen = dropdown.style.display !== 'none';
                                dropdown.style.display = isOpen ? 'none' : '';
                                codeBtn.classList.toggle('open', !isOpen);
                                if (!isOpen) {
                                    searchInput.value = '';
                                    renderCountryList('');
                                    setTimeout(() => searchInput.focus(), 50);
                                }
                            };

                            // Close dropdown on outside click
                            document.addEventListener('click', (e) => {
                                if (!phoneWrapper.contains(e.target)) {
                                    dropdown.style.display = 'none';
                                    codeBtn.classList.remove('open');
                                }
                            });

                            phoneWrapper.appendChild(codeBtn);
                            phoneWrapper.appendChild(dropdown);
                            phoneWrapper.appendChild(input);
                            fieldWrap.appendChild(phoneWrapper);

                            // Override oninput to prepend country code + real-time validation
                            input.oninput = (e) => {
                                const num = e.target.value.replace(/[^0-9]/g, '');
                                e.target.value = num;
                                const fullVal = num ? selectedCountry.dial + num : '';
                                formData[step.dataKey] = fullVal;
                                const oldErr = fieldWrap.querySelector('.crm-webflow-field-error');
                                if (oldErr) oldErr.remove();
                                const vErr = num ? validateFieldInput('PHONE', step.dataKey, fullVal) : null;
                                if (vErr) {
                                    input.classList.add('input-error');
                                    input.classList.remove('input-success');
                                    const errDiv = document.createElement('div');
                                    errDiv.className = 'crm-webflow-field-error';
                                    errDiv.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg><span>${vErr}</span>`;
                                    fieldWrap.appendChild(errDiv);
                                } else {
                                    input.classList.remove('input-error');
                                    if (num) input.classList.add('input-success');
                                    else input.classList.remove('input-success');
                                }
                            };

                            // Skip the normal fieldWrap.appendChild(input) below
                            // since we already appended to phoneWrapper
                            bodyContainer.appendChild(fieldWrap);
                            return;
                            // --- End Country Code Phone Widget ---
                        } else if (fieldType === 'DATE') {
                            input.type = 'date';
                            input.min = getLocalDateString();
                        } else if (fieldType === 'TIME') {
                            input.type = 'time';
                        } else if (fieldType === 'NUMBER') {
                            input.type = 'number';
                            input.placeholder = 'Enter number';
                        } else {
                            input.type = 'text';
                            input.placeholder = 'Enter here...';
                        }

                        // Set maxLength based on field type / dataKey
                        const dk = (step.dataKey || '').toLowerCase();
                        if (dk === 'name' || dk.endsWith('_name') || dk.startsWith('name_')) {
                            input.maxLength = 66;
                        } else if (fieldType === 'EMAIL') {
                            // already set above
                        } else if (dk === 'subject') {
                            input.maxLength = 255;
                        }

                        input.oninput = (e) => {
                            formData[step.dataKey] = e.target.value;
                            const oldErr = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (oldErr) oldErr.remove();
                            const rawVal = e.target.value.trim();
                            const vErr = rawVal ? validateFieldInput(fieldType, step.dataKey, rawVal) : null;
                            if (vErr) {
                                input.classList.add('input-error');
                                input.classList.remove('input-success');
                                const errDiv = document.createElement('div');
                                errDiv.className = 'crm-webflow-field-error';
                                errDiv.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg><span>${vErr}</span>`;
                                fieldWrap.appendChild(errDiv);
                            } else {
                                input.classList.remove('input-error');
                                if (rawVal) input.classList.add('input-success');
                                else input.classList.remove('input-success');
                            }
                        };
                        fieldWrap.appendChild(input);
                    }

                    bodyContainer.appendChild(fieldWrap);
                });

                nextSubmitBtn.onclick = () => {
                    let hasErrors = false;
                    screenSteps.forEach(step => {
                        const fieldType = getFieldType(step);
                        const isReq = step.required !== false;
                        const rawVal = (formData[step.dataKey] || '').toString().trim();
                        const fieldWrap = bodyContainer.querySelector(`[data-data-key="${step.dataKey}"]`);

                        if (fieldWrap) {
                            const oldErr = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (oldErr) oldErr.remove();
                            const inputEl = fieldWrap.querySelector('input, textarea, select');
                            if (inputEl) inputEl.classList.remove('input-error');
                        }

                        let errMsg = '';
                        if (isReq && !rawVal) {
                            errMsg = 'This field is required';
                        } else if (rawVal) {
                            const vErr = validateFieldInput(fieldType, step.dataKey, rawVal);
                            if (vErr) errMsg = vErr;
                        }

                        if (errMsg && fieldWrap) {
                            hasErrors = true;
                            const inputEl = fieldWrap.querySelector('input, textarea, select');
                            if (inputEl) inputEl.classList.add('input-error');

                            const errDiv = document.createElement('div');
                            errDiv.className = 'crm-webflow-field-error';
                            errDiv.setAttribute('role', 'alert');
                            const errSpan = document.createElement('span');
                            errSpan.textContent = errMsg;
                            errDiv.appendChild(errSpan);
                            fieldWrap.appendChild(errDiv);
                        }
                    });

                    if (hasErrors) return;

                    if (screenIdx < totalScreens - 1) {
                        currentScreenIdx++;
                        renderScreen(currentScreenIdx);
                        messages.scrollTop = messages.scrollHeight;
                    } else {
                        nextSubmitBtn.disabled = true;
                        nextSubmitBtn.innerHTML = `
                            <svg class="crm-spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10" stroke-opacity="0.25"/><path d="M12 2a10 10 0 0 1 10 10"/></svg>
                            <span>Submitting...</span>
                        `;
                        if (onSubmit) {
                            onSubmit(formData, {
                                close: () => {
                                    formWrap.remove();
                                    if (anchorCard) {
                                        const ctaBtn = anchorCard.querySelector('.crm-webflow-cta-btn');
                                        if (ctaBtn) ctaBtn.style.display = 'none';
                                    }
                                },
                                reset: () => {
                                    nextSubmitBtn.disabled = false;
                                    nextSubmitBtn.innerHTML = `<span>Submit</span><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>`;
                                }
                            });
                        }
                    }
                };
            }

            renderScreen(0);

            if (anchorCard) {
                anchorCard.classList.add('has-inline-form');
                anchorCard.appendChild(formWrap);
            } else if (anchorRow) {
                anchorRow.appendChild(formWrap);
            } else {
                const row = document.createElement('div');
                row.className = 'message-row message-row--bot crm-form-row';
                row.appendChild(createMsgAvatar('bot'));
                const holder = document.createElement('div');
                holder.className = 'crm-webflow-cta-card crm-webflow-cta-card--form-only';
                holder.appendChild(formWrap);
                row.appendChild(holder);
                messages.appendChild(row);
            }

            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => {
                formWrap.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 80);
        }
    };
}
