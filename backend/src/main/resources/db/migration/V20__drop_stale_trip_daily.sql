-- V20: drop stale capability row 'trip_daily' (points at non-existent table tbox_trip_day;
-- resurrected once by a stale yaml left in target/classes). Authoritative seeds live in V19.
DELETE FROM capability_definition WHERE tenant_id = 'T1' AND id = 'trip_daily';
