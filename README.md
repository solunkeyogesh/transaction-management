# Vehicle Isolation Lab (inside `order-service`)

Continuously simulates moving vehicles (lat/lon) and exposes endpoints to **observe and compare transaction isolation levels** (`RU`, `RC`, `RR`, `SER`) on **MySQL/InnoDB**. You can also toggle **locking reads** to prevent phantoms at `RC`.

---

## What’s inside

**Packages**
```
com.zomato.vehicle.domain     # JPA entities + repos (VehicleState, VehiclePositionHistory)
com.zomato.vehicle.config     # Seed data (V-1..V-20)
com.zomato.vehicle.gen        # Continuous writer (scheduler): moves vehicles every ~300ms
com.zomato.vehicle.core       # Programmatic TX service with selectable isolation
com.zomato.vehicle.api        # REST endpoints to run the lab
```

**Key ideas**
- Writer updates rows frequently → natural concurrency.
- Reader endpoints open **one transaction** (or two, if you use the two-TX variant) and **read twice** with a pause to reveal:
  - **Non-repeatable reads** (row value changes between two reads).
  - **Phantoms** (range count changes as rows move into/out of the range).
- We **clear the JPA 1st-level cache** between reads so the second read hits the DB.

---

## Prereqs

- Java 21+ (or your project’s JDK)
- MySQL 8.x (InnoDB)
- Spring Boot 3.x with JPA/Hibernate + Hikari (already in your app)

**Recommended MySQL index (for precise range/next-key locking):**
```sql
CREATE INDEX idx_vehicle_state_lat_lon
  ON vehicle_state (latitude, longitude);
```

---

## Enable & Configure

1) Ensure scheduling is enabled (once):
```java
@EnableScheduling
@SpringBootApplication
public class OrderServiceApplication { ... }
```

2) (Optional) Tweaks in `application.yml`:
```yaml
vehicle:
  gen:
    rate-ms: 300          # generator tick (ms); lower = faster movement

logging:
  level:
    org.hibernate.SQL: debug
    org.springframework.transaction.interceptor: debug
    com.zomato.vehicle: info

spring:
  datasource:
    hikari:
      transaction-isolation: TRANSACTION_READ_COMMITTED  # global default (optional)
```

---

## Run

```bash
mvn spring-boot:run
# or run your OrderServiceApplication from IDE
```

On startup you’ll see seed logs and periodic “tick” logs from the generator.

---

## Endpoints

Base path: `/vehicle/lab`

| Endpoint | Params | Purpose |
|---|---|---|
| `GET /read-twice` | `vehicleId`, `pauseMs` (default 5000), `level` (`RU|RC|RR|SER`) | Reads the same row twice in **one transaction** to detect non-repeatable reads. |
| `GET /scan-box` | `minLat,maxLat,minLon,maxLon`, `pauseMs` (5000), `level` (`RU|RC|RR|SER`), `lock` (`none|share|update`) | Scans a bounding box twice to detect **phantoms**. With `lock=share/update`, InnoDB takes next-key locks and prevents phantoms even at `RC`. |

**JPQL used by `/scan-box`:**
```sql
select *
from vehicle_state
where latitude  between :minLat and :maxLat
  and longitude between :minLon and :maxLon;
-- locking variants add: FOR SHARE  (PESSIMISTIC_READ)  or  FOR UPDATE (PESSIMISTIC_WRITE)
```

---

## How to test isolation (copy/paste)

### 1) Non-repeatable read

- **READ COMMITTED** (often changes between reads):
```bash
curl "http://localhost:8080/vehicle/lab/read-twice?vehicleId=V-1&pauseMs=6000&level=RC"
# Expect: "changed": true (the vehicle moved)
```

- **REPEATABLE READ** (MySQL default snapshot):
```bash
curl "http://localhost:8080/vehicle/lab/read-twice?vehicleId=V-1&pauseMs=6000&level=RR"
# Often: "changed": false (stable snapshot)
```

