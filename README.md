# Big Data Pipeline - Stage 2

A microservices-based data processing pipeline for ingesting, indexing, and searching book content from the Gutenberg Project.

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

This project implements a distributed data pipeline consisting of four microservices:

```
┌─────────────────┐
│ Control Service │ ──> Orchestrates the entire pipeline
└────────┬────────┘
         │
         ├──> ┌────────────────────┐
         │    │ Ingestion Service  │ ──> Downloads and stores books
         │    └────────────────────┘
         │
         ├──> ┌────────────────────┐
         │    │ Indexer Service    │ ──> Creates inverted index & metadata catalog
         │    └────────────────────┘
         │
         └──> ┌────────────────────┐
              │ Search Service     │ ──> Provides search functionality
              └────────────────────┘
```

### Data Flow

1. **Ingestion**: Downloads books from Gutenberg Project and stores them in the datalake
2. **Indexing**: Creates an inverted index and metadata catalog from the ingested books
3. **Search**: Provides full-text search capabilities using the inverted index

## Services

### Control Service (Port 7000)

Orchestrates the entire pipeline and manages the workflow between services.

**Key Features:**
- Pipeline orchestration for individual books
- Status tracking and monitoring
- Log management
- History of processing operations

### Ingestion Service (Port 7001)

Handles downloading and storing book content from Project Gutenberg.

**Key Features:**
- Downloads books from Gutenberg mirror
- Stores raw content in the datalake
- Tracks ingestion status and progress
- Metadata extraction

### Indexer Service (Port 7002)

Creates searchable indexes and metadata catalogs from ingested books.

**Key Features:**
- Inverted index creation for full-text search
- Metadata catalog generation
- SQLite datamart for structured queries
- Incremental indexing support
- Progress tracking

### Search Service (Port 7003)

Provides search functionality over the indexed content.

**Key Features:**
- Full-text search with AND/OR modes
- Metadata filtering (author, language)
- Pagination support
- Book metadata retrieval
- Dynamic index reloading

## Prerequisites

- **Java 17** or higher
- **Maven 3.6+**
- **Internet connection** (for downloading books from Gutenberg)

## Building the Project

Build all services using Maven:

```bash
# Build Control Service
cd control-service
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

### Start All Services

Start each service in a separate terminal:

```bash/cmd
# Terminal 1 - Control Service
java -jar control-service/target/control-service-1.0.0.jar

# Terminal 2 - Ingestion Service
java -jar ingestion-service/target/ingestion-service-1.0.0.jar

# Terminal 3 - Indexer Service
java -jar indexer-service/target/indexer-service-1.0.0.jar

# Terminal 4 - Search Service
java -jar search-service/target/search-service-1.0.0.jar
```

### Verify Services are Running

```bash/cmd
curl http://localhost:7000/control/history
curl http://localhost:7001/status
curl http://localhost:7002/status
curl http://localhost:7003/status
```

## API Documentation

### Control Service (7000)

#### Start Pipeline for a Book
```bash
POST /control/start/{book_id}
```
Example:
```bash
curl -X POST http://localhost:7000/control/start/1
```

#### Get Book Status
```bash
GET /control/status/{book_id}
```
Example:
```bash
curl http://localhost:7000/control/status/1
```

#### Get Processing History
```bash
GET /control/history
```

#### Get Logs
```bash
GET /control/logs
```

#### Rebuild All Indexes
```bash
POST /control/rebuild
```

---

### Ingestion Service (7001)

#### Ingest a Book
```bash
POST /ingest/{book_id}
```
Example:
```bash
curl -X POST http://localhost:7001/ingest/84
```

#### Get Ingestion Status
```bash
GET /ingest/status/{book_id}
```

#### Get Service Status
```bash
GET /status
```

---

### Indexer Service (7002)

#### Get Service Status
```bash
GET /status
```

#### Get Indexing Status
```bash
GET /index/status
```

#### Update Index for a Book
```bash
POST /index/update/{book_id}
```
Example:
```bash
curl -X POST http://localhost:7002/index/update/84
```

#### Rebuild Full Index
```bash
POST /index/rebuild
```

---

### Search Service (7003)

#### Health Check
```bash
GET /health
```

#### Service Status
```bash
GET /status
```

#### Search Books
```bash
GET /search?q={query}&mode={and|or}&author={author}&language={language}&page={page}&pageSize={size}
```

Parameters:
- `q` (required): Search query
- `mode` (optional): Search mode - "and" or "or" (default: "and")
- `author` (optional): Filter by author
- `language` (optional): Filter by language
- `page` (optional): Page number (default: 1)
- `pageSize` (optional): Results per page (default: 20)

Example:
```bash
curl "http://localhost:7003/search?q=love&mode=and&page=1&pageSize=10"
```

#### Get Book by ID
```bash
GET /book/{id}
```
Example:
```bash
curl http://localhost:7003/book/84
```

#### Reload Index
```bash
POST /admin/reload
```

## Configuration

Each service can be configured via `application.properties` files:

### Ingestion Service
```properties
server.port=7001

