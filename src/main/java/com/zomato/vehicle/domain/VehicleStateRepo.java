package com.zomato.vehicle.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface VehicleStateRepo extends JpaRepository<VehicleState, Long> {
	Optional<VehicleState> findByVehicleId(String vehicleId);

	@Query("""
			select v from VehicleState v
			where v.latitude between :minLat and :maxLat
			  and v.longitude between :minLon and :maxLon
			""")
	List<VehicleState> findInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
			@Param("minLon") double minLon, @Param("maxLon") double maxLon);

	// 🔒 Locking read: MySQL will translate PESSIMISTIC_READ to "FOR SHARE" (takes
	// next-key locks)
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select v from VehicleState v
			where v.latitude between :minLat and :maxLat
			  and v.longitude between :minLon and :maxLon
			""")
	List<VehicleState> findInBoxForShare(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
			@Param("minLon") double minLon, @Param("maxLon") double maxLon);

	// Optional: exclusive locks (stronger)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select v from VehicleState v
			where v.latitude between :minLat and :maxLat
			  and v.longitude between :minLon and :maxLon
			""")
	List<VehicleState> findInBoxForUpdate(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
			@Param("minLon") double minLon, @Param("maxLon") double maxLon);
}
