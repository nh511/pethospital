# M14 Web 医生端与管理员端

更新：2026-10-28。状态：未开始；任务 0/20。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F01、F04、F06、F07、F08、F09、F10、F11、F12、F13、F14、F15、F16、F17、F18。
- 主要产物：web/ 页面与测试。
- 表责任：无独占业务表；读取或使用既有表。
- 模块级前置：[M01](foundation.md)、[M03](identity-access.md)、[M04](shared-services.md)。具体任务还须满足下面列出的前置任务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备；此处所有项尚未实现。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度，不把多项一起标完成。

### M14-01

- [ ] M14-01 建立Web API客户端

  - 前置任务：[M01-04](foundation.md#m01-04)、[M02-06](database-contract.md#m02-06)、[M03-04](identity-access.md#m03-04)。
  - 本次范围：Axios+生成TS类型，Envelope/401/403/409处理、Access Token内存持有、refresh cookie与CSRF；仅GET按需重试，写请求key在一次用户操作内稳定。
  - 交付产物：web/src/api与错误展示组件。
  - 验收条件：并发401只刷新一次；刷新失败退出；不能在localStorage持久化refresh；409不自动重放覆盖。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1401.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-01.md`。目前无实现证据。

### M14-02

- [ ] M14-02 实现后台登录和动态菜单框架

  - 前置任务：[M14-01](web-console.md#m14-01)、[M03-05](identity-access.md#m03-05)。
  - 本次范围：登录页、角色切换导航、权限按钮及路由守卫；以/me和/me/menus驱动，不依据客户端伪造role。
  - 交付产物：登录及医生/管理员两套布局。
  - 验收条件：普通USER不能进WEB；权限变化刷新菜单；刷新后会话恢复；直开无权路由有明确拒绝页。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1402.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-02.md`。目前无实现证据。

### M14-03

- [ ] M14-03 实现账号管理页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M03-08](identity-access.md#m03-08)。
  - 本次范围：用户列表/后台开通/详情纠错/启停/重置密码，展示未处理预约阻止停用原因。
  - 交付产物：账号管理页。
  - 验收条件：无权限按钮不出现；后端409展示原原因；敏感资料默认脱敏；密码不显示在列表或埋点。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1403.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-03.md`。目前无实现证据。

### M14-04

- [ ] M14-04 实现角色和菜单配置页面

  - 前置任务：[M14-03](web-console.md#m14-03)、[M03-07](identity-access.md#m03-07)。
  - 本次范围：角色列表、权限勾选树、菜单树编辑与用户角色分配；加载version后再保存。
  - 交付产物：RBAC页面。
  - 验收条件：最后管理员保护可见；树循环后端拒绝能提示；保存冲突不覆盖他人修改。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1404.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-04.md`。目前无实现证据。

### M14-05

- [ ] M14-05 实现医生资料与排班页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M06-06](doctors-scheduling.md#m06-06)、[M04-05](shared-services.md#m04-05)。
  - 本次范围：管理员医生资料/资质、医生本人介绍；时段列表、新建、容量和开关；分角色使用同组件。
  - 交付产物：医生资料页与排班页。
  - 验收条件：医生不能选他人；满额/重叠错误可见；关闭不会在UI假装取消原预约；证件下载需票据。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1405.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-05.md`。目前无实现证据。

### M14-06

- [ ] M14-06 实现预约列表与基础动作

  - 前置任务：[M14-02](web-console.md#m14-02)、[M07-06](appointments.md#m07-06)。
  - 本次范围：本人/全院预约列表详情、confirm/reject/cancel/start/no-show；按allowedActions显示且再次确认有原因动作。
  - 交付产物：预约工作台。
  - 验收条件：角色与状态显示准确；双击不重复提交；旧version提示刷新；不出现已到院或任意状态编辑下拉框。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1406.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-06.md`。目前无实现证据。

### M14-07

- [ ] M14-07 实现预约改期协商页面

  - 前置任务：[M14-06](web-console.md#m14-06)、[M07-09](appointments.md#m07-09)。
  - 本次范围：在预约详情选择同医生新slot、申请/接受/拒绝/撤回和历史；失败保留原时间。
  - 交付产物：改期面板。
  - 验收条件：不能自提自批；新slot满员409原时间仍显示；过期申请不能操作；区别主状态和申请状态。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1407.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-07.md`。目前无实现证据。

### M14-08

- [ ] M14-08 实现病历草稿与完成接诊页面

  - 前置任务：[M14-06](web-console.md#m14-06)、[M08-02](clinical-records.md#m08-02)。
  - 本次范围：检查/诊断/治疗/药物表单、附件、保存草稿与complete；用户自述和AI参考分区。
  - 交付产物：诊疗编辑页。
  - 验收条件：未填必需字段不能完成；失败保持编辑内容；提交成功才显示COMPLETED；不自动把AI写诊断。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1408.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-08.md`。目前无实现证据。

### M14-09

- [ ] M14-09 实现历史病历与修订页面

  - 前置任务：[M14-08](web-console.md#m14-08)、[M08-04](clinical-records.md#m08-04)。
  - 本次范围：病历只读详情、版本对比、署名医生修订与原因；非署名隐藏修订入口。
  - 交付产物：病历历史与修订页。
  - 验收条件：旧版本不被新附件覆盖；过期version可恢复本地草稿但需人工重填；管理员仅审计只读。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1409.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-09.md`。目前无实现证据。

### M14-10

- [ ] M14-10 实现医生宠物档案工作区

  - 前置任务：[M14-02](web-console.md#m14-02)、[M06-07](doctors-scheduling.md#m06-07)、[M05-06](pet-health.md#m05-06)、[M08-04](clinical-records.md#m08-04)。
  - 本次范围：授权宠物列表、日志、临床病历；管理员基础纠错与只读日志；不越权查看全用户。
  - 交付产物：宠物/日志只读和纠错页。
  - 验收条件：确认前后范围一致；30天边界后权限提示；基础资料与诊断来源明确。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1410.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-10.md`。目前无实现证据。

### M14-11

- [ ] M14-11 实现关联AI记录与审计页面

  - 前置任务：[M14-06](web-console.md#m14-06)、[M12-08](ai-consultation.md#m12-08)。
  - 本次范围：医生从预约打开关联会话，管理员按单独权限审计会话及引用；不展示模型隐藏推理。
  - 交付产物：AI协同只读页。
  - 验收条件：未关联会话不拼造ID访问；免责声明常驻；下架来源历史引用可看快照；普通ADMIN403。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1411.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-11.md`。目前无实现证据。

### M14-12

- [ ] M14-12 实现健康指南编辑和发布页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M09-03](health-content.md#m09-03)。
  - 本次范围：管理员指南列表、富文本编辑、封面上传、发布下架；医生只读。
  - 交付产物：指南运营页。
  - 验收条件：脚本不执行；上传失败不发布；下架状态一致；version冲突保留编辑内容。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1412.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-12.md`。目前无实现证据。

### M14-13

- [ ] M14-13 实现健康标准专业维护页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M09-05](health-content.md#m09-05)。
  - 本次范围：医生标准表单、管理员发布下架；展示物种、年龄、单位、参考条件与来源。
  - 交付产物：标准维护页。
  - 验收条件：管理员不能改专业正文；医生编辑后显示待发布；数值区间错误可见；未知年龄不误套用。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1413.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-13.md`。目前无实现证据。

### M14-14

- [ ] M14-14 实现急症规则编辑与审核页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M10-06](emergency-triage.md#m10-06)。
  - 本次范围：草稿表单、条件构建器、医生审核、管理员启停、预览测试；不提供自由脚本输入。
  - 交付产物：规则管理页。
  - 验收条件：批准版本只读；测试不计入真实问诊；否定/未知样例能展示证据和版本。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1414.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-14.md`。目前无实现证据。

### M14-15

- [ ] M14-15 实现知识文档与版本审核页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M11-02](knowledge-rag.md#m11-02)。
  - 本次范围：文档/来源输入、版本列表、医生审核；批准内容只读，新增版本修改。
  - 交付产物：知识编辑和审核页。
  - 验收条件：标签重分类限制可见；普通管理员不能专业批准；新草稿不替换已发布版。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1415.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-15.md`。目前无实现证据。

### M14-16

- [ ] M14-16 实现索引任务与检索预览页面

  - 前置任务：[M14-15](web-console.md#m14-15)、[M11-10](knowledge-rag.md#m11-10)。
  - 本次范围：索引进度轮询、失败重试新job、READY后发布、下架与搜索预览；不把进度100%当已发布。
  - 交付产物：知识索引发布页。
  - 验收条件：失败分块不能点发布；索引完成且审核通过才可发布；预览无候选明确显示资料不足。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1416.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-16.md`。目前无实现证据。

### M14-17

- [ ] M14-17 实现通知、个人资料和公共附件交互

  - 前置任务：[M14-02](web-console.md#m14-02)、[M04-05](shared-services.md#m04-05)、[M04-06](shared-services.md#m04-06)、[M03-05](identity-access.md#m03-05)。
  - 本次范围：通知未读/已读、本人资料/改密、上传进度与授权下载；由共用组件供各业务页使用。
  - 交付产物：Web公共交互。
  - 验收条件：不能读他人通知；改密后重新登录；票据过期刷新而非持久缓存；无密钥进入打包产物。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1417.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-17.md`。目前无实现证据。

### M14-18

- [ ] M14-18 实现审计与调用元数据页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M04-07](shared-services.md#m04-07)。
  - 本次范围：按权限查看操作轨迹和模型用量；筛选、分页、脱敏字段；无修改/删除日志按钮。
  - 交付产物：审计与模型调用列表。
  - 验收条件：普通ADMIN无专项权限不可见；查询条件受范围限制；签名URL和敏感上下文不泄漏。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1418.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-18.md`。目前无实现证据。

### M14-19

- [ ] M14-19 实现医院报表与医生工作量页面

  - 前置任务：[M14-02](web-console.md#m14-02)、[M13-05](reporting.md#m13-05)。
  - 本次范围：ECharts展示overview、appointments、ai、doctors、pets、content、model-usage及本人workload；逐图标口径/分母/未知。
  - 交付产物：报表页与医生首页统计。
  - 验收条件：零分母显示暂无数据；成熟7天转化口径可见；无虚构营收/AI准确率；本人页面不含他人工作量。
  - 验证方法：`pnpm --dir web exec vitest run tests/M1419.spec.ts` 加 `pnpm --dir web typecheck`；按该页状态矩阵编写组件/请求断言。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-19.md`。目前无实现证据。

### M14-20

- [ ] M14-20 完成Web真实后端角色流程验收

  - 前置任务：[M14-04](web-console.md#m14-04)、[M14-05](web-console.md#m14-05)、[M14-07](web-console.md#m14-07)、[M14-09](web-console.md#m14-09)、[M14-10](web-console.md#m14-10)、[M14-11](web-console.md#m14-11)、[M14-12](web-console.md#m14-12)、[M14-13](web-console.md#m14-13)、[M14-14](web-console.md#m14-14)、[M14-16](web-console.md#m14-16)、[M14-17](web-console.md#m14-17)、[M14-18](web-console.md#m14-18)、[M14-19](web-console.md#m14-19)。
  - 本次范围：Playwright分别跑两医生与管理员，覆盖登录、排班、接单、病历、发布知识、报表和权限拒绝；对照API清单检查页面入口。
  - 交付产物：Web E2E测试、构建产物及真实联调证据。
  - 验收条件：使用真实后端/MySQL而非全量mock；typecheck/build/test通过；所有本模块页面有截图/测试记录；无控制台未处理异常。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M14-20.md`。目前无实现证据。

## API责任清单

本模块消费后端契约，不重复声明端点实现责任；页面涉及的API以对应任务范围及接口设计为准。

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

