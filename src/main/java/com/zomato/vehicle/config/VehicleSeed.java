package com.zomato.vehicle.config;

import com.zomato.vehicle.domain.VehicleState;
import com.zomato.vehicle.domain.VehicleStateRepo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VehicleSeed {

  @Bean
  CommandLineRunner seedVehicles(VehicleStateRepo repo) {
    return args -> {
      int n = 20;                       // number of vehicles to simulate
      for (int i = 1; i <= n; i++) {
        String id = "V-" + i;
        repo.findByVehicleId(id).orElseGet(() -> repo.save(
            VehicleState.builder()
              .vehicleId(id)
              .latitude(12.9716)        // start near BLR
              .longitude(77.5946)
              .heading(Math.random() * 360)
              .speedKph(20 + Math.random() * 60)
              .build()
        ));
      }
    };
  }
}
