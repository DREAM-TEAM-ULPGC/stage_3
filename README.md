# Stage 3: Distributed Search Engine with Hazelcast Cluster

## Project Overview

This project implements **Stage 3** of the Big Data Search Engine Project for ULPGC. It evolves the modular architecture from Stage 2 into a **distributed cluster** with scalability and fault tolerance as inherent properties.

Building on the **Data Layer** from Stage 1, this system:
- Uses the same **datalake structure** (YYYYMMDD/HH/BOOK_ID.header.txt, BOOK_ID.body.txt)
- Separates **header/body** using Gutenberg markers (*** START/END OF THE PROJECT GUTENBERG EBOOK)
- Distributes the **inverted index** across a Hazelcast cluster using MultiMap

## Architecture Overview

The system uses a microservices architecture with the following components:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              NGINX LOAD BALANCER                             │
│                                   (port 80)                                  │
│                               least_conn policy                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                        │
                    ┌───────────────────┼───────────────────┐
                    ▼                   ▼                   ▼
            ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
            │   SEARCH-1   │    │   SEARCH-2   │    │   SEARCH-3   │
            │  (Javalin)   │    │  (Javalin)   │    │  (Javalin)   │
            │   :8080      │    │   :8080      │    │   :8080      │
            └──────┬───────┘    └──────┬───────┘    └──────┬───────┘
                   │                   │                   │
                   └───────────────────┼───────────────────┘
                                       ▼
         ┌─────────────────────────────────────────────────────────────┐
         │                    HAZELCAST CLUSTER                         │
         │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐          │
         │  │ HAZELCAST-1 │◄─►│ HAZELCAST-2 │◄─►│ HAZELCAST-3 │          │
         │  │   :5701     │  │   :5701     │  │   :5701     │          │
         │  └─────────────┘  └─────────────┘  └─────────────┘          │
         │                                                              │
         │                MultiMap: inverted-index                      │
         │                   (word → Set<docId>)                        │
         │                   backup-count: 2                            │
         └─────────────────────────────────────────────────────────────┘
                                       ▲
                                       │
            ┌──────────────────────────┼──────────────────────────┐
            │                          │                          │
    ┌───────┴───────┐          ┌───────┴───────┐          ┌───────┴───────┐
    │   INDEXER-1   │          │   INDEXER-2   │          │   INDEXER-3   │
    │  (JMS Client) │          │  (JMS Client) │          │  (JMS Client) │
    └───────┬───────┘          └───────┬───────┘          └───────┴───────┘
            │                          │                          │
            └──────────────────────────┼──────────────────────────┘
                                       │
                              ┌────────▼────────┐
                              │    ACTIVEMQ     │
                              │  Message Broker │
                              │  :61616, :8161  │
                              │                 │
                              │ Queue:          │
                              │ document.ingested│
                              └────────┬────────┘
                                       ▲
            ┌──────────────────────────┼──────────────────────────┐
            │                          │                          │
    ┌───────┴───────┐          ┌───────┴───────┐          ┌───────┴───────┐
    │   CRAWLER-1   │          │   CRAWLER-2   │          │   CRAWLER-3   │
    │ (Gutenberg)   │          │ (Gutenberg)   │          │ (Gutenberg)   │
    │               │          │               │          │               │
    │ Partition: 0  │          │ Partition: 1  │          │ Partition: 2  │
    └───────┬───────┘          └───────┬───────┘          └───────┴───────┘
            │                          │                          │
            └──────────────────────────┼──────────────────────────┘
                                       │
                              ┌────────▼────────┐
                              │    DATALAKE     │
                              │  (Shared Volume)│
                              └─────────────────┘
```

## Components

### 1. Crawler Service
- Downloads books from Project Gutenberg
- **Separates header/body** using Gutenberg markers (Stage 1 requirement)
- **Extracts metadata** from header: title, author, language, year (Stage 2 requirement)
- **Datalake structure**: `YYYYMMDD/HH/BOOK_ID.header.txt` and `BOOK_ID.body.txt`
- Partitions work across crawler instances (distributed crawling)
- Publishes `document.ingested` events to ActiveMQ (includes metadata)
- Stores documents in shared datalake volume with replication factor R=2

### 2. Indexer Service
- Subscribes to ActiveMQ `document.ingested` queue
- Tokenizes document content (filters stop words, min length 3)
- Maintains **inverted index in Hazelcast MultiMap** (word → Set<docId>)
- Stores **document metadata in Hazelcast IMap** (docId → {title, author, language, year})
- Supports concurrent indexing from multiple instances

### 3. Search Service
- REST API for querying the inverted index (`/search?q=word`)
- Connects to Hazelcast cluster as client
- TF-based ranking of search results
- **Stage 2 API compliance**: Returns `book_id`, `title`, `author`, `language`, `year`
- **Supports filters**: `author`, `language`, `year`
- Health check endpoints for Nginx load balancer

### 4. Hazelcast Cluster
- 3-node cluster for high availability
- **MultiMap** storing inverted index (word → Set<docId>)
- Replication factor: 2 (backup-count)
- TCP/IP discovery between nodes

### 5. ActiveMQ
- Message broker for async communication
- Queue: `document.ingested`
- Persistent delivery mode

### 6. NGINX Load Balancer
- Routes requests to search service cluster
- `least_conn` policy for optimal distribution
- Health checks and automatic failover

## Quick Start

### Prerequisites
- Docker and Docker Compose
- 8GB+ RAM recommended

### Build and Run

```bash
# Build all services
docker-compose build

