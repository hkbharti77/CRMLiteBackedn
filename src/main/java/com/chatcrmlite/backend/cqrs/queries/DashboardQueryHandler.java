package com.chatcrmlite.backend.cqrs.queries;

import com.chatcrmlite.backend.cqrs.projections.ConversationSummary;
import com.chatcrmlite.backend.cqrs.projections.ConversationSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DashboardQueryHandler {

    private final ConversationSummaryRepository repository;

    public List<ConversationSummary> handle(GetTenantDashboardQuery query) {
        // High-speed lookup from the Read Model table
        return repository.findByTenantIdOrderByLastUpdatedAtDesc(query.getTenantId());
    }
}