- **SERIALIZABLE** (strongest; may block generator):
```bash
curl "http://localhost:8080/vehicle/lab/read-twice?vehicleId=V-1&pauseMs=30000&level=SER"
# Expect: "changed": false and the generator may stall updating V-1 during your TX
```

### 2) Phantom read (range count)

Pick a box near the seed (Bengaluru area):

- **READ COMMITTED (no locks)** → phantoms likely:
```bash
curl "http://localhost:8080/vehicle/lab/scan-box?minLat=12.9700&maxLat=12.9750&minLon=77.5900&maxLon=77.6000&pauseMs=30000&level=RC&lock=none"
# Expect: "phantom": true (firstCount != secondCount)
```

- **READ COMMITTED + locking read** → prevent phantoms:
```bash
curl "http://localhost:8080/vehicle/lab/scan-box?minLat=12.9700&maxLat=12.9750&minLon=77.5900&maxLon=77.6000&pauseMs=30000&level=RC&lock=share"
# or lock=update
# Expect: "phantom": false (range is protected by next-key locks)
```

- **SERIALIZABLE** → also prevents phantoms (may block more):
```bash
curl "http://localhost:8080/vehicle/lab/scan-box?minLat=12.9700&maxLat=12.9750&minLon=77.5900&maxLon=77.6000&pauseMs=30000&level=SER"
# Expect: "phantom": false (updates that would alter the range block)
```

> If you get `firstCount: 0, secondCount: 0`, widen/narrow the box or increase `pauseMs`.

---

## Locking modes on MySQL (what `lock` does)

- `lock=none` → normal consistent reads (snapshot). Phantoms possible at `RC`.
- `lock=share` → `PESSIMISTIC_READ` → **`FOR SHARE`**. Takes **next-key locks** on the index range; prevents phantoms.
- `lock=update` → `PESSIMISTIC_WRITE` → **`FOR UPDATE`**. Stronger; also prevents phantoms.

**Make sure the index exists**:
```sql
CREATE INDEX idx_vehicle_state_lat_lon
  ON vehicle_state (latitude, longitude);
```

---

## How isolation levels behave (cheat sheet)

| Level | Dirty read | Non-repeatable read | Phantom | Notes |
|---|---|---|---|---|
| RU | ❌ (InnoDB treats like RC) | ❌ | ❌ | Generally same as RC in MySQL |
| RC | ✅ blocks dirty | ⚠️ may happen | ⚠️ may happen | Best default; pair with locks when needed |
| RR | ✅ | ✅ | ✅* (with next-key locks) | MySQL default snapshot |
| SER | ✅ | ✅ | ✅ | Strongest; causes blocking & possible timeouts |

---

## Observability / Verification

**Show active isolation (MySQL):**
```sql
SELECT @@transaction_isolation;
```

**See locks/waits while a long `SER` or `lock=share` scan runs:**
```sql
SELECT * FROM performance_schema.data_lock_waits;
SELECT * FROM performance_schema.data_locks;
SHOW ENGINE INNODB STATUS\G
```

**App logs**  
Enable:
```yaml
logging.level.org.hibernate.SQL: debug
logging.level.org.springframework.transaction.interceptor: debug
```
You’ll see SQL, tx begin/commit, and (for locking reads) `... FOR SHARE` / `FOR UPDATE`.

---

## Troubleshooting

- **“changed”: false at RC**: ensure we clear the persistence context between reads (already in `VehicleIsolationService`), and use a pause long enough (≥ 5–10s).
- **No phantoms at RC**: you might be scanning an empty or too-stable box. Move the box closer to current positions (use `/read-twice?pauseMs=0` to locate), increase `pauseMs`, or speed up the generator.
- **No blocking at SER**: confirm `level=SER`, index exists, and you’re actually touching the same row/range; also verify via `SELECT @@transaction_isolation`.

---

## Cleaning up

The generator stores positions in `vehicle_position_history`. You can truncate it safely:
```sql
TRUNCATE TABLE vehicle_position_history;
```

---

## License

This lab code is intended for educational/demo purposes inside your `order-service`.
