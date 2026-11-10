# M02 数据库迁移与接口契约

更新：2026-10-30。状态：任务已实现但模块门槛未满足；任务 7/7。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F01、F02、F03、F04、F05、F06、F07、F08、F09、F10、F11、F12、F13、F14、F15、F16、F17、F18、F19。
- 主要产物：Flyway 迁移、Mapper、doc/openapi.yaml。
- 表责任：无独占业务表；统一创建全部33表，业务实现由对应模块负责。
- 模块级前置：[M01](foundation.md)。具体任务还须满足下面列出的前置任务。

本模块负责DDL与契约，不把业务控制器或页面的完成度算入本模块。迁移完成不代表其他模块已实现。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；当前7项均已验证，但因M01模块门槛未完成，M02模块checkbox仍保持未完成。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M02-01

- [x] M02-01 建立 Flyway 迁移入口

  - 前置任务：[M01-06](foundation.md#m01-06)、[M01-07](foundation.md#m01-07)。
  - 本次范围：使用 Boot 管理的 Flyway core+mysql 依赖；按33表清单编排迁移；为循环引用先建表后添加FK；空库迁移与校验。
  - 交付产物：迁移目录、schema版本策略和数据库初始化IT。
  - 验收条件：空库可启动；二次启动不重复建表；checksum变更会失败；不通过修改已应用脚本消除错误。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0201IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M02-01](../evidence/M02-01.md)：Flyway入口、版本策略、空库/二次启动及checksum失败均在MySQL8.4.13真实通过。

### M02-02

- [x] M02-02 落地账号与共享表

  - 前置任务：[M02-01](database-contract.md#m02-01)。
  - 本次范围：创建 sys_user/sys_role/sys_user_role/sys_menu/sys_role_menu/auth_session/doctor_profile/file_object/file_binding/audit_log/user_notice/consent_record。
  - 交付产物：对应DDL、索引和枚举校验。
  - 验收条件：密码/手机号字段类型正确；关联唯一性生效；created/updated/version齐全；FK加入前后顺序可运行。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0202IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M02-02](../evidence/M02-02.md)：12表、字段类型、唯一键、CHECK及17个RESTRICT FK在MySQL8.4.13真实通过。

### M02-03

- [x] M02-03 落地宠物、排班和临床表

  - 前置任务：[M02-02](database-contract.md#m02-02)。
  - 本次范围：创建 pet/pet_health_log/doctor_slot/appointment/appointment_status_log/appointment_change_request/medical_record/medical_record_version。
  - 交付产物：8表迁移及索引。
  - 验收条件：一单一病历、版本唯一、每单一个OPEN guard的NULL唯一语义均在真实MySQL验证；终态不级联删除。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0203IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M02-03](../evidence/M02-03.md)：8表、9组唯一键、21个RESTRICT FK及关键NULL guard/病历历史约束在MySQL8.4.13真实通过。

### M02-04

- [x] M02-04 落地 AI、RAG和内容表

  - 前置任务：[M02-03](database-contract.md#m02-03)。
  - 本次范围：创建 ai_session/ai_message/ai_call_log/ai_reference/triage_rule/triage_event/knowledge_document/knowledge_version/knowledge_chunk/knowledge_index_job/health_guide/health_standard/guide_view_event。
  - 交付产物：13表迁移、剩余FK与CHECK/索引。
  - 验收条件：33表总数正确；一轮一个最终回复、索引任务guard唯一；外部向量不存为MySQL假向量列。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0204IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M02-04](../evidence/M02-04.md)：13表、V5等待型FK、33表总数、单轮唯一回复、索引guard及无MySQL假向量列在MySQL8.4.13真实通过。

### M02-05

- [x] M02-05 建立 Mapper 和固定演示夹具

  - 前置任务：[M02-04](database-contract.md#m02-04)。
  - 本次范围：加入 MP3.5.18 Boot3 starter及分页所需依赖；统一JSON TypeHandler、雪花ID和版本字段；创建纯虚构两用户两医生一管理员及测试业务数据。
  - 交付产物：Mapper映射、测试fixture和独立demo导入命令。
  - 验收条件：JSON/UTC/大整数往返一致；夹具可重建；演示密码从环境注入且不能在生产自动创建。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0205IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：`doc/evidence/M02-05.md`；MP3.5.18 Mapper/分页/乐观锁/ASSIGN_ID、统一JsonNode TypeHandler、显式本机demo CLI和固定夹具已实现。M0205IT在MySQL8.4.13通过4项，普通测试21项、M0204IT回归5项通过。

### M02-06

- [x] M02-06 冻结 OpenAPI 与类型导出

  - 前置任务：[M01-06](foundation.md#m01-06)。
  - 本次范围：将详细设计158动作整理为 OpenAPI3.0.3 YAML，含鉴权、version、幂等、错误DTO；生成TS类型供两客户端共享，确认覆盖登记表。
  - 交付产物：doc/openapi.yaml、generated类型、契约校验脚本。
  - 验收条件：解析/引用全通过；所有158方法路径恰好一次；ID为string；示例符合schema；不凭空生成未实现控制器。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：[M02-06验收记录](../evidence/M02-06.md)：158项方法+路径双源覆盖及引用/示例/字符串ID校验通过；两端类型一致，Springdoc只把现有`GET /hospital`标为IMPLEMENTED；后端23项、Web生产构建和小程序类型检查通过。

### M02-07

- [x] M02-07 核对数据库约束与跨表归属

  - 前置任务：[M02-05](database-contract.md#m02-05)、[M02-06](database-contract.md#m02-06)。
  - 本次范围：为 doctorId/userId 不同含义、pet/appointment/record一致性、activeVersion属于document、附件多态建立验证清单和SQL断言。
  - 交付产物：关系检查IT和33表字典映射。
  - 验收条件：构造跨用户、跨医生和跨文档非法数据，FK或服务校验明确拒绝；所有主要查询有对应索引。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0207IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M02-07验收记录](../evidence/M02-07.md)：真实MySQL8.4.13中5项关系/FK/33表索引断言通过，普通回归23项通过；字典与服务级关系守卫已落地，临时容器已清理。

## API责任清单

无独占后端业务端点；基础设施/契约/集成工作按上方任务验收。

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

