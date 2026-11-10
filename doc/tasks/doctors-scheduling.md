# M06 医生资料与可预约时段

更新：2026-11-09。小任务已验收 7/7，模块级前置待满足。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F10、F13。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`doctor_profile`、`doctor_slot`。
- 模块级前置：[M04](shared-services.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M06-01

- [x] M06-01 实现医生资料管理

  - 前置任务：[M03-07](identity-access.md#m03-07)、[M04-04](shared-services.md#m04-04)。
  - 本次范围：实现 /admin/doctors GET/POST/PATCH、/staff/doctor-profile GET/PATCH；用户ID和医生ID严格区分，资质附件授权。
  - 交付产物：医生资料后端。
  - 验收条件：非DOCTOR账号不能建profile；医生只能改本人介绍；管理员维护资质且写审计。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0601IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-01验收记录](../evidence/M06-01.md)：普通57/57、真实MySQL/S3联合回归27/27、OpenAPI与双端类型检查通过。

### M06-02

- [x] M06-02 实现公开医生列表详情

  - 前置任务：[M06-01](doctors-scheduling.md#m06-01)。
  - 本次范围：实现 /doctors GET与详情，仅ACTIVE且账号/角色有效，专长查询，脱敏字段白名单。
  - 交付产物：用户医生查询。
  - 验收条件：停用账号/资料不可选；无资质文件与手机号泄漏；未知doctorId404。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0602IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-02验收记录](../evidence/M06-02.md)：普通57/57、真实MySQL联合回归20/20、公开字段与MINI权限边界通过。

### M06-03

- [x] M06-03 实现排班创建与查询

  - 前置任务：[M06-01](doctors-scheduling.md#m06-01)。
  - 本次范围：实现 /staff/slots GET/POST；锁doctor_profile做重叠检测，UTC和slotDate上海日期一致。
  - 交付产物：SlotService与时段创建。
  - 验收条件：相邻时段允许；重叠拒绝；并发建立重叠仅一单成功；容量正数；医生不能指定他人ID。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0603IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-03验收记录](../evidence/M06-03.md)：普通57/57、真实MySQL联合回归25/25、并发重叠仅一笔成功。

### M06-04

- [x] M06-04 实现容量调整和开关时段

  - 前置任务：[M06-03](doctors-scheduling.md#m06-03)。
  - 本次范围：实现 slot PATCH/close/open，仅capacity可改，容量不少于占用，时间/医生不可原地改。
  - 交付产物：时段动作。
  - 验收条件：已有预约的关闭不静默取消；容量低于occupiedCount409；过去时段不能重开；全部审计。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0604IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-04验收记录](../evidence/M06-04.md)：普通57/57、真实MySQL联合回归25/25、幂等审计与预约/占用保留边界通过。

### M06-05

- [x] M06-05 实现用户可预约时段查询

  - 前置任务：[M06-02](doctors-scheduling.md#m06-02)、[M06-04](doctors-scheduling.md#m06-04)。
  - 本次范围：实现 /doctors/{doctorId}/slots，14天窗口、未来OPEN和正剩余名额；availability来自真实占用。
  - 交付产物：用户时段接口。
  - 验收条件：跨日边界正确；过去/关闭/满额不提供提交入口；动态余量不被客户端值控制。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0605IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-05验收记录](../evidence/M06-05.md)：普通57/57、真实MySQL联合回归30/30、跨日/过滤/动态余量与公开字段边界通过。

### M06-06

- [x] M06-06 实现医生启停与前置检查

  - 前置任务：[M06-01](doctors-scheduling.md#m06-01)、[M06-04](doctors-scheduling.md#m06-04)。
  - 本次范围：实现 /admin/doctors/{doctorId}/disable/enable，统一锁顺序；存在未终结预约拒绝最终停用，列出待处理数量。
  - 交付产物：医生生命周期动作。
  - 验收条件：用seed有效预约验证409；关新预约入口可先操作slots；处理后停用成功；旧会话无新临床写权限。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0606IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M06-06验收记录](../evidence/M06-06.md)：普通57/57、真实MySQL联合回归35/35，预约前置保护、幂等/version、启用门槛及旧会话临床权限收缩通过。

### M06-07

- [x] M06-07 实现医生工作台与宠物查询

  - 前置任务：[M06-01](doctors-scheduling.md#m06-01)、[M05-04](pet-health.md#m05-04)、[M03-10](identity-access.md#m03-10)。
  - 本次范围：实现 /staff/dashboard、/staff/pets、详情、health-logs查询；按确认状态及30天范围，不扩到所有用户。
  - 交付产物：本人工作台与范围列表。
  - 验收条件：fixture待确认只能见必要信息；确认后见对应pet；跨医生拒绝；满30天边界用Clock验证。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0607IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M06-07验收记录](../evidence/M06-07.md)。普通58/58、真实MySQL M0607IT 5/5、六类联合29/29，OpenAPI、双端类型、小程序Vitest3/3和敏感扫描通过。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/doctors` | [M06-02](doctors-scheduling.md#m06-02) |
| GET | `/doctors/{doctorId}` | [M06-02](doctors-scheduling.md#m06-02) |
| GET | `/doctors/{doctorId}/slots` | [M06-05](doctors-scheduling.md#m06-05) |
| GET | `/staff/dashboard` | [M06-07](doctors-scheduling.md#m06-07) |
| GET | `/staff/doctor-profile` | [M06-01](doctors-scheduling.md#m06-01) |
| PATCH | `/staff/doctor-profile` | [M06-01](doctors-scheduling.md#m06-01) |
| GET | `/staff/slots` | [M06-03](doctors-scheduling.md#m06-03) |
| POST | `/staff/slots` | [M06-03](doctors-scheduling.md#m06-03) |
| PATCH | `/staff/slots/{slotId}` | [M06-04](doctors-scheduling.md#m06-04) |
| POST | `/staff/slots/{slotId}/close` | [M06-04](doctors-scheduling.md#m06-04) |
| POST | `/staff/slots/{slotId}/open` | [M06-04](doctors-scheduling.md#m06-04) |
| GET | `/staff/pets` | [M06-07](doctors-scheduling.md#m06-07) |
| GET | `/staff/pets/{petId}` | [M06-07](doctors-scheduling.md#m06-07) |
| GET | `/staff/pets/{petId}/health-logs` | [M06-07](doctors-scheduling.md#m06-07) |
| GET | `/admin/doctors` | [M06-01](doctors-scheduling.md#m06-01) |
| POST | `/admin/doctors` | [M06-01](doctors-scheduling.md#m06-01) |
| PATCH | `/admin/doctors/{doctorId}` | [M06-01](doctors-scheduling.md#m06-01) |
| POST | `/admin/doctors/{doctorId}/disable` | [M06-06](doctors-scheduling.md#m06-06) |
| POST | `/admin/doctors/{doctorId}/enable` | [M06-06](doctors-scheduling.md#m06-06) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

