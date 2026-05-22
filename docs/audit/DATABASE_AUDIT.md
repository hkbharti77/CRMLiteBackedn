# Database & Scalability Audit — CRMLite Backend

## 1. Schema Design Analysis
The schema is relational (PostgreSQL) but uses several "NoSQL" patterns for flexibility.

### [JSON Column Usage]
- **Lead.enquiries**: Stored as a JSON string in a `text` column.
- **Appointment.collected_data**: Stored similarly.
- **Risk**: You cannot efficiently query or aggregate data inside these columns (e.g., "Find all leads with a requirement for 'Service X'") without a full table scan.
- **Recommendation**: Migrate to PostgreSQL `JSONB` for indexing and `path_query` support.

## 2. Multi-Tenancy Isolation
- **Pattern**: Shared Schema, Shared Table, Discriminator Column (`owner_id` or `tenant_id`).
- **Index Check**: Most repositories use `findAllByOwner`. Ensure an index exists on `(owner_id, id)` for every table to prevent performance degradation as the database grows.

## 3. Query Efficiency & N+1 Risks
- **Findings**: Some repositories correctly use `JOIN FETCH` (e.g., `LeadRepository`).
- **Hidden Risk**: Many entities have `@ManyToOne(fetch = FetchType.LAZY)` (Correct), but default Spring Data `findAll()` will still cause N+1 if the child entities are accessed in the Service/Controller layer without an EntityGraph.

## 4. Vector Storage & Scaling
- **Strategy**: Embeddings are stored in the DB as `jsonb` (mapped to `float[]`) but searched **in-memory**.
- **Pros**: Zero DB load for vector math; extremely fast retrieval.
- **Cons**: 
  - **Memory Bound**: You cannot store more vectors than can fit in the JVM heap. 
  - **Boot Time**: Every time a tenant "wakes up," the system must load all their chunks from the DB and build the in-memory index.
  - **Horizontal Scaling**: If you have 2 server nodes, both must load and cache the vectors, doubling the memory cost.

## 5. Performance Bottlenecks
- **Message Growth**: The `messages` and `processed_messages` tables will grow exponentially. 
- **Partitioning**: No table partitioning is implemented. This will slow down all queries once these tables hit millions of rows.
- **Transaction Locks**: In `WhatsAppFlowService`, the `@Transactional` wrapper on the entire `processFlow` might hold locks longer than necessary while waiting for external I/O (though `@Transactional` is needed for state consistency).
