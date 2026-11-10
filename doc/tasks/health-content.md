# M09 健康指南与通用健康标准

更新：2026-11-02。状态：进行中；任务 4/6。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F08、F09。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`health_guide`、`health_standard`、`guide_view_event`。
- 模块级前置：[M04](shared-services.md)、[M06](doctors-scheduling.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M09-01

- [x] M09-01 实现健康指南后台录入与编辑

  - 前置任务：[M04-04](shared-services.md#m04-04)、[M03-07](identity-access.md#m03-07)。
  - 本次范围：实现 staff/health/guides 列表、详情、创建、PATCH；仅管理员编辑，医生只读；富文本白名单和来源字段。
  - 交付产物：指南后台CRUD，不含硬删除。
  - 验收条件：注入脚本被清洗；版本冲突409；医生编辑403；非法附件拒绝。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0901IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M09-01验收记录](../evidence/M09-01.md)：普通57/57、真实MySQL/S3联合回归27/27，清洗、权限、附件、乐观锁、幂等和审计边界通过。

### M09-02

- [x] M09-02 实现指南发布与下架

  - 前置任务：[M09-01](health-content.md#m09-01)。
  - 本次范围：publish/offline动作，version和原因，发布时校验正文/来源/封面READY。
  - 交付产物：发布门槛和审计。
  - 验收条件：草稿不在用户查询；下架立即不可读新正文；重复key不重发；指南不自动进RAG。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0902IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M09-02验收记录](../evidence/M09-02.md)：普通57/57、真实MySQL联合回归24/24，发布门槛、下架原因、状态循环、幂等与不自动入RAG通过。

### M09-03

- [ ] M09-03 实现用户指南浏览和去重统计

  - 前置任务：[M09-02](health-content.md#m09-02)。
  - 本次范围：实现 health/guides 列表详情、按上海日期记录guide_view_event；正文输出安全。
  - 交付产物：指南用户接口和去重事件。
  - 验收条件：同用户同文章同日多次仅一事件；不同时区边界正确；下架文章404。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0903IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 阶段证据：[M09-03阶段记录](../evidence/M09-03.md)：生产实现、普通57/57和OpenAPI158/138已通过；真实MySQL用例因测试固定全局Clock连续两次影响JWT认证而在路由前401，任务保持未勾选，后续改为专用业务日期时钟后恢复验收。

### M09-04

- [x] M09-04 实现健康标准医生录入与维护

  - 前置任务：[M06-01](doctors-scheduling.md#m06-01)、[M04-01](shared-services.md#m04-01)。
  - 本次范围：实现 staff/health/standards 列表详情创建修改；物种、年龄、指标、单位、区间、来源和条件。
  - 交付产物：标准专业字段验证。
  - 验收条件：上下界不成对/逆序400；未知年龄允许；仅医生写；编辑后回DRAFT且保留审计。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0904IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M09-04验收记录](../evidence/M09-04.md)：普通57/57、真实MySQL联合回归29/29，角色矩阵、医生归属、区间/未知年龄、回DRAFT、乐观锁、幂等与审计通过。

### M09-05

- [x] M09-05 实现标准发布与用户查询

  - 前置任务：[M09-04](health-content.md#m09-04)。
  - 本次范围：管理员publish/offline，用户按species/age/metric读取；文本标准不强制数值。
  - 交付产物：标准发布与公开查询。
  - 验收条件：不同物种不能误匹配；DRAFT不出现在用户结果；管理员改专业正文403；不将标准当自动诊断。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0905IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M09-05验收记录](../evidence/M09-05.md)：普通57/57、真实MySQL联合回归29/29，发布下架、物种/指标/年龄隔离、未知年龄、文本标准与角色矩阵通过。

### M09-06

- [ ] M09-06 完成健康内容端点契约验收

  - 前置任务：[M09-03](health-content.md#m09-03)、[M09-05](health-content.md#m09-05)。
  - 本次范围：补齐权限矩阵、来源显示和分页，生成含猫犬及未知年龄的测试资料。
  - 交付产物：HealthContentContractIT。
  - 验收条件：健康指南及标准全部已设计端点覆盖；返回脱敏、无待审核条目；浏览数可人工复算。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0906IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M09-06.md`。目前无实现证据。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/health/guides` | [M09-03](health-content.md#m09-03) |
| GET | `/health/guides/{guideId}` | [M09-03](health-content.md#m09-03) |
| GET | `/health/standards` | [M09-05](health-content.md#m09-05) |
| GET | `/staff/health/guides` | [M09-01](health-content.md#m09-01) |
| POST | `/staff/health/guides` | [M09-01](health-content.md#m09-01) |
| GET | `/staff/health/guides/{guideId}` | [M09-01](health-content.md#m09-01) |
| PATCH | `/staff/health/guides/{guideId}` | [M09-01](health-content.md#m09-01) |
| POST | `/staff/health/guides/{guideId}/publish` | [M09-02](health-content.md#m09-02) |
| POST | `/staff/health/guides/{guideId}/offline` | [M09-02](health-content.md#m09-02) |
| GET | `/staff/health/standards` | [M09-04](health-content.md#m09-04) |
| POST | `/staff/health/standards` | [M09-04](health-content.md#m09-04) |
| GET | `/staff/health/standards/{standardId}` | [M09-04](health-content.md#m09-04) |
| PATCH | `/staff/health/standards/{standardId}` | [M09-04](health-content.md#m09-04) |
| POST | `/staff/health/standards/{standardId}/publish` | [M09-05](health-content.md#m09-05) |
| POST | `/staff/health/standards/{standardId}/offline` | [M09-05](health-content.md#m09-05) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

