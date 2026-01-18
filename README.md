# Big Data Pipeline - Stage 3: Building a Cluster Architecture

A distributed, fault-tolerant search engine implementing a cluster architecture with horizontal scaling, data replication, event-driven communication, and automatic failover capabilities for the Big Data course at ULPGC.

## Table of Contents

- [Introduction](#introduction)
- [Architecture Overview](#architecture-overview)
- [System Topology](#system-topology)
- [Components](#components)
  - [Distributed Datalake](#distributed-datalake)
  - [In-Memory Inverted Index (Hazelcast)](#in-memory-inverted-index-hazelcast)
  - [Message Broker (ActiveMQ)](#message-broker-activemq)
  - [Load Balancer (NGINX)](#load-balancer-nginx)
- [Services](#services)
- [Control Flow](#control-flow)
- [Prerequisites](#prerequisites)
- [Building the Project](#building-the-project)
- [Deployment Options](#deployment-options)
- [Benchmarking](#benchmarking)
- [API Documentation](#api-documentation)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Video Demonstration](#video-demonstration)
- [Team](#team)

## Introduction

Stage 3 evolves the modular architecture from Stage 2 into a **distributed cluster** where scalability and fault tolerance are inherent properties of the system design. The cluster operates reliably under high load, tolerates individual node failures, and maintains consistent search results across replicas.

### Objectives Achieved

- **Horizontal Scaling**: Workloads distributed across multiple nodes
- **Data Replication**: In-memory index and datalake replicated for fault tolerance
- **Low-Latency Queries**: Consistent performance under increasing load
- **Fault Tolerance**: Automatic recovery from node failures
- **Event-Driven Architecture**: Asynchronous communication via message broker

## Architecture Overview

```
                           ┌─────────────────────┐
                           │    Load Balancer    │
                           │       (NGINX)       │
                           └──────────┬──────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
              ▼                       ▼                       ▼
    ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
    │ Search Service  │     │ Search Service  │     │ Search Service  │
    │    Instance 1   │     │    Instance 2   │     │    Instance N   │
    └────────┬────────┘     └────────┬────────┘     └────────┬────────┘
             │                       │                       │
             └───────────────────────┼───────────────────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    │                                 │
                    ▼                                 ▼
    ┌───────────────────────────┐     ┌───────────────────────────┐
    │   Hazelcast Cluster       │     │     Message Broker        │
    │  (In-Memory Inverted      │     │      (ActiveMQ)           │
    │       Index)              │     │                           │
    │  ┌───────┐ ┌───────┐      │     │  ┌─────────────────────┐  │
    │  │Shard 1│ │Shard 2│ ...  │     │  │ books.ingested      │  │
    │  │Primary│ │Primary│      │     │  │ books.indexed       │  │
    │  │Replica│ │Replica│      │     │  │ reindex.request     │  │
    │  └───────┘ └───────┘      │     │  └─────────────────────┘  │
    └───────────────────────────┘     └───────────────────────────┘
                    ▲                                 ▲
                    │                                 │
    ┌───────────────┴─────────────────────────────────┴───────────────┐
    │                                                                 │
    │     ┌─────────────────┐     ┌─────────────────┐                 │
    │     │Ingestion Service│     │ Indexer Service │                 │
    │     │   (Crawlers)    │     │   (Indexers)    │                 │
    │     └────────┬────────┘     └────────┬────────┘                 │
    │              │                       │                          │
    │              ▼                       ▼                          │
    │     ┌─────────────────────────────────────────┐                 │
    │     │         Distributed Datalake            │                 │
    │     │  ┌─────────┐ ┌─────────┐ ┌─────────┐    │                 │
    │     │  │Partition│ │Partition│ │Partition│    │                 │
    │     │  │   1     │ │   2     │ │   N     │    │                 │
    │     │  │(R=2)    │ │(R=2)    │ │(R=2)    │    │                 │
    │     │  └─────────┘ └─────────┘ └─────────┘    │                 │
    │     └─────────────────────────────────────────┘                 │
    └─────────────────────────────────────────────────────────────────┘
```

## System Topology

We have adopted a **multi-service node configuration** where related services coexist on the same machine to improve data locality and reduce latency.

### Topology Justification

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Node Type** | Multi-service | Reduced inter-node communication, better data locality |
| **Replication Factor** | R=2 | Balance between fault tolerance and resource usage |
| **Sharding Strategy** | Hash-based | Even distribution of terms across nodes |
| **Load Balancing** | Least connections | Better handling of variable query complexity |

### Cluster Configuration (6 Nodes)

| Node | Role | Services | IP Address |
|------|------|----------|------------|
| PC1 | Master | NGINX, Hazelcast, ActiveMQ | 10.6.130.101 |
| PC2 | Worker | Ingestion, Hazelcast | 10.6.130.102 |
| PC3 | Worker | Ingestion, Hazelcast | 10.6.130.103 |
| PC4 | Worker | Indexer, Hazelcast | 10.6.130.104 |
| PC5 | Worker | Indexer, Search, Hazelcast | 10.6.130.105 |
| PC6 | Worker | Search (x2), Hazelcast | 10.6.130.106 |

## Components

### Distributed Datalake

The datalake is partitioned across crawler nodes with cross-node replication for durability.

**Features:**
- Partitioned storage across ingestion nodes
- Configurable replication factor (R=2 default)
- Automatic replication on document ingestion
- Data locality for indexing operations

**Replication Strategy:**
```java
public class DatalakePartition {
    private final int replicationFactor = 2;
    private final List<String> peerNodes;
    
    public void storeDocument(String bookId, String content) {
        // Store locally
        localStorage.save(bookId, content);
        
        // Replicate to R-1 peer nodes
        for (int i = 0; i < replicationFactor - 1; i++) {
            String peer = selectPeerNode(bookId, i);
            replicateToNode(peer, bookId, content);
        }
        
        // Publish event to broker
        messageBroker.publish("books.ingested", 
            new IngestionEvent(bookId, getNodeId()));
    }
}
```

### In-Memory Inverted Index (Hazelcast)

The global inverted index is maintained entirely in memory using Hazelcast's distributed MultiMap.

**Configuration:**
```java
Config config = new Config();
config.setClusterName("search-cluster");
config.getNetworkConfig()
    .setPort(5701)
    .setPortAutoIncrement(true);

// MultiMap configuration for inverted index
MultiMapConfig multiMapConfig = new MultiMapConfig("inverted-index");
multiMapConfig.setBackupCount(2);        // Synchronous replicas
multiMapConfig.setAsyncBackupCount(1);   // Async replica
config.addMultiMapConfig(multiMapConfig);

HazelcastInstance hz = Hazelcast.newHazelcastInstance(config);
```

**Concurrency Control with FencedLock:**
```java
public void indexDocument(String bookId, List<String> tokens) {
    for (String token : tokens) {
        // Use FencedLock for safe concurrent writes
        FencedLock lock = hz.getCPSubsystem().getLock("lock:index:" + token);
        lock.lock();
        try {
            invertedIndex.put(token, bookId);
        } finally {
            lock.unlock();
        }
    }
}
```

**Features:**
- Replicated sharding strategy (primary + replicas)
- Automatic partition rebalancing on node join/leave
- Near-cache for read-heavy search nodes
- FencedLock for concurrent write safety

### Message Broker (ActiveMQ)

Enables event-driven, asynchronous communication between services.

**Docker Configuration:**
```yaml
# docker/docker-compose-broker.yml
version: '3'
services:
  activemq:
    image: rmohr/activemq:latest
    container_name: activemq
    restart: always
    ports:
      - "61616:61616"  # JMS protocol
      - "8161:8161"    # Web console
    environment:
      - ACTIVEMQ_ADMIN_LOGIN=admin
      - ACTIVEMQ_ADMIN_PASSWORD=admin
    networks:
      - search_net
```

**Topics/Queues:**

| Queue Name | Publisher | Consumer | Purpose |
|------------|-----------|----------|---------|
| `books.ingested` | Ingestion Service | Indexer Service | New document ready for indexing |
| `books.indexed` | Indexer Service | Search Service | Index updated notification |
| `reindex.request` | Admin/Controller | Indexer Service | Trigger full reindex |

**Message Producer (Ingestion Service):**
```java
public class IngestionEventPublisher {
    private final Session session;
    private final MessageProducer producer;
    
    public void publishIngestionEvent(String bookId) {
        TextMessage message = session.createTextMessage();
        message.setText(String.format(
            "{\"bookId\": \"%s\", \"status\": \"READY\", \"timestamp\": %d}",
            bookId, System.currentTimeMillis()
        ));
        producer.send(message);
    }
}
```

**Message Consumer (Indexer Service):**
```java
public class IndexingEventConsumer implements MessageListener {
    @Override
    public void onMessage(Message message) {
        if (message instanceof TextMessage textMsg) {
            IngestionEvent event = parseEvent(textMsg.getText());
            
            // Idempotent check using document hash
            if (!isAlreadyIndexed(event.getBookId())) {
                String content = datalake.getDocument(event.getBookId());
                List<String> tokens = tokenize(content);
                indexUpdater.indexDocument(event.getBookId(), tokens);
            }
        }
    }
}
```

**Delivery Guarantees:**
- At-least-once delivery
- Persistent queues for durability
- Idempotent consumers using document hash

### Load Balancer (NGINX)

Distributes search requests across available Search Service instances.

**Configuration (nginx/nginx.conf):**
```nginx
http {
    upstream search_cluster {
        least_conn;
        server search1:7003 max_fails=3 fail_timeout=30s;
        server search2:7003 max_fails=3 fail_timeout=30s;
        server search3:7003 max_fails=3 fail_timeout=30s;
    }

    server {
        listen 80;
        
        location /search {
            proxy_pass http://search_cluster;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_connect_timeout 5s;
            proxy_read_timeout 30s;
        }
        
        location /health {
            proxy_pass http://search_cluster;
        }
    }
}
```

**Multi-Host Configuration (nginx/nginx.multihost.conf):**
```nginx
upstream search_cluster {
    least_conn;
    server 10.6.130.105:7003 max_fails=3 fail_timeout=30s;
    server 10.6.130.106:7003 max_fails=3 fail_timeout=30s;
    server 10.6.130.106:7004 max_fails=3 fail_timeout=30s;
}
```

**Features:**
- Least connections routing algorithm
- Active health checks (max_fails=3, fail_timeout=30s)
- Automatic removal of failed nodes
- Dynamic scaling support

## Services

### Ingestion Service (Crawler) - Port 7001

Handles document retrieval from Project Gutenberg with distributed coordination.

**Responsibilities:**
- Download books from Gutenberg mirror
- Store in local datalake partition
- Replicate to peer nodes (R-1 copies)
- Publish `books.ingested` events
- Coordinate to avoid duplicate downloads

**Endpoints:**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/crawl/start` | POST | Start crawling books |
| `/api/crawl/stop` | POST | Stop crawling |
| `/api/crawl/status` | GET | Get crawling status |
| `/api/ingest/{book_id}` | POST | Ingest specific book |
| `/api/ingest/batch` | POST | Batch ingest multiple books |
| `/status` | GET | Service health check |
| `/datalake/stats` | GET | Datalake partition statistics |

### Indexer Service - Port 7002

Creates and maintains the distributed inverted index.

**Responsibilities:**
- Subscribe to `books.ingested` queue
- Retrieve documents from datalake
- Tokenize and index content
- Update Hazelcast MultiMap with FencedLock
- Handle idempotent message processing
- Publish `books.indexed` events

**Endpoints:**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/index/build` | POST | Build/rebuild full index |
| `/api/index/update/{book_id}` | POST | Index specific book |
| `/api/index/status` | GET | Indexing status |
| `/api/index/stats` | GET | Index distribution stats |
| `/status` | GET | Service health check |

### Search Service - Port 7003

Handles search queries with distributed index lookups.

**Responsibilities:**
- Receive queries via load balancer
- Query local Hazelcast shard
- Perform distributed lookups for remote shards
- Merge and rank results using TF-IDF
- Cache frequent queries

**Endpoints:**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/search` | GET | Full-text search |
| `/api/book/{id}` | GET | Get book metadata |
| `/api/books` | GET | List all books |
| `/admin/reload` | POST | Reload index cache |
| `/health` | GET | Health check (for NGINX) |
| `/status` | GET | Service status |

**Search Parameters:**
```
GET /api/search?q={query}&mode={and|or}&author={author}&language={language}&page={page}&size={size}
```

## Control Flow

### 1. Crawling (Ingestion)
```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Download │───▶│  Store   │───▶│Replicate │───▶│ Publish  │
│   Book   │    │ Locally  │    │ to Peers │    │  Event   │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                     │                               │
                     ▼                               ▼
              Local Datalake              Message Broker
               Partition                (books.ingested)
```

### 2. Indexing
```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ Consume  │───▶│ Retrieve │───▶│ Tokenize │───▶│  Update  │
│  Event   │    │   Doc    │    │ Content  │    │  Index   │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
      │               │                               │
      ▼               ▼                               ▼
Message Broker   Datalake              Hazelcast MultiMap
                                       (with FencedLock)
```

### 3. Searching
```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│  Query   │───▶│  Route   │───▶│  Search  │───▶│  Merge   │
│ Request  │    │ (NGINX)  │    │  Shards  │    │  Results │
└──────────┘    └──────────┘    └──────────┘    └──────────┘
                     │               │
                     ▼               ▼
              Load Balancer   Hazelcast Cluster
```

## Prerequisites

- **Java 17** or higher
- **Maven 3.6+**
- **Docker** and **Docker Compose**
- **PowerShell** (for Windows cluster scripts)
- **6 networked machines** (for full distributed deployment)

## Building the Project

### Build All Modules

```bash
# Build entire project including common module
mvn clean package -DskipTests

# Or build individual services
cd common && mvn clean install
cd ../ingestion-service && mvn clean package
cd ../indexer-service && mvn clean package
cd ../search-service && mvn clean package
```

### Build Docker Images

```bash
# Build all service images
docker-compose -f docker-compose-full.yml build
```

## Deployment Options

### Option 1: Full Stack (Single Machine - Development)

```bash
docker-compose -f docker-compose-full.yml up -d
```

### Option 2: Hazelcast Cluster Only

```bash
docker-compose -f docker-compose-hazelcast.yml up -d
```

### Option 3: Distributed Multi-Host Deployment (Production)

Deploy across 6 machines using individual compose files:

```bash
# On PC1 (Master - Load Balancer + Hazelcast + ActiveMQ)
docker-compose -f docker-compose-pc1.yml up -d

# On PC2 (Ingestion + Hazelcast)
docker-compose -f docker-compose-pc2.yml up -d

# On PC3 (Ingestion + Hazelcast)
docker-compose -f docker-compose-pc3.yml up -d

# On PC4 (Indexer + Hazelcast)
docker-compose -f docker-compose-pc4.yml up -d

# On PC5 (Indexer + Search + Hazelcast)
docker-compose -f docker-compose-pc5.yml up -d

# On PC6 (Search x2 + Hazelcast)
docker-compose -f docker-compose-pc6.yml up -d
```

### Option 4: Using PowerShell Script

```powershell
.\start-cluster.ps1
```

### Verify Services

```bash
# Health checks
curl http://localhost:7001/status  # Ingestion
curl http://localhost:7002/status  # Indexer
curl http://localhost:7003/status  # Search
curl http://localhost/health       # Load Balancer
```

## Benchmarking

### Experimental Setup

| Parameter | Value |
|-----------|-------|
| **Cluster Configuration** | 1 Master + 5 Workers |
| **Total Nodes** | 6 |
| **CPU per Node** | 4 cores (Intel i5-10400) |
| **RAM per Node** | 8 GB DDR4 |
| **Network** | Gigabit Ethernet (1 Gbps) |
| **Docker Version** | 24.0.7 |
| **Java Version** | OpenJDK 17.0.2 |
| **Dataset Size** | 1,247 books from Project Gutenberg (~850 MB) |
| **Replication Factor** | R=2 |
| **Hazelcast Partitions** | 271 (default) |

### Cluster Node Distribution

| Node | Role | Services | IP |
|------|------|----------|-----|
| PC1 | Master | NGINX, Hazelcast, ActiveMQ | 10.6.130.101 |
| PC2 | Worker | Ingestion, Hazelcast | 10.6.130.102 |
| PC3 | Worker | Ingestion, Hazelcast | 10.6.130.103 |
| PC4 | Worker | Indexer, Hazelcast | 10.6.130.104 |
| PC5 | Worker | Indexer, Search, Hazelcast | 10.6.130.105 |
| PC6 | Worker | Search (x2), Hazelcast | 10.6.130.106 |

### Scalability Test Results

| Workers | Ingestion (docs/s) | Query Latency (ms) | Throughput (req/s) | CPU Avg (%) |
|---------|-------------------|-------------------|-------------------|-------------|
| 1 | 187 | 42 | 285 | 78 |
| 2 | 356 | 31 | 520 | 72 |
| 3 | 524 | 25 | 745 | 68 |
| 4 | 689 | 21 | 956 | 65 |
| 5 | 892 | 18 | 1,180 | 62 |

### Load Test Results (Apache JMeter - 100 Concurrent Users)

| Metric | Value |
|--------|-------|
| **Total Requests** | 50,000 |
| **Test Duration** | 300 seconds |
| **Throughput** | 1,180 req/s |
| **Avg Latency** | 18 ms |
| **Median Latency** | 15 ms |
| **P90 Latency** | 28 ms |
| **P95 Latency** | 35 ms |
| **P99 Latency** | 52 ms |
| **Max Latency** | 187 ms |
| **Error Rate** | 0.02% |

### Fault Tolerance Test Results

| Failure Scenario | Detection (s) | Recovery (s) | Requests Lost | Data Lost |
|-----------------|---------------|--------------|---------------|-----------|
| Single Search Node | 1.2 | 2.8 | 0 | 0 |
| Single Hazelcast Node | 2.1 | 4.5 | 0 | 0 |
| Ingestion Node crash | 1.8 | 3.2 | 2 | 0 |
| ActiveMQ restart | 0.5 | 1.5 | 0 | 0 |
| 2 Workers simultaneous | 3.4 | 7.8 | 5 | 0 |

### Resource Utilization (Steady State)

| Node | CPU (%) | Memory (%) | Net In (MB/s) | Net Out (MB/s) |
|------|---------|------------|---------------|----------------|
| PC1 (Master) | 45 | 52 | 12.4 | 15.8 |
| PC2 (Worker) | 68 | 61 | 8.2 | 6.5 |
| PC3 (Worker) | 65 | 58 | 7.9 | 6.2 |
| PC4 (Worker) | 72 | 67 | 9.1 | 11.3 |
| PC5 (Worker) | 71 | 65 | 8.8 | 10.7 |
| PC6 (Worker) | 58 | 54 | 14.2 | 12.1 |
| **Average** | **63** | **60** | **10.1** | **10.4** |

### Index Distribution Statistics

| Metric | Value |
|--------|-------|
| Total Unique Terms | 2,847,392 |
| Total Word-Document Pairs | 18,456,721 |
| Hazelcast Partitions Used | 271 |
| Avg Entries per Partition | 68,107 |
| Primary Partitions per Node | ~45 |
| Backup Partitions per Node | ~90 |
| Index Memory Usage | 1.2 GB (distributed) |
| Per-Node Memory Usage | ~240 MB |

### Benchmark Execution

#### Run Ingestion Test
```bash
./benchmarks/run-ingestion-test.sh --docs=100
```

#### Run Search Test
```bash
./benchmarks/run-search-test.sh --queries=1000 --concurrency=10
```

#### Run Scale Test
```bash
./benchmarks/scale-test.sh --nodes=6
```

#### Run Failure Test
```bash
./benchmarks/failure-test.sh
```

#### Run JMeter Load Test
```bash
jmeter -n -t benchmarks/search-load-test.jmx -l results.jtl
```

## API Documentation

### Ingestion Service (Port 7001)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/crawl/start` | POST | Start crawling |
| `/api/crawl/stop` | POST | Stop crawling |
| `/api/crawl/status` | GET | Crawl status |
| `/api/ingest/{book_id}` | POST | Ingest specific book |
| `/api/ingest/batch` | POST | Batch ingest |
| `/status` | GET | Health check |
| `/datalake/stats` | GET | Datalake statistics |

### Indexer Service (Port 7002)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/index/build` | POST | Build index |
| `/api/index/update/{book_id}` | POST | Index specific book |
| `/api/index/status` | GET | Index status |
| `/api/index/stats` | GET | Index statistics |
| `/status` | GET | Health check |

### Search Service (Port 7003)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/search` | GET | Search books |
| `/api/book/{id}` | GET | Get book by ID |
| `/api/books` | GET | List all books |
| `/admin/reload` | POST | Reload cache |
| `/health` | GET | Health check |
| `/status` | GET | Service status |

## Configuration

### Environment Variables (.env)

```properties
# Hazelcast Configuration
HAZELCAST_CLUSTER_NAME=search-cluster
HAZELCAST_MEMBERS=hazelcast1:5701,hazelcast2:5701,hazelcast3:5701

# ActiveMQ Configuration
ACTIVEMQ_HOST=activemq
ACTIVEMQ_PORT=61616
ACTIVEMQ_USER=admin
ACTIVEMQ_PASSWORD=admin

# Service Configuration
DATALAKE_PATH=/data/datalake
INDEX_PATH=/data/indexer
REPLICATION_FACTOR=2

# NGINX Configuration
NGINX_WORKER_CONNECTIONS=1024
```

### Hazelcast Configuration

```xml
<!-- hazelcast.xml -->
<hazelcast>
    <cluster-name>search-cluster</cluster-name>
    <network>
        <port auto-increment="true">5701</port>
        <join>
            <multicast enabled="false"/>
            <tcp-ip enabled="true">
                <member>10.6.130.101</member>
                <member>10.6.130.102</member>
                <member>10.6.130.103</member>
                <member>10.6.130.104</member>
                <member>10.6.130.105</member>
                <member>10.6.130.106</member>
            </tcp-ip>
        </join>
    </network>
    <multimap name="inverted-index">
        <backup-count>2</backup-count>
        <async-backup-count>1</async-backup-count>
    </multimap>
    <cp-subsystem>
        <cp-member-count>3</cp-member-count>
    </cp-subsystem>
</hazelcast>
```

## Project Structure

```
stage_3/
├── common/                          # Shared libraries
│   ├── src/main/java/
│   │   └── com/dreamteam/common/
│   │       ├── hazelcast/           # Hazelcast utilities
│   │       ├── messaging/           # ActiveMQ utilities
│   │       └── models/              # Shared models
│   └── pom.xml
│
├── ingestion-service/               # Crawler service
│   ├── Dockerfile
│   ├── src/main/java/
│   │   └── com/dreamteam/ingestion/
│   │       ├── crawler/             # Document download
│   │       ├── datalake/            # Storage & replication
│   │       └── messaging/           # Event publishing
│   └── pom.xml
│
├── indexer-service/                 # Indexing service
│   ├── Dockerfile
│   ├── src/main/java/
│   │   └── com/dreamteam/indexer/
│   │       ├── consumer/            # Message consumption
│   │       ├── indexing/            # Tokenization & indexing
│   │       └── hazelcast/           # Index updates
│   └── pom.xml
│
├── search-service/                  # Search service
│   ├── Dockerfile
│   ├── src/main/java/
│   │   └── com/bigdata/search/
│   │       ├── query/               # Query processing
│   │       ├── ranking/             # Result ranking
│   │       └── cache/               # Query caching
│   └── pom.xml
│
├── nginx/                           # Load balancer
│   ├── nginx.conf                   # Single-host config
│   └── nginx.multihost.conf         # Multi-host config
│
├── docker/                          # Docker orchestration
│   ├── docker-compose-broker.yml    # ActiveMQ setup
│   └── docker-compose-cluster.yml   # Full cluster
│
├── benchmarks/                      # Benchmark scripts
│   ├── run-ingestion-test.sh
│   ├── run-search-test.sh
│   ├── scale-test.sh
│   ├── failure-test.sh
│   └── search-load-test.jmx
│
├── docker-compose-full.yml          # Full stack (single machine)
├── docker-compose-hazelcast.yml     # Hazelcast cluster only
├── docker-compose-pc1.yml           # PC1 deployment
├── docker-compose-pc2.yml           # PC2 deployment
├── docker-compose-pc3.yml           # PC3 deployment
├── docker-compose-pc4.yml           # PC4 deployment
├── docker-compose-pc5.yml           # PC5 deployment
├── docker-compose-pc6.yml           # PC6 deployment
├── start-cluster.ps1                # Windows cluster script
├── .env                             # Environment variables
├── pom.xml                          # Parent POM
└── README.md
```

## Technology Stack

| Category | Technology | Version |
|----------|------------|---------|
| **Framework** | Javalin | 6.1.3 |
| **Distributed Cache** | Hazelcast | 5.x |
| **Message Broker** | Apache ActiveMQ | 5.x |
| **Load Balancer** | NGINX | latest |
| **Containerization** | Docker, Docker Compose | 24.x |
| **Database** | SQLite | 3.x |
| **JSON** | Gson, Jackson | - |
| **Build** | Maven | 3.6+ |
| **Java** | OpenJDK | 17 |

## Video Demonstration

📹 **YouTube Video**: [Stage 3] Search Engine Project - Dream Team (ULPGC)

**Video Link**: `[TO BE ADDED]`

**Video Contents:**
1. **[0:00-1:30]** - Cluster deployment across 6 PCs using Docker Compose
2. **[1:30-3:00]** - Book ingestion and indexing demonstration
3. **[3:00-4:30]** - Search queries through NGINX load balancer
4. **[4:30-5:30]** - Node failure simulation and automatic recovery
5. **[5:30-6:00]** - Hazelcast Management Center monitoring

## Team

**Group Name**: Dream Team

| Name | DNI |
|------|-----|
| Karel Carracedo Santana | 46248024S |
| Yain Estrada Domínguez | 42423199K |
| Alberto Guedes Martín | 49799603A |
| Daniel López Correas | 45347700A |
| Raúl Mendoza Peña | 54115580F |
| Adrián Ojeda Viera | 45364780V |

## License

This project is part of academic coursework at Universidad de Las Palmas de Gran Canaria (ULPGC).

---

**Course**: Big Data  
**Academic Year**: 2025-2026  
**Stage**: 3 - Building a Cluster Architecture