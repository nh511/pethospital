INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1201,NULL,'账号查看','BUTTON',NULL,'admin:users:view',10,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:users:view');

INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1202,NULL,'账号管理','BUTTON',NULL,'admin:users:manage',20,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:users:manage');

INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1203,NULL,'权限查看','BUTTON',NULL,'admin:rbac:view',30,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:rbac:view');

INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1204,NULL,'权限管理','BUTTON',NULL,'admin:rbac:manage',40,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:rbac:manage');

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1301,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:users:view'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1302,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:users:manage'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1303,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:rbac:view'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1304,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:rbac:manage'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
