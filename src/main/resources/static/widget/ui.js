/**
 * CRM Chat Widget - UI Controller & DOM Component Renderer
 */

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

            widgetWrap.innerHTML = `
                <div class="chat-tooltip" id="chat-tooltip">
                    <span class="chat-tooltip-text">Hi there! 👋 Chat with us!</span>
                </div>
                <div class="chat-button" id="chat-toggle" title="Chat with us">
                    ${icons.chat}
                </div>
                <div class="chat-panel" id="chat-panel">
                    <div class="chat-header">
                        <div class="chat-header-info">
                            <div class="niche-icon-wrap" id="chat-header-icon">${theme.nicheIcon || '🤖'}</div>
                            <div class="chat-header-text">
                                <span class="chat-header-title">${theme.businessName || 'Assistant'}</span>
                                <div class="chat-header-status">
                                    <span class="status-dot-pulse"></span>
                                    <span class="status-text">Online • Replies instantly</span>
                                </div>
                            </div>
                        </div>
                        <div class="chat-header-actions">
                            <button id="chat-zoom" class="header-action-btn" title="Expand view">${icons.zoomIn}</button>
                            <button id="chat-clear" class="header-action-btn" title="New thread">${icons.clear}</button>
                            <button id="chat-close" class="header-action-btn" title="Close chat">${icons.close}</button>
                        </div>
                    </div>
                    <div class="chat-messages" id="chat-messages"></div>
                    <div id="typing" class="typing-wrap" style="display:none;">
                        <div class="typing-dots">
                            <div class="dot"></div><div class="dot"></div><div class="dot"></div>
                        </div>
                    </div>
                    <div class="chat-input-container">
                        <button id="chat-menu" class="chat-menu-btn" title="Explore Menu & Services">${icons.menu}</button>
                        <input type="text" id="chat-input" class="chat-input" placeholder="Loading..." autocomplete="off" disabled>
                        <button id="chat-send" class="send-btn" title="Send message" disabled>${icons.send}</button>
                    </div>
                    <div class="chat-confirm-overlay" id="chat-confirm-overlay">
                        <div class="chat-confirm-box">
                            <h4>Restart Chat</h4>
                            <p>Are you sure you want to start a new conversation? This will clear current messages.</p>
                            <div class="chat-confirm-actions">
                                <button id="chat-confirm-cancel" class="flow-btn" style="background:#f1f5f9;border-color:#e2e8f0;color:#64748b;">Cancel</button>
                                <button id="chat-confirm-ok" class="flow-btn selected">Start New</button>
                            </div>
                        </div>
                    </div>
                    <div class="chat-menu-overlay" id="chat-menu-overlay">
                        <div class="chat-header" style="border-radius: 24px 24px 0 0;">
                            <div class="chat-header-info">
                                <div class="niche-icon-wrap">${theme.nicheIcon || '🤖'}</div>
                                <div class="chat-header-text">
                                    <span class="chat-header-title">${theme.businessName || 'Assistant'}</span>
                                    <div class="chat-header-status">
                                        <span class="status-dot-pulse"></span>
                                        <span class="status-text">Select an option</span>
                                    </div>
                                </div>
                            </div>
                            <button id="chat-menu-close" class="header-action-btn" title="Back to chat">${icons.close}</button>
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

            toggle.onclick = () => {
                panel.classList.toggle('open');
                const tooltip = document.getElementById('chat-tooltip');
                if (panel.classList.contains('open')) {
                    if (tooltip) tooltip.style.display = 'none';
                    messages.scrollTop = messages.scrollHeight;
                    setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
                    if (!input.disabled) input.focus();
                    toggle.style.transform = 'scale(0) rotate(90deg)';
                    toggle.style.opacity = '0';
                } else {
                    if (tooltip) tooltip.style.display = 'block';
                }
            };

            closeBtn.onclick = () => {
                panel.classList.remove('open');
                const tooltip = document.getElementById('chat-tooltip');
                if (tooltip && !tooltip.classList.contains('dismissed')) tooltip.style.display = 'block';
                toggle.style.transform = 'scale(1) rotate(0deg)';
                toggle.style.opacity = '1';
            };

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
                menuBody.innerHTML = '<div style="padding:32px 20px;text-align:center;color:#64748b;font-size:14px;font-weight:500;">No additional options available.</div>';
            }
        },

        applyTheme(activeTheme, apiBase) {
            const t = activeTheme || theme;
            const { widgetWrap, toggle } = elements;

            if (t.primaryColor) {
                document.documentElement.style.setProperty('--primary-color', t.primaryColor);
            }

            document.querySelectorAll('.chat-header-title').forEach(el => {
                el.textContent = t.businessName || 'Assistant';
            });

            document.querySelectorAll('.niche-icon-wrap').forEach(iconWrap => {
                const resolvedLogo = resolveImageUrl(t.logoUrl || t.widgetIconUrl, apiBase);
                if (resolvedLogo) {
                    iconWrap.innerHTML = `<img src="${resolvedLogo}" alt="Logo" style="width:100%;height:100%;border-radius:8px;object-fit:contain;padding:2px;box-sizing:border-box;display:block;" onerror="this.onerror=null;this.parentElement.textContent='${t.nicheIcon || '🤖'}';">`;
                    iconWrap.style.background = 'transparent';
                } else {
                    iconWrap.textContent = t.nicheIcon || '🤖';
                }
            });

            if (toggle) {
                const iconImage = resolveImageUrl(t.widgetIconUrl || t.logoUrl, apiBase);
                if (iconImage) {
                    toggle.classList.add('has-custom-icon');
                    toggle.innerHTML = '';
                    const img = document.createElement('img');
                    img.src = iconImage;
                    img.alt = 'Widget Launcher Icon';
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

            // Watermark
            if (t.showWatermark && widgetWrap) {
                const existing = widgetWrap.querySelector('.crm-watermark');
                if (!existing) {
                    const watermark = document.createElement('div');
                    watermark.className = 'crm-watermark';
                    watermark.textContent = '⚡ Powered by CRMLite';
                    widgetWrap.querySelector('.chat-input-container').insertAdjacentElement('afterend', watermark);
                }
            }
        },

        renderMessageBubble(entry, containerEl) {
            const container = containerEl || elements.messages;
            if (!container || !entry || !entry.text) return;

            const msgDiv = document.createElement('div');
            msgDiv.className = `message ${entry.sender}`;

            const textSpan = document.createElement('span');
            textSpan.innerHTML = parseMarkdown(entry.text);

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date(entry.time || Date.now()).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

            msgDiv.appendChild(textSpan);
            msgDiv.appendChild(timeSpan);
            container.appendChild(msgDiv);
            container.scrollTop = container.scrollHeight;
            setTimeout(() => { container.scrollTop = container.scrollHeight; }, 30);
            return msgDiv;
        },

        renderBotBubbleWithCTAs(text, ctaButtons, onCtaSelect) {
            const { messages } = elements;
            if (!messages) return;

            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot has-ctas';

            const textSpan = document.createElement('span');
            textSpan.innerHTML = parseMarkdown(text);
            msgDiv.appendChild(textSpan);

            if (ctaButtons && ctaButtons.length > 0) {
                const ctaWrap = document.createElement('div');
                ctaWrap.className = 'cta-card-wrap';
                ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
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

            messages.appendChild(msgDiv);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
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
            textSpan.innerHTML = parseMarkdown(bodyMsg);
            msgDiv.appendChild(textSpan);

            const t = currentTheme || theme;

            if (parsed && parsed.sections && parsed.sections.length > 0 && parsed.sections[0].rows && parsed.sections[0].rows.length > 0) {
                const btnWrap = document.createElement('div');
                btnWrap.className = 'cta-card-wrap';

                parsed.sections[0].rows.forEach(btnConfig => {
                    const btn = document.createElement('button');
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

            messages.appendChild(msgDiv);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => { messages.scrollTop = messages.scrollHeight; }, 30);
            return { text: bodyMsg, sender: 'bot', time: Date.now() };
        },

        renderButtons(options, onSelect, showTypeInstead = false, onTypeInstead = null) {
            const { messages } = elements;
            if (!messages) return;

            const container = document.createElement('div');
            container.className = 'flow-buttons';

            options.forEach(opt => {
                const btn = document.createElement('button');
                btn.className = 'flow-btn';
                if (opt === 'Cancel') {
                    btn.style.cssText = 'background:#fef2f2; border:1.5px solid #fca5a5; color:#dc2626;';
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
                const skipLink = document.createElement('div');
                skipLink.className = 'flow-skip-link';
                skipLink.textContent = '✏️ Type response instead';
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
                sendBtn.style.opacity = enabled ? '1' : '0.4';
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
            el.innerHTML = `⚠️ <span>${msg}</span>`;
            messages.appendChild(el);
            messages.scrollTop = messages.scrollHeight;
            setTimeout(() => el.remove(), 3500);
        }
    };
}
