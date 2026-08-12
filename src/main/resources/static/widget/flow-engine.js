/**
 * CRM Chat Widget - Flow Engine & State Machine
 */

import { SUPPORT_STEPS, ENDPOINT_MAP } from './constants.js';
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
        const entry = { text: t, sender: 'bot', time: Date.now() };
        const history = storage.loadHistory();
        history.push(entry);
        storage.saveHistory(history);

        const currentTheme = getTheme();
        ui.renderBotBubbleWithCTAs(t, currentTheme.ctaButtons || [], (btnConfig) => {
            engine.handleCtaAction(btnConfig);
        });
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
            addBotBubble('💡 Tip: Want a quick response? You can fill a short form and we\'ll get back to you!');
            formSuggested = true;

            ui.renderButtons(['📋 Fill Quick Form', 'Keep chatting'], (val) => {
                if (val === '📋 Fill Quick Form') {
                    addUserBubble('📋 Fill Quick Form');
                    mode = 'flow';
                    ui.setInputEnabled(false);
                    this.renderStep(0);
                }
            });
        },

        suggestSupport() {
            if (supportConfig && supportConfig.enabled === false) return;
            addBotBubble('Need technical help? You can open a support ticket or talk directly with a live support agent.');

            ui.renderButtons(['💬 Connect with Live Agent', '🎫 Open Support Ticket'], (val) => {
                if (val === '💬 Connect with Live Agent') {
                    this.requestLiveSupport();
                } else if (val === '🎫 Open Support Ticket') {
                    this.startSupportFlow();
                }
            });
        },

        async requestLiveSupport() {
            addUserBubble('💬 Connect with Live Agent');
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
                    addBotBubble('⚠️ Support team currently unavailable. Please open a support ticket.');
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('⚠️ Connection error. Please try again.');
            }
        },

        startSupportFlow() {
            addUserBubble('🎫 Open Support Ticket');
            mode = 'support';
            currentStep = 0;
            collectedData = {};
            this.renderSupportStep(0);
        },

        async startFlow(actionType, label) {
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
                    mode = 'flow';
                    this.renderStep(0);
                } else {
                    addBotBubble('⚠️ This form is not configured yet.');
                    ui.setInputEnabled(true);
                    mode = 'rag';
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('⚠️ Connection error. Please try again.');
                ui.setInputEnabled(true);
                mode = 'rag';
            }
        },

        async startCatalogFlow() {
            addUserBubble('View Services/Products');
            mode = 'catalog';
            ui.setInputEnabled(false);
            ui.setTyping(true);

            try {
                const catalog = await apiClient.fetchCatalog();
                ui.setTyping(false);
                if (catalog && catalog.length > 0) {
                    addBotBubble('Here is our catalog:');
                    if (catalogManager) catalogManager.renderCatalog(catalog);
                } else {
                    addBotBubble('Our catalog is currently empty.');
                    ui.setInputEnabled(true);
                    mode = 'rag';
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('⚠️ Connection error. Please try again.');
                ui.setInputEnabled(true);
                mode = 'rag';
            }
        },

        startAboutFlow(aboutUsText) {
            addUserBubble('About Us');
            if (aboutUsText && aboutUsText.trim() !== '') {
                addBotBubble(aboutUsText);
            } else {
                addBotBubble('Information about our business is coming soon!');
            }
            mode = 'rag';
        },

        async renderSupportStep(index) {
            const steps = SUPPORT_STEPS;
            if (index >= steps.length) {
                this.submitTicket();
                return;
            }

            const step = steps[index];
            currentStep = index;

            let greetingMsg = (supportConfig && supportConfig.greetingMessage)
                ? supportConfig.greetingMessage
                : 'Welcome to our Support channel! Please provide a few details so we can assist you better.';

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

                const activeButtons = ui.getElements().messages.querySelectorAll('.flow-buttons:last-child');
                activeButtons.forEach(b => b.style.display = 'none');

                const currentTheme = getTheme();
                const menuEntry = ui.renderCustomMenu(currentTheme.flowCancelMenuJson, 'Form cancelled.', false, currentTheme, (id, title) => {
                    this.handleMenuAction(id, title);
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
                const result = await apiClient.submitFlow(endpoint, collectedData);
                ui.setTyping(false);
                if (result.ok) {
                    mode = 'rag';
                    setTimeout(() => {
                        ui.setInputEnabled(true, 'Ask me anything...');
                        const currentTheme = getTheme();
                        const menuEntry = ui.renderCustomMenu(currentTheme.flowCompletionMenuJson, result.data.message || '✅ Thank you! We\'ll be in touch.', false, currentTheme, (id, title) => {
                            this.handleMenuAction(id, title);
                        });
                        if (menuEntry) {
                            const history = storage.loadHistory();
                            history.push(menuEntry);
                            storage.saveHistory(history);
                        }
                    }, 800);
                } else {
                    addBotBubble('⚠️ Submission failed. Please try again later.');
                    ui.setInputEnabled(true);
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('⚠️ Connection error. Please try again.');
            }
        },

        async submitTicket() {
            const validation = validateSupportPayload(collectedData);

            if (!validation.isValid) {
                addBotBubble(`⚠️ Please provide your ${validation.missing.join(', ')} before submitting.`);
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
                        const menuEntry = ui.renderCustomMenu(currentTheme.flowCompletionMenuJson, data.message || '✅ Support ticket created successfully!', false, currentTheme, (id, title) => {
                            this.handleMenuAction(id, title);
                        });
                        if (menuEntry) {
                            const history = storage.loadHistory();
                            history.push(menuEntry);
                            storage.saveHistory(history);
                        }
                    }, 1000);
                } else if (result.status === 400) {
                    const errMsg = data.error || data.message || (data.errors && Object.values(data.errors).join(', ')) || 'Some required fields are missing. Please check your inputs.';
                    addBotBubble(`⚠️ ${errMsg}`);
                    mode = 'support';
                    ui.setInputEnabled(true);
                } else if (result.status === 429) {
                    addBotBubble('⚠️ Too many requests. Please wait a moment and try again.');
                    mode = 'rag';
                    ui.setInputEnabled(true);
                } else {
                    addBotBubble(`⚠️ ${data.error || 'Failed to create ticket. Please try again.'}`);
                    mode = 'support';
                    ui.setInputEnabled(true);
                }
            } catch (e) {
                ui.setTyping(false);
                addBotBubble('⚠️ Connection error. Please check your connection and try again.');
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
                const container = document.createElement('div');
                container.className = 'flow-buttons';
                currentTheme.ctaButtons.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.className = 'flow-btn';
                    btn.style.cssText = 'background:transparent; border:1px solid var(--primary-color); color:var(--primary-color); font-size:12px; margin-right: 5px; margin-bottom: 5px;';
                    btn.textContent = btnConfig.label;
                    btn.onclick = () => {
                        container.querySelectorAll('.flow-btn').forEach(b => b.disabled = true);
                        this.handleCtaAction(btnConfig);
                    };
                    container.appendChild(btn);
                });
                messages.appendChild(container);
                messages.scrollTop = messages.scrollHeight;
            }
        },

        handleCtaAction(btnConfig) {
            if (btnConfig.action === 'SUPPORT') {
                this.startSupportFlow();
            } else if (btnConfig.action === 'APPOINTMENT' || btnConfig.action === 'BOOKING' || btnConfig.action === 'LEAD') {
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

        handleMenuAction(id, title) {
            if (id === 'trigger_flow_lead') {
                this.startFlow('LEAD', title);
            } else if (id === 'trigger_flow_appointment') {
                this.startFlow('APPOINTMENT', title);
            } else if (id === 'trigger_flow_booking') {
                this.startFlow('BOOKING', title);
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
