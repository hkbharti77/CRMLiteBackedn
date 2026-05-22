# SRE Runbook: Multi-Region Operations

## 1. Database Failover (Aurora Global)
In the event of a primary region (`us-east-1`) outage:

1. **Verify Outage**: Check CloudWatch metrics for `DatabaseConnections` and `CPUUtilization`.
2. **Promote Secondary**: 
   ```bash
   aws rds failover-global-cluster \
       --global-cluster-identifier crmlite-global-db \
       --target-db-cluster-identifier crmlite-secondary-cluster
   ```
3. **Update Application Config**: Ensure `SPRING_DATASOURCE_WRITER_URL` in Kubernetes secrets points to the new writer endpoint.

## 2. WhatsApp Webhook Routing Updates
To move a tenant between regions:

1. **Update Edge Router**: Modify `REGION_MAPPING` in `deployment/edge-router/index.js`.
2. **Deploy Worker**: 
   ```bash
   wrangler deploy
   ```
3. **Verify Propagation**: Send a test message to the tenant's WhatsApp number and verify it appears in the new region's logs.

## 3. Traffic Redirection (Anycast)
If a region is degraded but not completely down:

1. **Lower Weight**: Reduce the endpoint weight in AWS Global Accelerator for the degraded region.
2. **DNS Switch**: If necessary, update the Route53 `ALIAS` record for `api.crmlite.com` to point exclusively to the healthy region's ALB.

## 4. Monitoring Dashboard
- **Global DB Lag**: `AuroraGlobalDBReplicationLag` (Critical if > 5s).
- **Edge Latency**: Cloudflare Worker execution time.
- **Webhook Success Rate**: 2xx vs 5xx at the Edge Router.
