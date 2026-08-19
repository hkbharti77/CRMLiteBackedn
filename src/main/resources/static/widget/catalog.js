/**
 * CRM Chat Widget - Catalog & Products/Services Presentation
 */

import { escapeHtml, resolveImageUrl } from './markdown.js';

export function createCatalogManager({ messagesContainer, onAddUserBubble, createBotRow, apiBase, resolveImageUrl: customResolveImageUrl, getTheme, onActionSelect }) {
    const resolveUrl = customResolveImageUrl || resolveImageUrl;

    function getMediaUrl(item) {
        if (!item) return null;
        let url = item.imageUrl;
        if (!url && item.hasImage && item.id) {
            url = `/public/images/${item.id}`;
        }
        if (!url) return null;
        if (typeof resolveUrl === 'function') {
            return resolveUrl(url, apiBase);
        }
        if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:') || url.startsWith('blob:')) {
            return url;
        }
        const base = (apiBase || '').replace(/\/api\/v1\/public\/?$/, '').replace(/\/api\/v1\/?$/, '').replace(/\/$/, '');
        return `${base}${url.startsWith('/') ? '' : '/'}${url}`;
    }

    function isVideoUrl(url) {
        return url && /\.(mp4|webm|mov|avi|3gp)($|\?)/i.test(url);
    }

    function isDocUrl(url) {
        return url && /\.(pdf|doc|docx|xls|xlsx|txt)($|\?)/i.test(url);
    }

    return {
        renderCatalog(catalog) {
            if (!messagesContainer) return;

            const row = createBotRow ? createBotRow() : null;
            const container = document.createElement('div');
            container.className = 'catalog-container';

            catalog.forEach(item => {
                const card = document.createElement('button');
                card.type = 'button';
                card.className = 'catalog-card';

                const mediaUrl = getMediaUrl(item);
                if (mediaUrl) {
                    if (isVideoUrl(mediaUrl)) {
                        const videoWrap = document.createElement('div');
                        videoWrap.className = 'catalog-card-media-wrap catalog-card-video-wrap';
                        videoWrap.innerHTML = `
                            <video class="catalog-card-image" src="${mediaUrl}" preload="metadata" muted playsinline></video>
                            <div class="catalog-media-badge">▶ Video</div>
                        `;
                        card.appendChild(videoWrap);
                    } else if (isDocUrl(mediaUrl)) {
                        const docWrap = document.createElement('div');
                        docWrap.className = 'catalog-card-media-wrap catalog-card-doc-wrap';
                        docWrap.innerHTML = `
                            <div class="catalog-doc-preview">📄 Document</div>
                        `;
                        card.appendChild(docWrap);
                    } else {
                        const img = document.createElement('img');
                        img.className = 'catalog-card-image';
                        img.src = mediaUrl;
                        img.alt = item.name || 'Product image';
                        img.loading = 'lazy';
                        img.onerror = () => {
                            img.style.display = 'none';
                        };
                        card.appendChild(img);
                    }
                }

                const titleEl = document.createElement('div');
                titleEl.className = 'catalog-card-title';
                titleEl.textContent = item.name || '';

                const descEl = document.createElement('div');
                descEl.className = 'catalog-card-desc';
                descEl.textContent = item.description || '';

                card.appendChild(titleEl);
                card.appendChild(descEl);
                card.onclick = () => this.showCatalogDetails(item);
                container.appendChild(card);
            });

            if (row) {
                row.querySelector('.message-row-content')?.appendChild(container) ||
                    row.appendChild(container);
                messagesContainer.appendChild(row);
            } else {
                messagesContainer.appendChild(container);
            }
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        },

        showCatalogDetails(item) {
            if (!messagesContainer || !item) return;

            if (onAddUserBubble) {
                onAddUserBubble(`Tell me more about ${item.name}`);
            }

            const row = createBotRow ? createBotRow() : null;
            const container = document.createElement('div');
            container.className = 'catalog-details';

            const mediaUrl = getMediaUrl(item);
            if (mediaUrl) {
                if (isVideoUrl(mediaUrl)) {
                    const video = document.createElement('video');
                    video.className = 'catalog-details-image catalog-details-video';
                    video.src = mediaUrl;
                    video.controls = true;
                    video.playsInline = true;
                    container.appendChild(video);
                } else if (isDocUrl(mediaUrl)) {
                    const docLink = document.createElement('a');
                    docLink.className = 'catalog-details-doc-link';
                    docLink.href = mediaUrl;
                    docLink.target = '_blank';
                    docLink.rel = 'noopener noreferrer';
                    docLink.textContent = '📄 View Attached Document';
                    container.appendChild(docLink);
                } else {
                    const img = document.createElement('img');
                    img.className = 'catalog-details-image';
                    img.src = mediaUrl;
                    img.alt = item.name || 'Product image';
                    img.loading = 'lazy';
                    img.onerror = () => {
                        img.style.display = 'none';
                    };
                    container.appendChild(img);
                }
            }

            const titleEl = document.createElement('div');
            titleEl.className = 'catalog-details-title';
            titleEl.textContent = item.name || '';

            const descEl = document.createElement('div');
            descEl.className = 'catalog-details-desc';
            descEl.textContent = item.description || 'No additional details available.';

            container.appendChild(titleEl);
            container.appendChild(descEl);

            // Flow CTA Buttons (Configured by admin in /settings/flow-cta)
            const currentTheme = (typeof getTheme === 'function' ? getTheme() : null) || {};
            let buttonList = [];

            try {
                if (currentTheme.aiResponseMenuJson) {
                    const parsed = typeof currentTheme.aiResponseMenuJson === 'string'
                        ? JSON.parse(currentTheme.aiResponseMenuJson)
                        : currentTheme.aiResponseMenuJson;
                    if (parsed && parsed.enabled !== false) {
                        if (Array.isArray(parsed)) {
                            buttonList = parsed;
                        } else if (Array.isArray(parsed.buttons)) {
                            buttonList = parsed.buttons;
                        } else if (parsed.sections && parsed.sections.length > 0 && parsed.sections[0].rows) {
                            buttonList = parsed.sections[0].rows;
                        }
                    }
                }
            } catch (e) {
                console.warn('Failed to parse aiResponseMenuJson for catalog details', e);
            }

            if (!buttonList || buttonList.length === 0) {
                if (currentTheme.ctaButtons && currentTheme.ctaButtons.length > 0) {
                    buttonList = currentTheme.ctaButtons;
                } else {
                    buttonList = [
                        { id: 'trigger_flow_lead', label: 'Enquire Now', linkType: 'lead' },
                        { id: 'trigger_flow_appointment', label: 'Book Appointment', linkType: 'appointment' }
                    ];
                }
            }

            if (buttonList && buttonList.length > 0) {
                const btnWrap = document.createElement('div');
                btnWrap.className = 'cta-card-wrap catalog-details-ctas';

                buttonList.forEach(btnConfig => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'flow-btn';

                    const label = btnConfig.label || btnConfig.title || 'Enquire Now';
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
                            onActionSelect(btnConfig.id || linkType, label, linkType, item);
                        }
                    };
                    btnWrap.appendChild(btn);
                });
                container.appendChild(btnWrap);
            }

            if (row) {
                const content = row.querySelector('.message-row-content');
                if (content) content.appendChild(container);
                else row.appendChild(container);
                messagesContainer.appendChild(row);
            } else {
                container.classList.add('message', 'bot');
                messagesContainer.appendChild(container);
            }
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        }
    };
}
