# M04 文件、通知、审计与幂等

更新：2026-10-31。状态：小任务完成、模块前置未满足；任务 7/7。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F15、F16、F17。
- 主要产物：server/ 对应业务包及测试。
- 表责任：`file_object`、`file_binding`、`audit_log`、`user_notice`。
- 模块级前置：[M03](identity-access.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；当前7项已完成。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M04-01

- [x] M04-01 实现审计及全局写幂等

  - 前置任务：[M03-03](identity-access.md#m03-03)、[M02-05](database-contract.md#m02-05)。
  - 本次范围：实现 audit append、operationKey/requestHash 和原资源引用；业务事务与审计同提交；普通日志脱敏；供后续Service调用。
  - 交付产物：AuditService、IdempotencyService、脱敏处理。
  - 验收条件：同key同参数重放原结果，同key异路径/参数409；并发只成功一次；回滚无残留业务记录；不记录密码/令牌。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0401IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-01](../evidence/M04-01.md)：事务型审计/幂等、并发唯一执行、原结果重放、失败回滚及敏感脱敏已通过47项普通测试、M0401IT 4项和24项真实MySQL定向回归。

### M04-02

- [x] M04-02 实现 S3 私有存储适配器

  - 前置任务：[M01-03](foundation.md#m01-03)。
  - 本次范围：AWS SDK v2 S3Client+S3Presigner 连接 SeaweedFS，显式endpoint/region/path-style；应用凭据最小权限；内部/公网签名域名分离。
  - 交付产物：ObjectStorageAdapter及真实S3 IT。
  - 验收条件：上传/读取字节hash一致；匿名拒绝；预签名60秒可读、过期拒绝；不能生成指向容器内部域名的用户票据。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0402IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-02](../evidence/M04-02.md)：AWS SDK v2适配器、内部/公网endpoint隔离、真实SeaweedFS上传读取hash、匿名拒绝及60秒/过期票据均通过；普通47项、真实S3 4项和审计联合回归8项通过。

### M04-03

- [x] M04-03 实现安全上传与元数据

  - 前置任务：[M04-01](shared-services.md#m04-01)、[M04-02](shared-services.md#m04-02)。
  - 本次范围：实现 /files multipart及详情；JPEG/PNG重编码去EXIF、PDF实际类型/大小与危险特征隔离校验；QUARANTINED至READY/REJECTED。
  - 交付产物：上传端点、验证策略、隔离目录。
  - 验收条件：扩展名伪造、超限、可执行/加密或活动PDF拒绝；正常图/PDF成功；objectKey不回传；无扫描器不虚称全面查毒。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0403IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-03](../evidence/M04-03.md)：安全 multipart 上传、Tika 实际类型识别、图片重编码、PDF 危险特征拒绝、状态流转和严格元数据响应已通过52项普通测试、M0403IT 7项及23项跨模块真实回归。

### M04-04

- [x] M04-04 实现业务附件绑定策略

  - 前置任务：[M04-03](shared-services.md#m04-03)、[M03-10](identity-access.md#m03-10)。
  - 本次范围：通过业务Service绑定、更新可变草稿/日志时解绑；MEDICAL_VERSION/KNOWLEDGE_VERSION附件只追加；校验来源用户与授权。
  - 交付产物：FileBindingService和校验API。
  - 验收条件：不能把他人文件绑定自己的日志；版本附件不能解绑；事务回滚不留下绑定；不新增任意binding公共端点。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0404IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-04](../evidence/M04-04.md)：业务事务内附件替换/追加、来源与对象授权、版本只追加、回滚无残留及无公共绑定端点已通过52项普通测试、M0404IT 5项和26项真实联合回归。

### M04-05

- [x] M04-05 实现下载票据与未绑定清理

  - 前置任务：[M04-04](shared-services.md#m04-04)。
  - 本次范围：实现 download-ticket/discard；验证业务对象范围；签名URL不入日志；清理仅无绑定孤儿，不删除历史证据。
  - 交付产物：授权下载和孤儿清理任务。
  - 验收条件：换businessId/已过授权范围拒绝；未绑定可本人预览；历史病历附件不可discard；S3删除失败可恢复且元数据不误报成功。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0405IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-05](../evidence/M04-05.md)：精确业务绑定与当前对象范围校验、未绑定本人预览、60秒私有下载、历史附件保护、幂等丢弃及存储失败恢复已通过52项普通测试、M0405IT 5项和40项真实联合回归。

### M04-06

- [x] M04-06 实现同事务站内消息

  - 前置任务：[M04-01](shared-services.md#m04-01)。
  - 本次范围：实现通知写入、eventKey去重、本人列表和已读；消息只含提醒及资源链接，客户端打开再次鉴权。
  - 交付产物：NoticeService与 /notices 接口。
  - 验收条件：业务回滚无通知；重放不重复；用户不能读/标记他人消息；详情不泄露诊断。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0406IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-06](../evidence/M04-06.md)：强制加入业务事务的通知写入、稳定事件去重、固定安全模板、本人分页/未读查询和幂等已读已通过52项普通测试、M0406IT 5项及24项真实联合回归。

### M04-07

- [x] M04-07 实现审计和模型运维查询

  - 前置任务：[M04-01](shared-services.md#m04-01)、[M03-07](identity-access.md#m03-07)。
  - 本次范围：实现 /admin/audit-logs、/admin/ai/call-logs；按专门权限区分元数据与模型上下文，读取审计防止递归。
  - 交付产物：只读后台查询和分页过滤。
  - 验收条件：普通ADMIN未分配权限403；无token/plaintext泄漏；查询有时间上限；没有日志删除修改端点。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0407IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M04-07](../evidence/M04-07.md)：审计/模型调用只读分页、数据库专门权限、上下文额外授权、31天上限、读取留痕与无敏感字段已通过52项普通测试、M0407IT 5项及29项真实联合回归。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| POST | `/files` | [M04-03](shared-services.md#m04-03) |
| GET | `/files/{fileId}` | [M04-03](shared-services.md#m04-03) |
| POST | `/files/{fileId}/download-ticket` | [M04-05](shared-services.md#m04-05) |
| POST | `/files/{fileId}/discard` | [M04-05](shared-services.md#m04-05) |
| GET | `/notices` | [M04-06](shared-services.md#m04-06) |
| POST | `/notices/{noticeId}/read` | [M04-06](shared-services.md#m04-06) |
| GET | `/admin/audit-logs` | [M04-07](shared-services.md#m04-07) |
| GET | `/admin/ai/call-logs` | [M04-07](shared-services.md#m04-07) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

