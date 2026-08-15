/**
 * CRM Chat Widget - UI Controller & DOM Component Renderer
 */

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
    let botLogoUrl = '';
    let apiBaseRef = '';

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

            const initials = businessInitials(theme.businessName);

            widgetWrap.innerHTML = `
                <div class="chat-tooltip" id="chat-tooltip">
                    <span class="chat-tooltip-text">Chat with us</span>
                </div>
                <div class="chat-button" id="chat-toggle" title="Chat with us" role="button" tabindex="0">
                    ${icons.chat}
                </div>
                <div class="chat-panel" id="chat-panel" role="dialog" aria-label="Chat">
                    <div class="chat-header">
                        <div class="chat-header-info">
                            <div class="niche-icon-wrap" id="chat-header-icon"><span class="avatar-initials">${initials}</span></div>
                            <div class="chat-header-text">
                                <span class="chat-header-title">${theme.businessName || 'Assistant'}</span>
                                <div class="chat-header-status">
                                    <span class="status-dot-pulse" aria-hidden="true"></span>
                                    <span class="status-text">Online</span>
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
                    <div class="chat-messages" id="chat-messages"></div>
                    <div id="typing" class="typing-wrap" style="display:none;" aria-live="polite">
                        <div class="msg-avatar msg-avatar--bot" aria-hidden="true"><span>A</span></div>
                        <div class="typing-dots">
                            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
                        </div>
                    </div>
                    <div class="chat-input-container">
                        <button type="button" id="chat-menu" class="chat-menu-btn" title="Menu" aria-label="Open menu">${icons.menu}</button>
                        <div class="chat-composer">
                            <input type="text" id="chat-input" class="chat-input" placeholder="Loading..." autocomplete="off" disabled aria-label="Message">
                            <button type="button" id="chat-send" class="send-btn" title="Send message" disabled aria-label="Send">${icons.send}</button>
                        </div>
                    </div>
                    <div class="chat-confirm-overlay" id="chat-confirm-overlay">
                        <div class="chat-confirm-box" role="alertdialog" aria-labelledby="chat-confirm-title">
                            <h4 id="chat-confirm-title">Restart conversation</h4>
                            <p>Are you sure you want to start a new conversation? This will clear current messages.</p>
                            <div class="chat-confirm-actions">
                                <button type="button" id="chat-confirm-cancel" class="flow-btn flow-btn--muted">Cancel</button>
                                <button type="button" id="chat-confirm-ok" class="flow-btn selected">Start new</button>
                            </div>
                        </div>
                    </div>
                    <div class="chat-menu-overlay" id="chat-menu-overlay">
                        <div class="chat-header chat-header--menu">
                            <div class="chat-header-info">
                                <div class="niche-icon-wrap"><span class="avatar-initials">${initials}</span></div>
                                <div class="chat-header-text">
                                    <span class="chat-header-title">${theme.businessName || 'Assistant'}</span>
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

            const openPanel = () => {
                panel.classList.add('open');
                const tooltip = document.getElementById('chat-tooltip');
                if (tooltip) tooltip.style.display = 'none';
                messages.scrollTop = messages.scrollHeight;
                setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
                if (!input.disabled) input.focus();
                toggle.classList.add('is-hidden');
            };

            const closePanel = () => {
                panel.classList.remove('open');
                const tooltip = document.getElementById('chat-tooltip');
                if (tooltip && !tooltip.classList.contains('dismissed')) tooltip.style.display = 'block';
                toggle.classList.remove('is-hidden');
            };

            toggle.onclick = () => {
                if (panel.classList.contains('open')) closePanel();
                else openPanel();
            };

            toggle.onkeydown = (e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    toggle.click();
                }
            };

            closeBtn.onclick = closePanel;

            clearBtn.onclick = () => {
                confirmOverlay.classList.add('active');
            };

            confirmCancelBtn.onclick = () => {
                confirmOverlay.classList.remove('active');
            };

            confirmOkBtn.onclick = () => {
                confirmOverlay.classList.remove('active');
                if (onRestartConfirm) onRestartConfirm();
            };

            zoomBtn.onclick = () => {
                isZoomed = !isZoomed;
                panel.classList.toggle('zoomed', isZoomed);
                zoomBtn.innerHTML = isZoomed ? icons.zoomOut : icons.zoomIn;
                zoomBtn.title = isZoomed ? 'Standard view' : 'Expand view';
                messages.scrollTop = messages.scrollHeight;
                if (onZoomToggle) onZoomToggle(isZoomed);
            };

            menuBtn.onclick = () => {
                menuOverlay.classList.add('active');
            };

            menuCloseBtn.onclick = () => {
                menuOverlay.classList.remove('active');
            };

            if (onSendMessage) {
                sendBtn.onclick = onSendMessage;
                input.onkeypress = (e) => {
                    if (e.key === 'Enter') onSendMessage();
                };
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
                        const cardEl = document.createElement('div');
                        cardEl.className = 'menu-card';
                        cardEl.innerHTML = `
                            <div class="menu-card-icon">${icons[card.icon] || icons.briefcase}</div>
                            <div class="menu-card-title">${card.title || ''}</div>
                            <div class="menu-card-subtitle">${card.subtitle || ''}</div>
                        `;

                        cardEl.onclick = () => {
                            menuOverlay.classList.remove('active');
                            if (onMenuCardClick) {
                                onMenuCardClick(card);
                            }
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

        renderBotBubbleWithCTAs(text, ctaButtons, onCtaSelect) {
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
                    btn.innerHTML = `
                        <span class="btn-text">${btnConfig.label}</span>
                        <svg class="btn-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                    `;
                    btn.onclick = () => {
                        btn.classList.add('selected');
                        ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (onCtaSelect) onCtaSelect(btnConfig);
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
        },

        renderCustomMenu(jsonString, defaultMessage, overrideBodyText, currentTheme, onActionSelect) {
            const { messages } = elements;
            if (!messages) return;

            let bodyMsg = defaultMessage || 'Please select an option:';
            let parsed = null;
            try {
                if (jsonString) {
                    parsed = JSON.parse(jsonString);
                    if (parsed && parsed.bodyText && !overrideBodyText) {
                        bodyMsg = parsed.bodyText;
                    }
                }
            } catch (e) {
                console.warn('Failed to parse custom menu JSON', e);
            }

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas';

            const textSpan = document.createElement('span');
            textSpan.className = 'message-text';
            textSpan.innerHTML = parseMarkdown(bodyMsg);
            msgDiv.appendChild(textSpan);

            const t = currentTheme || activeTheme || theme;

            if (parsed && parsed.sections && parsed.sections.length > 0 && parsed.sections[0].rows && parsed.sections[0].rows.length > 0) {
                const btnWrap = document.createElement('div');
                btnWrap.className = 'cta-card-wrap';

                parsed.sections[0].rows.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';
                    btn.innerHTML = `
                        <span class="btn-text">${btnConfig.title}</span>
                        <svg class="btn-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                    `;
                    btn.onclick = () => {
                        btn.classList.add('selected');
                        btnWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (onActionSelect) onActionSelect(btnConfig.id, btnConfig.title);
                    };
                    btnWrap.appendChild(btn);
                });
                msgDiv.appendChild(btnWrap);
            } else if (t.ctaButtons && t.ctaButtons.length > 0) {
                const ctaWrap = document.createElement('div');
                ctaWrap.className = 'cta-card-wrap';
                t.ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';
                    btn.innerHTML = `
                        <span class="btn-text">${btnConfig.label}</span>
                        <svg class="btn-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                    `;
                    btn.onclick = () => {
                        btn.classList.add('selected');
                        ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (onCtaAction) onCtaAction(btnConfig);
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

        renderButtons(options, onSelect, showTypeInstead = false, onTypeInstead = null) {
            const { messages } = elements;
            if (!messages) return;

            const container = document.createElement('div');
            container.className = 'flow-buttons';

            options.forEach(opt => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = opt === 'Cancel' ? 'flow-btn flow-btn--danger' : 'flow-btn';
                if (opt === 'Cancel' || opt === 'Skip') {
                    btn.innerHTML = `<span class="btn-text">${opt}</span>`;
                } else {
                    btn.innerHTML = `
                        <span class="btn-text">${opt}</span>
                        <svg class="btn-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                    `;
                }
                btn.onclick = () => {
                    btn.classList.add('selected');
                    container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                    onSelect(opt);
                };
                container.appendChild(btn);
            });

            messages.appendChild(container);

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
                messages.appendChild(skipLink);
            }

            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
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
            el.innerHTML = `<span>${msg}</span>`;
            messages.appendChild(el);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => el.remove(), 3500);
        },

        renderWebFlowCtaCard({ title, subtitle, badgeText, buttonText, onOpen }) {
            const { messages } = elements;
            if (!messages) return;

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas crm-webflow-bubble';

            const card = document.createElement('div');
            card.className = 'crm-webflow-cta-card';

            const bText = badgeText || 'INTERACTIVE FORM';
            const tText = title || 'Complete Your Request';
            const dText = subtitle || 'Tap the button below to open the interactive form and submit your details in seconds.';
            const btnLabel = buttonText || '🚀 Open Interactive Form';

            card.innerHTML = `
                <div class="crm-webflow-cta-badge">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7.5L14.5 2z"/><polyline points="14 2 14 8 20 8"/></svg>
                    <span>${bText}</span>
                </div>
                <div class="crm-webflow-cta-title">${tText}</div>
                <div class="crm-webflow-cta-desc">${dText}</div>
                <button type="button" class="crm-webflow-cta-btn">
                    <span>${btnLabel}</span>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"></path><path d="m12 5 7 7-7 7"></path></svg>
                </button>
            `;

            const btn = card.querySelector('.crm-webflow-cta-btn');
            btn.onclick = () => {
                if (onOpen) onOpen();
            };

            msgDiv.appendChild(card);
            appendMessageRow(messages, 'bot', msgDiv);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => {
                messages.scrollTop = messages.scrollHeight;
                msgDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 60);
            return card;
        },

        renderWebFlowSuccessCard({ title, details }) {
            const { messages } = elements;
            if (!messages) return;

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas crm-webflow-bubble';

            const card = document.createElement('div');
            card.className = 'crm-webflow-success-card';

            const headerText = title || '🎉 Form Submitted Successfully!';
            const detailText = details || 'Thank you! Your information has been securely recorded. A confirmation email has been dispatched.';

            card.innerHTML = `
                <div class="crm-webflow-success-header">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
                    <span>${headerText}</span>
                </div>
                <div class="crm-webflow-success-details">${detailText}</div>
            `;

            msgDiv.appendChild(card);
            appendMessageRow(messages, 'bot', msgDiv);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => {
                messages.scrollTop = messages.scrollHeight;
                msgDiv.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 60);
        },

        renderWebFlowModal({ title, subtitle, steps = [], initialData = {}, onSubmit, onCancel }) {
            const panel = elements.panel || document.getElementById('crm-chat-panel');
            if (!panel) return;

            // Remove any existing webflow modal
            const existingOverlay = panel.querySelector('.crm-webflow-overlay');
            if (existingOverlay) existingOverlay.remove();

            const overlay = document.createElement('div');
            overlay.className = 'crm-webflow-overlay';

            // Partition steps into screens (approx 2-3 fields per screen for sleek UX)
            const screens = [];
            let currentChunk = [];
            steps.forEach((step, idx) => {
                currentChunk.push(step);
                if (currentChunk.length >= 2 || idx === steps.length - 1) {
                    screens.push(currentChunk);
                    currentChunk = [];
                }
            });
            if (screens.length === 0) screens.push([]);

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

            function renderScreen(screenIdx) {
                const screenSteps = screens[screenIdx] || [];
                const totalScreens = screens.length;
                const progressPct = Math.round(((screenIdx + 1) / totalScreens) * 100);

                overlay.innerHTML = `
                    <div class="crm-webflow-sheet">
                        <div class="crm-webflow-header">
                            <div class="crm-webflow-header-info">
                                <div class="crm-webflow-header-title">
                                    <span>✨</span>
                                    <span>${title || 'Interactive Form'}</span>
                                </div>
                                <div class="crm-webflow-header-subtitle">
                                    ${subtitle ? subtitle + ' • ' : ''}Screen ${screenIdx + 1} of ${totalScreens}
                                </div>
                            </div>
                            <button type="button" class="crm-webflow-close-btn" title="Close Form">✕</button>
                        </div>
                        <div class="crm-webflow-progress-bar">
                            <div class="crm-webflow-progress-fill" style="width: ${progressPct}%"></div>
                        </div>
                        <div class="crm-webflow-body"></div>
                        <div class="crm-webflow-footer">
                            <button type="button" class="crm-webflow-btn crm-webflow-btn-secondary btn-back-cancel">
                                ${screenIdx === 0 ? '✕ Cancel' : '← Back'}
                            </button>
                            <button type="button" class="crm-webflow-btn crm-webflow-btn-primary btn-next-submit">
                                <span>${screenIdx === totalScreens - 1 ? '🚀 Submit Form' : 'Next Screen ➔'}</span>
                            </button>
                        </div>
                    </div>
                `;

                // Wire Close & Cancel/Back
                const closeBtn = overlay.querySelector('.crm-webflow-close-btn');
                const backCancelBtn = overlay.querySelector('.btn-back-cancel');
                const nextSubmitBtn = overlay.querySelector('.btn-next-submit');
                const bodyContainer = overlay.querySelector('.crm-webflow-body');

                const dismissModal = () => {
                    overlay.style.opacity = '0';
                    setTimeout(() => overlay.remove(), 200);
                    if (onCancel) onCancel();
                };

                closeBtn.onclick = dismissModal;
                backCancelBtn.onclick = () => {
                    if (screenIdx === 0) {
                        dismissModal();
                    } else {
                        currentScreenIdx--;
                        renderScreen(currentScreenIdx);
                    }
                };

                // Render dynamic fields for this screen
                screenSteps.forEach(step => {
                    const fieldType = getFieldType(step);
                    const fieldWrap = document.createElement('div');
                    fieldWrap.className = 'crm-webflow-field';
                    fieldWrap.dataset.dataKey = step.dataKey;

                    const isReq = step.required !== false;
                    const val = formData[step.dataKey] || '';

                    const labelEl = document.createElement('label');
                    labelEl.className = 'crm-webflow-label';
                    labelEl.innerHTML = `<span>${step.question || step.dataKey}</span> ${isReq ? '<span class="required-star">*</span>' : ''}`;
                    fieldWrap.appendChild(labelEl);

                    if (fieldType === 'OPTIONS') {
                        const radioGroup = document.createElement('div');
                        radioGroup.className = 'crm-webflow-radio-group';
                        step.options.forEach(opt => {
                            const radioCard = document.createElement('div');
                            radioCard.className = `crm-webflow-radio-card ${val === opt ? 'selected' : ''}`;
                            radioCard.innerHTML = `
                                <div class="crm-webflow-radio-dot"></div>
                                <span class="crm-webflow-radio-label">${opt}</span>
                            `;
                            radioCard.onclick = () => {
                                radioGroup.querySelectorAll('.crm-webflow-radio-card').forEach(c => c.classList.remove('selected'));
                                radioCard.classList.add('selected');
                                formData[step.dataKey] = opt;
                                const errEl = fieldWrap.querySelector('.crm-webflow-field-error');
                                if (errEl) errEl.remove();
                            };
                            radioGroup.appendChild(radioCard);
                        });
                        fieldWrap.appendChild(radioGroup);
                    } else if (fieldType === 'TEXTAREA') {
                        const ta = document.createElement('textarea');
                        ta.className = 'crm-webflow-textarea';
                        ta.rows = 3;
                        ta.placeholder = `Enter ${step.question || 'details'}...`;
                        ta.value = val;
                        ta.oninput = (e) => {
                            formData[step.dataKey] = e.target.value;
                            ta.classList.remove('input-error');
                            const errEl = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (errEl) errEl.remove();
                        };
                        fieldWrap.appendChild(ta);
                    } else {
                        const input = document.createElement('input');
                        input.className = 'crm-webflow-input';
                        input.value = val;

                        if (fieldType === 'EMAIL') {
                            input.type = 'email';
                            input.placeholder = 'e.g. yourname@example.com';
                        } else if (fieldType === 'PHONE') {
                            input.type = 'tel';
                            input.placeholder = 'e.g. +91 9876543210';
                        } else if (fieldType === 'DATE') {
                            input.type = 'date';
                            // Set min date to today
                            input.min = new Date().toISOString().split('T')[0];
                        } else if (fieldType === 'TIME') {
                            input.type = 'time';
                        } else if (fieldType === 'NUMBER') {
                            input.type = 'number';
                            input.placeholder = 'Enter amount/number';
                        } else {
                            input.type = 'text';
                            input.placeholder = `Enter ${step.question || 'here'}...`;
                        }

                        input.oninput = (e) => {
                            formData[step.dataKey] = e.target.value;
                            input.classList.remove('input-error');
                            const errEl = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (errEl) errEl.remove();
                        };
                        fieldWrap.appendChild(input);
                    }

                    bodyContainer.appendChild(fieldWrap);
                });

                // Next / Submit Button Validation & Action
                nextSubmitBtn.onclick = () => {
                    let hasErrors = false;
                    screenSteps.forEach(step => {
                        const fieldType = getFieldType(step);
                        const isReq = step.required !== false;
                        const rawVal = (formData[step.dataKey] || '').toString().trim();
                        const fieldWrap = bodyContainer.querySelector(`[data-data-key="${step.dataKey}"]`);

                        // Clear existing error
                        if (fieldWrap) {
                            const oldErr = fieldWrap.querySelector('.crm-webflow-field-error');
                            if (oldErr) oldErr.remove();
                            const inputEl = fieldWrap.querySelector('input, textarea, select');
                            if (inputEl) inputEl.classList.remove('input-error');
                        }

                        let errMsg = '';
                        if (isReq && !rawVal) {
                            errMsg = 'This field is required';
                        } else if (rawVal && fieldType === 'EMAIL') {
                            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
                            if (!emailRegex.test(rawVal)) errMsg = 'Please enter a valid email address';
                        } else if (rawVal && fieldType === 'PHONE') {
                            const digits = rawVal.replace(/\D/g, '');
                            if (digits.length < 7) errMsg = 'Please enter a valid phone number (min 7 digits)';
                        }

                        if (errMsg && fieldWrap) {
                            hasErrors = true;
                            const inputEl = fieldWrap.querySelector('input, textarea, select');
                            if (inputEl) inputEl.classList.add('input-error');

                            const errDiv = document.createElement('div');
                            errDiv.className = 'crm-webflow-field-error';
                            errDiv.innerHTML = `⚠️ <span>${errMsg}</span>`;
                            fieldWrap.appendChild(errDiv);
                        }
                    });

                    if (hasErrors) return;

                    if (screenIdx < totalScreens - 1) {
                        currentScreenIdx++;
                        renderScreen(currentScreenIdx);
                    } else {
                        // Final Submit
                        nextSubmitBtn.disabled = true;
                        nextSubmitBtn.innerHTML = `
                            <svg class="crm-spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10" stroke-opacity="0.25"/><path d="M12 2a10 10 0 0 1 10 10"/></svg>
                            <span>Submitting...</span>
                        `;
                        if (onSubmit) {
                            onSubmit(formData, {
                                close: () => {
                                    overlay.style.opacity = '0';
                                    setTimeout(() => overlay.remove(), 200);
                                },
                                reset: () => {
                                    nextSubmitBtn.disabled = false;
                                    nextSubmitBtn.innerHTML = `<span>🚀 Submit Form</span>`;
                                }
                            });
                        }
                    }
                };
            }

            renderScreen(0);
            panel.appendChild(overlay);
        }
    };
}
