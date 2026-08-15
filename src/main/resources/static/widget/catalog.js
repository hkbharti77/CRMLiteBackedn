/**
 * CRM Chat Widget - Catalog & Products/Services Presentation
 */

export function createCatalogManager({ messagesContainer, onAddUserBubble }) {
    return {
        renderCatalog(catalog) {
            if (!messagesContainer) return;

            const container = document.createElement('div');
            container.className = 'catalog-container';

            catalog.forEach(item => {
                const card = document.createElement('div');
                card.className = 'catalog-card';
                card.setAttribute('role', 'button');
                card.tabIndex = 0;

                let imgHtml = '';
                if (item.hasImage) {
                    imgHtml = `<img class="catalog-card-image" src="/public/images/${item.id}" alt="">`;
                }

                card.innerHTML = `
                    ${imgHtml}
                    <div class="catalog-card-title">${item.name || ''}</div>
                    <div class="catalog-card-desc">${item.description || ''}</div>
                `;

                const open = () => this.showCatalogDetails(item);
                card.onclick = open;
                card.onkeydown = (e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        open();
                    }
                };
                container.appendChild(card);
            });

            messagesContainer.appendChild(container);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        },

        showCatalogDetails(item) {
            if (!messagesContainer || !item) return;

            if (onAddUserBubble) {
                onAddUserBubble(`Tell me more about ${item.name}`);
            }

            const container = document.createElement('div');
            container.className = 'catalog-details message bot';

            let imgHtml = '';
            if (item.hasImage) {
                imgHtml = `<img class="catalog-details-image" src="/public/images/${item.id}" alt="">`;
            }

            container.innerHTML = `
                ${imgHtml}
                <div class="catalog-details-title">${item.name || ''}</div>
                <div class="catalog-details-desc">${item.description || 'No additional details available.'}</div>
            `;

            messagesContainer.appendChild(container);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        }
    };
}
