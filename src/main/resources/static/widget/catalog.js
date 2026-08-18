/**
 * CRM Chat Widget - Catalog & Products/Services Presentation
 */

import { escapeHtml } from './markdown.js';

export function createCatalogManager({ messagesContainer, onAddUserBubble, createBotRow }) {
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

                if (item.hasImage) {
                    const img = document.createElement('img');
                    img.className = 'catalog-card-image';
                    img.src = `/public/images/${item.id}`;
                    img.alt = item.name || 'Product image';
                    card.appendChild(img);
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

            if (item.hasImage) {
                const img = document.createElement('img');
                img.className = 'catalog-details-image';
                img.src = `/public/images/${item.id}`;
                img.alt = item.name || 'Product image';
                container.appendChild(img);
            }

            const titleEl = document.createElement('div');
            titleEl.className = 'catalog-details-title';
            titleEl.textContent = item.name || '';

            const descEl = document.createElement('div');
            descEl.className = 'catalog-details-desc';
            descEl.textContent = item.description || 'No additional details available.';

            container.appendChild(titleEl);
            container.appendChild(descEl);

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
