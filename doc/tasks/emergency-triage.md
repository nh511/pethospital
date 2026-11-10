# M10 急症规则与风险提示

更新：2026-11-03。状态：小任务已完成、模块门槛待满足；任务 6/6。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F06。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`triage_rule`；本模块输出分诊DTO，`triage_event` 的问诊事件持久化由 M12 统一负责。
- 模块级前置：[M04](shared-services.md)、[M06](doctors-scheduling.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M10-01

- [x] M10-01 定义可配置规则Schema与纯规则评估器

  - 前置任务：[M01-06](foundation.md#m01-06)。
  - 本次范围：固定predicate允许字段/运算、物种和证据结构；含明确发生、否定、假设、历史已结束与UNKNOWN；禁止任意脚本。
  - 交付产物：TriageEvaluator与JSON Schema。
  - 验收条件：肯定命中，否定/假设不直接当当前事件；歧义标UNKNOWN而非安全；恶意脚本/未知运算拒绝。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1001IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-01验收记录](../evidence/M10-01.md)：普通35/35、真实MySQL8.4.13的M1001IT 3/3、三类定向回归13/13，OpenAPI与双端类型检查通过，22份XML累计109项全绿且敏感扫描0匹配。

### M10-02

- [x] M10-02 实现规则草稿与新修订

  - 前置任务：[M10-01](emergency-triage.md#m10-01)、[M04-01](shared-services.md#m04-01)。
  - 本次范围：实现triage-rules列表、详情、创建、PATCH；已APPROVED不原地改，创建同code新revision。
  - 交付产物：规则管理端点。
  - 验收条件：同code/revision唯一；无来源不能提交；管理员可编辑草稿，普通医生只读。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1002IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-02验收记录](../evidence/M10-02.md)：普通57/57、真实MySQL 8.4.13的M1002IT 5/5、五类联合回归22/22，OpenAPI与双端类型检查通过，排除已单独留证暂停的M0903失败XML后36份报告累计178项全绿且敏感扫描0匹配。

### M10-03

- [x] M10-03 实现提交审核与医生审核

  - 前置任务：[M10-02](emergency-triage.md#m10-02)、[M06-01](doctors-scheduling.md#m06-01)。
  - 本次范围：submit-review/review，启用医生身份，APPROVE/REJECT和备注；审核正文冻结。
  - 交付产物：规则审核流程。
  - 验收条件：非医生不能批准；IN_REVIEW外状态409；驳回可改再提交；批准后旧payload不可改。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1003IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-03验收记录](../evidence/M10-03.md)：普通57/57、真实MySQL 8.4.13的M1003IT 5/5、六类联合回归27/27，OpenAPI与双端类型检查通过，37份有效XML累计183项全绿且敏感扫描0匹配。

### M10-04

- [x] M10-04 实现启用、停用与规则预览

  - 前置任务：[M10-03](emergency-triage.md#m10-03)。
  - 本次范围：enable/disable/test；新revision启用同事务停旧版；预览不写真实问诊/报表。
  - 交付产物：规则生效指针与测试端点。
  - 验收条件：同code只有一个enabled；未批准不能启用；预览与运行评估结果一致；回滚不会双版本生效。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1004IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-04验收记录](../evidence/M10-04.md)：普通58/58、真实MySQL 8.4.13的M1004IT 5/5、七类联合回归32/32，OpenAPI158/157、双端类型检查通过，38份有效XML累计189项全绿且敏感扫描0匹配。

### M10-05

- [x] M10-05 提供问诊前置分诊服务

  - 前置任务：[M10-04](emergency-triage.md#m10-04)。
  - 本次范围：接收userMessage、问卷和宠物物种，规则命中返回固定EMERGENCY建议、证据与医院联系配置；返回统一风险DTO供M12落库。
  - 交付产物：TriageService和规则回复DTO。
  - 验收条件：外部DeepSeek/Qdrant都断开仍提示；固定免责声明；明确急症不等待填完问卷；无命中不输出已排除急症。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1005IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-05验收记录](../evidence/M10-05.md)：普通58/58、真实MySQL 8.4.13的M1005IT 5/5、八类联合回归37/37；外部服务断开急症仍固定返回且零AI/事件写入，OpenAPI与双端类型通过，39份有效XML累计194项全绿且敏感扫描0匹配。

### M10-06

- [x] M10-06 建立分诊回归样例集

  - 前置任务：[M10-05](emergency-triage.md#m10-05)。
  - 本次范围：至少覆盖每条演示规则的肯定/否定/假设/历史/未知及物种错配，演示规则标非临床验证。
  - 交付产物：版本化fixture与规则回归IT。
  - 验收条件：样例逐条给预期及证据；错误用例能定位ruleVersion；模型不能覆盖规则风险；真实资料需医生审核才能启用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1006IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M10-06验收记录](../evidence/M10-06.md)：版本化演示fixture覆盖两条规则的12类逐例矩阵，普通58/58、真实MySQL 8.4.13的M1006IT 15/15、九类联合回归52/52；OpenAPI与双端类型通过，40份有效XML累计209项全绿且敏感扫描0匹配。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/staff/triage-rules` | [M10-02](emergency-triage.md#m10-02) |
| POST | `/staff/triage-rules` | [M10-02](emergency-triage.md#m10-02) |
| GET | `/staff/triage-rules/{ruleId}` | [M10-02](emergency-triage.md#m10-02) |
| PATCH | `/staff/triage-rules/{ruleId}` | [M10-02](emergency-triage.md#m10-02) |
| POST | `/staff/triage-rules/{ruleId}/submit-review` | [M10-03](emergency-triage.md#m10-03) |
| POST | `/staff/triage-rules/{ruleId}/review` | [M10-03](emergency-triage.md#m10-03) |
| POST | `/staff/triage-rules/{ruleId}/enable` | [M10-04](emergency-triage.md#m10-04) |
| POST | `/staff/triage-rules/{ruleId}/disable` | [M10-04](emergency-triage.md#m10-04) |
| POST | `/staff/triage-rules/test` | [M10-04](emergency-triage.md#m10-04) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

