# Backend Intern Assignment - Factory Event Service

## 1. Architecture
* **Framework:** Java 17, Spring Boot 3.x
* **Database:** H2 (In-Memory) for high-speed local execution.
* **Build Tool:** Maven
* **Design Pattern:** Layered Architecture
    * **Controller:** Handles HTTP requests and validation.
    * **Service:** Contains core business logic (deduplication, bulk processing, stats calculation).
    * **Repository:** Manages data access with Spring Data JPA.
    * **Database:** H2 In-Memory database with indexed columns for performance.

## 2. Deduplication & Update Logic
The system enforces data integrity using `eventId` as the unique business key :

1.  **Deduplication (Ignore):**
    * If an incoming `eventId` already exists in the database **AND** the payload (machineId, eventTime, duration, defectCount) is identical, the new event is ignored.
2.  **Update Strategy (Winning Record):**
    * If an incoming `eventId` exists but the payload differs, we compare timestamps.
    * **Rule:** We ignore the `receivedTime` provided in the JSON input. Instead, the system assigns `Instant.now()` to the batch upon arrival.
    * **Logic:**
        * If the existing database record has a `receivedTime` that is *after* the current processing time (simulating a race condition where newer data was already saved), we ignore the update.
        * Otherwise, we overwrite the existing record with the new payload and the current system timestamp.

## 3. Thread-Safety
The application handles concurrent requests from multiple sensors using:
* **Transactional Semantics:** The `processBatch` method is annotated with `@Transactional`. This ensures that the entire batch processing (fetch, logic, save) occurs within a specific isolation level, preventing partial writes.
* **Database Constraints:** The `eventId` column has a `UNIQUE` constraint. This acts as the final guardrail; if two threads attempt to insert the same `eventId` simultaneously, the database ensures only one succeeds, preventing corrupt duplicate data.

## 4. Data Model
The system uses a single table `MACHINE_EVENTS`:

| Column | Type | Description |
| :--- | :--- | :--- |
| `id` | BIGINT (PK) | Auto-incremented internal ID. |
| `event_id` | VARCHAR | **Indexed & Unique**. The business key provided by sensors. |
| `machine_id` | VARCHAR | The machine identifier. |
| `event_time` | TIMESTAMP | When the event occurred (used for stats windows). |
| `received_time`| TIMESTAMP | System timestamp set by the API when data arrived. |
| `duration_ms` | BIGINT | Duration of the event. |
| `defect_count` | INT | Number of defects (-1 indicates unknown). |

**In-Memory Structure:** During batch processing, existing records are loaded into a `HashMap<String, MachineEvent>` for O(1) lookups.

## 5. Performance Strategy
**Goal:** Process 1,000 events in < 1 second.
**Result:** ~637 ms (See `BENCHMARK.md`).

**Optimizations Implemented:**
1.  **Bulk Processing (N+1 Fix):** Instead of executing 1 Select and 1 Insert for every event (2,000 DB calls for a batch of 1,000), the system:
    * Extracts all incoming `eventIds`.
    * Fetches all matching existing records in **one** query.
    * Processes logic in-memory.
    * Saves all changes in **one** bulk `saveAll()` operation.
    * **Impact:** Reduced DB round-trips from ~2,000 to 2.
2.  **Database Indexing:** Added `@Index` on the `eventId` column to ensure the bulk fetch is highly efficient.
3.  **H2 In-Memory:** Eliminates disk I/O latency.

## 6. Edge Cases & Assumptions
* **Assumption:** The `receivedTime` in the input JSON is unreliable and is ignored in favor of the server's `Instant.now()` to ensure a trusted timeline.
* **Validation:**
    * **Future Events:** Events > 15 minutes in the future are rejected.
    * **Invalid Duration:** Durations < 0 or > 6 hours are rejected.
* **Defect Handling:** Events with `defectCount = -1` are stored for record-keeping but are excluded from `defectsCount` and `avgDefectRate` calculations.
* **Top Defect Lines:** Since the input data lacks a `lineId`, the system assumes `machineId` represents the line for aggregation purposes.

## 7. Setup & Run Instructions
**Prerequisites:** Java 17+, Maven.

1.  **Run the Application:**
    ```bash
    ./mvnw spring-boot:run
    ```
2.  **Run Unit Tests:**
    ```bash
    ./mvnw test
    ```
3.  **Run Benchmark:**
    ```bash
    ./mvnw -Dtest=BenchmarkTest test
    ```
4.  **API Endpoints:**
    * **Ingest:** `POST /events/batch`
    * **Stats:** `GET /stats?machineId=M-1&start=...&end=...`
    * **Console:** Access H2 Console at `http://localhost:8080/h2-console` (JDBC URL: `jdbc:h2:mem:factorydb`)

## 8. Improvements with More Time
* **Persistent Database:** Migrate from H2 to PostgreSQL/TimescaleDB for production data durability.
* **Async Processing:** Use a message queue (Kafka/RabbitMQ) to decouple ingestion from processing for higher scalability under massive load.
* **Better Error Handling:** Implement a global exception handler and more granular error codes for partial batch failures.
* **Security:** Implement API Key authentication for the endpoints.