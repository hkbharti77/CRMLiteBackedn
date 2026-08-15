/**
 * CRM Chat Widget - Modular Entrypoint Orchestrator
 */

import { ICONS, DEFAULT_THEME } from './constants.js';
import { createStorageManager } from './storage.js';
import { parseMarkdown, resolveImageUrl } from './markdown.js';
import { createApiClient } from './api.js';
import { createCatalogManager } from './catalog.js';
import { createUIController } from './ui.js';
import { createFlowEngine } from './flow-engine.js';

export async function initWidget({ businessId, apiBase } = {}) {
    console.log("CRM Chat Widget Initializing (Modular)...");

    if (!businessId) {
        console.warn("CRM Chat Widget: No businessId provided.");
        return;
    }

    let theme = { ...DEFAULT_THEME };
    const storage = createStorageManager(businessId);
    const apiClient = createApiClient(apiBase, businessId);

    let ui = null;
    let flowEngine = null;
    let catalogManager = null;
    
    let inactivityTimer = null;

    const resetInactivityTimer = () => {
        if (inactivityTimer) clearTimeout(inactivityTimer);
        // 30 minutes timeout
        inactivityTimer = setTimeout(() => {
            if (flowEngine && ui) {
                const timeoutPrompt = "It seems you've been inactive for a while. Would you like to connect with our team directly via Contact Sales, or do you have More Questions?";
                const timeoutCtas = [
                    { label: "Book Enquiry", action: "ENQUIRY" },
                    { label: "Have a question", action: "SUPPORT" }
                ];
                
                const entry = { text: timeoutPrompt, sender: 'bot', time: Date.now(), ctas: timeoutCtas };
                const history = storage.loadHistory();
                history.push(entry);
                storage.saveHistory(history);
                
                ui.renderBotBubbleWithCTAs(timeoutPrompt, timeoutCtas, (btnConfig) => {
                    resetInactivityTimer();
                    flowEngine.handleCtaAction(btnConfig);
                });
            }
        }, 30 * 60 * 1000);
    };

    const sendMessage = async () => {
        const { input } = ui.getElements();
        const val = input ? input.value.trim() : '';

        if (flowEngine.mode === 'flow' || flowEngine.mode === 'support') {
            flowEngine.handleFreeTextInput(val);
            return;
        }

        if (!val) return;

        resetInactivityTimer();

        flowEngine.addUserBubble(val);
        if (input) input.value = '';
        ui.setTyping(true);

        const triggered = flowEngine.evaluateTrigger(val);

        try {
            const chatHistory = storage.loadHistory();
            const isReturning = chatHistory.length > 2;

            const data = await apiClient.sendChatMessage(val, isReturning, storage.getSessionId());

            if (data.ctaButtons !== undefined) {
                theme.ctaButtons = data.ctaButtons;
            }

            ui.setTyping(false);
            if (data.isGuardrail) {
                const fallbackMsg = data.response || (data.reason === 'abuse_throttled'
                    ? (theme.guardrailMessageAbuse || "We cannot process requests containing inappropriate or abusive language. Please communicate respectfully or select an option from the menu.")
                    : (theme.guardrailMessageGibberish || "We couldn't understand your request. Please rephrase your message or select one of the available options below."));
                setTimeout(() => {
                    const entry = ui.renderCustomMenu(theme.aiResponseMenuJson, fallbackMsg, true, theme, (id, title) => {
                        flowEngine.handleMenuAction(id, title);
                    });
                    if (entry) {
                        const history = storage.loadHistory();
                        history.push(entry);
                        storage.saveHistory(history);
                    }
                }, 500);
            } else {
                const finalResponse = data.response || "I'm sorry, I couldn't understand that.";
                if (triggered) {
                    flowEngine.addBotBubble(finalResponse);
                    setTimeout(() => flowEngine.suggestForm(), 600);
                } else {
                    const entry = ui.renderCustomMenu(theme.aiResponseMenuJson, finalResponse, true, theme, (id, title) => {
                        flowEngine.handleMenuAction(id, title);
                    });
                    if (entry) {
                        const history = storage.loadHistory();
                        history.push(entry);
                        storage.saveHistory(history);
                    }
                }
            }
        } catch (e) {
            ui.setTyping(false);
            const entry = ui.renderCustomMenu(theme.aiResponseMenuJson, "Connection lost. Please try again.", true, theme, (id, title) => {
                flowEngine.handleMenuAction(id, title);
            });
            if (entry) {
                const history = storage.loadHistory();
                history.push(entry);
                storage.saveHistory(history);
            }
        }
    };

    ui = createUIController({
        theme,
        icons: ICONS,
        parseMarkdown,
        resolveImageUrl,
        onSendMessage: sendMessage,
        onRestartConfirm: () => flowEngine.restart(),
        onZoomToggle: () => {},
        onMenuCardClick: (card) => {
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
        },
        onCtaAction: (btnConfig) => flowEngine.handleCtaAction(btnConfig)
    });

    const elements = ui.buildWidgetDOM();

    catalogManager = createCatalogManager({
        messagesContainer: elements.messages,
        onAddUserBubble: (t) => flowEngine.addUserBubble(t)
    });

    flowEngine = createFlowEngine({
        apiClient,
        storage,
        ui,
        catalogManager,
        getTheme: () => theme,
        setTheme: (newTheme) => { theme = newTheme; }
    });

    // Initial Bootstrap
    try {
        const bootstrapData = await apiClient.fetchBootstrapData();

        if (bootstrapData.theme) {
            theme = { ...theme, ...bootstrapData.theme };
            ui.applyTheme(theme, apiBase);
        }

        if (bootstrapData.flow && bootstrapData.flow.steps && bootstrapData.flow.steps.length) {
            flowEngine.init(bootstrapData.flow);
        }

        if (bootstrapData.triggers) {
            flowEngine.triggerConfig = bootstrapData.triggers;
        }

        if (bootstrapData.supportConfig) {
            flowEngine.supportConfig = bootstrapData.supportConfig;
        }

        const chatHistory = storage.loadHistory();
        if (chatHistory.length > 0) {
            chatHistory.forEach(m => {
                if (m.ctas && m.ctas.length > 0) {
                    ui.renderBotBubbleWithCTAs(m.text, m.ctas, (btnConfig) => flowEngine.handleCtaAction(btnConfig));
                } else {
                    ui.renderMessageBubble(m);
                }
            });
            flowEngine.showDynamicCTAs();
        } else {
            let wm = theme.welcomeMessage;
            if (!wm || String(wm).trim() === '') wm = 'Hello! How can I help you today?';
            flowEngine.addBotBubbleWithCTAs(wm);
        }

        ui.setInputEnabled(true, 'Ask me anything...');
        resetInactivityTimer();
    } catch (e) {
        console.error('CRM Chat: Bootstrap failed', e);
        flowEngine.addBotBubble("System offline. Please refresh.");
    }
}

// Auto-run if script was embedded directly as module
if (typeof document !== 'undefined') {
    const script = document.currentScript || document.querySelector('script[data-business-id]');
    const businessId = script
        ? script.getAttribute('data-business-id')
        : new URLSearchParams(window.location.search).get('businessId');

    if (businessId) {
        let API_BASE = 'http://localhost:8080/api/v1/public';
        if (script && script.src) {
            try {
                const url = new URL(script.src, window.location.href);
                API_BASE = `${url.origin}/api/v1/public`;
            } catch (e) {
                console.warn('Could not determine API_BASE, defaulting to localhost');
            }
        }
        initWidget({ businessId, apiBase: API_BASE });
    }
}
