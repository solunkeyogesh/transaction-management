package com.zomato.vehicle.api;

import com.zomato.vehicle.core.VehicleIsolationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/vehicle/lab")
@RequiredArgsConstructor
public class VehicleLabController {

  private final VehicleIsolationService svc;

  // Example: GET /vehicle/lab/read-twice?vehicleId=V-1&pauseMs=5000&level=RC
  @GetMapping("/read-twice")
  public Map<String,Object> readTwice(
      @RequestParam String vehicleId,
      @RequestParam(defaultValue = "5000") long pauseMs,
      @RequestParam(defaultValue = "RC") String level) {
    return svc.readLatestTwice(vehicleId, pauseMs, level);
  }

  // Example: GET /vehicle/lab/scan-box?minLat=12.97&maxLat=12.99&minLon=77.58&maxLon=77.61&pauseMs=5000&level=RC
  @GetMapping("/scan-box")
  public Map<String,Object> scanBox(
      @RequestParam double minLat, @RequestParam double maxLat,
      @RequestParam double minLon, @RequestParam double maxLon,
      @RequestParam(defaultValue = "5000") long pauseMs,
      @RequestParam(defaultValue = "RC") String level,@RequestParam(defaultValue = "none") String lock) {
    return svc.scanBoxTwice(minLat, maxLat, minLon, maxLon, pauseMs, level,lock);
  }
}