# Start the cluster
docker-compose up -d

# View logs
docker-compose logs -f

# Check status
docker-compose ps
```

### Access Points

| Service | URL | Description |
|---------|-----|-------------|
| Search API | http://localhost/search?q=word | Main search endpoint |
| Health Check | http://localhost/health | Load balancer health |
| ActiveMQ Console | http://localhost:8161 | Admin UI (admin/admin) |
| Stats | http://localhost/admin/stats | Query statistics |

## API Endpoints

### Search
```bash
# Single word search
curl "http://localhost/search?q=adventure"

# Multi-word search
curl "http://localhost/search?q=sherlock+holmes"

# With author filter (Stage 2)
curl "http://localhost/search?q=adventure&author=Jules+Verne"

# With language filter (Stage 2)
curl "http://localhost/search?q=adventure&language=fr"

# With year filter (Stage 2)
curl "http://localhost/search?q=adventure&year=1865"

# Combined filters (Stage 2)
curl "http://localhost/search?q=adventure&author=Jules+Verne&language=fr&year=1865"
```

### Response Format (Stage 2 Compliant)
```json
{
  "query": "adventure",
  "filters": { "author": "Jules Verne", "language": "fr" },
  "count": 2,
  "results": [
    {
      "book_id": 12,
      "title": "De la Terre à la Lune",
      "author": "Jules Verne",
      "language": "fr",
      "year": 1865
    },
    {
      "book_id": 6500,
      "title": "Vingt mille lieues sous les mers",
      "author": "Jules Verne",
      "language": "fr",
      "year": 1870
    }
  ]
}
```

### Health & Status
```bash
# Health check
curl http://localhost/health

# Service status
curl http://localhost/status

# Query statistics
curl http://localhost/admin/stats

# Index size
curl http://localhost/admin/index-size
```

## Benchmarking

### Throughput Test
```bash
# Install Apache Bench
# Run 1000 requests, 10 concurrent
ab -n 1000 -c 10 "http://localhost/search?q=adventure"

# Or use hey
hey -n 1000 -c 10 "http://localhost/search?q=adventure"
```

### Expected Results
- **Throughput**: 500-2000 req/s (depending on hardware)
- **Latency P50**: < 10ms
- **Latency P99**: < 50ms

## Fault Tolerance Testing

### Node Failure
```bash
# Stop one search node
docker-compose stop search2

# Verify cluster continues working
curl http://localhost/search?q=test

# Stop one Hazelcast node
docker-compose stop hazelcast2

# Verify data is still available (replication)
curl http://localhost/search?q=test

# Restart nodes
docker-compose start search2 hazelcast2
```

### Load Balancer Failover
```bash
# Make multiple requests and observe node distribution
for i in {1..10}; do
  curl -s "http://localhost/status" | jq .node
done
```

## Scaling

### Add More Search Nodes
```bash
docker-compose up -d --scale search=5
```

### Add More Indexers
```bash
docker-compose up -d --scale indexer=5
```

## Configuration

### Environment Variables

| Variable | Service | Default | Description |
|----------|---------|---------|-------------|
| ACTIVEMQ_BROKER_URL | crawler, indexer | tcp://activemq:61616 | ActiveMQ connection |
| HAZELCAST_CLUSTER | indexer, search | hazelcast1:5701,... | Hazelcast cluster addresses |
| NODE_ID | all | service1 | Unique node identifier |
| REPLICATION_FACTOR | crawler | 2 | Datalake replication |
| HTTP_PORT | search | 8080 | REST API port |

## Project Structure

```
stage_3/
├── docker-compose.yml          # Full cluster orchestration
├── README.md                   # This file
├── nginx/
│   └── nginx.conf             # Load balancer configuration
├── hazelcast/
│   └── hazelcast.yaml         # Hazelcast cluster config
├── crawler-service/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/dreamteam/crawler/
│       └── CrawlerService.java
├── indexer-service/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/dreamteam/indexer/
│       └── IndexerService.java
└── search-service/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/dreamteam/search/
        └── SearchService.java
```

## Technologies

- **Java 17**: Service implementation
- **Hazelcast 5.3**: Distributed in-memory data grid
- **Apache ActiveMQ**: Message broker
- **Javalin**: Lightweight web framework
- **NGINX**: Load balancer
- **Docker Compose**: Container orchestration
- **Maven**: Build system

## Authors

- DREAM TEAM ULPGC

## License

Educational project - Universidad de Las Palmas de Gran Canaria
