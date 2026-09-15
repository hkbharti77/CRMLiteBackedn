package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.GraphEvidence;
import com.chatcrmlite.backend.dto.rag.HybridRetrievalResult;
import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import com.chatcrmlite.backend.dto.rag.RetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalSource;
import com.chatcrmlite.backend.services.HybridSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates parallel vector (existing HybridSearchService) + graph retrieval.
 * Does not call the LLM to merge results.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final HybridSearchService hybridSearchService;
    private final GraphRetrievalService graphRetrievalService;
    private final QueryAnalyzerService queryAnalyzerService;

    @Value("${rag.mode:VECTOR}")
    private String ragModeProperty;

    public HybridRetrievalResult retrieve(String query, UUID tenantId, float[] queryEmbedding,
                                          boolean conversationRequiresRag, int topK) {
        long analysisStart = System.currentTimeMillis();
        QueryAnalysis analysis = queryAnalyzerService.analyze(query, tenantId);
        // Respect conversation router skip for VECTOR path when mode is VECTOR
        RagMode mode = RagMode.from(ragModeProperty);
        if (!conversationRequiresRag && mode == RagMode.VECTOR) {
            analysis.setRequiresRag(false);
            analysis.setRequiresVector(false);
            analysis.setRequiresGraph(false);
        }
        long analysisLatency = System.currentTimeMillis() - analysisStart;

        List<RetrievalResult> vectorResults = new ArrayList<>();
        List<GraphEvidence> graphResults = new ArrayList<>();
        boolean vectorDegraded = false;
        boolean graphDegraded = false;
        long vectorLatency = 0;
        long graphLatency = 0;

        boolean runVector = analysis.isRequiresVector() && mode != RagMode.GRAPH;
        boolean runGraph = analysis.isRequiresGraph() && mode != RagMode.VECTOR;

        if (runVector && runGraph) {
            CompletableFuture<List<RetrievalResult>> vectorFuture = CompletableFuture.supplyAsync(() ->
                    safeVectorSearch(tenantId, queryEmbedding, query, topK));
            CompletableFuture<List<GraphEvidence>> graphFuture = CompletableFuture.supplyAsync(() ->
                    graphRetrievalService.retrieve(analysis));

            long vStart = System.currentTimeMillis();
            try {
                vectorResults = vectorFuture.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                vectorDegraded = true;
                log.warn("[HybridRetrieval] Vector path failed: {}", e.getMessage());
                vectorFuture.cancel(true);
            }
            vectorLatency = System.currentTimeMillis() - vStart;

            long gStart = System.currentTimeMillis();
            try {
                graphResults = graphFuture.get(8, TimeUnit.SECONDS);
            } catch (Exception e) {
                graphDegraded = true;
                log.warn("[HybridRetrieval] Graph path failed: {}", e.getMessage());
                graphFuture.cancel(true);
            }
            graphLatency = System.currentTimeMillis() - gStart;
        } else if (runVector) {
            long vStart = System.currentTimeMillis();
            try {
                vectorResults = safeVectorSearch(tenantId, queryEmbedding, query, topK);
            } catch (Exception e) {
                vectorDegraded = true;
                log.warn("[HybridRetrieval] Vector path failed: {}", e.getMessage());
            }
            vectorLatency = System.currentTimeMillis() - vStart;
        } else if (runGraph) {
            long gStart = System.currentTimeMillis();
            try {
                if (!graphRetrievalService.isAvailable()) {
                    graphDegraded = true;
                    log.warn("[HybridRetrieval] Neo4j unavailable — graph path skipped");
                } else {
                    graphResults = graphRetrievalService.retrieve(analysis);
                }
            } catch (Exception e) {
                graphDegraded = true;
                log.warn("[HybridRetrieval] Graph path failed: {}", e.getMessage());
            }
            graphLatency = System.currentTimeMillis() - gStart;
        }

        int maxHop = graphResults.stream()
                .mapToInt(GraphEvidence::getHopDepth)
                .max()
                .orElse(0);

        log.info("[HybridRetrieval] tenant={} mode={} vectorHits={} graphHits={} "
                        + "analysisMs={} vectorMs={} graphMs={} vectorDegraded={} graphDegraded={}",
                tenantId, mode, vectorResults.size(), graphResults.size(),
                analysisLatency, vectorLatency, graphLatency, vectorDegraded, graphDegraded);

        return HybridRetrievalResult.builder()
                .queryAnalysis(analysis)
                .vectorResults(vectorResults)
                .graphResults(graphResults)
                .analysisLatencyMs(analysisLatency)
                .vectorLatencyMs(vectorLatency)
                .graphLatencyMs(graphLatency)
                .vectorDegraded(vectorDegraded)
                .graphDegraded(graphDegraded)
                .graphTraversalDepth(maxHop)
                .graphNodesReturned((int) graphResults.stream()
                        .map(GraphEvidence::getEntityId)
                        .distinct()
                        .count())
                .graphRelationshipsReturned(graphResults.size())
                .build();
    }

    private List<RetrievalResult> safeVectorSearch(UUID tenantId, float[] queryEmbedding, String query, int topK) {
        List<HybridSearchService.ScoredChunk> chunks =
                hybridSearchService.hybridSearchDetailed(tenantId, queryEmbedding, query, topK);
        List<RetrievalResult> results = new ArrayList<>();
        for (HybridSearchService.ScoredChunk c : chunks) {
            results.add(RetrievalResult.builder()
                    .id(c.chunkId() != null ? c.chunkId().toString() : UUID.randomUUID().toString())
                    .tenantId(tenantId)
                    .score(c.score())
                    .originalScore(c.score())
                    .content(c.content())
                    .sourceType(RetrievalSource.VECTOR_CHUNK)
                    .sourceId(c.chunkId() != null ? c.chunkId().toString() : null)
                    .sourceLabel(c.source())
                    .metadata(java.util.Map.of(
                            "documentId", c.documentId() != null ? c.documentId().toString() : "",
                            "source", c.source() != null ? c.source() : "document"
                    ))
                    .build());
        }
        return results;
    }
}
