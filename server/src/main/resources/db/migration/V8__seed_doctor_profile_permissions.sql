INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1208,NULL,'医生资料查看','BUTTON',NULL,'admin:doctors:view',50,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:doctors:view');

INSERT INTO sys_menu(id,parent_id,name,kind,route_path,permission_code,sort_no,status,version)
SELECT -1209,NULL,'医生资料管理','BUTTON',NULL,'admin:doctors:manage',60,'ACTIVE',0
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission_code='admin:doctors:manage');

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1308,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:doctors:view'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);

INSERT INTO sys_role_menu(id,role_id,menu_id)
SELECT -1309,r.id,m.id
FROM sys_role r JOIN sys_menu m ON m.permission_code='admin:doctors:manage'
WHERE r.code='ADMIN'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
