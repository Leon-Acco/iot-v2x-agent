-- ============================================================
-- V17: reseed capability_definition (TBOX device scope, 24 capabilities)
-- Old seeds pointed at non-existent/deprecated tables (tbox_trip_day, ADAS).
-- Wipe all rows; CapabilityRegistry re-seeds from resources/capability/*.yaml
-- at startup. capability_definition_history is kept for audit.
-- ============================================================
DELETE FROM capability_definition WHERE tenant_id = 'T1';
