package com.zomato.vehicle.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Table(name = "vehicle_position_history", indexes = {
  @Index(name = "idx_vhist_vid_ts", columnList = "vehicleId, ts")
})
public class VehiclePositionHistory {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String vehicleId;

  @Column(nullable = false)
  private double latitude;

  @Column(nullable = false)
  private double longitude;

  @Column(nullable = false)
  private Instant ts;                   // event time
}