datalake.dir=./datalake

ingestion.log.file=./datalake/ingestions.log

```

### Indexer Service
```properties
server.port=7002
datalake.path=datalake
index.output.path=indexer/inverted_index.json
catalog.output.path=metadata/catalog.json
db.path=datamart/datamart.db
index.progress.path=indexer/progress.json
catalog.progress.path=metadata/progress_parser.json
```

### Search Service
```properties
server.port=7003
index.path=indexer/inverted_index.json
db.path=datamart/datamart.db
```

## Project Structure

```
stage2/
├── control-service/          # Orchestration service
│   ├── src/
│   │   └── main/java/com/dreamteam/
│   │       ├── App.java
│   │       ├── control/      # Controllers
│   │       ├── model/        # Data models
│   │       └── utils/        # Utilities
│   └── pom.xml
│
├── ingestion-service/        # Book ingestion service
│   ├── src/
│   │   ├── main/java/com/dreamteam/ingestion/
│   │   │   ├── control/      # REST controllers
│   │   │   ├── core/         # Core ingestion logic
│   │   │   ├── config/       # Configuration loader
│   │   │   └── utils/        # Utilities
│   │   └── resources/
│   │       └── application.properties
│   └── pom.xml
│
├── indexer-service/          # Indexing service
│   ├── src/
│   │   ├── main/java/com/dreamteam/
│   │   │   ├── App.java
│   │   │   ├── config/       # Configuration loader
│   │   │   ├── core/         # Indexing logic
│   │   │   ├── datamart/     # Database operations
│   │   │   ├── progress/     # Progress tracker
│   │   │   └── service/      # Service layer
│   │   └── resources/
│   │       └── application.properties
│   └── pom.xml
│
├── search-service/           # Search service
│   ├── src/
│   │   └── main/java/com/dreamteam/search/
│   │       ├── App.java
│   │       ├── SearchEngine.java
│   │       ├── MetadataDao.java
│   │       ├── models/       # Data models
│   │       └── util/         # Utilities
│   └── pom.xml
│
├── datalake/                 # Raw book storage
├── indexer/                  # Inverted index storage
├── metadata/                 # Metadata catalogs
├── datamart/                 # SQLite database
└── README.md
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

## Example Workflow

Complete workflow to process a book:

```bash/cmd
# 1. Start the pipeline for book ID 84 (Frankenstein)
curl -X POST http://localhost:7000/control/start/84

# 2. Check the status
curl http://localhost:7000/control/status/84

# 3. Search for content
curl "http://localhost:7003/search?q=monster&mode=and"

# 4. Get book metadata
curl http://localhost:7003/book/84
```

## Data Storage

- **Datalake**: Raw book files (`datalake/`)
- **Inverted Index**: JSON file with word-to-document mappings (`indexer/inverted_index.json`)
- **Metadata Catalog**: JSON file with book metadata (`metadata/catalog.json`)
- **Datamart**: SQLite database with structured data (`datamart/datamart.db`)
- **Progress Files**: JSON files tracking processing progress

## Contributing

This project was developed by **DREAM-TEAM-ULPGC** for Big Data course work.

## License

This project is part of academic coursework at ULPGC.

---

**Author**: DREAM-TEAM-ULPGC  
**Course**: Big Data  
**Stage**: 2
