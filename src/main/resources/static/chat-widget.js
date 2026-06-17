(function () {
    console.log("CRM Chat Widget Init Started...");
    // alert("Chat Widget JS is executing!");
    const script = document.currentScript || document.querySelector('script[data-business-id]');
    const businessId = script
        ? script.getAttribute('data-business-id')
        : new URLSearchParams(window.location.search).get('businessId');

    if (!businessId) return;

    let API_BASE = 'http://localhost:8080/api/v1/public';
    if (script && script.src) {
        try {
            const url = new URL(script.src, window.location.href);
            API_BASE = `${url.origin}/api/v1/public`;
        } catch (e) {
            console.warn('Could not determine API_BASE, defaulting to localhost');
        }
    }

    // ── SVG Icons ─────────────────────────────────────────────────────────
    const ICONS = {
        chat: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>`,
        close: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`,
        send: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>`,
        clear: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>`
    };

    const STORAGE_KEY = `crm_chat_history_${businessId}`;

    function loadHistory() {
        try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); }
        catch (e) { return []; }
    }
    function saveHistory(messages) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-50))); }
        catch (e) { console.warn('CRM Chat: Could not save history', e); }
    }
    function clearHistory() { localStorage.removeItem(STORAGE_KEY); }

    let chatHistory = loadHistory();

    let theme = {
        primaryColor: '#3b82f6',
        secondaryColor: '#1e293b',
        accentColor: '#3b82f6',
        backgroundColor: '#ffffff',
        fontFamily: 'Inter, sans-serif',
        nicheIcon: '🤖',
        businessName: 'Assistant',
        welcomeMessage: 'Hello! How can I help you today?'
    };

    const flowEngine = {
        config: null,
        triggerConfig: null,
        supportConfig: null,
        formSuggested: false,
        currentStep: 0,
        collectedData: {},
        servicesCache: null,
        mode: 'idle', // 'rag', 'flow', 'support', 'submitting'

        _messages: null,
        _input: null,
        _send: null,
        _typing: null,

        attachToDOM(messagesEl, inputEl, sendEl, typingEl) {
            this._messages = messagesEl;
            this._input = inputEl;
            this._send = sendEl;
            this._typing = typingEl;
        },

        init(flowConfig) {
            this.config = flowConfig;
            this.currentStep = 0;
            this.collectedData = {};
            this.mode = 'rag'; // stays 'rag' until flow is explicitly started
            this._setInputEnabled(true);
            if (this._input) {
                this._input.placeholder = 'Ask me anything...';
            }
        },

        evaluateTrigger(message) {
            if (!this.triggerConfig || this.formSuggested || this.mode !== 'rag') return false;
            const msg = message.toLowerCase().trim();
            
            const directTriggers = this.triggerConfig.direct_triggers || [];
            if (directTriggers.some(phrase => msg.includes(phrase.toLowerCase()))) return true;

            const intentTriggers = this.triggerConfig.intent_triggers || {};
            for (const group of Object.values(intentTriggers)) {
                if ((group || []).some(phrase => msg.includes(phrase.toLowerCase()))) return true;
            }

            // Simple support trigger
            if (msg.includes('support') || msg.includes('help') || msg.includes('ticket')) {
                setTimeout(() => this._suggestSupport(), 600);
                return false; 
            }

            return false;
        },

        _suggestForm() {
            this._addBotBubble('💡 Tip: Want a quick response? You can fill a short form and we\'ll get back to you!');
            this.formSuggested = true;

            const container = document.createElement('div');
            container.className = 'flow-buttons';

            const formBtn = document.createElement('button');
            formBtn.className = 'flow-btn';
            formBtn.textContent = '📋 Fill Quick Form';
            formBtn.onclick = () => {
                container.remove();
                this._addUserBubble('📋 Fill Quick Form');
                this.mode = 'flow';
                this._setInputEnabled(false);
                this.renderStep(0);
            };

            const skipBtn = document.createElement('button');
            skipBtn.className = 'flow-btn';
            skipBtn.style.cssText = 'background:transparent; color: var(--primary-color); border: 1px solid var(--primary-color);';
            skipBtn.textContent = 'Keep chatting';
            skipBtn.onclick = () => container.remove();

            container.appendChild(formBtn);
            container.appendChild(skipBtn);
            this._messages.appendChild(container);
            this._messages.scrollTop = this._messages.scrollHeight;
        },

        _suggestSupport() {
            if (this.supportConfig && !this.supportConfig.enabled) return;
            this._addBotBubble('Need technical help? You can open a support ticket directly here.');
            
            const container = document.createElement('div');
            container.className = 'flow-buttons';

            const supportBtn = document.createElement('button');
            supportBtn.className = 'flow-btn';
            supportBtn.textContent = '🎫 Open Support Ticket';
            supportBtn.onclick = () => {
                container.remove();
                this.startSupportFlow();
            };
            container.appendChild(supportBtn);
            this._messages.appendChild(container);
            this._messages.scrollTop = this._messages.scrollHeight;
        },

        startSupportFlow() {
            this._addUserBubble('🎫 Open Support Ticket');
            this.mode = 'support';
            this.currentStep = 0;
            this.collectedData = {};
            this.renderSupportStep(0);
        },

        async renderSupportStep(index) {
            const steps = [
                { q: "What is your full name?", key: "name" },
                { q: "And your email address?", key: "email" },
                { q: "Which category best describes your issue?", key: "category", type: "buttons" },
                { q: "Please provide a short subject for your ticket.", key: "subject" },
                { q: "Tell us more about the issue. Please provide details.", key: "message" }
            ];

            if (index >= steps.length) {
                this.submitTicket();
                return;
            }

            const step = steps[index];
            this.currentStep = index;
            this._addBotBubble(step.q);

            if (step.type === 'buttons') {
                this._setInputEnabled(false);
                const categories = (this.supportConfig && this.supportConfig.categories) || ["General", "Billing", "Technical"];
                this._renderButtons(categories, (val) => {
                    this.collectedData[step.key] = val;
                    this._addUserBubble(val);
                    this.renderSupportStep(index + 1);
                });
            } else {
                this._setInputEnabled(true);
                this._input.placeholder = 'Type here...';
                this._input.focus();
            }
        },

        async renderStep(index) {
            const step = this.config.steps[index];
            if (!step) return;

            // Entering a flow step — lock mode to 'flow' so free-text
            // input is never routed to the RAG chat endpoint.
            this.mode = 'flow';
            this.currentStep = index;

            this._addBotBubble(step.question);

            if (step.usesButtons) {
                this._setInputEnabled(false);
                const options = await this.resolveOptions(step);
                this._renderButtons(options, (selected) => {
                    this.recordAnswer(step.dataKey, selected);
                    this.advance();
                }, true);
            } else {
                this._setInputEnabled(true);
                this._input.placeholder = 'Type here...';
                this._input.focus();
            }
        },

        async resolveOptions(step) {
            if (!step.dynamicSource) return step.options || [];
            if (this.servicesCache) return this.servicesCache;

            try {
                const res = await fetch(`${API_BASE}/services/${businessId}`);
                if (res.ok) {
                    const services = await res.json();
                    this.servicesCache = services.length > 0 ? services : (step.options || []);
                }
            } catch (e) {
                this.servicesCache = step.options || [];
            }
            return this.servicesCache || [];
        },

        recordAnswer(dataKey, value) {
            this.collectedData[dataKey] = value.trim();
            this._addUserBubble(value);
        },

        advance() {
            this.currentStep++;
            if (this.currentStep < this.config.steps.length) {
                this.renderStep(this.currentStep);
            } else {
                this.submit();
            }
        },

        handleFreeTextInput(rawValue) {
            const text = rawValue.trim();
            if (!text) {
                this._showValidationError('Please enter a response.');
                return;
            }

            if (this.mode === 'support') {
                this.collectedData[["name","email","category","subject","message"][this.currentStep]] = text;
                this._addUserBubble(text);
                this._input.value = '';
                this.renderSupportStep(this.currentStep + 1);
                return;
            }

            if (this.mode === 'flow') {
                const step = this.config.steps[this.currentStep];
                if (!step) return;

                // If this step uses buttons but user typed instead, validate against options
                if (step.usesButtons && step.options && step.options.length > 0) {
                    const match = step.options.find(
                        opt => opt.toLowerCase() === text.toLowerCase()
                    );
                    if (!match) {
                        this._showValidationError(
                            `Please choose one of: ${step.options.join(', ')}`
                        );
                        return;
                    }
                    this.recordAnswer(step.dataKey, match);
                } else {
                    this.recordAnswer(step.dataKey, text);
                }

                this._input.value = '';
                this.advance();
                return;
            }
        },

        async submit() {
            this.mode = 'submitting';
            this._setInputEnabled(false);
            this._setTyping(true);

            const endpointMap = { 'LEAD_CAPTURE': 'lead', 'ENQUIRY': 'enquiry', 'APPOINTMENT': 'appointment', 'BOOKING': 'booking' };
            const url = `${API_BASE}/${endpointMap[this.config.flowType] || 'enquiry'}/${businessId}`;

            try {
                const res = await fetch(url, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ data: this.collectedData })
                });

                this._setTyping(false);
                if (res.ok) {
                    const body = await res.json();
                    this.showConfirmation(body.message || '✅ Thank you! We\'ll be in touch.');
                } else {
                    this._addBotBubble('⚠️ Submission failed. Please try again later.');
                    this._setInputEnabled(true);
                }
            } catch (e) {
                this._setTyping(false);
                this._addBotBubble('⚠️ Connection error. Please try again.');
            }
        },

        async submitTicket() {
            this.mode = 'submitting';
            this._setInputEnabled(false);
            this._setTyping(true);

            try {
                const res = await fetch(`${API_BASE}/support/${businessId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.collectedData)
                });
                this._setTyping(false);
                const data = await res.json();

                if (res.ok) {
                    this.mode = 'rag';
                    this._addBotBubble(data.message || '✅ Support ticket created successfully!');
                    if (data.ticketNumber) {
                        this._addBotBubble(`Your Ticket Number: **#${data.ticketNumber}**`);
                    }
                    setTimeout(() => {
                        this._addBotBubble('Feel free to ask me anything else!');
                        this._setInputEnabled(true);
                        this._input.placeholder = 'Ask me anything...';
                    }, 1000);
                } else {
                    this._addBotBubble(`⚠️ ${data.error || 'Failed to create ticket.'}`);
                    this._setInputEnabled(true);
                }
            } catch (e) {
                this._setTyping(false);
                this._addBotBubble('⚠️ Connection error. Please try again.');
            }
        },

        showConfirmation(message) {
            this.mode = 'rag';
            this._addBotBubble(message);
            setTimeout(() => {
                this._addBotBubble('💬 Feel free to ask me anything else!');
                this._setInputEnabled(true);
                this._input.placeholder = 'Ask me anything...';
            }, 800);
        },

        restart() {
            this.collectedData = {};
            this.currentStep = 0;
            this.mode = 'rag';
            chatHistory = [];
            clearHistory();
            this._messages.innerHTML = '';
            this._addBotBubble(theme.welcomeMessage);
            this._setInputEnabled(true);
            this._showSupportOptions();
        },

        _showSupportOptions() {
            if (this.supportConfig && this.supportConfig.enabled) {
                const container = document.createElement('div');
                container.className = 'flow-buttons';
                const supportBtn = document.createElement('button');
                supportBtn.className = 'flow-btn';
                supportBtn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px;';
                supportBtn.textContent = '🎫 Get Support';
                supportBtn.onclick = () => this.startSupportFlow();
                container.appendChild(supportBtn);
                this._messages.appendChild(container);
                this._messages.scrollTop = this._messages.scrollHeight;
            }
        },

        _addBotBubble(text) {
            const entry = { text, sender: 'bot', time: Date.now() };
            chatHistory.push(entry);
            saveHistory(chatHistory);
            _renderMessageBubble(entry, this._messages);
        },

        _addUserBubble(text) {
            const entry = { text, sender: 'user', time: Date.now() };
            chatHistory.push(entry);
            saveHistory(chatHistory);
            _renderMessageBubble(entry, this._messages);
        },

        _renderButtons(options, onSelect, showTypeInstead = false) {
            const container = document.createElement('div');
            container.className = 'flow-buttons';

            options.forEach(opt => {
                const btn = document.createElement('button');
                btn.className = 'flow-btn';
                btn.textContent = opt;
                btn.onclick = () => {
                    container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                    onSelect(opt);
                };
                container.appendChild(btn);
            });

            this._messages.appendChild(container);

            if (showTypeInstead) {
                const skipLink = document.createElement('div');
                skipLink.className = 'flow-skip-link';
                skipLink.textContent = '✏️ Type instead';
                skipLink.onclick = () => {
                    skipLink.remove();
                    container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                    this._setInputEnabled(true);
                    this._input.placeholder = 'Type here...';
                    this._input.focus();
                };
                this._messages.appendChild(skipLink);
            }
            this._messages.scrollTop = this._messages.scrollHeight;
        },

        _setInputEnabled(enabled) {
            this._input.disabled = !enabled;
            this._send.disabled = !enabled;
            this._send.style.opacity = enabled ? '1' : '0.5';
        },

        _setTyping(isTyping) {
            if (isTyping) {
                this._typing.style.display = 'flex';
                this._messages.scrollTop = this._messages.scrollHeight;
            } else {
                this._typing.style.display = 'none';
            }
        },

        _showValidationError(msg) {
            const el = document.createElement('div');
            el.className = 'validation-msg';
            el.textContent = msg;
            this._messages.appendChild(el);
            this._messages.scrollTop = this._messages.scrollHeight;
            setTimeout(() => el.remove(), 3000);
        }
    };

    function _renderMessageBubble(entry, container) {
        if (!container) return;
        const msgDiv = document.createElement('div');
        msgDiv.className = `message ${entry.sender}`;

        const textSpan = document.createElement('span');
        textSpan.innerHTML = entry.text.replace(/\*\*(.*?)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br>');

        const timeSpan = document.createElement('span');
        timeSpan.className = 'message-time';
        timeSpan.textContent = new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

        msgDiv.appendChild(textSpan);
        msgDiv.appendChild(timeSpan);
        container.appendChild(msgDiv);
        container.scrollTop = container.scrollHeight;
    }

    async function bootstrap() {
        // Build basic structure
        const widgetWrap = document.createElement('div');
        widgetWrap.id = 'crm-chat-widget';
        document.body.appendChild(widgetWrap);

        widgetWrap.innerHTML = `
            <div class="chat-button" id="chat-toggle">
                ${ICONS.chat}
            </div>
            <div class="chat-panel" id="chat-panel">
                <div class="chat-header">
                    <div class="chat-header-info">
                        <div class="niche-icon-wrap">${theme.nicheIcon}</div>
                        <span class="chat-header-title">${theme.businessName}</span>
                    </div>
                    <div style="display:flex; gap:12px; align-items:center;">
                        <button id="chat-clear" title="Clear chat" style="background:none;border:none;color:white;cursor:pointer;opacity:0.8;display:flex;">${ICONS.clear}</button>
                        <button id="chat-close" style="background:none;border:none;color:white;cursor:pointer;display:flex;">${ICONS.close}</button>
                    </div>
                </div>
                <div class="chat-messages" id="chat-messages"></div>
                <div id="typing" class="typing-wrap" style="display:none;">
                    <div class="typing-dots">
                        <div class="dot"></div><div class="dot"></div><div class="dot"></div>
                    </div>
                </div>
                <div class="chat-input-container">
                    <input type="text" id="chat-input" class="chat-input" placeholder="Loading..." autocomplete="off" disabled>
                    <button id="chat-send" class="send-btn" disabled>${ICONS.send}</button>
                </div>
            </div>
        `;

        const panel = document.getElementById('chat-panel');
        const toggle = document.getElementById('chat-toggle');
        const input = document.getElementById('chat-input');
        const messages = document.getElementById('chat-messages');

        flowEngine.attachToDOM(messages, input, document.getElementById('chat-send'), document.getElementById('typing'));

        // Event listeners
        toggle.onclick = () => {
            panel.classList.toggle('open');
            if (panel.classList.contains('open')) {
                messages.scrollTop = messages.scrollHeight;
                if (!input.disabled) input.focus();
                toggle.style.transform = 'scale(0) rotate(90deg)';
                toggle.style.opacity = '0';
            }
        };

        document.getElementById('chat-close').onclick = () => {
            panel.classList.remove('open');
            toggle.style.transform = 'scale(1) rotate(0deg)';
            toggle.style.opacity = '1';
        };

        document.getElementById('chat-clear').onclick = () => {
            if (confirm('Clear conversation?')) flowEngine.restart();
        };

        const sendMessage = async () => {
            const val = input.value.trim();
            if (!val) return;

            if (flowEngine.mode === 'flow' || flowEngine.mode === 'support') {
                flowEngine.handleFreeTextInput(val);
                return;
            }

            flowEngine._addUserBubble(val);
            input.value = '';
            flowEngine._setTyping(true);

            const triggered = flowEngine.evaluateTrigger(val);

            try {
                const res = await fetch(`${API_BASE}/chat/${businessId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ message: val })
                });
                const data = await res.json();
                flowEngine._setTyping(false);
                flowEngine._addBotBubble(data.response || "I'm sorry, I couldn't understand that.");
                if (triggered) setTimeout(() => flowEngine._suggestForm(), 600);
            } catch (e) {
                flowEngine._setTyping(false);
                flowEngine._addBotBubble("Connection lost. Please try again.");
            }
        };

        document.getElementById('chat-send').onclick = sendMessage;
        input.onkeypress = (e) => { if (e.key === 'Enter') sendMessage(); };

        // Initial Data Fetch
        try {
            const [tRes, fRes, trRes, sRes] = await Promise.all([
                fetch(`${API_BASE}/config/${businessId}`),
                fetch(`${API_BASE}/flow/${businessId}`),
                fetch(`${API_BASE}/triggers/${businessId}`),
                fetch(`${API_BASE}/support/config/${businessId}`)
            ]);

            if (tRes.ok) {
                const t = await tRes.json();
                theme = { ...theme, ...t };
                document.documentElement.style.setProperty('--primary-color', theme.primaryColor);
                document.querySelector('.chat-header-title').textContent = theme.businessName;
                document.querySelector('.niche-icon-wrap').textContent = theme.nicheIcon;
            }

            if (fRes.ok) {
                const flow = await fRes.json();
                if (flow.steps && flow.steps.length) flowEngine.init(flow);
            }

            if (trRes.ok) flowEngine.triggerConfig = await trRes.json();

            if (sRes.ok) flowEngine.supportConfig = await sRes.json();

            // Load history or welcome
            if (chatHistory.length > 0) {
                chatHistory.forEach(m => _renderMessageBubble(m, messages));
            } else {
                flowEngine._addBotBubble(theme.welcomeMessage);
                flowEngine._showSupportOptions();
            }
            
            input.placeholder = 'Ask me anything...';
            flowEngine._setInputEnabled(true);

        } catch (e) {
            console.error('CRM Chat: Bootstrap failed', e);
            flowEngine._addBotBubble("System offline. Please refresh.");
        }
    }

    bootstrap();
})();

