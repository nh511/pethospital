# M05 宠物资料与日常健康日志

更新：2026-11-09。小任务已验收 7/7，模块级前置待满足。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F02、F03。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`pet`、`pet_health_log`。
- 模块级前置：[M04](shared-services.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度。

### M05-01

- [x] M05-01 实现宠物创建及列表

  - 前置任务：[M04-01](shared-services.md#m04-01)。
  - 本次范围：实现 /pets POST/GET，白名单字段、物种/日期校验、当前用户归属和稳定分页。
  - 交付产物：PetController/Service/Mapper。
  - 验收条件：两个用户互不可见；生日未来400；非法owner字段被拒；大ID字符串不失真。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0501IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-01验收记录](../evidence/M05-01.md)：普通58/58、真实MySQL 8.4.13的M0501IT 5/5、五类联合回归24/24，Mini Vitest 3/3、OpenAPI与双端类型通过，40份有效XML累计209项全绿且敏感扫描0匹配。

### M05-02

- [x] M05-02 实现宠物详情与版本编辑

  - 前置任务：[M05-01](pet-health.md#m05-01)。
  - 本次范围：实现 /pets/{petId} GET/PATCH；自述与临床摘要分栏，体重/病史带来源时间，摘要从已提交病历读取。
  - 交付产物：详情DTO与版本更新。
  - 验收条件：过期version409；只显示已有已提交病历摘要，不读取草稿；用户不能改医生诊断。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0502IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-02验收记录](../evidence/M05-02.md)：普通58/58、真实MySQL 8.4.13的M0502IT 5/5、六类联合回归29/29，Mini Vitest 3/3、OpenAPI与双端类型通过，41份有效XML累计214项全绿且敏感扫描0匹配。

### M05-03

- [x] M05-03 实现宠物归档

  - 前置任务：[M05-02](pet-health.md#m05-02)。
  - 本次范围：archive动作锁pet；检查未终结预约与QUEUED/PROCESSING轮；归档后禁止新预约/问诊，历史仍可读。
  - 交付产物：归档动作及共享ACTIVE校验器。
  - 验收条件：seed预约/生成中均409；无依赖可归档；不物理删除历史；与并发创建共用pet锁。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0503IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-03验收记录](../evidence/M05-03.md)：普通58/58、真实MySQL 8.4.13的M0503IT 5/5、七类联合回归34/34，Mini Vitest 3/3、OpenAPI与双端类型通过，42份有效XML累计219项全绿且敏感扫描0匹配。

### M05-04

- [x] M05-04 实现健康日志新增与查询

  - 前置任务：[M05-01](pet-health.md#m05-01)、[M04-04](shared-services.md#m04-04)。
  - 本次范围：实现日志列表/详情/创建，observedAt、体重、自述标签；附图通过 FileBindingService绑定。
  - 交付产物：日志查询与创建。
  - 验收条件：非主人拒绝；空记录400；体重非正400；附件越权失败且整笔回滚。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0504IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-04验收记录](../evidence/M05-04.md)：普通58/58、真实MySQL 8.4.13的M0504IT 5/5、八类联合回归39/39，Mini Vitest 3/3、OpenAPI与双端类型通过，43份有效XML累计224项全绿且敏感扫描0匹配。

### M05-05

- [x] M05-05 实现健康日志修订

  - 前置任务：[M05-04](pet-health.md#m05-04)。
  - 本次范围：实现日志PATCH，version和改前改后审计，附件更新保留被临床版本引用的文件。
  - 交付产物：日志版本更新。
  - 验收条件：并发仅一次成功；旧内容可由审计追溯；医生不能改主人自述；解绑不删历史引用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0505IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-05验收记录](../evidence/M05-05.md)：普通58/58、真实MySQL 8.4.13的M0505IT 5/5、九类联合回归44/44，Mini Vitest 3/3、OpenAPI与双端类型通过，44份有效XML累计229项全绿且敏感扫描0匹配。

### M05-06

- [x] M05-06 实现管理员基础纠错和日志查询

  - 前置任务：[M05-02](pet-health.md#m05-02)、[M05-05](pet-health.md#m05-05)。
  - 本次范围：实现 /admin/pets 列表/详情/PATCH、health-logs只读；纠错权限、reason与审计；不变更owner/诊断。
  - 交付产物：管理员宠物端点。
  - 验收条件：无纠错权限403；缺reason400；尝试改owner拒绝；全文日志仅审计权限可见。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0506IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M05-06验收记录](../evidence/M05-06.md)：普通58/58、真实MySQL 8.4.13的M0506IT 5/5、九类联合回归44/44，Mini Vitest 3/3、OpenAPI与双端类型通过，46份有效XML累计239项全绿且敏感扫描0匹配。

### M05-07

- [x] M05-07 实现健康时间线聚合

  - 前置任务：[M05-02](pet-health.md#m05-02)、[M05-04](pet-health.md#m05-04)。
  - 本次范围：实现 /pets/{petId}/timeline，聚合LOG/MEDICAL/AI/APPOINTMENT各表已存在摘要；外模块未实现时用固定夹具验证。
  - 交付产物：按业务时间+id稳定游标的聚合查询。
  - 验收条件：同一时刻事件排序稳定，翻页不漏不重；无草稿诊断泄漏；只查主人宠物。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0507IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M05-07验收记录](../evidence/M05-07.md)。普通58/58、真实MySQL M0507IT 4/4、九类联合44/44，OpenAPI、双端类型、小程序Vitest 3/3和敏感扫描通过。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/pets` | [M05-01](pet-health.md#m05-01) |
| POST | `/pets` | [M05-01](pet-health.md#m05-01) |
| GET | `/pets/{petId}` | [M05-02](pet-health.md#m05-02) |
| PATCH | `/pets/{petId}` | [M05-02](pet-health.md#m05-02) |
| POST | `/pets/{petId}/archive` | [M05-03](pet-health.md#m05-03) |
| GET | `/pets/{petId}/health-logs` | [M05-04](pet-health.md#m05-04) |
| POST | `/pets/{petId}/health-logs` | [M05-04](pet-health.md#m05-04) |
| GET | `/pets/{petId}/health-logs/{logId}` | [M05-04](pet-health.md#m05-04) |
| PATCH | `/pets/{petId}/health-logs/{logId}` | [M05-05](pet-health.md#m05-05) |
| GET | `/pets/{petId}/timeline` | [M05-07](pet-health.md#m05-07) |
| GET | `/admin/pets` | [M05-06](pet-health.md#m05-06) |
| GET | `/admin/pets/{petId}` | [M05-06](pet-health.md#m05-06) |
| PATCH | `/admin/pets/{petId}` | [M05-06](pet-health.md#m05-06) |
| GET | `/admin/pets/{petId}/health-logs` | [M05-06](pet-health.md#m05-06) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

