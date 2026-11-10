INSERT INTO sys_role(id, code, name, status, version)
SELECT -1101, 'USER', '用户', 'ACTIVE', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'USER');

INSERT INTO sys_role(id, code, name, status, version)
SELECT -1102, 'DOCTOR', '医生', 'ACTIVE', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'DOCTOR');

INSERT INTO sys_role(id, code, name, status, version)
SELECT -1103, 'ADMIN', '管理员', 'ACTIVE', 0
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE code = 'ADMIN');
