# M08 临床病历与版本追溯

更新：2026-11-09。任务 5/5，模块级前置与客户端联调仍待验收。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F04、F13、F16、F17。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`medical_record`、`medical_record_version`。
- 模块级前置：[M07](appointments.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度。

### M08-01

- [x] M08-01 实现病历草稿读取与保存

  - 前置任务：[M07-06](appointments.md#m07-06)、[M04-04](shared-services.md#m04-04)。
  - 本次范围：实现 medical-draft GET/PUT；一单一记录，诊疗中/本人医生限制，固定MedicalRecordInput，附件草稿绑定。
  - 交付产物：草稿服务和DTO。
  - 验收条件：主人的查询不见草稿；跨医生写拒绝；旧recordVersion409；无草稿可创建且不虚增正式revision。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0801IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M08-01验收记录](../evidence/M08-01.md)。普通58/58、真实MySQL M0801IT 4/4、四类联合17/17，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M08-02

- [x] M08-02 实现提交病历并完成预约

  - 前置任务：[M08-01](clinical-records.md#m08-01)、[M07-10](appointments.md#m07-10)。
  - 本次范围：实现 /staff/appointments/{appointmentId}/complete；病历初版、附件版本、预约COMPLETED、日志/审计/通知同事务。
  - 交付产物：唯一正式完成入口。
  - 验收条件：缺检查/诊断等必填拒绝；故障注入后仍诊疗中且无半份正式记录；重复key只一个revision1。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0802IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M08-02验收记录](../evidence/M08-02.md)。普通58/58、真实MySQL M0802IT 4/4、五类联合26/26，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M08-03

- [x] M08-03 实现病历修订

  - 前置任务：[M08-02](clinical-records.md#m08-02)。
  - 本次范围：revisions POST，本人署名医生、reason和version；前后快照不可变，不把AI输出自动写诊断。
  - 交付产物：修订服务与不可变版本。
  - 验收条件：并发修订仅一个成功；缺原因400；其他医生/管理员拒绝；旧附件仍可下载。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0803IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M08-03验收记录](../evidence/M08-03.md)。普通58/58、真实MySQL及SeaweedFS M0803IT 3/3、五类联合21/21，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M08-04

- [x] M08-04 实现用户及医生病历查询

  - 前置任务：[M08-02](clinical-records.md#m08-02)、[M08-03](clinical-records.md#m08-03)、[M03-10](identity-access.md#m03-10)。
  - 本次范围：实现宠物病历列表、record详情/versions、staff宠物medical-records；30天范围外只允许原医生本人病历版本。
  - 交付产物：正式病历查询DTO。
  - 验收条件：用户只看自己宠物已提交内容；不暴露草稿；管理员需审计权限；时间边界可测。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0804IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M08-04验收记录](../evidence/M08-04.md)。普通58/58、真实MySQL M0804IT 3/3、五类联合21/21，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M08-05

- [x] M08-05 验证病历历史、附件与授权一致

  - 前置任务：[M08-04](clinical-records.md#m08-04)、[M04-05](shared-services.md#m04-05)。
  - 本次范围：针对修订、附件解绑、主人归档、医生停用和重放组合验证，更新演示数据。
  - 交付产物：MedicalHistoryIT及示例病历。
  - 验收条件：旧版本内容不变；归档宠物历史仍在；停用医生无法新写；角色越权全部被拒。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0805IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M08-05验收记录](../evidence/M08-05.md)。普通58/58、M0205IT 4/4、真实MySQL与SeaweedFS M0805IT 1/1、五类联合20/20，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/pets/{petId}/medical-records` | [M08-04](clinical-records.md#m08-04) |
| GET | `/medical-records/{recordId}` | [M08-04](clinical-records.md#m08-04) |
| GET | `/medical-records/{recordId}/versions` | [M08-04](clinical-records.md#m08-04) |
| POST | `/staff/appointments/{appointmentId}/complete` | [M08-02](clinical-records.md#m08-02) |
| GET | `/staff/appointments/{appointmentId}/medical-draft` | [M08-01](clinical-records.md#m08-01) |
| PUT | `/staff/appointments/{appointmentId}/medical-draft` | [M08-01](clinical-records.md#m08-01) |
| POST | `/staff/medical-records/{recordId}/revisions` | [M08-03](clinical-records.md#m08-03) |
| GET | `/staff/pets/{petId}/medical-records` | [M08-04](clinical-records.md#m08-04) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

