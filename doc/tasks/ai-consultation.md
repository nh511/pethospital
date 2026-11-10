# M12 DeepSeek 多轮问诊与协同

更新：2026-11-01。状态：进行中；任务 1/9。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F05、F06、F13、F19。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`ai_session`、`ai_message`、`ai_call_log`、`ai_reference`、`triage_event`。
- 模块级前置：[M05](pet-health.md)、[M10](emergency-triage.md)、[M11](knowledge-rag.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M12-01

- [ ] M12-01 实现会话生命周期与列表

  - 前置任务：[M05-03](pet-health.md#m05-03)、[M03-09](identity-access.md#m03-09)。
  - 本次范围：实现ai/sessions创建/查询/关闭，绑定pet和owner；归档检查、无处理中轮才可关闭。
  - 交付产物：SessionService与端点。
  - 验收条件：不同用户/宠物隔离；关会话后拒绝新消息；传入其他pet拒绝；snapshot标注来源。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1201IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-01.md`。目前无实现证据。

### M12-02

- [ ] M12-02 实现消息持久化和轮询接口

  - 前置任务：[M12-01](ai-consultation.md#m12-01)、[M04-01](shared-services.md#m04-01)。
  - 本次范围：实现messages POST/GET及message详情；clientMessageId+hash幂等、会话行锁分配seq、QUEUED落库；每会话只一个未结束轮。
  - 交付产物：消息接口及持久任务入口。
  - 验收条件：重复发送不重复消息；异内容同key409；重开页面能恢复；轮询不触发外部调用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1202IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-02.md`。目前无实现证据。

### M12-03

- [x] M12-03 实现DeepSeek HTTP适配器

  - 前置任务：[M01-06](foundation.md#m01-06)、[M03-09](identity-access.md#m03-09)。
  - 本次范围：RestClient调用/chat/completions，模型deepseek-flash、thinking disabled、JSON输出；显式超时/限流/用量采集和输入去标识。
  - 交付产物：DeepSeekAdapter与HTTP stub IT。
  - 验收条件：stub覆盖合法JSON、坏JSON、429、5xx、超时；密钥不出前端/日志；不自动重发可能收费调用；usage未知为NULL。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1203IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M12-03](../evidence/M12-03.md)：RestClient适配、出站去标识、显式超时和无自动重试已通过57项普通测试及7项HTTP stub集成验证。

### M12-04

- [ ] M12-04 实现上下文与滚动摘要

  - 前置任务：[M12-02](ai-consultation.md#m12-02)、[M12-03](ai-consultation.md#m12-03)。
  - 本次范围：必要宠物临床来源快照、summaryThroughSeq、近期轮次和检索块，Token预算与确定性裁剪；长用户原文构建有记录的短检索query，不修改原始消息或急症输入；摘要使用独立调用日志。
  - 交付产物：ContextBuilder、摘要任务和prompt版本。
  - 验收条件：摘要失败不漏边界或重要过敏/用药事实；不重复含摘要原轮；不保存隐藏推理；身份数据不外发。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1204IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-04.md`。目前无实现证据。

### M12-05

- [ ] M12-05 接入规则优先问诊worker

  - 前置任务：[M12-02](ai-consultation.md#m12-02)、[M12-04](ai-consultation.md#m12-04)、[M10-05](emergency-triage.md#m10-05)、[M11-08](knowledge-rag.md#m11-08)。
  - 本次范围：领取token/lease→再次校验授权→TriageService；高危直接固定回复跳过RAG/LLM；否则检索并生成。
  - 交付产物：问诊worker编排。
  - 验收条件：高危时mock证明LLM和检索调用数0；低危才走RAG；知识故障/无命中不能编造依据；撤回后未发任务停止。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1205IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-05.md`。目前无实现证据。

### M12-06

- [ ] M12-06 实现输出校验、引用快照与最终落库

  - 前置任务：[M12-05](ai-consultation.md#m12-05)。
  - 本次范围：JSON Schema校验、风险最高优先、引用只从真实候选选；同事务保存唯一回复/引用/triage_event/用户完成状态，固定免责声明。
  - 交付产物：AnswerValidator、完成事务和引用端点。
  - 验收条件：伪造引用被拒；模型不能降低规则风险；迟到worker token不符不能写；完整引用保存原chunk快照。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1206IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-06.md`。目前无实现证据。

### M12-07

- [ ] M12-07 实现失败恢复和显式重试

  - 前置任务：[M12-06](ai-consultation.md#m12-06)。
  - 本次范围：处理FAILED/FAILED_UNKNOWN、lease过期与generationNo fencing，retry endpoint；成功轮不得覆盖。
  - 交付产物：恢复扫描与retry动作。
  - 验收条件：崩溃点注入不重复插消息；外呼结果未知禁止自动重发；用户显式重试新代；旧结果不能覆盖新回复。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1207IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-07.md`。目前无实现证据。

### M12-08

- [ ] M12-08 实现医生协同和后台审计读取

  - 前置任务：[M12-06](ai-consultation.md#m12-06)、[M07-04](appointments.md#m07-04)、[M04-07](shared-services.md#m04-07)。
  - 本次范围：实现staff预约ai-session、admin/ai/sessions列表详情；只开放关联会话，单独审计权限，敏感上下文额外控制。
  - 交付产物：协同读取与访问审计。
  - 验收条件：未确认、跨医生、非关联会话拒绝；普通ADMIN无问诊审计权限拒绝；历史引用下架后仍可读快照。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1208IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-08.md`。目前无实现证据。

### M12-09

- [ ] M12-09 完成真实DeepSeek问诊验收

  - 前置任务：[M12-07](ai-consultation.md#m12-07)、[M12-08](ai-consultation.md#m12-08)。
  - 本次范围：用虚构宠物资料和真实Key跑多轮、检索引用、急症跳过；核对供应商返回requestId/usage，不把生成内容当临床结论。
  - 交付产物：doc/evidence/deepseek-live.md和受控live测试。
  - 验收条件：真实至少两轮上下文连续；每轮含免责声明；记录费用/用量是否可得；无Key标阻塞，stub通过不替代真实验收。
  - 验证方法：受控运行 `.\server\mvnw.cmd -f server/pom.xml -Plive "-Dit.test=M1209IT" verify`；必须先配置真实凭据，记录供应商回执与脱敏证据；不得在普通CI自动耗费配额。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M12-09.md`。目前无实现证据。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/ai/sessions` | [M12-01](ai-consultation.md#m12-01) |
| POST | `/ai/sessions` | [M12-01](ai-consultation.md#m12-01) |
| GET | `/ai/sessions/{sessionId}` | [M12-01](ai-consultation.md#m12-01) |
| POST | `/ai/sessions/{sessionId}/close` | [M12-01](ai-consultation.md#m12-01) |
| POST | `/ai/sessions/{sessionId}/messages` | [M12-02](ai-consultation.md#m12-02) |
| GET | `/ai/sessions/{sessionId}/messages` | [M12-02](ai-consultation.md#m12-02) |
| GET | `/ai/messages/{messageId}` | [M12-02](ai-consultation.md#m12-02) |
| POST | `/ai/messages/{messageId}/retry` | [M12-07](ai-consultation.md#m12-07) |
| GET | `/ai/messages/{messageId}/references` | [M12-06](ai-consultation.md#m12-06) |
| GET | `/staff/appointments/{appointmentId}/ai-session` | [M12-08](ai-consultation.md#m12-08) |
| GET | `/admin/ai/sessions` | [M12-08](ai-consultation.md#m12-08) |
| GET | `/admin/ai/sessions/{sessionId}` | [M12-08](ai-consultation.md#m12-08) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

