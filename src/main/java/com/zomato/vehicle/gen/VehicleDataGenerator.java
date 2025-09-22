package com.zomato.vehicle.gen;

import com.zomato.vehicle.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class VehicleDataGenerator {

  private final VehicleStateRepo stateRepo;
  private final VehiclePositionHistoryRepo historyRepo;

  /**
   * Every 300ms: move each vehicle a tiny step and persist
   * Isolation: READ_COMMITTED is perfect for high-throughput writers.
   */
  @Scheduled(fixedRateString = "${vehicle.gen.rate-ms:10}")
  @Transactional(isolation = Isolation.SERIALIZABLE)
  public void tick() {
    List<VehicleState> all = stateRepo.findAll();
    for (VehicleState v : all) {
      // simple straight-line drift based on heading/speed
      double metersPerTick = (v.getSpeedKph() * 1000.0 / 3600.0) * (0.3); // 0.3s tick by default
      double dLat = (metersPerTick / 111_111.0) * Math.cos(Math.toRadians(v.getHeading()));
      double dLon = (metersPerTick / 111_111.0) * Math.sin(Math.toRadians(v.getHeading()));

      v.setLatitude(v.getLatitude() + dLat);
      v.setLongitude(v.getLongitude() + dLon);
      v.setHeading((v.getHeading() + (Math.random() - 0.5) * 10) % 360);

      // write history (phantom demo)
      historyRepo.save(VehiclePositionHistory.builder()
          .vehicleId(v.getVehicleId())
          .latitude(v.getLatitude())
          .longitude(v.getLongitude())
          .ts(Instant.now())
          .build());
    }
     stateRepo.saveAll(all); // not needed; entities are managed & dirty-checked
  }
}
