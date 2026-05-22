# Data Retention & Growth Audit — CRMLite Backend

## 1. Linear Growth Tables
The following tables grow linearly with every interaction and have **no automatic cleanup**:

| Table | High-Risk Columns | Projected Growth (10k Users) |
| :--- | :--- | :--- |
| `chat_messages` | `content` (text) | 5GB - 20GB / Month |
| `security_logs` | `details` (json) | 2GB / Month |
| `leads` | `enquiries` (json text) | 1GB / Month |
| `processed_messages` | `message_id` | 500MB / Month (30-day TTL exists) |

### [The Storage Explosion]
If a bot attack generates 1 million messages, the `chat_messages` table will bloat. Without **Partitioning**, queries like `findByContact` will start to slow down as the B-Tree index becomes too large for RAM.

## 2. Vector Growth & Indexing
- **Vector Chunks**: Every document uploaded creates ~5-10 chunks.
- **Index Performance**: The current system uses `pgvector` only for storage; the search is done in-memory. 
- **The Risk**: When you move to `pgvector` native search (highly recommended), an index like `HNSW` or `IVFFlat` will be needed. These indices consume **significant additional storage** and require `maintenance_work_mem` tuning.

## 3. The "Orphaned Data" Problem
- **Tenant Deletion**: When a `User` (Owner) is deleted, do we perform a hard delete of all their `Contact`, `Lead`, `Message`, and `DocumentChunk` records?
- **Audit Findings**: The current models use standard `@ManyToOne` relationships. Without `cascade = CascadeType.REMOVE` at every level, the database will accumulate "Ghost Data" that can never be accessed but consumes storage.

## 4. Retention Policy Recommendations

| Data Type | Retention Period | Action |
| :--- | :--- | :--- |
| **Incoming Webhook Raw** | 7 Days | Delete (Audit only) |
| **Chat History** | 6 Months | Move to **Cold Storage** (S3/Compressed) |
| **Security Logs** | 1 Year | Archive for compliance |
| **Processed Message IDs** | 3 Days | Delete (Idempotency window is small) |
| **Leads & Contacts** | Permanent | Keep until manual deletion |

## 5. Strategic Data Roadmap
1. **Table Partitioning**: Partition the `chat_messages` table by `owner_id` or `created_at` (Monthly partitions).
2. **JSONB Migration**: Convert all `text`-based JSON columns to `JSONB` to save space (via compression) and allow partial updates.
3. **Audit Log Sharding**: Move `ActivityLog` to a separate database or a time-series optimized store if the volume exceeds 1k events/sec.
