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
        menu: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="3" y1="12" x2="21" y2="12"></line><line x1="3" y1="6" x2="21" y2="6"></line><line x1="3" y1="18" x2="21" y2="18"></line></svg>`,
        calendar: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"></rect><line x1="16" y1="2" x2="16" y2="6"></line><line x1="8" y1="2" x2="8" y2="6"></line><line x1="3" y1="10" x2="21" y2="10"></line></svg>`,
        briefcase: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"></rect><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"></path></svg>`,
        info: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line></svg>`,
        settings: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>`,
        doc: `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>`,
        chat: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>`,
        close: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`,
        send: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"></line><polygon points="22 2 15 22 11 13 2 9 22 2"></polygon></svg>`,
        clear: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path><line x1="12" y1="9" x2="12" y2="15"></line><line x1="9" y1="12" x2="15" y2="12"></line></svg>`,
        zoomIn: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 3 21 3 21 9"></polyline><polyline points="9 21 3 21 3 15"></polyline><line x1="21" y1="3" x2="14" y2="10"></line><line x1="3" y1="21" x2="10" y2="14"></line></svg>`,
        zoomOut: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="4 14 10 14 10 20"></polyline><polyline points="20 10 14 10 14 4"></polyline><line x1="10" y1="14" x2="3" y2="21"></line><line x1="21" y1="3" x2="14" y2="10"></line></svg>`
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

        async startCatalogFlow() {
            this._addUserBubble('View Services/Products');
            this.mode = 'catalog';
            this._setInputEnabled(false);
            this._setTyping(true);

            try {
                const res = await fetch(`${API_BASE}/catalog/${businessId}`);
                this._setTyping(false);
                if (res.ok) {
                    const catalog = await res.json();
                    if (catalog && catalog.length > 0) {
                        this._addBotBubble('Here is our catalog:');
                        this._renderCatalog(catalog);
                    } else {
                        this._addBotBubble('Our catalog is currently empty.');
                        this._setInputEnabled(true);
                        this.mode = 'rag';
                    }
                } else {
                    this._addBotBubble('⚠️ Failed to load catalog.');
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

        _renderCatalog(catalog) {
            const container = document.createElement('div');
            container.className = 'catalog-container';
            container.style.display = 'flex';
            container.style.flexDirection = 'column';
            container.style.gap = '10px';
            container.style.marginTop = '10px';

            catalog.forEach(item => {
                const card = document.createElement('div');
                card.className = 'catalog-card';
                card.style.border = '1px solid #e2e8f0';
                card.style.borderRadius = '8px';
                card.style.padding = '12px';
                card.style.background = '#f8fafc';
                card.style.cursor = 'pointer';

                let imgHtml = '';
                if (item.hasImage) {
                    imgHtml = `<img src="/public/images/${item.id}" alt="${item.name}" style="width:100%;height:120px;object-fit:cover;border-radius:4px;margin-bottom:8px;">`;
                }

                card.innerHTML = `
                    ${imgHtml}
                    <div style="font-weight:600;color:#1e293b;font-size:14px;margin-bottom:4px;">${item.name}</div>
                    <div style="color:#64748b;font-size:12px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">${item.description || ''}</div>
                `;

                card.onclick = () => this._showCatalogDetails(item);
                container.appendChild(card);
            });

            this._messages.appendChild(container);
            this._messages.scrollTop = this._messages.scrollHeight;
            this._setInputEnabled(true);
            this.mode = 'rag';
        },

        _showCatalogDetails(item) {
            this._addUserBubble(`Tell me more about ${item.name}`);
            
            const container = document.createElement('div');
            container.className = 'catalog-details message bot';
            container.style.display = 'flex';
            container.style.flexDirection = 'column';
            
            let imgHtml = '';
            if (item.hasImage) {
                imgHtml = `<img src="/public/images/${item.id}" alt="${item.name}" style="width:100%;max-height:200px;object-fit:cover;border-radius:8px;margin-bottom:12px;">`;
            }

            container.innerHTML = `
                ${imgHtml}
                <div style="font-weight:600;font-size:16px;margin-bottom:8px;color:#1e293b;">${item.name}</div>
                <div style="font-size:14px;color:#475569;white-space:pre-wrap;line-height:1.5;">${item.description || 'No additional details available.'}</div>
            `;

            this._messages.appendChild(container);
            this._messages.scrollTop = this._messages.scrollHeight;
        },

        startAboutFlow(aboutUsText) {
            this._addUserBubble('About Us');
            if (aboutUsText && aboutUsText.trim() !== '') {
                this._addBotBubble(aboutUsText);
            } else {
                this._addBotBubble('Information about our business is coming soon!');
            }
            this.mode = 'rag';
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
                options.push('Cancel');
                this._renderButtons(options, (val) => {
                    if (val === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
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

                const btnOpts = [];
                // For optional phone step, render a Skip button
                if (step.key === 'phone' && (!this.supportConfig || this.supportConfig.phoneRequired === false)) {
                    btnOpts.push('Skip');
                }
                btnOpts.push('Cancel');
                
                this._renderButtons(btnOpts, (val) => {
                    if (val === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    this.collectedData[step.key] = '';
                    this._addUserBubble('Skip');
                    this.renderSupportStep(index + 1);
                });
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
                options.push('Cancel');
                this._renderButtons(options, (selected) => {
                    if (selected === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    this.recordAnswer(step.dataKey, selected);
                    this.advance();
                }, true);
            } else {
                this._setInputEnabled(true);
                this._input.placeholder = 'Type here...';
                this._input.focus();

                const btnOpts = [];
                if (!step.required) {
                    btnOpts.push('Skip');
                }
                btnOpts.push('Cancel');

                this._renderButtons(btnOpts, (val) => {
                    if (val === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    this.recordAnswer(step.dataKey, val);
                    this.advance();
                });
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

            if (text.toLowerCase() === 'cancel') {
                this._addUserBubble(rawValue);
                this.mode = 'rag';
                this._setInputEnabled(true);
                this._input.placeholder = 'Ask me anything...';
                this._input.value = '';
                // Remove any visible flow buttons from the screen
                const activeButtons = this._messages.querySelectorAll('.flow-buttons:last-child');
                activeButtons.forEach(b => b.style.display = 'none');
                
                this._renderCustomMenu(theme.flowCancelMenuJson, 'Form cancelled.');
                return;
            }

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
                    this.mode = 'rag';
                    setTimeout(() => {
                        this._setInputEnabled(true);
                        this._input.placeholder = 'Ask me anything...';
                        this._renderCustomMenu(theme.flowCompletionMenuJson, body.message || '✅ Thank you! We\'ll be in touch.');
                    }, 800);
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
                    if (data.ticketNumber) {
                        this._addBotBubble(`Your Ticket Number: **#${data.ticketNumber}**`);
                    }
                    setTimeout(() => {
                        this._setInputEnabled(true);
                        this._input.placeholder = 'Ask me anything...';
                        this._renderCustomMenu(theme.flowCompletionMenuJson, data.message || '✅ Support ticket created successfully!');
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
                this._showDynamicCTAs();
            }, 800);
        },

        restart() {
            this.collectedData = {};
            this.currentStep = 0;
            this.mode = 'rag';
            chatHistory = [];
            clearHistory();
            this._messages.innerHTML = '';
            this._addBotBubbleWithCTAs(theme.welcomeMessage);
            this._setInputEnabled(true);
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

        _handleMenuAction(id, title) {
            if (id === 'trigger_flow_lead') {
                this.startFlow('LEAD', title);
            } else if (id === 'trigger_flow_appointment') {
                this.startFlow('APPOINTMENT', title);
            } else if (id === 'trigger_flow_booking') {
                this.startFlow('BOOKING', title);
            } else {
                this._addUserBubble(title);
                this._input.value = title;
                document.getElementById('chat-send').click();
            }
        },

        _renderCustomMenu(jsonString, defaultMessage, overrideBodyText = false) {
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

            const entry = { text: bodyMsg, sender: 'bot', time: Date.now() };
            chatHistory.push(entry);
            saveHistory(chatHistory);

            if (!this._messages) return;

            // Build single bubble element
            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot';

            const textSpan = document.createElement('span');
            textSpan.innerHTML = String(bodyMsg).replace(/\*\*(.*?)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br>');
            msgDiv.appendChild(textSpan);

            if (parsed && parsed.sections && parsed.sections.length > 0 && parsed.sections[0].rows && parsed.sections[0].rows.length > 0) {
                const btnWrap = document.createElement('div');
                btnWrap.style.cssText = 'margin-top:10px; display:flex; flex-wrap:wrap; gap:6px;';
                
                parsed.sections[0].rows.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.className = 'flow-btn';
                    btn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px; margin:0;';
                    btn.textContent = btnConfig.title;
                    btn.onclick = () => {
                        btnWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        this._handleMenuAction(btnConfig.id, btnConfig.title);
                    };
                    btnWrap.appendChild(btn);
                });
                msgDiv.appendChild(btnWrap);
            } else {
                // Fallback to theme CTA buttons if no custom menu config is present
                if (theme.ctaButtons && theme.ctaButtons.length > 0) {
                    const ctaWrap = document.createElement('div');
                    ctaWrap.style.cssText = 'margin-top:10px; display:flex; flex-wrap:wrap; gap:6px;';
                    theme.ctaButtons.forEach(btnConfig => {
                        const btn = document.createElement('button');
                        btn.className = 'flow-btn';
                        btn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px; margin:0;';
                        btn.textContent = btnConfig.label;
                        btn.onclick = () => {
                            ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
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
                                    this._input.value = btnConfig.label;
                                    document.getElementById('chat-send').click();
                                }
                            }
                        };
                        ctaWrap.appendChild(btn);
                    });
                    msgDiv.appendChild(ctaWrap);
                }
            }

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            msgDiv.appendChild(timeSpan);

            this._messages.appendChild(msgDiv);
            this._messages.scrollTop = this._messages.scrollHeight;
        },

        _addBotBubble(text) {
            if (!text || String(text).trim() === '') text = "Hello! How can I help you today?";
            const entry = { text, sender: 'bot', time: Date.now() };
            chatHistory.push(entry);
            saveHistory(chatHistory);
            _renderMessageBubble(entry, this._messages);
        },

        // Renders greeting text + CTA buttons in a SINGLE bubble (no double bubble)
        _addBotBubbleWithCTAs(text) {
            if (!text || String(text).trim() === '') text = "Hello! How can I help you today?";
            const entry = { text, sender: 'bot', time: Date.now() };
            chatHistory.push(entry);
            saveHistory(chatHistory);

            if (!this._messages) return;

            // Build the wrapper same as _renderMessageBubble
            const msgDiv = document.createElement('div');
            msgDiv.className = 'message bot';

            const textSpan = document.createElement('span');
            textSpan.innerHTML = String(text).replace(/\*\*(.*?)\*\*/g, '<b>$1</b>').replace(/\n/g, '<br>');

            const timeSpan = document.createElement('span');
            timeSpan.className = 'message-time';
            timeSpan.textContent = new Date(entry.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

            msgDiv.appendChild(textSpan);

            // Inject CTA buttons inside the same bubble (above the timestamp)
            if (theme.ctaButtons && theme.ctaButtons.length > 0) {
                const ctaWrap = document.createElement('div');
                ctaWrap.style.cssText = 'margin-top:10px; display:flex; flex-wrap:wrap; gap:6px;';
                theme.ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.className = 'flow-btn';
                    btn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px; margin:0;';
                    btn.textContent = btnConfig.label;
                    btn.onclick = () => {
                        ctaWrap.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
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
                                this._input.value = btnConfig.label;
                                document.getElementById('chat-send').click();
                            }
                        }
                    };
                    ctaWrap.appendChild(btn);
                });
                msgDiv.appendChild(ctaWrap);
            }

            msgDiv.appendChild(timeSpan);
            this._messages.appendChild(msgDiv);
            this._messages.scrollTop = this._messages.scrollHeight;
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
                if (opt === 'Cancel') {
                    btn.style.cssText = 'background:transparent; border:1px solid #ef4444; color:#ef4444; margin-top:4px;';
                }
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
        const existingWidget = document.getElementById('crm-chat-widget');
        if (existingWidget) {
            existingWidget.remove();
        }

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
                        <button id="chat-zoom" title="Zoom" style="background:none;border:none;color:white;cursor:pointer;opacity:0.8;display:flex;">${ICONS.zoomIn}</button>
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
                    <button id="chat-menu" class="chat-menu-btn" title="Menu" style="background:none;border:none;color:#64748b;cursor:pointer;padding:8px 0 8px 8px;display:flex;align-items:center;justify-content:center;">${ICONS.menu}</button>
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
                <div class="chat-menu-overlay" id="chat-menu-overlay">
                    <div class="chat-header" style="border-radius: 24px 24px 0 0;">
                        <div class="chat-header-info">
                            <div class="niche-icon-wrap">${theme.nicheIcon}</div>
                            <span class="chat-header-title">${theme.businessName}</span>
                        </div>
                        <button id="chat-menu-close" style="background:none;border:none;color:white;cursor:pointer;display:flex;opacity:0.8;">${ICONS.close}</button>
                    </div>
                    <div class="chat-menu-body" id="chat-menu-body"></div>
                </div>
            </div>
        `;

        const panel = document.getElementById('chat-panel');
        const toggle = document.getElementById('chat-toggle');
        const input = document.getElementById('chat-input');
        const messages = document.getElementById('chat-messages');

        flowEngine.attachToDOM(messages, input, document.getElementById('chat-send'), document.getElementById('typing'));

        const menuOverlay = document.getElementById('chat-menu-overlay');
        const menuBody = document.getElementById('chat-menu-body');

        function renderMenuOverlay() {
            menuBody.innerHTML = ''; // Clear previous content
            if (theme.menuSections && theme.menuSections.length > 0) {
                theme.menuSections.forEach(section => {
                    const sectionEl = document.createElement('div');
                    sectionEl.className = 'menu-section';
                    
                    const titleEl = document.createElement('div');
                    titleEl.className = 'menu-section-title';
                    titleEl.textContent = section.title;
                    sectionEl.appendChild(titleEl);

                    const gridEl = document.createElement('div');
                    gridEl.className = 'menu-cards-grid';

                    section.cards.forEach(card => {
                        const cardEl = document.createElement('div');
                        cardEl.className = 'menu-card';
                        cardEl.innerHTML = `
                            <div class="menu-card-icon">${ICONS[card.icon] || ICONS.briefcase}</div>
                            <div class="menu-card-title">${card.title}</div>
                            <div class="menu-card-subtitle">${card.subtitle}</div>
                        `;
                        
                        cardEl.onclick = () => {
                            menuOverlay.classList.remove('active');
                            if (card.actionType === 'FLOW') {
                                flowEngine.startFlow(card.actionPayload, card.title);
                            } else if (card.actionType === 'SUPPORT') {
                                flowEngine.startSupportFlow();
                            } else if (card.actionType === 'CATALOG') {
                                flowEngine.startCatalogFlow();
                            } else if (card.actionType === 'ABOUT') {
                                flowEngine.startAboutFlow(theme.aboutUs);
                            } else if (card.actionType === 'LINK') {
                                window.open(card.actionPayload, '_blank');
                            }
                        };
                        
                        gridEl.appendChild(cardEl);
                    });
                    
                    sectionEl.appendChild(gridEl);
                    menuBody.appendChild(sectionEl);
                });
            } else {
                menuBody.innerHTML = '<div style="padding:20px;text-align:center;color:#64748b;font-size:14px;">No options available.</div>';
            }
        }
        
        // Initial render (will show "No options" until fetch completes)
        renderMenuOverlay();

        document.getElementById('chat-menu').onclick = () => {
            menuOverlay.classList.add('active');
        };
        document.getElementById('chat-menu-close').onclick = () => {
            menuOverlay.classList.remove('active');
        };

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

        // Zoom toggle
        const zoomBtn = document.getElementById('chat-zoom');
        let isZoomed = false;
        zoomBtn.onclick = () => {
            isZoomed = !isZoomed;
            panel.classList.toggle('zoomed', isZoomed);
            zoomBtn.innerHTML = isZoomed ? ICONS.zoomOut : ICONS.zoomIn;
            zoomBtn.title = isZoomed ? 'Zoom Out' : 'Zoom In';
            messages.scrollTop = messages.scrollHeight;
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
                // Determine if this is a returning user: they have messages in history before this interaction
                // Or if we track wasReturningUser flag. Wait, let's just use chatHistory.length.
                // If they have > 2 messages (e.g. initial bot greeting + their first message = 2).
                // If it's > 2, they've been here a while. Or we can check if they had history loaded.
                const isReturning = chatHistory.length > 2;
                
                const res = await fetch(`${API_BASE}/chat/${businessId}`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ message: val, isReturning: String(isReturning) })
                });
                const data = await res.json();
                
                // Update CTAs dynamically if the backend provided a fresh list
                if (data.ctaButtons !== undefined) {
                    theme.ctaButtons = data.ctaButtons;
                }

                flowEngine._setTyping(false);
                if (data.isGuardrail) {
                    let fallbackMsg = data.response || (data.reason === 'abuse_throttled' 
                        ? (theme.guardrailMessageAbuse || "We cannot process requests containing inappropriate or abusive language. Please communicate respectfully or select an option from the menu.")
                        : (theme.guardrailMessageGibberish || "We couldn't understand your request. Please rephrase your message or select one of the available options below."));
                    setTimeout(() => flowEngine._renderCustomMenu(theme.aiResponseMenuJson, fallbackMsg, true), 500);
                } else {
                    const finalResponse = data.response || "I'm sorry, I couldn't understand that.";
                    if (triggered) {
                        flowEngine._addBotBubble(finalResponse);
                        setTimeout(() => flowEngine._suggestForm(), 600);
                    } else {
                        flowEngine._renderCustomMenu(theme.aiResponseMenuJson, finalResponse, true);
                    }
                }
            } catch (e) {
                flowEngine._setTyping(false);
                flowEngine._renderCustomMenu(theme.aiResponseMenuJson, "Connection lost. Please try again.", true);
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
                
                document.querySelectorAll('.chat-header-title').forEach(el => {
                    el.textContent = theme.businessName;
                });
                
                document.querySelectorAll('.niche-icon-wrap').forEach(iconWrap => {
                    if (theme.logoUrl) {
                        iconWrap.innerHTML = `<img src="${theme.logoUrl}" alt="Logo" style="width:100%;height:100%;border-radius:50%;object-fit:cover;">`;
                        iconWrap.style.background = 'transparent';
                    } else {
                        iconWrap.textContent = theme.nicheIcon;
                    }
                });

                // Render dynamic menu cards after theme is loaded
                renderMenuOverlay();

                if (theme.showWatermark) {
                    const watermark = document.createElement('div');
                    watermark.style.cssText = 'text-align:center; font-size:10px; color:#94a3b8; padding:4px 0; background:var(--background-color); border-top:1px solid #f1f5f9;';
                    watermark.textContent = '⚡ Powered by CRMLite';
                    widgetWrap.querySelector('.chat-input-container').insertAdjacentElement('afterend', watermark);
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
                // Single bubble: greeting text + CTA buttons together
                flowEngine._addBotBubbleWithCTAs(wm);
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

