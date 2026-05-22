# JVM & Performance Engineering Audit — CRMLite Backend

## 1. Memory Footprint: The Hidden Native Cost
The system uses `LangChain4j` with a localized embedding model (`AllMiniLmL6V2`).

### [The ONNX Runtime Factor]
- **Issue**: These models often run via the **ONNX Runtime**, which allocates memory **outside the JVM Heap** (Off-heap / Native memory).
- **Risk**: If you set `-Xmx2G` on a 4GB RAM server, and ONNX takes 2.5GB, the Linux OOM Killer will kill the Java process even if the Heap is 90% empty.
- **Audit Requirement**: Monitor `RES` (Resident Set Size) in `top` vs `Xmx`.

## 2. Vector Store Inefficiency
### [Preloading Spikes]
- **Method**: `LocalVectorStoreService.preloadTenant(ownerId)`
- **Behavior**: It fetches all `DocumentChunk` records for a tenant.
- **Risk**: If a tenant has 10,000 document chunks, preloading them all at once into the heap will cause a significant GC pause. 
- **Recommendation**: Use a fixed-size cache with **Eviction Policies** (Lru/Lfu) instead of unbounded preloading.

### [Object Churn]
- Every AI response involves:
  1. JSON Webhook -> POJO (Jackson).
  2. Text -> Embedding (Native call + Array allocation).
  3. Embedding -> Cosine Similarity (Thousands of float operations).
  4. Prompt Template -> String Interpolation.
- **GC Impact**: High allocation rate of `float[]` and `String` objects leads to frequent **Young Gen GC** collections.

## 3. Thread Utilization & Starvation
### [Executor Tuning]
- `preloadExecutor` is set to `newFixedThreadPool(2)`.
- **The Problem**: If 20 new tenants log in at once, the 21st tenant will wait in an unbounded queue for their vectors to load.
- **The Bot Experience**: The first 10-20 seconds of a chat will be "silent" or "menu-only" because the AI chunks aren't ready yet.

## 4. JVM Tuning Recommendations

| Parameter | Value | Rationale |
| :--- | :--- | :--- |
| **Max Heap (-Xmx)** | 75% of System RAM | Leave 25% for OS and ONNX Native overhead. |
| **GC Algorithm** | **G1GC** | Better for low-latency response-driven applications like Chat. |
| **String Deduplication** | Enabled | Vector metadata and intent keys often repeat; saves ~10% heap. |
| **Caffeine Max Size** | 500MB - 1GB | Prevent the vector cache from growing until it triggers OOM. |

## 5. Performance Limit Estimates
Based on current code patterns:
- **Max Chunks per Node**: ~100,000 (assuming 384-dim vectors + metadata).
- **Max Concurrent Chats**: ~50 per node (limited by LLM I/O wait and Tomcat threads).
- **Startup Latency**: 5-10 seconds (Model loading + Flyway migrations).
