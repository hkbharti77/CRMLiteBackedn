# Redis aur Celery Usage - Complete Summary

## ❌ CELERY KA USE NAHI HUA HAI

Is project mein **Celery bilkul use nahi hua hai**. Ye Java/Spring Boot project hai, Python nahi.

---

## ✅ REDIS KA USE - Complete Details

### 📍 **Redis Kaha Kaha Use Hua Hai:**

## 1. **Configuration Files (Setup)**

### `pom.xml` (Line 232-239)
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version>
</dependency>
```
**Kya karta hai:** Redis ko Spring Boot project mein integrate karta hai

---

### `docker-compose.production.yml` (Line 88-106)
```yaml
redis:
  image: redis:7.2.4-alpine
  container_name: crmlite_redis
  command: redis-server --requirepass ${REDIS_PASSWORD}
  ports:
    - "6379:6379"
```
**Kya karta hai:** Redis container ko setup karta hai production environment mein

---

## 2. **Main Configuration Class**

### `RedisConfig.java`
**Location:** `src/main/java/com/chatcrmlite/backend/config/RedisConfig.java`

**Ye file Redis ko configure karti hai:**

#### A) **RedisTemplate Bean** (Line 41-63)
```java
@Bean
public RedisTemplate<String, Object> redisTemplate(...)
```
**Kya karta hai:** Redis mein data store aur retrieve karne ke liye template

#### B) **CacheManager Bean** (Line 70-94)
```java
@Bean
public CacheManager cacheManager(...)
```
**Kya karta hai:** @Cacheable annotation ke liye cache management
- Cache expiry: 10 minutes
- JSON serialization use karta hai

#### C) **Redis Pub/Sub** (Line 122-135)
```java
@Bean
public ChannelTopic webSocketTopic()
```
**Kya karta hai:** WebSocket events ke liye Redis Pub/Sub

---

## 3. **Redis Services (Actual Use)**

### A) **RedisStateService.java** ⭐ Main Service
**Location:** `src/main/java/com/chatcrmlite/backend/services/RedisStateService.java`

**Ye service 5 main kaam karti hai:**

#### i) **Key-Value Storage** (Line 32-42)
```java
public void set(String key, Object value, Duration ttl)
public <T> T get(String key, Class<T> clazz)
public void delete(String key)
```
**Use Case:** Temporary data store karna (TTL ke saath)

#### ii) **Counter/Increment** (Line 44-50)
```java
public Long increment(String key, Duration ttl)
```
**Use Case:** API rate limiting, request counting

#### iii) **Distributed Lock** (Line 55-63)
```java
public boolean tryLock(String lockKey, Duration ttl)
public void unlock(String lockKey)
```
**Use Case:** Multiple servers mein same time pe ek hi operation prevent karna

---

### B) **IdempotencyService.java** 🔒 Duplicate Prevention
**Location:** `src/main/java/com/chatcrmlite/backend/services/IdempotencyService.java`

**Kya karta hai:**
```java
StringRedisTemplate redisTemplate
valueOperations.setIfAbsent(key, "CLAIMED", Duration)
```
**Use Case:** 
- Duplicate webhook requests ko prevent karna
- Same message 2 baar process na ho

---

### C) **SemanticCacheService.java** 🧠 AI Response Caching
**Location:** `src/main/java/com/chatcrmlite/backend/services/SemanticCacheService.java`

**Kya karta hai:**
```java
@Cacheable(value = "ai_responses", key = "#hash")
```
**Use Case:**
- AI responses ko cache karna
- Same question dobara aaye toh cache se return karna
- Cost saving (Gemini API calls reduce)

---

### D) **CostTracker.java** 💰 AI Cost Tracking
**Location:** `src/main/java/com/chatcrmlite/backend/services/CostTracker.java`

**Kya karta hai:**
```java
@Cacheable(value = "tenant_costs")
```
**Use Case:**
- Tenant ki AI usage cost track karna
- Monthly quota check karna

---

### E) **DistributedSchedulerService.java** ⏰ Scheduled Jobs
**Location:** `src/main/java/com/chatcrmlite/backend/services/DistributedSchedulerService.java`

**Kya karta hai:**
```java
redisStateService.tryLock("scheduler:reminder", Duration.ofMinutes(5))
```
**Use Case:**
- Reminder jobs ko ek hi server pe run karna
- Multiple servers mein duplicate jobs prevent karna

---

## 4. **Redis Streams (Async Queue System)** 🚀

### A) **QueueRouter.java** - Message Producer
**Location:** `src/main/java/com/chatcrmlite/backend/services/workflow/QueueRouter.java`

```java
StringRedisTemplate redisTemplate
redisTemplate.opsForStream().add(streamName, payload)
```
**Use Case:**
- WhatsApp messages ko Redis Stream mein enqueue karna
- 3 streams: AI processing, Flow execution, Message delivery

---

### B) **WebhookWorker.java** - Stream Consumer
**Location:** `src/main/java/com/chatcrmlite/backend/services/WebhookWorker.java`

```java
StreamListener<String, ObjectRecord<String, String>>
redisTemplate.opsForStream().acknowledge(groupName, record)
```
**Use Case:**
- Redis stream se messages consume karna
- WhatsApp webhook processing

---

### C) **FlowWorker.java** - Flow Processing
**Location:** `src/main/java/com/chatcrmlite/backend/services/workflow/FlowWorker.java`

```java
redisTemplate.opsForStream().acknowledge()
```
**Use Case:**
- Appointment/Booking flow processing
- Async execution

---

### D) **AIWorker.java** - AI Processing
**Location:** `src/main/java/com/chatcrmlite/backend/services/workflow/AIWorker.java`

**Use Case:**
- AI responses ko async process karna
- Heavy AI operations ko background mein run karna

---

### E) **DeliveryWorker.java** - Message Delivery
**Location:** `src/main/java/com/chatcrmlite/backend/services/workflow/DeliveryWorker.java`

**Use Case:**
- WhatsApp messages ko send karna
- Delivery status track karna

---

## 5. **WebSocket Real-time Updates** 🔄

### A) **DistributedWebSocketPublisher.java**
**Location:** `src/main/java/com/chatcrmlite/backend/services/websocket/DistributedWebSocketPublisher.java`

```java
redisTemplate.convertAndSend("ws:events:broadcast", event)
```
**Use Case:**
- Multi-server setup mein WebSocket events broadcast karna
- Real-time dashboard updates

---

### B) **WebSocketEventBus.java**
**Location:** `src/main/java/com/chatcrmlite/backend/services/websocket/WebSocketEventBus.java`

```java
public void handleMessage(String message)
```
**Use Case:**
- Redis Pub/Sub se events receive karna
- Connected WebSocket clients ko notify karna

---

## 6. **Caching (Performance Optimization)** ⚡

### Files Using @Cacheable:

#### A) **FlowConfigService.java**
```java
@Cacheable(value = "flow_configs", key = "#user.id + '_' + #explicitSuffix")
```
**Cache kya:** Tenant ki flow configuration

#### B) **FlowDefinitionLoader.java**
```java
@Cacheable(value = "flow_machine_defs", key = "#flowDefinitionId")
```
**Cache kya:** Flow state machine definitions

#### C) **BusinessServiceRepository.java**
```java
@Cacheable(value = "business_services", key = "#owner.id")
```
**Cache kya:** Business services list

#### D) **TagService.java**
```java
@Cacheable("tenant_tags")
```
**Cache kya:** Contact tags

---

## 📊 **Redis Kaha Kya Store Karta Hai - Summary**

| Use Case | Redis Data Structure | TTL | Purpose |
|----------|---------------------|-----|---------|
| **Idempotency** | String (SET) | 5 minutes | Duplicate requests prevent |
| **Distributed Lock** | String (SETNX) | 1-5 minutes | Job coordination |
| **AI Response Cache** | Hash | 10 minutes | Cost saving |
| **Flow Config Cache** | Hash | 10 minutes | Performance |
| **Rate Limiting** | Counter | 1 hour | API throttling |
| **Message Queue** | Redis Stream | Persistent | Async processing |
| **WebSocket Events** | Pub/Sub | Instant | Real-time updates |
| **Session State** | String | 30 minutes | Conversation flow |

---

## 🔍 **Health Monitoring**

### `PlatformHealthController.java`
```java
GET /api/v1/platform/health/status
```
**Check karta hai:**
- Redis connectivity
- Database connectivity
- AI API status

---

## 🚨 **Redis Failure Handling**

### Runbook: `docs/sre/runbooks/RedisFailure.md`

**Symptoms:**
- `RedisConnectionException` in logs
- WebSocket updates failing
- AI Cost Tracking not updating

**Investigation:**
```bash
redis-cli -h ${REDIS_HOST} ping
redis-cli INFO memory
```

---

## 📝 **Environment Variables**

```bash
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=your_password
```

**Configuration files:**
- `application.properties`
- `docker-compose.yml`
- `deployment/k8s/production.yaml`

---

## ✅ **SUMMARY - Redis Ka Complete Use**

### **7 Main Uses:**

1. ✅ **Caching** - Performance improvement (10 min TTL)
2. ✅ **Message Queue** - Redis Streams for async processing
3. ✅ **Pub/Sub** - WebSocket real-time events
4. ✅ **Idempotency** - Duplicate prevention
5. ✅ **Distributed Locks** - Multi-server coordination
6. ✅ **Rate Limiting** - API throttling
7. ✅ **Session Storage** - Conversation state

### **Architecture:**
```
WhatsApp Webhook 
    ↓
Redis Stream (Enqueue)
    ↓
3 Worker Types (Consume):
    - WebhookWorker (Main processing)
    - AIWorker (AI responses)
    - FlowWorker (Appointment/Booking flows)
    - DeliveryWorker (Message delivery)
    ↓
Redis Pub/Sub
    ↓
WebSocket Broadcast
    ↓
Dashboard Real-time Updates
```

---

## ❌ **CELERY NAHI HAI**

Is project mein:
- ❌ **Celery nahi hai** (Ye Python library hai)
- ✅ **Redis Streams hai** (Java-based async processing)
- ✅ **Spring Boot @Scheduled hai** (Cron jobs ke liye)

**Alternative:**
- Celery ki jagah **Redis Streams + StreamListener** use hua hai
- Background jobs ke liye **@Scheduled annotations** use hue hain

---

**Total Redis-related Files: 20+**
**Main Services Using Redis: 15+**
**Docker Containers: 2 (redis + redis-exporter)**
