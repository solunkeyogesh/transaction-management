# Interview Notes — Continuous Writer Thread vs. Transactions (MySQL Isolation Demo)

These are the notes I use to explain a live concurrency setup to interviewers: **one thread continuously writes GPS positions**, while my **API transaction reads twice** to demonstrate isolation levels and locking.

---

## 1) High-level story (30–45s pitch)

- I built a tiny **vehicle-service lab** inside my app.
- A **scheduled writer thread** updates vehicle rows (lat/lon) every ~300 ms and commits each tick.
- My **read endpoint** opens a transaction at a selected **isolation level** (RC / RR / SER), reads **twice** with a pause, and compares.
- This cleanly demonstrates **non-repeatable reads**, **phantoms**, and how **locking reads** or **SERIALIZABLE** prevent them—while also showing the trade‑off (blocking/throughput).

---

## 2) Roles: thread vs transaction

> **Thread** = execution context in the JVM.  
> **Transaction** = DB snapshot + lock scope (BEGIN…COMMIT).

- **Writer thread (scheduler)** runs repeatedly. Each run opens a **short DB transaction** (READ_COMMITTED), updates rows, inserts into history, then **commits**.
- **Reader** is an HTTP request. It opens **one DB transaction**, reads, waits, reads again, then commits (or uses two separate transactions for before/after snapshots).

---

## 3) Minimal code you can describe

**Writer (continuous updates, own transactions):**
```java
@Scheduled(fixedRate = 300)
@Transactional(isolation = Isolation.READ_COMMITTED)
public void tick() {
  var all = stateRepo.findAll();
  for (var v : all) {
    // move a tiny step based on heading/speed
    v.setLatitude(v.getLatitude() + dLat);
    v.setLongitude(v.getLongitude() + dLon);
    historyRepo.save(new VehiclePositionHistory(v.getVehicleId(), v.getLatitude(), v.getLongitude(), Instant.now()));
  }
  // implicit commit at method exit
}
```

**Reader (read twice inside ONE transaction):**
```java
public Map<String,Object> readLatestTwice(String vehicleId, long pauseMs, String level) {
  return inTx(map(level), "veh-read-" + level, () -> {
    var a = repo.findByVehicleId(vehicleId).orElseThrow();
    var first = Map.of("lat", a.getLatitude(), "lon", a.getLongitude());
    em.clear();                 // avoid JPA 1st-level cache; force DB hit next time
    Thread.sleep(pauseMs);      // give writer time to commit new version
    var b = repo.findByVehicleId(vehicleId).orElseThrow();
    var second = Map.of("lat", b.getLatitude(), "lon", b.getLongitude());
    return Map.of("first", first, "second", second, "changed", !first.equals(second));
  });
}
```

**Range scan (phantoms) + optional locking:**
```java
@Query("""
  select v from VehicleState v
  where v.latitude between :minLat and :maxLat
    and v.longitude between :minLon and :maxLon
""")
List<VehicleState> findInBox(...);

@Lock(LockModeType.PESSIMISTIC_READ)     // -> FOR SHARE (MySQL)
@Query("""
  select v from VehicleState v
  where v.latitude between :minLat and :maxLat
    and v.longitude between :minLon and :maxLon
""")
List<VehicleState> findInBoxForShare(...);
```

> **Index for clean range locks:** `CREATE INDEX idx_vehicle_state_lat_lon ON vehicle_state(latitude, longitude);`

---

## 4) What each isolation *shows* (MySQL/InnoDB)

| Isolation | Read twice (same row) | Range scan twice | Under the hood |
|---|---|---|---|
| **RC** (READ COMMITTED) | Often **changes** → non-repeatable read | Count may **change** → phantom | Each select sees **latest committed** version |
| **RR** (REPEATABLE READ) | Usually **stable snapshot** | Many phantom cases also blocked in **locking** reads | Consistent reads + next‑key locks for locking reads |
| **SER** (SERIALIZABLE) | **Stable**, writer may **block** | **Stable**, writer may **block** | Plain selects act like locking reads; strongest guarantees |

**Preventing phantoms at RC:** use **locking reads** (`FOR SHARE` / `PESSIMISTIC_READ`) on an **indexed range** → InnoDB takes **next‑key locks**.

---

## 5) ASCII picture (what’s happening)

```
Writer thread (every 300ms):          Reader (one request):

 [BEGIN][UPDATE lat/lon][INSERT hist][COMMIT]   [BEGIN tx at RC/RR/SER]
 [BEGIN][UPDATE lat/lon][INSERT hist][COMMIT]   read V-1
 [BEGIN][UPDATE lat/lon][INSERT hist][COMMIT]   (pause)
 ...                                            read V-1 again
                                               [COMMIT]
```

- At **RC**: the second read usually sees a newer committed version → **changed = true**.
- At **SER** (and some RR cases): the reader may hold locks → writer’s update **blocks** until commit.

---

## 6) Talking points / Q&A

- **Q: How do you prove isolation is applied?**  
  A: Log `SELECT @@transaction_isolation;`, enable SQL logs, and observe `FOR SHARE/UPDATE` for locking reads. Also, see blocking in `performance_schema.data_locks`.
- **Q: What about JPA cache?**  
  A: I call `EntityManager.clear()` between reads so the second read hits the DB instead of returning the same managed entity.
- **Q: Why not always SERIALIZABLE?**  
  A: It prevents anomalies but **reduces concurrency** (more blocking/timeouts). I prefer **RC + explicit locks** where needed.
- **Q: How do you prevent phantoms without SER?**  
  A: Use **locking reads** on an **indexed** range; InnoDB takes next‑key locks that block rows entering/leaving the range.

---

## 7) One‑liner summary

> “I run a continuous writer that commits small updates every 300 ms and a reader that compares two reads inside one transaction at different isolation levels. At RC I can **see changes and phantoms**; at RR/SER I get **stable snapshots**—and with **locking reads** I can **prevent phantoms even at RC**. I clear the JPA cache between reads and verify locks/isolation via MySQL performance schema.”
