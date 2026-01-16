# Benchmark Results

## 1. Machine Specifications\
* **Device:** Lenovo Yoga Slim 6i\
* **CPU:** 12th Gen Intel(R) Core(TM) i5-1240P (1.70 GHz)\
* **RAM:** 16.0 GB\
* **OS:** Windows 11

## 2. Benchmark Command
To run the ingestion benchmark, the following command was used:\
```bash\
./mvnw -Dtest=BenchmarkTest test
```

## 3. Measured Timing


| Metric | Result | Constraint | Status |
| --- | --- | --- | --- |
| **Ingesting one batch of 1,000 events** | **637 ms** | < 1,000 ms | **PASSED** |



## 4. Optimizations Attempted

To ensure the system meets the sub-second processing requirement, the following optimizations were implemented:

### A. Bulk Database Processing (Solving N+1)

-   **Initial Problem:** Processing events one-by-one resulted in 1 Select + 1 Insert per event. For a batch of 1,000, this triggered **2,000 database calls**, causing the process to take >1.7 seconds.

-   **Optimization:** I implemented a bulk processing strategy:

    1.  Fetch all existing records for the batch in a single query (`findByEventIdIn`).

    2.  Process all deduplication and logic in-memory using a `HashMap`.

    3.  Save all new and updated records in a single `saveAll()` transaction.

-   **Result:** This reduced database round-trips from ~2,000 to just **2**, achieving the 637ms result.

### B. In-Memory Database

-   Used **H2 (In-Memory)** to eliminate disk I/O latency, ensuring the fastest possible read/write speeds during ingestion.

### C. Indexing

-   Added a database index (`@Index`) on the `eventId` column to ensure that the bulk fetch query remains performant (O(1) lookup behavior) regardless of table size.