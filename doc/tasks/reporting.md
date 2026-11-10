# M13 运营报表与医生工作量

更新：2026-10-31。状态：实施中；任务 1/5。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F18。
- 主要产物：server/ 对应业务包及测试。
- 表责任：无独占业务表；读取或使用既有表。
- 模块级前置：[M08](clinical-records.md)、[M09](health-content.md)、[M12](ai-consultation.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；当前1项已完成。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M13-01

- [x] M13-01 实现统一报表时间口径

  - 前置任务：[M01-06](foundation.md#m01-06)。
  - 本次范围：from/to半开区间、上海展示、范围≤366天，created和scheduled批次分离；零分母返回null。
  - 交付产物：ReportRange/Metric DTO和查询基础。
  - 验收条件：跨日/跨月边界无重复；非法大范围拒绝；空数据不出NaN；口径与设计一致。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1301IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M13-01](../evidence/M13-01.md)：半开UTC查询、上海展示、366天上限、批次隔离和零分母null已通过42项普通测试、M1301IT 2项及20项真实MySQL定向回归。

### M13-02

- [ ] M13-02 实现概览、预约与医生统计

  - 前置任务：[M13-01](reporting.md#m13-01)、[M08-02](clinical-records.md#m08-02)、[M07-10](appointments.md#m07-10)。
  - 本次范围：overview/appointments/doctors、staff workload；首次确认耗时、已结束时段结果与当前待处理。
  - 交付产物：对应4个统计端点。
  - 验收条件：固定夹具人工可复算；医生不能切doctorId看他人；取消/爽约/未终结分母清楚。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1302IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M13-02.md`。目前无实现证据。

### M13-03

- [ ] M13-03 实现AI使用、转化与模型用量

  - 前置任务：[M13-01](reporting.md#m13-01)、[M12-07](ai-consultation.md#m12-07)。
  - 本次范围：ai/model-usage统计；按消息轮去重急症、满7天成熟会话显式关联转化、调用类型和模型分组。
  - 交付产物：AI与调用报表。
  - 验收条件：重复规则不重复计；未成熟会话不进转化分母；UNKNOWN Token保留NULL；不虚构准确率和收入。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1303IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M13-03.md`。目前无实现证据。

### M13-04

- [ ] M13-04 实现宠物和健康内容分布

  - 前置任务：[M13-01](reporting.md#m13-01)、[M09-03](health-content.md#m09-03)、[M05-02](pet-health.md#m05-02)。
  - 本次范围：pets/content统计，物种/年龄未知单列，规范症状代码按轮去重，指南每日去重。
  - 交付产物：两个统计端点。
  - 验收条件：未知生日无负年龄；AI猜测疾病不当确诊；与浏览事件表人工核对一致。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1304IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M13-04.md`。目前无实现证据。

### M13-05

- [ ] M13-05 完成报表契约与数据隔离验收

  - 前置任务：[M13-02](reporting.md#m13-02)、[M13-03](reporting.md#m13-03)、[M13-04](reporting.md#m13-04)。
  - 本次范围：校验全院管理员权限、医生只本人、索引查询计划与响应时间基线。
  - 交付产物：ReportContractIT及EXPLAIN证据。
  - 验收条件：8条设计报表动作全部覆盖；无越权明细；固定数据集响应基线有真实测量值，超限需说明优化。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1305IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M13-05.md`。目前无实现证据。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/admin/reports/overview` | [M13-02](reporting.md#m13-02) |
| GET | `/admin/reports/appointments` | [M13-02](reporting.md#m13-02) |
| GET | `/admin/reports/ai` | [M13-03](reporting.md#m13-03) |
| GET | `/admin/reports/doctors` | [M13-02](reporting.md#m13-02) |
| GET | `/admin/reports/pets` | [M13-04](reporting.md#m13-04) |
| GET | `/admin/reports/content` | [M13-04](reporting.md#m13-04) |
| GET | `/admin/reports/model-usage` | [M13-03](reporting.md#m13-03) |
| GET | `/staff/reports/workload` | [M13-02](reporting.md#m13-02) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

