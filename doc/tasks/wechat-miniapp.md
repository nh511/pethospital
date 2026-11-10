# M15 微信小程序用户端

更新：2026-10-28。状态：未开始；任务 0/12。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F01、F02、F03、F04、F05、F06、F08、F09、F10、F11、F12、F15、F16、F19。
- 主要产物：miniapp/ 页面与测试。
- 表责任：无独占业务表；读取或使用既有表。
- 模块级前置：[M01](foundation.md)、[M03](identity-access.md)、[M04](shared-services.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M15-01

- [ ] M15-01 实现小程序请求封装

  - 前置任务：[M01-05](foundation.md#m01-05)、[M02-06](database-contract.md#m02-06)、[M03-04](identity-access.md#m03-04)。
  - 本次范围：wx.request/wx.uploadFile、统一类型与Token、401刷新单飞、业务错误、写幂等key；TS测试替换wx API。
  - 交付产物：miniapp服务层。
  - 验收条件：轮询不写入重复key；刷新失败退出；请求不带后台角色标识；无模型/微信Secret。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1501.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-01.md`。目前无实现证据。

### M15-02

- [ ] M15-02 实现注册登录与授权告知

  - 前置任务：[M15-01](wechat-miniapp.md#m15-01)、[M03-02](identity-access.md#m03-02)、[M03-05](identity-access.md#m03-05)、[M03-09](identity-access.md#m03-09)。
  - 本次范围：账号注册/登录、wx.login、首次隐私版本、AI授权接受/撤回、个人中心资料与改密。
  - 交付产物：登录与个人中心页。
  - 验收条件：首次登录可完成；撤回AI仍能用预约；模拟code只在测试profile；真code验收单列不可冒充。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1502.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-02.md`。目前无实现证据。

### M15-03

- [ ] M15-03 实现宠物列表与基础资料编辑

  - 前置任务：[M15-02](wechat-miniapp.md#m15-02)、[M05-03](pet-health.md#m05-03)、[M04-05](shared-services.md#m04-05)。
  - 本次范围：宠物创建/详情/编辑、头像上传、归档及错误提示；主人自述与医生摘要分栏。
  - 交付产物：宠物页面。
  - 验收条件：不能修改临床字段；有待办归档显示409原因；返回后列表刷新；图片上传失败不假成功。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1503.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-03.md`。目前无实现证据。

### M15-04

- [ ] M15-04 实现日常健康日志页面

  - 前置任务：[M15-03](wechat-miniapp.md#m15-03)、[M05-05](pet-health.md#m05-05)。
  - 本次范围：日志列表、新增、详情编辑与附件；输入体重、饮食、排便、活动；处理version冲突。
  - 交付产物：日志页面。
  - 验收条件：空日志不能提交；旧version不无声覆盖；来源显示用户自述；旧临床引用附件不被删。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1504.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-04.md`。目前无实现证据。

### M15-05

- [ ] M15-05 实现AI会话列表和消息恢复

  - 前置任务：[M15-03](wechat-miniapp.md#m15-03)、[M12-07](ai-consultation.md#m12-07)。
  - 本次范围：建会话/选择pet、发送、轮询、滚动加载历史、关闭会话、失败提示与显式重试。
  - 交付产物：问诊聊天页面。
  - 验收条件：后台返回后恢复同消息；同会话未完成时不并发发送；成功建议不能重试覆盖；离页停轮询。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1505.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-05.md`。目前无实现证据。

### M15-06

- [ ] M15-06 实现风险提示、引用与预约入口

  - 前置任务：[M15-05](wechat-miniapp.md#m15-05)、[M10-05](emergency-triage.md#m10-05)、[M12-06](ai-consultation.md#m12-06)。
  - 本次范围：少量急症筛查问卷、红色立即就医提示、医院电话/地图、引用快照、每条建议免责声明；携aiSessionId进预约页。
  - 交付产物：问诊安全展示和引用抽屉。
  - 验收条件：RULE结果立即可见且不等待自由生成；UNKNOWN不显示安全；非猫犬覆盖限制；提示语历史查看仍有。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1506.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-06.md`。目前无实现证据。

### M15-07

- [ ] M15-07 实现医生选择和可预约时段页面

  - 前置任务：[M15-03](wechat-miniapp.md#m15-03)、[M06-05](doctors-scheduling.md#m06-05)、[M07-02](appointments.md#m07-02)。
  - 本次范围：医生列表详情、时段与余量、选择本人pet和主诉、关联可选会话提交。
  - 交付产物：预约提交页。
  - 验收条件：余量变化409要求重选；双击只一单；不把前端余量当保证；无AI会话也可预约。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1507.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-07.md`。目前无实现证据。

### M15-08

- [ ] M15-08 实现预约列表详情与取消

  - 前置任务：[M15-07](wechat-miniapp.md#m15-07)、[M07-05](appointments.md#m07-05)。
  - 本次范围：七状态展示、确认期限、取消原因；变更后刷新状态与名额提示。
  - 交付产物：我的预约页。
  - 验收条件：无已到院；开始后隐藏用户取消；网络重发不重复取消；他人预约不可打开。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1508.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-08.md`。目前无实现证据。

### M15-09

- [ ] M15-09 实现双向改期协商页面

  - 前置任务：[M15-08](wechat-miniapp.md#m15-08)、[M07-09](appointments.md#m07-09)。
  - 本次范围：用户提出/撤回，接受或拒绝医生申请；显示原时间与候选时间和期限，目标满员不替换原预约。
  - 交付产物：改期页。
  - 验收条件：不能自提自批；超时明确提示；同医生限制；接受成功后列表和详情时间一致。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1509.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-09.md`。目前无实现证据。

### M15-10

- [ ] M15-10 实现正式病历与健康时间线

  - 前置任务：[M15-03](wechat-miniapp.md#m15-03)、[M08-04](clinical-records.md#m08-04)、[M05-07](pet-health.md#m05-07)。
  - 本次范围：已提交病历只读、历史修订、检查附件票据、日志/AI/预约时间线。
  - 交付产物：健康档案页。
  - 验收条件：不显示医生草稿；用户无诊断编辑入口；签名过期可重新取票；归档历史仍可查。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1510.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-10.md`。目前无实现证据。

### M15-11

- [ ] M15-11 实现健康科普与通知页面

  - 前置任务：[M15-02](wechat-miniapp.md#m15-02)、[M09-06](health-content.md#m09-06)、[M04-06](shared-services.md#m04-06)。
  - 本次范围：指南列表详情、物种年龄健康标准、医院资料、通知列表与已读；显示来源和条件。
  - 交付产物：科普/标准/通知页。
  - 验收条件：下架文章不可读；不把标准异常诊断为疾病；重复读同日不增浏览；通知不含全文诊断。
  - 验证方法：`pnpm --dir miniapp exec vitest run tests/M1511.spec.ts` 加 `pnpm --dir miniapp typecheck`；单元测试替换wx，真机验收由M15-12执行。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-11.md`。目前无实现证据。

### M15-12

- [ ] M15-12 完成开发者工具和真机联调

  - 前置任务：[M15-04](wechat-miniapp.md#m15-04)、[M15-06](wechat-miniapp.md#m15-06)、[M15-09](wechat-miniapp.md#m15-09)、[M15-10](wechat-miniapp.md#m15-10)、[M15-11](wechat-miniapp.md#m15-11)。
  - 本次范围：真实微信AppID/Secret只在对应环境、HTTPS合法域名、upload/download域名、真机微信登录及预约→病历→AI流程。
  - 交付产物：doc/evidence/wechat-device.md、设备/工具版本和截图。
  - 验收条件：TS和Vitest通过；开发者工具与真机均走真实后端；开发禁域名校验不算正式验收；缺资质/域名明确阻塞，模块不勾完。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M15-12.md`。目前无实现证据。

## API责任清单

本模块消费后端契约，不重复声明端点实现责任；页面涉及的API以对应任务范围及接口设计为准。

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

