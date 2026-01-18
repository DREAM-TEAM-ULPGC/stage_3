# Big Data Pipeline - Stage 3

A distributed, microservices-based data processing pipeline for ingesting (crawling), indexing, and searching book content from the Gutenberg Project — now deployed as a **6-node cluster** with **ActiveMQ**, **Nginx**, and **Hazelcast**.

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Services](#services)
- [Prerequisites](#prerequisites)
- [Building the Project](#building-the-project)
- [Running the Services](#running-the-services)
- [API Documentation](#api-documentation)
- [Configuration](#configuration)
- [Project Structure](#project-structure)

## Architecture Overview

Stage 3 evolves the pipeline into a **cluster of 6 PCs**:

- **PC1 (Master node)**: runs the cluster infrastructure (**ActiveMQ broker**, **Nginx**, **Hazelcast**) plus the application services (**crawler**, **indexer**, **search**).
- **PC2–PC6 (Worker nodes)**: run only the application services (**crawler**, **indexer**, **search**).

High-level layout:


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

### Data Flow

1. **Crawling / Ingestion**
   - Crawler services download books from the Gutenberg Project.
   - Raw files are stored into the **datalake**, which is **replicated across the cluster**.
   - The crawling stage tolerates worker node shutdowns/restarts: crawl jobs are distributed through ActiveMQ, and if a consumer goes down, pending/unacknowledged messages are redelivered to remaining crawler nodes.

2. **Indexing**
   - Indexers create/update the inverted index and metadata catalog.
   - Indexing is **multi-threaded** for better throughput and latency.

3. **Search**
   - Search services serve queries using the index + metadata store.
   - Nginx exposes a single entrypoint while search instances can scale horizontally.

## Services

### Cluster Infrastructure (Master only)

#### ActiveMQ Broker
Message broker used to distribute work (crawl/index tasks) among nodes and enable queue redistribution when nodes join/leave.

#### Hazelcast
Cluster coordination and distributed data features, used to support replication and cluster behavior.

#### Nginx
Single HTTP entrypoint and load balancer in front of the cluster.

### Application Services (All nodes)

#### Ingestion Service
Downloads books from Project Gutenberg and writes them into the distributed datalake.

#### Indexer Service
Builds searchable structures from the datalake. Creates an inverted index for full-text search, generating a metadata catalog, performing multi-threaded indexing for better throughput and efficiency, and supporting both incremental updates and full rebuilds.

#### Search Service
Provides search functionality over indexed content. Includes full-text search with AND/OR modes, metadata-based filtering, pagination, index reload/refresh support, and a dedicated benchmark endpoint for performance evaluation

## Prerequisites

- **Java 17** or higher
- **Maven 3.6+**
- **Docker + Docker Compose** (Stage 3 is designed to run as containers)
- **Network connectivity between the 6 PCs**
- **Internet connection** (for downloading books from Gutenberg)

## Building the Project

From the repository root, you can build everything via Maven:

```bash
mvn clean package


# Build Ingestion Service
cd ../ingestion-service
mvn clean package

# Build Indexer Service
cd ../indexer-service
mvn clean package

# Build Search Service
cd ../search-service
mvn clean package
```

Each service will generate a fat JAR in its respective `target/` directory.

## Running the Services

This stage provides per-node Docker Compose files (PC1–PC6).

### Deployment of the cluster

For a correct operation of the cluster, these are the points to follow:

1. Clone the repository on each PC (PC1..PC6).
2. Configure the IP addresses in .env and also in  nginx.multihost.conf by replacing the variables "PCX_IP" with the real address for each PC.
3. Start the compose file matching each PC.

   On PC1:

   ```
   docker compose -f docker-compose-pc1.yml up -d --build
   ```

   Rest of PC's:

   ```
   docker compose -f docker-compose-pc2.yml up -d --build
   docker compose -f docker-compose-pc3.yml up -d --build
   docker compose -f docker-compose-pc4.yml up -d --build
   docker compose -f docker-compose-pc5.yml up -d --build
   docker compose -f docker-compose-pc6.yml up -d --build
   ```

### Verify Services are Running

To verify the satatus of each service, simply write the URL in any browser as shown below. 

```
http://PCX_IP:7001/status # Ingestion service.
http://PCX_IP:7002/status # Indexer service.
http://PCX_IP:7003/status # Search engine.
```

## API Documentation

### Ingestion Service (7001)

#### Start Pipeline for a Book
```powershell
iwr -Method Post http://PCX_IP:7001/ingest/{book_id}
```
Example:
```powershell
iwr -Method Post http://10.26.14.239:7001/ingest/11
```

#### Start Pipeline for a Book Queue

```powershell
iwr -Method Post http://PCX_IP:7001/benchmark/start?n={numBooks}
```
Example:
```powershell
iwr -Method Post http://10.26.14.239:7001/benchmark/start?n=500
```

#### Get Book Status
```powershell
iwr http://PCX_IP:7001/ingest/status/{book_id}
```
Example:
```powershell
iwr http://10.26.14.239:7001/ingest/status/11
```

#### Get Datalake Stats
```powershell
iwr http://PCX_IP:7001/datalake/stats
```

### Indexer Service (7002)

#### Get Local Indexing Status
```powershell
iwr http://PCX_IP:7002/index/status
```

#### Get Distributed Indexing Status
```powershell
iwr http://PCX_IP:7002/index/distributed/stats
```

#### Index single book into distributed index
```powershell
iwr -Method Post http://PCX_IP:7002/index/distributed/{book_id}
```
Example
```powershell
iwr http://10.26.14.239:7002/index/distributed/11
```

#### Update Index for a Book
```powershell
iwr -Method Post http://PCX_IP:7002/index/update/{book_id}
```
Example:
```bash
iwr -Method Post http://10.26.14.239:7002/index/update/11
```

#### Rebuild Distributed Index
```powershell
iwr http://PCX_IP:7002/index/distributed/rebuild
```

### Search Service (7003)

#### Health Check
```powershell
iwr http://PCX_IP:7003/health
```

#### Service Status
```powershell
iwr http://PCX_IP:7003/status
```

#### Search Books
```powershell
iwr http://PCX_IP:7003/search?q={query}&mode={and|or}&author={author}&language={language}&page={page}&pageSize={pageSize}
```

Parameters:
- `q` (required): Search query
- `mode` (optional): Search mode - "and" or "or" (default: "and")
- `author` (optional): Filter by author
- `language` (optional): Filter by language
- `page` (optional): Page number (default: 1)
- `pageSize` (optional): Results per page (default: 20)

Example:
```powershell
iwr http://10.26.14.239:7003/search?q=love&mode=and&page=1&pageSize=10
```

#### Get Book Metadata by ID
```powershell
iwr http://PCX_IP:7003/book/{id}
```
Example:
```powershell
iwr http://10.26.14.239:7003/book/11
```

#### Distributed Search
```powershell
iwr http://PCX_IP:7003/search/distributed?q={query}&mode={and|or}&limit={limit}
```
Example:
```powershell
iwr http://10.26.14.239:7003/search/distributed?q=love&mode=and&limit=50
```

#### Get Distributed Index Stats
```powershell
iwr http://PCX_IP:7003/search/distributed/stats
```

## Technology Stack

- **Framework**: [Javalin](https://javalin.io/) - Lightweight web framework
- **JSON Processing**: Gson, Jackson
- **Database**: SQLite with JDBC
- **Build Tool**: Maven
- **Java Version**: 17

## Key Dependencies

- `io.javalin:javalin:6.1.3` - REST API framework
- `com.google.code.gson:gson:2.11.0` - JSON serialization
- `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` - Java 8 date/time support
- `org.xerial:sqlite-jdbc:3.45.0.0` - SQLite database driver
- `org.slf4j:slf4j-simple` - Logging

## Contributing

This project was developed by **DREAM-TEAM-ULPGC** for Big Data course work.

## License

This project is part of academic coursework at ULPGC.

---

**Author**: DREAM-TEAM-ULPGC  
**Course**: Big Data  
**Stage**: 2
