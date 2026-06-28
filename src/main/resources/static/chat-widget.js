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
        close: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`,
        send: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>`,
        clear: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path><line x1="12" y1="9" x2="12" y2="15"></line><line x1="9" y1="12" x2="15" y2="12"></line></svg>`
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
    // Temporary cleanup for users who have the old {{business}} text cached in their browsers
    chatHistory = chatHistory.filter(m => !m.text.includes('{{business}}'));

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

        // ── Support Flow Steps — mirrors support.json exactly ────────────────
        // Step order, dataKeys and greeting text must stay in sync with
        // /flows/support.json and FlowDefinitionLoader.buildMachineDefFromSteps()
        _supportSteps() {
            return [
                { key: "name",     q: "👤 What is your full name?",                                                                         type: "text" },
                { key: "email",    q: "📧 Please provide your email address so our support team can reach you:",                             type: "text" },
                { key: "phone",    q: "📱 What is your phone number?",                                                                      type: "text" },
                { key: "category", q: "🏷️ What category best describes your issue? (e.g., Billing, Technical, General)",                  type: "buttons" },
                { key: "subject",  q: "📝 Please provide a brief subject for your request:",                                               type: "text" },
                { key: "message",  q: "💬 Tell us more about the issue. Please provide details:",                                          type: "text" }
            ];
        },

        _validateFieldInput(fieldType, key, value) {
            const val = value.trim();
            const lowerKey = key.toLowerCase();
            const isNameField = lowerKey === 'name' || lowerKey.endsWith('_name') || lowerKey.startsWith('name_');

            if (isNameField) {
                if (val.length < 2) return 'Name must be at least 2 characters long.';
                if (val.length > 255) return 'Name must not exceed 255 characters.';
            }
            if (fieldType === 'EMAIL' || lowerKey === 'email') {
                if (val !== '') {
                    const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
                    if (!emailRegex.test(val)) return 'Please enter a valid email address.';
                    if (val.length > 255) return 'Email must not exceed 255 characters.';
                }
            }
            if (fieldType === 'PHONE' || lowerKey === 'phone' || lowerKey === 'mobile') {
                if (val !== '') {
                    // Extract only digits to check length (standard E.164 without separators is 10-15 digits)
                    const digits = val.replace(/\D/g, '');
                    if (digits.length < 10 || digits.length > 15) {
                        return 'Phone number must contain between 10 and 15 digits.';
                    }
                    // Reject repeating/dummy sequences (e.g., 0000000000, 9999999999)
                    if (/^(\d)\1+$/.test(digits)) {
                        return 'Please enter a valid active phone number.';
                    }
                    // General format check allowing optional leading + and typical separators
                    const phoneRegex = /^\+?[0-9\s\-()]+$/;
                    if (!phoneRegex.test(val)) {
                        return 'Please enter a valid phone number format.';
                    }
                }
            }
            if (key === 'subject') {
                if (val.length < 3) return 'Subject must be at least 3 characters long.';
                if (val.length > 255) return 'Subject must not exceed 255 characters.';
            }
            if (key === 'message') {
                if (val.length < 10) return 'Please describe your issue in more detail (at least 10 characters).';
                if (val.length > 5000) return 'Message must not exceed 5000 characters.';
            }
            return null;
        },

        startSupportFlow() {
            this._addUserBubble('🎫 Open Support Ticket');
            this.mode = 'support';
            this.currentStep = 0;
            this.collectedData = {};
            this.renderSupportStep(0);
        },

        async startFlow(actionType, label) {
            this._addUserBubble(label);
            this.mode = 'submitting';
            this._setInputEnabled(false);
            this._setTyping(true);

            let typeParam = actionType.toLowerCase();
            if (typeParam === 'lead_capture') typeParam = 'lead';

            try {
                const res = await fetch(`${API_BASE}/flow/${businessId}?type=${typeParam}`);
                this._setTyping(false);
                if (res.ok) {
                    const flow = await res.json();
                    if (flow && flow.steps && flow.steps.length > 0) {
                        this.config = flow;
                        this.currentStep = 0;
                        this.collectedData = {};
                        this.mode = 'flow';
                        this.renderStep(0);
                    } else {
                        this._addBotBubble('⚠️ This form is not configured yet.');
                        this._setInputEnabled(true);
                        this.mode = 'rag';
                    }
                } else {
                    this._addBotBubble('⚠️ Failed to load flow config.');
                    this._setInputEnabled(true);
                    this.mode = 'rag';
                }
            } catch (e) {
                this._setTyping(false);
                this._addBotBubble('⚠️ Connection error. Please try again.');
                this._setInputEnabled(true);
                this.mode = 'rag';
            }
        },

        async renderSupportStep(index) {
            const steps = this._supportSteps();

            if (index >= steps.length) {
                this.submitTicket();
                return;
            }

            const step = steps[index];
            this.currentStep = index;

            // ── Mirror backend: greeting + first question in ONE single bubble ──
            // Exactly as FlowDefinitionLoader.buildMachineDefFromSteps() does:
            //   if (i == 0 && greetingMessage != null)
            //       questionText = greetingMessage + "\n\n" + questionText;
            let greetingMsg = (this.supportConfig && this.supportConfig.greetingMessage)
                ? this.supportConfig.greetingMessage
                : 'Welcome to our Support channel! Please provide a few details so we can assist you better.';

            const bubbleText = (index === 0)
                ? greetingMsg + '\n\n' + step.q   // ONE bubble — greeting + question merged
                : step.q;                          // subsequent steps — question only

            this._addBotBubble(bubbleText);

            if (step.type === 'buttons') {
                this._setInputEnabled(false);
                // Always load categories live from the API (mirrors StateResolver.sendDynamicCategoriesList)
                const categories = await this._loadSupportCategories();
                const isOptional = !this.supportConfig || this.supportConfig.categoryRequired === false;
                const options = [...categories];
                if (isOptional) {
                    options.push('Skip');
                }
                this._renderButtons(options, (val) => {
                    if (val === 'Skip') {
                        this.collectedData[step.key] = '';
                    } else {
                        this.collectedData[step.key] = val;
                    }
                    this._addUserBubble(val);
                    this.renderSupportStep(index + 1);
                });
            } else {
                this._setInputEnabled(true);
                this._input.placeholder = 'Type here...';
                this._input.focus();

                // For optional phone step, render a Skip button
                if (step.key === 'phone' && (!this.supportConfig || this.supportConfig.phoneRequired === false)) {
                    this._renderButtons(['Skip'], (val) => {
                        this.collectedData[step.key] = '';
                        this._addUserBubble('Skip');
                        this.renderSupportStep(index + 1);
                    });
                }
            }
        },

        // Load categories from the public support config API
        // Mirrors SupportFormConfigService.getPublicConfig() + parseCategories()
        async _loadSupportCategories() {
            // Use already-loaded config first
            if (this.supportConfig && this.supportConfig.categories && this.supportConfig.categories.length > 0) {
                return this.supportConfig.categories;
            }
            try {
                const res = await fetch(`${API_BASE}/support/config/${businessId}`);
                if (res.ok) {
                    const cfg = await res.json();
                    this.supportConfig = cfg;
                    if (cfg.categories && cfg.categories.length > 0) return cfg.categories;
                }
            } catch (e) {
                console.warn('CRM Chat: Could not load support categories', e);
            }
            return ['General', 'Billing', 'Technical'];
        },

        async renderStep(index) {
            const step = this.config.steps[index];
            if (!step) return;

            // Entering a flow step — lock mode to 'flow' so free-text
            // input is never routed to the RAG chat endpoint.
            this.mode = 'flow';
            this.currentStep = index;

            let bubbleText = step.question;
            if (index === 0 && this.config.greetingMessage && this.config.greetingMessage.trim() !== '') {
                bubbleText = this.config.greetingMessage + '\n\n' + step.question;
            }

            this._addBotBubble(bubbleText);

            const hasOptions = step.usesButtons || step.usesList || step.fieldType === 'DROPDOWN' || step.dynamicSource || (step.options && step.options.length > 0);
            if (hasOptions) {
                this._setInputEnabled(false);
                const resolvedOptions = await this.resolveOptions(step);
                const options = [...resolvedOptions];
                if (!step.required) {
                    options.push('Skip');
                }
                this._renderButtons(options, (selected) => {
                    this.recordAnswer(step.dataKey, selected);
                    this.advance();
                }, true);
            } else {
                this._setInputEnabled(true);
                this._input.placeholder = 'Type here...';
                this._input.focus();

                if (!step.required) {
                    this._renderButtons(['Skip'], (val) => {
                        this.recordAnswer(step.dataKey, val);
                        this.advance();
                    });
                }
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
            const val = value ? value.trim() : '';
            if (val.toLowerCase() === 'skip' || val === '') {
                this.collectedData[dataKey] = '';
                this._addUserBubble('Skip');
            } else {
                this.collectedData[dataKey] = val;
                this._addUserBubble(value);
            }
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

            if (this.mode === 'support') {
                // Use the step definition array for the correct dataKey — mirrors backend saveAnswer()
                const steps = this._supportSteps();
                const step = steps[this.currentStep];
                if (step) {
                    if (!text) {
                        const isOptional = (step.key === 'phone' && (!this.supportConfig || this.supportConfig.phoneRequired === false)) ||
                                           (step.key === 'category' && (!this.supportConfig || this.supportConfig.categoryRequired === false));
                        if (isOptional) {
                            this.collectedData[step.key] = '';
                            this._addUserBubble('Skip');
                            this._input.value = '';
                            this.renderSupportStep(this.currentStep + 1);
                            return;
                        } else {
                            this._showValidationError('Please enter a response.');
                            return;
                        }
                    }

                    const validationError = this._validateFieldInput(step.type, step.key, text);
                    if (validationError) {
                        this._showValidationError(validationError);
                        return;
                    }
                    this.collectedData[step.key] = text;
                }
                this._addUserBubble(text);
                this._input.value = '';
                this.renderSupportStep(this.currentStep + 1);
                return;
            }

            if (this.mode === 'flow') {
                const step = this.config.steps[this.currentStep];
                if (!step) return;

                if (!text) {
                    if (!step.required) {
                        this.recordAnswer(step.dataKey, '');
                        this._input.value = '';
                        this.advance();
                        return;
                    } else {
                        this._showValidationError('Please enter a response.');
                        return;
                    }
                }

                // If this step has options but user typed instead, validate against options
                const hasOptions = step.usesButtons || step.usesList || step.fieldType === 'DROPDOWN' || step.dynamicSource || (step.options && step.options.length > 0);
                if (hasOptions) {
                    const validOptions = step.dynamicSource ? (this.servicesCache || []) : (step.options || []);
                    if (validOptions.length > 0) {
                        const match = validOptions.find(
                            opt => opt.toLowerCase() === text.toLowerCase()
                        );
                        if (!match) {
                            if (!step.required && text.toLowerCase() === 'skip') {
                                this.recordAnswer(step.dataKey, 'Skip');
                                this._input.value = '';
                                this.advance();
                                return;
                            }
                            this._showValidationError(
                                `Please choose one of: ${validOptions.join(', ')}`
                            );
                            return;
                        }
                        this.recordAnswer(step.dataKey, match);
                    } else {
                        this.recordAnswer(step.dataKey, text);
                    }
                } else {
                    const validationError = this._validateFieldInput(step.fieldType, step.dataKey, text);
                    if (validationError) {
                        this._showValidationError(validationError);
                        return;
                    }
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
            // ── Validate required fields before hitting the backend ─────────
            // Mirrors SupportRequest @NotBlank constraints exactly:
            //   name, email, subject, message → required
            //   phone, category               → optional
            const d = this.collectedData;
            const missing = [];
            if (!d.name    || !d.name.trim())    missing.push('name');
            if (!d.email   || !d.email.trim())   missing.push('email address');
            if (!d.subject || !d.subject.trim()) missing.push('subject');
            if (!d.message || !d.message.trim()) missing.push('issue description');

            if (missing.length > 0) {
                this._addBotBubble(`⚠️ Please provide your ${missing.join(', ')} before submitting.`);
                // Re-send the flow from the first missing field
                this.mode = 'support';
                const stepKeys = this._supportSteps().map(s => s.key);
                const firstMissingStep = stepKeys.findIndex(k => !d[k] || !d[k].trim());
                this.renderSupportStep(firstMissingStep >= 0 ? firstMissingStep : 0);
                return;
            }

            // ── Build payload matching SupportRequest exactly ───────────────
            // Optional fields: send null instead of empty string so @Size doesn't
            // fail and the backend treats them as absent
            const payload = {
                name:     d.name.trim(),
                email:    d.email.trim(),
                subject:  d.subject.trim(),
                message:  d.message.trim(),
                phone:    (d.phone    && d.phone.trim())    ? d.phone.trim()    : null,
                category: (d.category && d.category.trim()) ? d.category.trim() : null
            };

            this.mode = 'submitting';
            this._setInputEnabled(false);
            this._setTyping(true);

            // DEBUG: log the exact payload being sent — check browser DevTools Console
            console.log('[CRM Support] Submitting payload:', JSON.stringify(payload, null, 2));

            try {
                const res = await fetch(`${API_BASE}/support/${businessId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                this._setTyping(false);
                const data = await res.json();

                // DEBUG: log exact server response
                if (!res.ok) console.error('[CRM Support] Server error response:', res.status, JSON.stringify(data));

                if (res.ok || res.status === 201) {
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
                } else if (res.status === 400) {
                    // Backend @Valid validation error — show the error message
                    const errMsg = data.error || data.message
                        || (data.errors && Object.values(data.errors).join(', '))
                        || 'Some required fields are missing. Please check your inputs.';
                    this._addBotBubble(`⚠️ ${errMsg}`);
                    this.mode = 'support';
                    this._setInputEnabled(true);
                } else if (res.status === 429) {
                    this._addBotBubble('⚠️ Too many requests. Please wait a moment and try again.');
                    this.mode = 'rag';
                    this._setInputEnabled(true);
                } else {
                    this._addBotBubble(`⚠️ ${data.error || 'Failed to create ticket. Please try again.'}`);
                    this.mode = 'support';
                    this._setInputEnabled(true);
                }
            } catch (e) {
                this._setTyping(false);
                this._addBotBubble('⚠️ Connection error. Please check your connection and try again.');
                this.mode = 'support';
                this._setInputEnabled(true);
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
            this._showDynamicCTAs();
        },

        _showDynamicCTAs() {
            if (theme.ctaButtons && theme.ctaButtons.length > 0) {
                const container = document.createElement('div');
                container.className = 'flow-buttons';
                theme.ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.className = 'flow-btn';
                    btn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px; margin-right: 5px; margin-bottom: 5px;';
                    btn.textContent = btnConfig.label;
                    btn.onclick = () => {
                        container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        if (btnConfig.action === 'SUPPORT') {
                            this.startSupportFlow();
                        } else if (btnConfig.action === 'APPOINTMENT' || btnConfig.action === 'BOOKING' || btnConfig.action === 'LEAD') {
                            this.startFlow(btnConfig.action, btnConfig.label);
                        } else {
                            if (this.config && this.config.steps && this.config.steps.length > 0) {
                                this._addUserBubble(btnConfig.label);
                                this.mode = 'flow';
                                this._setInputEnabled(false);
                                this.renderStep(0);
                            } else {
                                // Fallback to RAG if no form is configured
                                this._input.value = btnConfig.label;
                                document.getElementById('chat-send').click();
                            }
                        }
                    };
                    container.appendChild(btn);
                });
                this._messages.appendChild(container);
                this._messages.scrollTop = this._messages.scrollHeight;
            }
        },

        _addBotBubble(text) {
            if (!text || String(text).trim() === '') text = "Hello! How can I help you today?";
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
        if (!entry || !entry.text) return;
        const msgDiv = document.createElement('div');
        msgDiv.className = `message ${entry.sender}`;

        const textSpan = document.createElement('span');
        textSpan.innerHTML = String(entry.text).replace(/\*\*(.*?)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br>');

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
                        <div class="niche-icon-wrap" id="chat-header-icon">${theme.nicheIcon}</div>
                        <span class="chat-header-title">${theme.businessName}</span>
                    </div>
                    <div style="display:flex; gap:12px; align-items:center;">
                        <button id="chat-clear" title="New thread" style="background:none;border:none;color:white;cursor:pointer;opacity:0.8;display:flex;">${ICONS.clear}</button>
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
                <div class="chat-confirm-overlay" id="chat-confirm-overlay">
                    <div class="chat-confirm-box">
                        <h4>Restart Chat</h4>
                        <p>Are you sure you want to start a new conversation? This will clear current messages.</p>
                        <div class="chat-confirm-actions">
                            <button id="chat-confirm-cancel" class="flow-btn">Cancel</button>
                            <button id="chat-confirm-ok" class="flow-btn selected">Start New</button>
                        </div>
                    </div>
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
            document.getElementById('chat-confirm-overlay').classList.add('active');
        };

        document.getElementById('chat-confirm-cancel').onclick = () => {
            document.getElementById('chat-confirm-overlay').classList.remove('active');
        };

        document.getElementById('chat-confirm-ok').onclick = () => {
            document.getElementById('chat-confirm-overlay').classList.remove('active');
            flowEngine.restart();
        };

        const sendMessage = async () => {
            const val = input.value.trim();

            if (flowEngine.mode === 'flow' || flowEngine.mode === 'support') {
                flowEngine.handleFreeTextInput(val);
                return;
            }

            if (!val) return;

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
                
                const iconWrap = document.getElementById('chat-header-icon');
                if (theme.logoUrl) {
                    iconWrap.innerHTML = `<img src="${theme.logoUrl}" alt="Logo" style="width:100%;height:100%;border-radius:50%;object-fit:cover;">`;
                    iconWrap.style.background = 'transparent';
                } else {
                    iconWrap.textContent = theme.nicheIcon;
                }

                if (theme.showWatermark) {
                    const watermark = document.createElement('div');
                    watermark.style.cssText = 'text-align:center; font-size:10px; color:#94a3b8; padding:4px 0; background:var(--background-color); border-top:1px solid #f1f5f9;';
                    watermark.textContent = '⚡ Powered by CRMLite';
                    document.querySelector('.chat-input-container').insertAdjacentElement('afterend', watermark);
                }
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
                flowEngine._showDynamicCTAs();
            } else {
                let wm = theme.welcomeMessage;
                if (!wm || String(wm).trim() === '') wm = 'Hello! How can I help you today?';
                flowEngine._addBotBubble(wm);
                flowEngine._showDynamicCTAs();
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

