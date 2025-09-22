package com.zomato.vehicle.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import com.zomato.vehicle.domain.VehicleState;
import com.zomato.vehicle.domain.VehicleStateRepo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VehicleIsolationService {

  private final PlatformTransactionManager txManager;
  private final VehicleStateRepo stateRepo;
  @PersistenceContext
  private EntityManager em;
  // Read the same row twice in one TX to detect non-repeatable reads
  public Map<String, Object> readLatestTwice(String vehicleId, long pauseMs, String level) {
    int iso = iso(level);
    return inTx(iso, "veh-read-" + level, () -> {
      VehicleState a = stateRepo.findByVehicleId(vehicleId).orElseThrow();
      var firstLat = a.getLatitude();
      var firstLon = a.getLongitude();
      // 🔽 ensure the next read goes to DB, not 1st-level cache
      em.clear();
      sleep(pauseMs);
      VehicleState b = stateRepo.findByVehicleId(vehicleId).orElseThrow();
      var secondLat = b.getLatitude();
      var secondLon = b.getLongitude();
      Map<String,Object> m = new HashMap<>();
      m.put("vehicleId", vehicleId);
      m.put("isolation", level);
      m.put("pauseMs", pauseMs);
      m.put("first",  map(firstLat, firstLon));
      m.put("second", map(secondLat, secondLon));
      m.put("changed", firstLat != secondLat || firstLon != secondLon);
      return m;
    });
  }

  // Count vehicles in a bounding box twice -> phantom check
  public Map<String, Object> scanBoxTwice(
		    double minLat, double maxLat, double minLon, double maxLon,
		    long pauseMs, String level, String lock) {

		  int iso = iso(level);
		  String lockMode = (lock == null) ? "none" : lock.trim().toLowerCase();

		  return inTx(iso, "veh-scan-" + level + "-" + lockMode, () -> {
		    int c1 = switch (lockMode) {
		      case "share"  -> stateRepo.findInBoxForShare(minLat, maxLat, minLon, maxLon).size();
		      case "update" -> stateRepo.findInBoxForUpdate(minLat, maxLat, minLon, maxLon).size();
		      default       -> stateRepo.findInBox(minLat, maxLat, minLon, maxLon).size();
		    };

		    em.clear();           // force the second read to hit the DB
		    sleep(pauseMs);

		    int c2 = switch (lockMode) {
		      case "share"  -> stateRepo.findInBoxForShare(minLat, maxLat, minLon, maxLon).size();
		      case "update" -> stateRepo.findInBoxForUpdate(minLat, maxLat, minLon, maxLon).size();
		      default       -> stateRepo.findInBox(minLat, maxLat, minLon, maxLon).size();
		    };

		    Map<String,Object> m = new HashMap<>();
		    m.put("box", String.format("[lat %.6f..%.6f, lon %.6f..%.6f]", minLat, maxLat, minLon, maxLon));
		    m.put("isolation", level);
		    m.put("lock", lockMode);
		    m.put("firstCount", c1);
		    m.put("secondCount", c2);
		    m.put("phantom", c1 != c2);
		    return m;
		  });
		}

  private static Map<String, Object> map(double lat, double lon) {
    Map<String,Object> m = new HashMap<>();
    m.put("lat", lat);
    m.put("lon", lon);
    return m;
    }

  private <T> T inTx(int iso, String name, Supplier<T> work) {
    DefaultTransactionDefinition def = new DefaultTransactionDefinition();
    def.setIsolationLevel(iso);
    def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    def.setName(name);
    TransactionStatus status = txManager.getTransaction(def);
    try {
      T res = work.get();
      txManager.commit(status);
      return res;
    } catch (RuntimeException e) {
      txManager.rollback(status);
      throw e;
    }
  }

  private static int iso(String level) {
    String l = level == null ? "RC" : level.trim().toUpperCase();
    return switch (l) {
      case "RU", "READ_UNCOMMITTED" -> TransactionDefinition.ISOLATION_READ_UNCOMMITTED;
      case "RC", "READ_COMMITTED"   -> TransactionDefinition.ISOLATION_READ_COMMITTED;
      case "RR", "REPEATABLE_READ"  -> TransactionDefinition.ISOLATION_REPEATABLE_READ;
      case "SER","SERIALIZABLE"     -> TransactionDefinition.ISOLATION_SERIALIZABLE;
      default -> throw new IllegalArgumentException("Unknown isolation: " + level);
    };
  }

  private static void sleep(long ms) {
    try { if (ms > 0) Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
  }
}
