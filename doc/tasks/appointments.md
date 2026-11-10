# M07 预约状态机与独立改期

更新：2026-11-09。小任务已验收 10/10，模块级前置待满足。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F11、F12、F16、F17。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`appointment`、`appointment_status_log`、`appointment_change_request`。
- 模块级前置：[M05](pet-health.md)、[M06](doctors-scheduling.md)。具体任务还须满足下面列出的前置任务。

预约创建对可选 aiSessionId 的关联校验直接读取已迁移表，可用种子会话测试，不依赖 M12 的生成接口，避免预约与 AI 形成循环依赖。

## 最小任务清单

完成表示实现、对应验证和证据均已齐备。

### M07-01

- [x] M07-01 实现七状态领域规则

  - 前置任务：[M01-06](foundation.md#m01-06)。
  - 本次范围：建立状态枚举与动作授权矩阵、时限计算器、取消来源和终态保护；只写可单测领域逻辑。
  - 交付产物：AppointmentStateMachine与配置默认值。
  - 验收条件：参数化测试覆盖所有允许边和非法边；没有已到院；Doctor/ADMIN权限不同；Clock边界精确。
  - 验证方法：`mvn -f server/pom.xml -Dtest=AppointmentStateMachineTest test`。本项是纯规则任务，无数据库依赖；模块事务交付仍需M05/M06和MySQL实测。
  - 证据：[M07-01](../evidence/M07-01.md)：12项规则与边界测试通过，未声称创建/取消/改期事务已经实现。

### M07-02

- [x] M07-02 实现创建占位事务

  - 前置任务：[M07-01](appointments.md#m07-01)、[M05-03](pet-health.md#m05-03)、[M06-05](doctors-scheduling.md#m06-05)、[M04-01](shared-services.md#m04-01)、[M04-06](shared-services.md#m04-06)。
  - 本次范围：实现 /appointments POST；doctor→pet→slots锁顺序，幂等、未来/启用/归档/重叠验证；可关联同主人同宠物aiSession。
  - 交付产物：创建预约与初始日志、通知。
  - 验收条件：10线程抢容量1只有1单；同key重放不多占；无AI会话也能预约；错误关联拒绝且回滚占位。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0702IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-02验收记录](../evidence/M07-02.md)。普通58/58、真实MySQL M0702IT 5/5、七类联合34/34，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-03

- [x] M07-03 实现两端预约查询与轨迹

  - 前置任务：[M07-02](appointments.md#m07-02)。
  - 本次范围：实现用户列表详情、staff列表详情、status-logs；当前状态、allowedActions和必要脱敏字段。
  - 交付产物：预约读接口。
  - 验收条件：用户仅本人、医生仅本人、管理员全院；PENDING不开放全部健康数据；稳定分页。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0703IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-03验收记录](../evidence/M07-03.md)。普通58/58、真实MySQL M0703IT 4/4、四类联合19/19，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-04

- [x] M07-04 实现医生确认和拒绝

  - 前置任务：[M07-03](appointments.md#m07-03)。
  - 本次范围：confirm/reject动作，核验截止、version、医生身份；确认不再扣位，拒绝释放一次。
  - 交付产物：两个预约动作与审计通知。
  - 验收条件：重复同key不重复通知/释放；超时不能确认；并发确认与拒绝仅一个成功。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0704IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-04验收记录](../evidence/M07-04.md)。普通58/58、真实MySQL M0704IT 4/4、六类联合28/28，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-05

- [x] M07-05 实现双方取消和待确认超时

  - 前置任务：[M07-04](appointments.md#m07-04)。
  - 本次范围：用户与医院cancel、SYSTEM过期清理，原子释放名额；用户过开始时间不能自主取消。
  - 交付产物：取消服务与定时扫描。
  - 验收条件：相同状态服务处理手动与超时；扫描重复不减成负数；回滚保持原预约；取消后OPEN改期失效。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0705IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-05验收记录](../evidence/M07-05.md)。普通58/58、真实MySQL M0705IT 5/5、六类联合28/28，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-06

- [x] M07-06 实现开始诊疗和爽约

  - 前置任务：[M07-04](appointments.md#m07-04)、[M07-05](appointments.md#m07-05)。
  - 本次范围：start仅对应医生、窗口校验；no-show需人工且超宽限；终态/管理员禁止代操作。
  - 交付产物：start/no-show动作。
  - 验收条件：开始与取消并发仅一方成功；爽约不释放历史消耗；不会由定时任务自动确诊爽约。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0706IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-06验收记录](../evidence/M07-06.md)。普通58/58、真实MySQL M0706IT 4/4、五类联合22/22，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-07

- [x] M07-07 实现改期申请与历史

  - 前置任务：[M07-03](appointments.md#m07-03)、[M07-05](appointments.md#m07-05)。
  - 本次范围：用户/staff创建和读取change-requests；同医生不同未来slot、每单一个OPEN、有效期取最早值。
  - 交付产物：申请端点和独立状态记录。
  - 验收条件：未接受不动原slot和占位；并发申请只有一个OPEN；过期旧guard先清理再允许新申请。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0707IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-07验收记录](../evidence/M07-07.md)。普通58/58、真实MySQL M0707IT 4/4、五类联合22/22，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

### M07-08

- [x] M07-08 实现改期接受的原子迁移

  - 前置任务：[M07-07](appointments.md#m07-07)。
  - 本次范围：另一方accept，锁旧新slot按ID升序；核验baseVersion、有效期、新容量，迁移占位和快照；主状态不变。
  - 交付产物：改期accept事务。
  - 验收条件：目标满/关闭/超时原预约全不变；成功释放旧占新；提前时缩短确认截止；不能自提自批或管理员代批。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0708IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-08验收记录](../evidence/M07-08.md)。普通58/58、真实MySQL M0708IT 6/6、四类联合20/20，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M07-09

- [x] M07-09 实现改期拒绝、撤回和过期

  - 前置任务：[M07-08](appointments.md#m07-08)。
  - 本次范围：reject/withdraw与过期任务，业务方权限、version，清openGuard，写通知及日志。
  - 交付产物：改期结束路径。
  - 验收条件：拒绝保留原预约；仅提出者撤回；主状态变化使申请失效；重复处理无副作用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0709IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-09验收记录](../evidence/M07-09.md)。普通58/58、真实MySQL M0709IT 4/4、四类联合18/18，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M07-10

- [x] M07-10 完成预约并发与故障专项验收

  - 前置任务：[M07-02](appointments.md#m07-02)、[M07-04](appointments.md#m07-04)、[M07-05](appointments.md#m07-05)、[M07-06](appointments.md#m07-06)、[M07-08](appointments.md#m07-08)、[M07-09](appointments.md#m07-09)。
  - 本次范围：为创建/拒绝/取消/开始/改期注入失败和线程竞争；核对occupied_count、状态日志、通知全一致。
  - 交付产物：AppointmentConcurrencyIT及SQL不变量断言。
  - 验收条件：多轮竞争无超卖、重复扣减、死锁后部分提交；可重放失败；complete留给M08，不伪造空病历完成。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0710IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M07-10验收记录](../evidence/M07-10.md)。普通58/58、真实MySQL M0710IT 9/9、七类联合37/37，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/appointments` | [M07-03](appointments.md#m07-03) |
| POST | `/appointments` | [M07-02](appointments.md#m07-02) |
| GET | `/appointments/{appointmentId}` | [M07-03](appointments.md#m07-03) |
| POST | `/appointments/{appointmentId}/cancel` | [M07-05](appointments.md#m07-05) |
| POST | `/appointments/{appointmentId}/change-requests` | [M07-07](appointments.md#m07-07) |
| GET | `/appointments/{appointmentId}/change-requests` | [M07-07](appointments.md#m07-07) |
| POST | `/change-requests/{changeRequestId}/accept` | [M07-08](appointments.md#m07-08) |
| POST | `/change-requests/{changeRequestId}/reject` | [M07-09](appointments.md#m07-09) |
| POST | `/change-requests/{changeRequestId}/withdraw` | [M07-09](appointments.md#m07-09) |
| GET | `/staff/appointments` | [M07-03](appointments.md#m07-03) |
| GET | `/staff/appointments/{appointmentId}` | [M07-03](appointments.md#m07-03) |
| POST | `/staff/appointments/{appointmentId}/confirm` | [M07-04](appointments.md#m07-04) |
| POST | `/staff/appointments/{appointmentId}/reject` | [M07-04](appointments.md#m07-04) |
| POST | `/staff/appointments/{appointmentId}/cancel` | [M07-05](appointments.md#m07-05) |
| POST | `/staff/appointments/{appointmentId}/start` | [M07-06](appointments.md#m07-06) |
| POST | `/staff/appointments/{appointmentId}/no-show` | [M07-06](appointments.md#m07-06) |
| GET | `/staff/appointments/{appointmentId}/status-logs` | [M07-03](appointments.md#m07-03) |
| GET | `/staff/appointments/{appointmentId}/change-requests` | [M07-07](appointments.md#m07-07) |
| POST | `/staff/appointments/{appointmentId}/change-requests` | [M07-07](appointments.md#m07-07) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

