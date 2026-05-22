# PostgreSQL Vector Storage Architecture

This document describes the vector storage architecture in CRMLite.

## Overview
CRMLite utilizes PostgreSQL's `pgvector` extension for storing and retrieving high-dimensional embeddings for Document RAG (Retrieval-Augmented Generation). By migrating from an in-memory `Caffeine`-based cache to `pgvector`, the system gains horizontal scalability, reduced memory pressure, and out-of-the-box Approximate Nearest Neighbor (ANN) search capabilities.

## Architecture

The vector search flow involves three main services:

1. **EmbeddingPersistenceService**: Handles batch ingestion of chunks.
    - Limits insertions per tenant (Tenant Quota enforcement: `MAX_CHUNKS_PER_TENANT`).
    - Exposes Micrometer metrics: `vector.ingestion.latency` and `vector.ingestion.count`.

2. **VectorSearchService**: Interfaces with PostgreSQL directly via native queries.
    - Uses the cosine distance operator `<=>`.
    - Exposes Micrometer metrics: `vector.search.latency`.

3. **SemanticRetriever**: Orchestrates embedding generation (using LangChain4j and ONNX models locally) and interacts with `VectorSearchService`.
    - Retrieves a larger initial result set and filters/re-ranks down to `topK`.
    - Exposes Micrometer metrics: `vector.retrieval.latency` and `vector.retrieval.queries`.

## Database Schema & Indexes

The `document_chunks` table stores text segments and their corresponding vector embeddings.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE document_chunks ADD COLUMN embedding vector(384) NOT NULL;
```

### HNSW Indexing
For performant ANN search, we use Hierarchical Navigable Small Worlds (HNSW) indexing over the vector column:

```sql
CREATE INDEX document_chunks_embedding_hnsw_idx 
ON document_chunks 
USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);
```
- `m = 16`: The maximum number of connections per layer (16 is recommended for general use cases).
- `ef_construction = 64`: The size of the dynamic candidate list for constructing the graph.

## Tenant Isolation
All queries strictly enforce `tenant_id` filtering in the `WHERE` clause before evaluating the `<=>` distance operator. The combination of B-Tree indexing on `tenant_id` and the HNSW index on `embedding` ensures efficient filtering.

## Metrics
The following Prometheus metrics are exposed via Micrometer:
- `Timer vector.ingestion.latency`: Time taken to insert chunks.
- `Counter vector.ingestion.count`: Number of chunks ingested.
- `Timer vector.search.latency`: Time taken by PostgreSQL to execute the ANN query.
- `Timer vector.retrieval.latency`: Total time taken for embedding generation + ANN search + re-ranking.
- `Counter vector.retrieval.queries`: Total number of queries performed.

## Benchmark Tool
A `VectorBenchmarkTool` profile allows load testing the HNSW index by simulating random queries and recording the `vector.retrieval.latency` distribution.
Run with: `--spring.profiles.active=benchmark`
