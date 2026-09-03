-- dst_v2x_sim: V2X AgentScope multi-agent demo database (simulated data)
CREATE DATABASE IF NOT EXISTS dst_v2x_sim;
USE dst_v2x_sim;

DROP TABLE IF EXISTS dim_fleet;
CREATE TABLE dim_fleet (
  fleet_id VARCHAR(32) NOT NULL COMMENT "fleet id",
  fleet_name VARCHAR(128) NOT NULL COMMENT "fleet name",
  region VARCHAR(64) NULL COMMENT "operation region",
  province VARCHAR(64) NULL COMMENT "province",
  city VARCHAR(64) NULL COMMENT "city",
  vehicle_count INT NULL COMMENT "vehicle scale",
  remark VARCHAR(255) NULL COMMENT "remark"
) ENGINE=OLAP
UNIQUE KEY(fleet_id)
DISTRIBUTED BY HASH(fleet_id) BUCKETS 1
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS dim_vehicle;
CREATE TABLE dim_vehicle (
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  plate_no VARCHAR(16) NOT NULL COMMENT "plate no",
  fleet_id VARCHAR(32) NOT NULL COMMENT "fleet id",
  brand VARCHAR(64) NULL COMMENT "brand",
  model VARCHAR(64) NULL COMMENT "model",
  energy_type VARCHAR(16) NULL COMMENT "BEV/PHEV",
  vehicle_type VARCHAR(32) NULL COMMENT "truck/van type",
  manufacture_year INT NULL COMMENT "manufacture year",
  rated_range_km INT NULL COMMENT "rated range km",
  battery_capacity_kwh DECIMAL(6,2) NULL COMMENT "battery kwh",
  register_date DATE NULL COMMENT "register date",
  status TINYINT NULL COMMENT "1 active 2 stopped"
) ENGINE=OLAP
UNIQUE KEY(vin)
DISTRIBUTED BY HASH(vin) BUCKETS 4
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_vehicle_status_daily;
CREATE TABLE fact_vehicle_status_daily (
  dt DATE NOT NULL COMMENT "date",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  online_minutes INT NULL COMMENT "online minutes",
  first_online_time DATETIME NULL COMMENT "first online",
  last_online_time DATETIME NULL COMMENT "last online",
  last_lng DOUBLE NULL COMMENT "last lng GCJ02",
  last_lat DOUBLE NULL COMMENT "last lat GCJ02",
  province VARCHAR(64) NULL COMMENT "province",
  city VARCHAR(64) NULL COMMENT "city",
  district VARCHAR(64) NULL COMMENT "district",
  odometer_km DOUBLE NULL COMMENT "odometer km"
) ENGINE=OLAP
DUPLICATE KEY(dt, vin)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_mileage_daily;
CREATE TABLE fact_mileage_daily (
  dt DATE NOT NULL COMMENT "date",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  mileage_km DOUBLE NULL COMMENT "mileage km",
  driving_minutes INT NULL COMMENT "driving minutes",
  avg_speed DOUBLE NULL COMMENT "avg speed",
  max_speed DOUBLE NULL COMMENT "max speed",
  night_driving_minutes INT NULL COMMENT "night driving min"
) ENGINE=OLAP
DUPLICATE KEY(dt, vin)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_trip;
CREATE TABLE fact_trip (
  trip_id VARCHAR(48) NOT NULL COMMENT "trip id",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  start_time DATETIME NOT NULL COMMENT "start time",
  end_time DATETIME NULL COMMENT "end time",
  start_lng DOUBLE NULL COMMENT "start lng",
  start_lat DOUBLE NULL COMMENT "start lat",
  end_lng DOUBLE NULL COMMENT "end lng",
  end_lat DOUBLE NULL COMMENT "end lat",
  start_city VARCHAR(64) NULL COMMENT "start city",
  end_city VARCHAR(64) NULL COMMENT "end city",
  mileage_km DOUBLE NULL COMMENT "mileage km",
  duration_min INT NULL COMMENT "duration min",
  avg_speed DOUBLE NULL COMMENT "avg speed"
) ENGINE=OLAP
DUPLICATE KEY(trip_id)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_charge;
CREATE TABLE fact_charge (
  charge_id VARCHAR(48) NOT NULL COMMENT "charge id",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  start_time DATETIME NOT NULL COMMENT "start time",
  end_time DATETIME NULL COMMENT "end time",
  charge_type VARCHAR(8) NULL COMMENT "fast/slow",
  charge_kwh DOUBLE NULL COMMENT "charge kwh",
  start_soc INT NULL COMMENT "start soc",
  end_soc INT NULL COMMENT "end soc",
  duration_min INT NULL COMMENT "duration min",
  station_name VARCHAR(128) NULL COMMENT "station name",
  city VARCHAR(64) NULL COMMENT "city"
) ENGINE=OLAP
DUPLICATE KEY(charge_id)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_alarm;
CREATE TABLE fact_alarm (
  alarm_id VARCHAR(48) NOT NULL COMMENT "alarm id",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  alarm_type INT NOT NULL COMMENT "alarm type code",
  alarm_name VARCHAR(64) NULL COMMENT "alarm name",
  alarm_level TINYINT NULL COMMENT "1 info 2 normal 3 severe",
  start_time DATETIME NOT NULL COMMENT "start time",
  end_time DATETIME NULL COMMENT "end time",
  lng DOUBLE NULL COMMENT "lng",
  lat DOUBLE NULL COMMENT "lat",
  city VARCHAR(64) NULL COMMENT "city",
  speed_kmh DOUBLE NULL COMMENT "speed kmh"
) ENGINE=OLAP
DUPLICATE KEY(alarm_id)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_fault;
CREATE TABLE fact_fault (
  fault_id VARCHAR(48) NOT NULL COMMENT "fault id",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  fault_code VARCHAR(16) NOT NULL COMMENT "fault code",
  fault_name VARCHAR(128) NULL COMMENT "fault name",
  fault_part VARCHAR(32) NULL COMMENT "fault part",
  fault_level TINYINT NULL COMMENT "1-4",
  report_time DATETIME NOT NULL COMMENT "report time",
  recover_time DATETIME NULL COMMENT "recover time null=active",
  city VARCHAR(64) NULL COMMENT "city"
) ENGINE=OLAP
DUPLICATE KEY(fault_id)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");

DROP TABLE IF EXISTS fact_geofence_event;
CREATE TABLE fact_geofence_event (
  event_id VARCHAR(48) NOT NULL COMMENT "event id",
  vin VARCHAR(32) NOT NULL COMMENT "vin",
  fence_name VARCHAR(64) NOT NULL COMMENT "fence name",
  fence_type VARCHAR(16) NULL COMMENT "warehouse/station/restricted",
  event_type VARCHAR(8) NOT NULL COMMENT "enter/exit",
  event_time DATETIME NOT NULL COMMENT "event time",
  lng DOUBLE NULL COMMENT "lng",
  lat DOUBLE NULL COMMENT "lat",
  city VARCHAR(64) NULL COMMENT "city"
) ENGINE=OLAP
DUPLICATE KEY(event_id)
DISTRIBUTED BY HASH(vin) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 3");
