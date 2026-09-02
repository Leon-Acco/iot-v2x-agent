-- V2: P0 演示种子用户
-- admin/admin123  管理员（3 个车队全量权限）
-- op1/op123456    车队一运营（仅 F001）
-- op2/op123456    车队二运营（仅 F002，用于越权测试）
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, roles, fleet_ids)
VALUES
('T1', 'admin', '54b9beaa3cd3796516ab9968aabf3c30e713e087c7e89c07826e80530254fcbb', '平台管理员',
 '["admin", "fleet_operator"]', '["F001", "F002", "F003"]'),
('T1', 'op1', '1b25f7dae81fedc4514bb717ed10ece9fe0992f2b970da19cfeb92eafc3d3609', '车队一运营',
 '["fleet_operator"]', '["F001"]'),
('T1', 'op2', '1b25f7dae81fedc4514bb717ed10ece9fe0992f2b970da19cfeb92eafc3d3609', '车队二运营',
 '["fleet_operator"]', '["F002"]')
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
