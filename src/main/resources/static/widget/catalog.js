/**
 * CRM Chat Widget - Catalog & Products/Services Presentation
 */

export function createCatalogManager({ messagesContainer, onAddUserBubble }) {
    return {
        renderCatalog(catalog) {
            if (!messagesContainer) return;

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
                    <div style="font-weight:600;color:#1e293b;font-size:14px;margin-bottom:4px;">${item.name || ''}</div>
                    <div style="color:#64748b;font-size:12px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">${item.description || ''}</div>
                `;

                card.onclick = () => this.showCatalogDetails(item);
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
            container.style.display = 'flex';
            container.style.flexDirection = 'column';

            let imgHtml = '';
            if (item.hasImage) {
                imgHtml = `<img src="/public/images/${item.id}" alt="${item.name}" style="width:100%;max-height:200px;object-fit:cover;border-radius:8px;margin-bottom:12px;">`;
            }

            container.innerHTML = `
                ${imgHtml}
                <div style="font-weight:600;font-size:16px;margin-bottom:8px;color:#1e293b;">${item.name || ''}</div>
                <div style="font-size:14px;color:#475569;white-space:pre-wrap;line-height:1.5;">${item.description || 'No additional details available.'}</div>
            `;

            messagesContainer.appendChild(container);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
        }
    };
}
