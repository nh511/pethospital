# M03 账号认证与角色权限

更新：2026-10-30。状态：进行中；任务 8/10。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F01、F14、F19。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`sys_user`、`sys_role`、`sys_user_role`、`sys_menu`、`sys_role_menu`、`auth_session`、`consent_record`。
- 模块级前置：[M02](database-contract.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M03-01

- [x] M03-01 实现用户注册和密码登录

  - 前置任务：[M02-05](database-contract.md#m02-05)。
  - 本次范围：实现 USER注册、MINI/WEB密码登录、BCrypt校验及 TokenBundle；禁止注册传入角色；后台需DOCTOR/ADMIN。
  - 交付产物：注册/登录接口和密码校验服务。
  - 验收条件：合法账号成功；密码错、停用、用户登录WEB均失败；不会返回hash；登录事件写 AUTH_LOGIN。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0301IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-01验收记录](../evidence/M03-01.md)：普通23/23、真实MySQL8.4.13的M0301IT 6/6，OpenAPI与双端类型检查通过。

### M03-02

- [ ] M03-02 实现微信 code 换身份

  - 前置任务：[M03-01](identity-access.md#m03-01)。
  - 本次范围：WechatIdentityAdapter 用服务端 AppSecret 换OpenID，保存HMAC查找值和加密值；stub与真实模式分别配置。
  - 交付产物：微信登录接口、绑定事务和真机验收说明。
  - 验收条件：伪造OpenID请求无效；同OpenID只建一用户；stub用例通过；真实code需有凭据验收，缺失不勾选。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0302IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M03-02.md`。目前无实现证据。

### M03-03

- [x] M03-03 实现 JWT 与会话撤销

  - 前置任务：[M03-01](identity-access.md#m03-01)。
  - 本次范围：使用 Spring Security OAuth2 Resource Server/Nimbus JOSE 签验JWT，校验aud/session/tokenVersion/账号状态；错误走统一Envelope。
  - 交付产物：SecurityFilterChain、当前身份和权限上下文。
  - 验收条件：过期、错误aud、撤销、停用、伪造签名均401/403；公开接口白名单精确。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0303IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-03验收记录](../evidence/M03-03.md)：普通23/23、真实MySQL8.4.13的M0303IT 5/5、M0301IT回归6/6，OpenAPI与双端类型检查通过；收尾Docker外部故障及回滚也已如实记录。

### M03-04

- [ ] M03-04 实现刷新、退出和密码变更

  - 前置任务：[M03-02](identity-access.md#m03-02)、[M03-03](identity-access.md#m03-03)。
  - 本次范围：实现旋转刷新、family重用检测、当前会话退出、修改密码撤销全部会话；Web Cookie+CSRF，MINI body。
  - 交付产物：刷新/退出/改密端点。
  - 验收条件：旧refresh重放撤销family；跨站刷新被拒；无密码微信用户须新鲜code；改密后旧access不可用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0304IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M03-04.md`。目前无实现证据。

### M03-05

- [x] M03-05 实现本人资料与权限菜单

  - 前置任务：[M03-03](identity-access.md#m03-03)。
  - 本次范围：实现 /me GET/PATCH、/me/menus；字段白名单、version、头像引用校验；手机号采用服务端AES-GCM加密和脱敏展示，密钥不入库；权限点控制不授予跨对象权限。
  - 交付产物：本人资料与菜单查询。
  - 验收条件：越权字段拒绝；旧version409；仅返回本人允许菜单；头像不是自己的或未READY拒绝。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0305IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-05验收记录](../evidence/M03-05.md)：普通25/25、真实MySQL8.4.13的M0305IT 5/5、M0301IT 6/6、M0303IT 5/5，OpenAPI与双端类型检查通过。

### M03-06

- [x] M03-06 实现管理员账号开通与资料

  - 前置任务：[M03-03](identity-access.md#m03-03)。
  - 本次范围：实现 /admin/users 列表、创建、详情、资料纠错；初始密码不进日志；绑定后台角色。
  - 交付产物：账号管理查询/创建/修改。
  - 验收条件：普通用户403；重复用户名409；跨角色修改不混入诊疗结论；返回脱敏资料。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0306IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-06验收记录](../evidence/M03-06.md)：普通25/25、真实MySQL8.4.13的M0306IT 5/5、M0301IT/M0303IT/M0305IT合计16/16，OpenAPI与双端类型检查通过，敏感报告扫描0匹配。

### M03-07

- [x] M03-07 实现角色、菜单与关联管理

  - 前置任务：[M03-06](identity-access.md#m03-06)。
  - 本次范围：实现 /admin/roles、/admin/menus及用户角色、角色菜单集合；版本校验、树无环、内置码保护。
  - 交付产物：RBAC管理端点和后端permission映射。
  - 验收条件：缺动作权限403；父子循环400；权限更新使受影响旧会话失效；不能移除最后有效管理员。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0307IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-07验收记录](../evidence/M03-07.md)：普通25/25、真实MySQL8.4.13的M0307IT 5/5、跨模块真实MySQL回归32/32，OpenAPI与双端类型检查通过。

### M03-08

- [x] M03-08 实现账号启停与密码重置

  - 前置任务：[M03-07](identity-access.md#m03-07)。
  - 本次范围：实现 disable/enable/reset-password；未终结预约先查询拒绝停用/移除医生角色；提供领域查询接口而非依赖预约Controller。
  - 交付产物：账号生命周期接口。
  - 验收条件：seed未终结预约返回待处理数409；终态后可停用；重置不泄露密码且撤销会话；last-admin保护。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0308IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-08验收记录](../evidence/M03-08.md)：普通25/25、真实MySQL8.4.13的M0308IT 5/5、八类跨模块回归37/37，OpenAPI与双端类型检查通过，18份XML累计85项全绿且敏感报告扫描0匹配。

### M03-09

- [x] M03-09 实现授权告知事件

  - 前置任务：[M03-03](identity-access.md#m03-03)。
  - 本次范围：实现 /policies/current、/me/consents 查询和追加；记录文档hash、版本与撤回；导出AI有效授权判断器。
  - 交付产物：政策配置与授权接口。
  - 验收条件：旧版本/错误hash拒绝；重复key不重复事件；撤回只阻止新AI外呼，不阻止宠物/预约。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0309IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-09验收记录](../evidence/M03-09.md)：普通25/25、真实MySQL8.4.13的M0309IT 6/6、关键跨模块回归47/47及注册/授权定向回归12/12，OpenAPI与双端类型检查通过，19份XML累计91项全绿且敏感扫描0匹配。

### M03-10

- [x] M03-10 实现统一对象授权策略

  - 前置任务：[M03-03](identity-access.md#m03-03)、[M03-07](identity-access.md#m03-07)。
  - 本次范围：定义主人范围、医生确认前后范围、完成后30天范围、原署名医生版本权限、问诊审计权限；先用DB夹具验证。
  - 交付产物：ResourceAccessPolicy与MINI/WEB越权IT。
  - 验收条件：两用户两医生互相不能读数据；ADMIN无资格不能临床写；未确认只读必要预约字段。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0310IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M03-10验收记录](../evidence/M03-10.md)：普通25/25、真实MySQL8.4.13的M0310IT 5/5、九类跨模块回归47/47，OpenAPI与双端类型检查通过，20份XML累计96项全绿且敏感扫描0匹配。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| POST | `/auth/user/register` | [M03-01](identity-access.md#m03-01) |
| POST | `/auth/user/login` | [M03-01](identity-access.md#m03-01) |
| POST | `/auth/wechat/login` | [M03-02](identity-access.md#m03-02) |
| POST | `/auth/web/login` | [M03-01](identity-access.md#m03-01) |
| POST | `/auth/refresh` | [M03-04](identity-access.md#m03-04) |
| POST | `/auth/logout` | [M03-04](identity-access.md#m03-04) |
| GET | `/me` | [M03-05](identity-access.md#m03-05) |
| PATCH | `/me` | [M03-05](identity-access.md#m03-05) |
| POST | `/me/change-password` | [M03-04](identity-access.md#m03-04) |
| GET | `/me/menus` | [M03-05](identity-access.md#m03-05) |
| GET | `/policies/current` | [M03-09](identity-access.md#m03-09) |
| GET | `/me/consents` | [M03-09](identity-access.md#m03-09) |
| POST | `/me/consents` | [M03-09](identity-access.md#m03-09) |
| GET | `/admin/users` | [M03-06](identity-access.md#m03-06) |
| POST | `/admin/users` | [M03-06](identity-access.md#m03-06) |
| GET | `/admin/users/{userId}` | [M03-06](identity-access.md#m03-06) |
| PATCH | `/admin/users/{userId}` | [M03-06](identity-access.md#m03-06) |
| POST | `/admin/users/{userId}/disable` | [M03-08](identity-access.md#m03-08) |
| POST | `/admin/users/{userId}/enable` | [M03-08](identity-access.md#m03-08) |
| POST | `/admin/users/{userId}/reset-password` | [M03-08](identity-access.md#m03-08) |
| GET | `/admin/users/{userId}/roles` | [M03-07](identity-access.md#m03-07) |
| PUT | `/admin/users/{userId}/roles` | [M03-07](identity-access.md#m03-07) |
| GET | `/admin/roles` | [M03-07](identity-access.md#m03-07) |
| POST | `/admin/roles` | [M03-07](identity-access.md#m03-07) |
| PATCH | `/admin/roles/{roleId}` | [M03-07](identity-access.md#m03-07) |
| GET | `/admin/roles/{roleId}/menus` | [M03-07](identity-access.md#m03-07) |
| PUT | `/admin/roles/{roleId}/menus` | [M03-07](identity-access.md#m03-07) |
| GET | `/admin/menus` | [M03-07](identity-access.md#m03-07) |
| POST | `/admin/menus` | [M03-07](identity-access.md#m03-07) |
| PATCH | `/admin/menus/{menuId}` | [M03-07](identity-access.md#m03-07) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

