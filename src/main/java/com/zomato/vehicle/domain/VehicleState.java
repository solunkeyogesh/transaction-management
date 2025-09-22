package com.zomato.vehicle.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "vehicle_state", indexes = {
		@Index(name = "idx_vehicle_state_vid", columnList = "vehicleId", unique = true),
		@Index(name = "idx_vehicle_state_lat_lon", columnList = "latitude, longitude") })
public class VehicleState {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String vehicleId; // e.g. "V-1"

	@Column(nullable = false)
	private double latitude;

	@Column(nullable = false)
	private double longitude;

	private double heading; // degrees 0..360
	private double speedKph; // simulated

	private Instant updatedAt;

	@PrePersist
	@PreUpdate
	void touch() {
		updatedAt = Instant.now();
	}
}
