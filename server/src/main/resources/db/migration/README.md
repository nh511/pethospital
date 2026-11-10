# 数据库迁移版本策略

Flyway迁移从空MySQL 8.4库执行，已应用文件不可修改、重命名或删除。发现错误时新增前向迁移修复；禁止通过`repair`掩盖已提交脚本的checksum变化。生产和测试都保持`validate-on-migrate=true`、`clean-disabled=true`、`baseline-on-migrate=false`、`out-of-order=false`。

版本编排：

- `V1__initialize_schema_versioning.sql`：仅建立版本历史，不创建业务表。
- `V2__identity_and_shared_tables.sql`：M02-02的12张账号与共享表。
- `V3__pet_scheduling_and_clinical_tables.sql`：M02-03的8张宠物、排班与临床表。
- `V4__ai_rag_and_content_tables.sql`：M02-04的13张AI、RAG与内容表。
- `V5__cross_module_foreign_keys.sql`：需要等待两端表都存在的跨模块/循环引用外键及最终约束。
- `V6__seed_builtin_roles.sql`：M03-01为全新数据库补齐USER、DOCTOR、ADMIN内置角色；只在对应code不存在时插入，不覆盖已有角色配置。
- `V7__seed_admin_permissions.sql`：M03-07补齐账号/RBAC查看与管理四个真实后端动作权限，并绑定内置ADMIN角色；只补缺项，不覆盖已有菜单或关联配置。

每个业务分组先创建表、主键、局部唯一键和索引；跨分组外键最后用`ALTER TABLE`添加。33张业务表的字段与索引仍以`docs/02-database-design.md`为准，本目录不创建未来版本的空占位脚本。
