/**
 * CRM Chat Widget - Flow Engine & State Machine
 */

import { SUPPORT_STEPS, ENDPOINT_MAP, FORM_META } from './constants.js';
import { validateFieldInput, validateSupportPayload } from './validator.js';

export function createFlowEngine({
    apiClient,
    storage,
    ui,
    catalogManager,
    getTheme,
    setTheme
}) {
    let config = null;
    let triggerConfig = null;
    let supportConfig = null;
    let formSuggested = false;
    let currentStep = 0;
    let collectedData = {};
    let servicesCache = null;
    let mode = 'idle'; // 'rag', 'flow', 'support', 'catalog', 'live_human', 'submitting'

    function addBotBubble(text) {
        const t = text || "Hello! How can I help you today?";
        const entry = { text: t, sender: 'bot', time: Date.now() };
        const history = storage.loadHistory();
        history.push(entry);
        storage.saveHistory(history);
        ui.renderMessageBubble(entry);
    }

    function addUserBubble(text) {
        const entry = { text, sender: 'user', time: Date.now() };
        const history = storage.loadHistory();
        history.push(entry);
        storage.saveHistory(history);
        ui.renderMessageBubble(entry);
    }

    function addBotBubbleWithCTAs(text) {
        const t = text || "Hello! How can I help you today?";
        const currentTheme = getTheme();
        const ctas = currentTheme.ctaButtons || [];
        const entry = { text: t, sender: 'bot', time: Date.now(), ctas: ctas.length ? ctas : undefined };
        const history = storage.loadHistory();
        history.push(entry);
        storage.saveHistory(history);

        ui.renderBotBubbleWithCTAs(t, ctas, (btnConfig) => {
            engine.handleCtaAction(btnConfig);
        });
    }

    function markLastCtasUsed() {
        const history = storage.loadHistory();
        for (let i = history.length - 1; i >= 0; i--) {
            if (history[i].ctas && history[i].ctas.length > 0 && !history[i].ctasUsed) {
                history[i].ctasUsed = true;
                storage.saveHistory(history);
                break;
            }
        }
    }

    const engine = {
        get mode() { return mode; },
        set mode(val) { mode = val; },

        get triggerConfig() { return triggerConfig; },
        set triggerConfig(val) { triggerConfig = val; },

        get supportConfig() { return supportConfig; },
        set supportConfig(val) { supportConfig = val; },

        get collectedData() { return collectedData; },

        init(flowConfig) {
            config = flowConfig;
            currentStep = 0;
            collectedData = {};
            mode = 'rag';
            ui.setInputEnabled(true, 'Ask me anything...');
        },

        evaluateTrigger(message) {
            if (!triggerConfig || formSuggested || mode !== 'rag') return false;
            const msg = (message || '').toLowerCase().trim();

            const directTriggers = triggerConfig.direct_triggers || [];
            if (directTriggers.some(phrase => msg.includes(phrase.toLowerCase()))) return true;

            const intentTriggers = triggerConfig.intent_triggers || {};
            for (const group of Object.values(intentTriggers)) {
                if ((group || []).some(phrase => msg.includes(phrase.toLowerCase()))) return true;
            }

            if (msg.includes('support') || msg.includes('help') || msg.includes('ticket')) {
                setTimeout(() => this.suggestSupport(), 600);
                return false;
            }

            return false;
        },

        suggestForm() {
            addBotBubble('Tip: Want a quick response? You can fill a short form and we\'ll get back to you.');
            formSuggested = true;

            ui.renderButtons(['Fill Quick Form', 'Keep chatting'], (val) => {
                if (val === 'Fill Quick Form') {
                    addUserBubble('Fill Quick Form');
                    mode = 'flow';
                    ui.setInputEnabled(false);
                    this.renderStep(0);
                } else if (val === 'Keep chatting') {
                    ui.removeLastFlowButtons();
                }
            });
        },

        suggestSupport() {
            if (supportConfig && supportConfig.enabled === false) return;
            addBotBubble('Need technical help? You can open a support ticket or talk directly with a live support agent.');

            ui.renderButtons(['Connect with Live Agent', 'Open Support Ticket'], (val) => {
                if (val === 'Connect with Live Agent') {
                    this.requestLiveSupport();
                } else if (val === 'Open Support Ticket') {
                    this.startSupportFlow();
                }
            });
        },

        async requestLiveSupport() {
            addUserBubble('Connect with Live Agent');
            ui.setTyping(true);

            try {
                const sessionId = storage.getSessionId();
                const result = await apiClient.requestLiveSupport({
                    sessionId,
                    name: collectedData.name || 'Website Visitor',
                    email: collectedData.email || '',
                    phone: collectedData.phone || ''
                });

                ui.setTyping(false);
                if (result.ok) {
                    addBotBubble(result.data.response || 'Connected with live support agent.');
                    mode = 'live_human';
                } else {
                    addBotBubble('Support team is currently unavailable. Please open a support ticket.');
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('Connection error. Please try again.');
            }
        },

        getFormMeta(actionType) {
            const key = (actionType || '').toUpperCase();
            return FORM_META[key] || FORM_META.DEFAULT;
        },

        getFormIconSvg(iconKey) {
            const icons = {
                calendar: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4"/><path d="M8 3v4"/><path d="M3 11h18"/></svg>`,
                briefcase: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M3 13h18"/></svg>`,
                doc: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/></svg>`,
                settings: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg>`
            };
            return icons[iconKey] || icons.doc;
        },

        openWebFlowForm({ actionType, label, title, subtitle, steps, onSubmit, onCancel, autoOpen = true }) {
            const meta = this.getFormMeta(actionType);
            const iconSvg = this.getFormIconSvg(meta.icon);
            const formTitle = title || label || meta.defaultTitle;
            const formSubtitle = subtitle || meta.defaultSubtitle;

            const openInlineForm = (anchorCard, anchorRow) => {
                ui.renderWebFlowModal({
                    title: formTitle,
                    subtitle: formSubtitle,
                    steps: steps || [],
                    anchorCard,
                    anchorRow,
                    onSubmit,
                    onCancel
                });
            };

            if (autoOpen) {
                ui.renderWebFlowInline({
                    title: formTitle,
                    subtitle: formSubtitle,
                    badgeText: meta.badge,
                    iconSvg,
                    steps: steps || [],
                    onSubmit,
                    onCancel
                });
                return;
            }

            const ctaResult = ui.renderWebFlowCtaCard({
                title: formTitle,
                subtitle: formSubtitle,
                badgeText: meta.badge,
                buttonText: meta.buttonText,
                iconSvg,
                onOpen: (card, row) => openInlineForm(card, row)
            });

            return ctaResult;
        },

        getWebFlowRoutingMode(category) {
            try {
                const currentTheme = getTheme() || {};
                const routingJson = currentTheme.webFlowsRoutingConfigJson;
                if (!routingJson) return 'WEB_FLOW';
                const routing = typeof routingJson === 'string' ? JSON.parse(routingJson) : routingJson;
                
                const cat = (category || '').toLowerCase();
                if (cat.includes('appt') || cat.includes('appointment')) return routing.appointments || routing.appointment || 'WEB_FLOW';
                if (cat.includes('book')) return routing.bookings || routing.booking || 'WEB_FLOW';
                if (cat.includes('lead') || cat.includes('enquiry')) return routing.leadGen || routing.lead || 'WEB_FLOW';
                if (cat.includes('supp') || cat.includes('ticket')) return routing.support || 'WEB_FLOW';
                if (cat.includes('feed') || cat.includes('survey')) return routing.feedback || 'WEB_FLOW';
                return routing.default || 'WEB_FLOW';
            } catch (e) {
                return 'WEB_FLOW';
            }
        },

        async startSupportFlow() {
            addUserBubble('Open Support Ticket');
            const routingMode = this.getWebFlowRoutingMode('support');

            if (routingMode === 'CHATBOT') {
                mode = 'support';
                currentStep = 0;
                collectedData = {};
                this.renderSupportStep(0);
            } else {
                mode = 'rag';
                ui.setInputEnabled(true, 'Ask me anything...');

                const categories = await this.loadSupportCategories();
                const supportSteps = [
                    { dataKey: 'name', question: 'Your Full Name', fieldType: 'TEXT', required: true },
                    { dataKey: 'email', question: 'Email Address', fieldType: 'EMAIL', required: true },
                    { dataKey: 'phone', question: 'Phone Number', fieldType: 'PHONE', required: false },
                    { dataKey: 'category', question: 'Issue Category', fieldType: 'OPTIONS', options: categories || ['General', 'Technical', 'Billing'], required: true },
                    { dataKey: 'subject', question: 'Subject / Summary', fieldType: 'TEXT', required: true },
                    { dataKey: 'description', question: 'Detailed Description', fieldType: 'TEXTAREA', required: true }
                ];

                this.openWebFlowForm({
                    actionType: 'SUPPORT',
                    title: 'Support Ticket Request',
                    subtitle: 'Customer Care',
                    steps: supportSteps,
                    onSubmit: async (formData, { close, reset }) => {
                        try {
                            const messageText = formData.message || formData.description || '';
                            const res = await apiClient.submitSupportTicket({
                                name: formData.name,
                                email: formData.email,
                                phone: formData.phone || '',
                                subject: formData.subject,
                                message: messageText,
                                description: messageText,
                                category: formData.category,
                                priority: 'MEDIUM'
                            });
                            if (res.ok) {
                                close();
                                const ticketNum = res.data && res.data.ticketNumber ? ` (Ticket #${res.data.ticketNumber})` : '';
                                ui.renderWebFlowSuccessCard({
                                    title: `Support ticket created${ticketNum}`,
                                    details: `Your ticket has been logged with our team. A confirmation email has been sent to ${formData.email}.`
                                });
                                mode = 'rag';
                                ui.setInputEnabled(true, 'Ask me anything...');
                            } else {
                                reset();
                                ui.showValidationError('Failed to submit ticket. Please check your fields and try again.');
                            }
                        } catch (err) {
                            reset();
                            ui.showValidationError('Network error submitting ticket. Please try again.');
                        }
                    },
                    onCancel: () => {
                        mode = 'rag';
                        ui.setInputEnabled(true, 'Ask me anything...');
                    }
                });
            }
        },

        async startFlow(actionType, label, preselectedService) {
            addUserBubble(label);
            mode = 'submitting';
            ui.setInputEnabled(false);
            ui.setTyping(true);

            try {
                const flow = await apiClient.fetchFlow(actionType);
                ui.setTyping(false);
                if (flow && flow.steps && flow.steps.length > 0) {
                    config = flow;
                    currentStep = 0;
                    collectedData = {};
                    if (preselectedService) {
                        collectedData.service = preselectedService;
                        collectedData.selectedService = preselectedService;
                    }

                    const routingMode = this.getWebFlowRoutingMode(actionType);
                    if (routingMode === 'CHATBOT') {
                        // Conversational mode (one question bubble at a time)
                        mode = 'flow';
                        this.renderStep(0);
                    } else {
                        mode = 'rag';
                        ui.setInputEnabled(true, 'Ask me anything...');

                        this.openWebFlowForm({
                            actionType,
                            label,
                            initialData: preselectedService ? { service: preselectedService, selectedService: preselectedService } : undefined,
                            title: label || this.getFormMeta(actionType).defaultTitle,
                            subtitle: (flow.greetingMessage && String(flow.greetingMessage).trim())
                                ? flow.greetingMessage
                                : undefined,
                            steps: flow.steps || [],
                            onSubmit: async (formData, { close, reset }) => {
                                try {
                                    const ep = ENDPOINT_MAP[actionType] || (actionType ? actionType.toLowerCase() : 'lead');
                                    const sessionId = storage.getSessionId();
                                    const res = await apiClient.submitFlow(ep, formData, sessionId);
                                    if (res.ok) {
                                        close();
                                        ui.renderWebFlowSuccessCard({
                                            title: 'Request submitted successfully',
                                            details: `Thank you. Your information has been recorded. A confirmation will be sent to ${formData.email || 'your email'}.`
                                        });
                                        mode = 'rag';
                                        ui.setInputEnabled(true, 'Ask me anything...');
                                    } else {
                                        reset();
                                        ui.showValidationError('Submission error. Please check the required fields and try again.');
                                    }
                                } catch (err) {
                                    reset();
                                    ui.showValidationError('Network connection error. Please try again.');
                                }
                            },
                            onCancel: () => {
                                mode = 'rag';
                                ui.setInputEnabled(true, 'Ask me anything...');
                            }
                        });
                    }
                } else {
                    addBotBubble('This form is not configured yet.');
                    ui.setInputEnabled(true);
                    mode = 'rag';
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('Connection error. Please try again.');
                ui.setInputEnabled(true);
                mode = 'rag';
            }
        },

        async startCatalogFlow() {
            addUserBubble('View Services/Products');
            mode = 'rag';
            ui.setInputEnabled(true, 'Ask me anything...');
            ui.setTyping(true);

            try {
                const catalog = await apiClient.fetchCatalog();
                ui.setTyping(false);
                if (catalog && catalog.length > 0) {
                    addBotBubble('Here is our catalog:');
                    if (catalogManager) catalogManager.renderCatalog(catalog);
                    ui.setInputEnabled(true, 'Ask me anything...');
                } else {
                    addBotBubble('Our catalog is currently empty.');
                    ui.setInputEnabled(true, 'Ask me anything...');
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('Connection error. Please try again.');
                ui.setInputEnabled(true, 'Ask me anything...');
            }
            mode = 'rag';
        },

        startAboutFlow(aboutUsText) {
            addUserBubble('About Us');
            if (aboutUsText && aboutUsText.trim() !== '') {
                addBotBubble(aboutUsText);
            } else {
                addBotBubble('Information about our business is coming soon!');
            }
            mode = 'rag';
            ui.setInputEnabled(true, 'Ask me anything...');
        },

        async renderSupportStep(index) {
            const steps = SUPPORT_STEPS;
            if (index >= steps.length) {
                this.submitTicket();
                return;
            }

            const step = steps[index];
            currentStep = index;

            let greetingMsg = 'Welcome to our Support channel. Please provide a few details so we can assist you.';
            if (supportConfig) {
                if (supportConfig.greetingMessage && String(supportConfig.greetingMessage).trim()) {
                    greetingMsg = supportConfig.greetingMessage;
                } else if (supportConfig.formDescription && String(supportConfig.formDescription).trim()) {
                    greetingMsg = supportConfig.formDescription;
                } else if (supportConfig.formTitle && String(supportConfig.formTitle).trim()) {
                    greetingMsg = supportConfig.formTitle;
                }
            }

            const bubbleText = (index === 0)
                ? greetingMsg + '\n\n' + step.q
                : step.q;

            addBotBubble(bubbleText);

            if (step.type === 'buttons') {
                ui.setInputEnabled(false);
                const categories = await this.loadSupportCategories();
                const isOptional = !supportConfig || supportConfig.categoryRequired === false;
                const options = [...categories];
                if (isOptional) options.push('Skip');
                options.push('Cancel');

                ui.renderButtons(options, (val) => {
                    if (val === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    if (val === 'Skip') {
                        collectedData[step.key] = '';
                    } else {
                        collectedData[step.key] = val;
                    }
                    addUserBubble(val);
                    this.renderSupportStep(index + 1);
                });
            } else {
                ui.setInputEnabled(true, 'Type here...');
                const inputEl = ui.getElements().input;
                if (inputEl) inputEl.focus();

                const btnOpts = [];
                if (step.key === 'phone' && (!supportConfig || supportConfig.phoneRequired === false)) {
                    btnOpts.push('Skip');
                }
                btnOpts.push('Cancel');

                ui.renderButtons(btnOpts, (val) => {
                    if (val === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    collectedData[step.key] = '';
                    addUserBubble('Skip');
                    this.renderSupportStep(index + 1);
                });
            }
        },

        async loadSupportCategories() {
            if (supportConfig && supportConfig.categories && supportConfig.categories.length > 0) {
                return supportConfig.categories;
            }
            try {
                const cfg = await apiClient.fetchSupportConfig();
                supportConfig = cfg;
                if (cfg.categories && cfg.categories.length > 0) return cfg.categories;
            } catch (e) {
                console.warn('CRM Chat: Could not load support categories', e);
            }
            return ['General', 'Billing', 'Technical'];
        },

        async renderStep(index) {
            if (!config || !config.steps) return;
            const step = config.steps[index];
            if (!step) return;

            mode = 'flow';
            currentStep = index;

            let bubbleText = step.question;
            if (index === 0 && config.greetingMessage && config.greetingMessage.trim() !== '') {
                bubbleText = config.greetingMessage + '\n\n' + step.question;
            }

            addBotBubble(bubbleText);

            const hasOptions = step.usesButtons || step.usesList || step.fieldType === 'DROPDOWN' || step.dynamicSource || (step.options && step.options.length > 0);
            if (hasOptions) {
                ui.setInputEnabled(false);
                const resolvedOptions = await this.resolveOptions(step);
                const options = [...resolvedOptions];
                if (!step.required) options.push('Skip');
                options.push('Cancel');

                ui.renderButtons(options, (selected) => {
                    if (selected === 'Cancel') {
                        this.handleFreeTextInput('Cancel');
                        return;
                    }
                    this.recordAnswer(step.dataKey, selected);
                    this.advance();
                }, true, () => {
                    ui.setInputEnabled(true, 'Type here...');
                    const inputEl = ui.getElements().input;
                    if (inputEl) inputEl.focus();
                });
            } else {
                ui.setInputEnabled(true, 'Type here...');
                const inputEl = ui.getElements().input;
                if (inputEl) inputEl.focus();

                const btnOpts = [];
                if (!step.required) btnOpts.push('Skip');
                btnOpts.push('Cancel');

                ui.renderButtons(btnOpts, (val) => {
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
            if (servicesCache) return servicesCache;

            try {
                const services = await apiClient.fetchServices();
                servicesCache = (services && services.length > 0) ? services : (step.options || []);
            } catch (e) {
                servicesCache = step.options || [];
            }
            return servicesCache || [];
        },

        recordAnswer(dataKey, value) {
            const val = value ? String(value).trim() : '';
            if (val.toLowerCase() === 'skip' || val === '') {
                collectedData[dataKey] = '';
                addUserBubble('Skip');
            } else {
                collectedData[dataKey] = val;
                addUserBubble(value);
            }
        },

        advance() {
            currentStep++;
            if (config && currentStep < config.steps.length) {
                this.renderStep(currentStep);
            } else {
                this.submit();
            }
        },

        handleFreeTextInput(rawValue) {
            const text = (rawValue || '').trim();

            if (text.toLowerCase() === 'cancel') {
                addUserBubble(rawValue);
                mode = 'rag';
                ui.setInputEnabled(true, 'Ask me anything...');
                const inputEl = ui.getElements().input;
                if (inputEl) inputEl.value = '';

                ui.removeLastFlowButtons();

                const currentTheme = getTheme();
                const menuEntry = ui.renderCustomMenu(currentTheme.flowCancelMenuJson, 'Form cancelled.', false, currentTheme, (id, title, actionType) => {
                    this.handleMenuAction(id, title, actionType);
                });
                if (menuEntry) {
                    const history = storage.loadHistory();
                    history.push(menuEntry);
                    storage.saveHistory(history);
                }
                return;
            }

            if (mode === 'support') {
                const steps = SUPPORT_STEPS;
                const step = steps[currentStep];
                if (step) {
                    if (!text) {
                        const isOptional = (step.key === 'phone' && (!supportConfig || supportConfig.phoneRequired === false)) ||
                            (step.key === 'category' && (!supportConfig || supportConfig.categoryRequired === false));
                        if (isOptional) {
                            collectedData[step.key] = '';
                            addUserBubble('Skip');
                            const inputEl = ui.getElements().input;
                            if (inputEl) inputEl.value = '';
                            this.renderSupportStep(currentStep + 1);
                            return;
                        } else {
                            ui.showValidationError('Please enter a response.');
                            return;
                        }
                    }

                    const validationError = validateFieldInput(step.type, step.key, text);
                    if (validationError) {
                        ui.showValidationError(validationError);
                        return;
                    }
                    collectedData[step.key] = text;
                }
                addUserBubble(text);
                const inputEl = ui.getElements().input;
                if (inputEl) inputEl.value = '';
                this.renderSupportStep(currentStep + 1);
                return;
            }

            if (mode === 'flow' && config) {
                const step = config.steps[currentStep];
                if (!step) return;

                if (!text) {
                    if (!step.required) {
                        this.recordAnswer(step.dataKey, '');
                        const inputEl = ui.getElements().input;
                        if (inputEl) inputEl.value = '';
                        this.advance();
                        return;
                    } else {
                        ui.showValidationError('Please enter a response.');
                        return;
                    }
                }

                const hasOptions = step.usesButtons || step.usesList || step.fieldType === 'DROPDOWN' || step.dynamicSource || (step.options && step.options.length > 0);
                if (hasOptions) {
                    const validOptions = step.dynamicSource ? (servicesCache || []) : (step.options || []);
                    if (validOptions.length > 0) {
                        const match = validOptions.find(opt => opt.toLowerCase() === text.toLowerCase());
                        if (!match) {
                            if (!step.required && text.toLowerCase() === 'skip') {
                                this.recordAnswer(step.dataKey, 'Skip');
                                const inputEl = ui.getElements().input;
                                if (inputEl) inputEl.value = '';
                                this.advance();
                                return;
                            }
                            ui.showValidationError(`Please choose one of: ${validOptions.join(', ')}`);
                            return;
                        }
                        this.recordAnswer(step.dataKey, match);
                    } else {
                        this.recordAnswer(step.dataKey, text);
                    }
                } else {
                    const validationError = validateFieldInput(step.fieldType, step.dataKey, text);
                    if (validationError) {
                        ui.showValidationError(validationError);
                        return;
                    }
                    this.recordAnswer(step.dataKey, text);
                }

                const inputEl = ui.getElements().input;
                if (inputEl) inputEl.value = '';
                this.advance();
                return;
            }
        },

        async submit() {
            mode = 'submitting';
            ui.setInputEnabled(false);
            ui.setTyping(true);

            const endpoint = ENDPOINT_MAP[config.flowType] || 'enquiry';

            try {
                const result = await apiClient.submitFlow(endpoint, collectedData, storage.getSessionId());
                ui.setTyping(false);
                if (result.ok) {
                    mode = 'rag';
                    setTimeout(() => {
                        ui.setInputEnabled(true, 'Ask me anything...');
                        const currentTheme = getTheme();
                        const menuEntry = ui.renderCustomMenu(currentTheme.flowCompletionMenuJson, result.data.message || 'Thank you! We\'ll be in touch.', false, currentTheme, (id, title, actionType) => {
                            this.handleMenuAction(id, title, actionType);
                        });
                        if (menuEntry) {
                            const history = storage.loadHistory();
                            history.push(menuEntry);
                            storage.saveHistory(history);
                        }
                    }, 800);
                } else {
                    addBotBubble('Submission failed. Please try again later.');
                    ui.setInputEnabled(true);
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('Connection error. Please try again.');
            }
        },

        async submitTicket() {
            const validation = validateSupportPayload(collectedData);

            if (!validation.isValid) {
                addBotBubble(`Please provide your ${validation.missing.join(', ')} before submitting.`);
                mode = 'support';
                const stepKeys = SUPPORT_STEPS.map(s => s.key);
                const firstMissingStep = stepKeys.findIndex(k => !collectedData[k] || !collectedData[k].trim());
                this.renderSupportStep(firstMissingStep >= 0 ? firstMissingStep : 0);
                return;
            }

            mode = 'submitting';
            ui.setInputEnabled(false);
            ui.setTyping(true);

            try {
                const result = await apiClient.submitSupportTicket(validation.payload);
                ui.setTyping(false);
                const data = result.data || {};

                if (result.ok) {
                    mode = 'rag';
                    if (data.ticketNumber) {
                        addBotBubble(`Your Ticket Number: **#${data.ticketNumber}**`);
                    }
                    setTimeout(() => {
                        ui.setInputEnabled(true, 'Ask me anything...');
                        const currentTheme = getTheme();
                        const menuEntry = ui.renderCustomMenu(currentTheme.flowCompletionMenuJson, data.message || 'Support ticket created successfully.', false, currentTheme, (id, title, actionType) => {
                            this.handleMenuAction(id, title, actionType);
                        });
                        if (menuEntry) {
                            const history = storage.loadHistory();
                            history.push(menuEntry);
                            storage.saveHistory(history);
                        }
                    }, 1000);
                } else if (result.status === 400) {
                    const errMsg = data.error || data.message || (data.errors && Object.values(data.errors).join(', ')) || 'Some required fields are missing. Please check your inputs.';
                    addBotBubble(errMsg);
                    mode = 'support';
                    ui.setInputEnabled(true);
                } else if (result.status === 429) {
                    addBotBubble('Too many requests. Please wait a moment and try again.');
                    mode = 'rag';
                    ui.setInputEnabled(true);
                } else {
                    addBotBubble(data.error || 'Failed to create ticket. Please try again.');
                    mode = 'support';
                    ui.setInputEnabled(true);
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('Connection error. Please check your connection and try again.');
                mode = 'support';
                ui.setInputEnabled(true);
            }
        },

        restart() {
            collectedData = {};
            currentStep = 0;
            mode = 'rag';
            storage.clearHistory();
            const { messages } = ui.getElements();
            if (messages) messages.innerHTML = '';

            const currentTheme = getTheme();
            addBotBubbleWithCTAs(currentTheme.welcomeMessage);
            ui.setInputEnabled(true, 'Ask me anything...');
        },

        showDynamicCTAs() {
            const currentTheme = getTheme();
            const { messages } = ui.getElements();
            if (!messages) return;

            if (currentTheme.ctaButtons && currentTheme.ctaButtons.length > 0) {
                ui.renderButtons(currentTheme.ctaButtons.map(b => b.label), (label) => {
                    const btnConfig = currentTheme.ctaButtons.find(b => b.label === label);
                    if (btnConfig) this.handleCtaAction(btnConfig);
                }, false, null, { outline: true, ctaConfigs: currentTheme.ctaButtons });
            }
        },

        handleCtaAction(btnConfig) {
            markLastCtasUsed();
            if (btnConfig.action === 'SUPPORT') {
                this.startSupportFlow();
            } else if (btnConfig.action === 'APPOINTMENT' || btnConfig.action === 'BOOKING' || btnConfig.action === 'LEAD' || btnConfig.action === 'ENQUIRY') {
                this.startFlow(btnConfig.action, btnConfig.label);
            } else {
                if (config && config.steps && config.steps.length > 0) {
                    addUserBubble(btnConfig.label);
                    mode = 'flow';
                    ui.setInputEnabled(false);
                    this.renderStep(0);
                } else {
                    const inputEl = ui.getElements().input;
                    if (inputEl) {
                        inputEl.value = btnConfig.label;
                        const sendBtn = ui.getElements().sendBtn;
                        if (sendBtn) sendBtn.click();
                    }
                }
            }
        },

        handleMenuAction(id, title, actionType, item) {
            const act = (actionType || '').toLowerCase();
            const idStr = (id || '').toLowerCase();
            const preselected = item && item.name ? item.name : undefined;

            if (act === 'lead' || idStr === 'trigger_flow_lead' || idStr === 'lead') {
                this.startFlow('LEAD', title, preselected);
            } else if (act === 'appointment' || idStr === 'trigger_flow_appointment' || idStr === 'appointment') {
                this.startFlow('APPOINTMENT', title, preselected);
            } else if (act === 'booking' || idStr === 'trigger_flow_booking' || idStr === 'booking') {
                this.startFlow('BOOKING', title, preselected);
            } else if (act === 'catalog' || idStr === 'catalog' || idStr === 'view_services' || idStr === 'view_services_list') {
                this.startCatalogFlow();
            } else if (act === 'support' || idStr === 'support') {
                this.startSupportFlow();
            } else {
                addUserBubble(title);
                const inputEl = ui.getElements().input;
                if (inputEl) {
                    inputEl.value = title;
                    const sendBtn = ui.getElements().sendBtn;
                    if (sendBtn) sendBtn.click();
                }
            }
        },

        addBotBubble,
        addUserBubble,
        addBotBubbleWithCTAs
    };

    return engine;
}
