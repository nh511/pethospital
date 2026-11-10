# AI 问诊宠物医院系统接口设计

版本：V1.0 接口契约设计稿；日期：2026-10-22。基路径 `/api/v1`。下面路径均相对此基路径，花括号表示字符串 ID。本稿为前后端对接契约，不表示已有运行接口。

2026-10-28：技术组件已确定，全部158个设计动作已分配主责任任务。见[任务总体进度](../doc/tasks/progress.md)及各模块API清单；不改变本稿既有路径和七状态预约规则。

## 1 通用协议

### 1.1 身份与数据权限

JWT Access Token 使用 `Authorization: Bearer <token>`，包含 subject/userId、sessionId、tokenVersion、aud（MINI/WEB）与过期时间。服务端检查签名、有效期、会话撤销、账号启用、角色权限及资源归属。客户端传入 userId/doctorId 不能代替身份检查。

小程序公开注册只产生 USER；医生和管理员不能通过公开注册创建。后台数据接口要求 WEB audience，用户业务接口要求 MINI audience。具有多角色的账号仍须通过相应端登录。`/me`、`/files`、`/notices` 为共用端点，但返回对应身份允许的内容。

设计默认 Access Token 30 分钟、Refresh Token 7 天，实际可配置。Web 刷新令牌以 Secure/HttpOnly/SameSite Cookie 传输并校验 Origin/CSRF；小程序通过请求体提交 refreshToken。退出、密码变更、角色变更和停用会撤销相关 auth_session；不能仅在前端删除 Token。

权限标识以 `资源:动作` 定义，例如 `appointment:confirm`、`medical:revise`、`knowledge:publish`、`audit:read`。下表“医生”表示还需启用 doctor_profile 与对应动作权限；“管理员”不自动获得临床写入能力。对象范围遵守功能清单第 3 节。

### 1.2 格式与分页

请求 JSON 使用 camelCase，数据库 snake_case 由后端映射。所有 ID 为字符串；日期为 `YYYY-MM-DD`，时刻为带时区 ISO 8601，如 `2026-10-23T09:00:00+08:00`。数据传输 UTF-8。

```json
{
  "code": "OK",
  "message": "成功",
  "data": {"id": "10001"},
  "traceId": "trace-generated-by-server"
}
```

查询列表 `page=1&pageSize=20`，最大 100，稳定默认排序 `createdAt DESC,id DESC`。响应 data 为 `{items:[],page:1,pageSize:20,total:0}`。过滤条件没有传入不代表取消归属校验。角色无权访问返回 403；非本人资源可统一返回 404 避免泄露存在性。时间查询统一 `[from,to)`。

### 1.3 幂等、并发与错误

除登录/注册/令牌刷新外，所有 POST 业务写请求携带 `Idempotency-Key`（16～64 位客户端随机值）。同一用户不能把 key 用于另一操作；服务端对 method+path+规范化 body 求 requestHash，以 audit_log 或对应业务唯一键保存结果引用，重复请求返回原创建资源/原状态事件的结果，相同 key 不同参数返回 409 IDEMPOTENCY_CONFLICT。审计日志不是无界缓存，重放策略及保留期需大于客户端重试窗口。

PUT/PATCH 请求体携带 `version`；文件绑定等集合操作同样关联业务版本。前端收到冲突后刷新资源，不能自动覆盖。客户端不能 PATCH 任意状态字段；使用下文动作接口。取消等 POST 同时要求 key 与 version；幂等重放在版本校验前识别。

| HTTP | code | 场景 |
|---|---|---|
| 400 | VALIDATION_ERROR | 参数不合法，附 fieldErrors |
| 401 | UNAUTHENTICATED/TOKEN_EXPIRED | 未登录、会话失效 |
| 403 | FORBIDDEN/CONSENT_REQUIRED | 无动作权限/AI 授权未接受 |
| 404 | NOT_FOUND | 资源不存在或不在可见范围 |
| 409 | VERSION_CONFLICT/STATE_CONFLICT | 乐观锁/状态不允许 |
| 409 | SLOT_FULL/SLOT_CLOSED/TIME_OVERLAP | 名额不足、停诊、时间冲突 |
| 409 | IDEMPOTENCY_CONFLICT/AI_TURN_IN_PROGRESS | 幂等冲突、同会话已有处理轮 |
| 410 | REQUEST_EXPIRED | 改期申请已过期 |
| 413/415 | FILE_TOO_LARGE/FILE_TYPE_NOT_ALLOWED | 文件限制 |
| 422 | KNOWLEDGE_NOT_READY/MEDICAL_RECORD_REQUIRED | 未具备发布条件/缺病历 |
| 429 | RATE_LIMITED | 登录、AI 或上传超过频率 |
| 503 | DEPENDENCY_UNAVAILABLE | 同步依赖故障；AI 异步失败写到消息状态 |

错误示例：`{code:"SLOT_FULL",message:"该时段已约满，请选择其他时段",data:null,traceId:"..."}`。不向客户端返回 SQL、堆栈、API Key 或内部对象存储路径。

## 2 账号与基础资源 F01/F14/F19

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| POST | /auth/user/register | 公开，仅 MINI | username/password/displayName、已阅读隐私版本；返回用户摘要；不接受 role |
| POST | /auth/user/login | 公开，MINI | username/password；返回 TokenBundle |
| POST | /auth/wechat/login | 公开，MINI | code、首次登录告知版本；后端换 OpenID，返回 TokenBundle；不接收客户端 OpenID |
| POST | /auth/web/login | 公开，WEB | username/password；必须具有 DOCTOR/ADMIN；返回 TokenBundle |
| POST | /auth/refresh | 有效刷新会话 | Web Cookie 或 MINI refreshToken；轮换刷新令牌 |
| POST | /auth/logout | 本人会话 | 撤销当前 session，返回 loggedOut=true |
| GET | /me | 登录 | UserSelf：id/displayName/phoneMasked/avatarFileId/roles/permissions |
| PATCH | /me | 登录 | displayName、avatarFileId、phone（可选）、version；不接受角色/状态 |
| POST | /me/change-password | 登录 | oldPassword/newPassword；有密码必须验证原密码；微信无密码设置需新鲜微信 code 证明；撤销全部会话 |
| GET | /me/menus | 后台 | 返回角色允许的菜单树，不替代接口权限 |
| GET | /policies/current | 公开 | 当前 PRIVACY/AI_SERVICE 文本、版本、noticeHash |
| GET | /me/consents | 登录 | 各 purpose 最新状态和版本 |
| POST | /me/consents | 登录 | purpose、policyVersion、noticeHash、action；追加授权事件 |
| GET | /hospital | 公开 | 医院名称、地址、营业时间、电话；由部署配置提供，无需首版新增医院表 |

TokenBundle：`{accessToken,expiresIn,sessionId,user}`，MINI 另含 refreshToken，Web 不在 JSON 返回刷新令牌。成功登录追加 AUTH_LOGIN 审计事件，供历史登录口径活跃人数统计；失败仅记录必要安全事件。密码强度、账号登录限流在服务端统一处理，不把校验仅留在前端。

## 3 用户宠物与健康日志 F02/F03/F04

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET/POST | /pets | 用户 | 列表 status；创建 PetInput，后端绑定当前 owner |
| GET/PATCH | /pets/{petId} | 主人 | PetDetail / PetInput+version；不修改临床字段 |
| POST | /pets/{petId}/archive | 主人 | version；无未终结预约、处理中会话才允许 |
| GET/POST | /pets/{petId}/health-logs | 主人 | 时间范围分页 / HealthLogInput |
| GET/PATCH | /pets/{petId}/health-logs/{logId} | 主人 | 日志详情 / HealthLogInput+version；校验 log.petId |
| GET | /pets/{petId}/timeline | 主人 | 按时间返回 LOG/MEDICAL/AI/APPOINTMENT 摘要；分类过滤 |
| GET | /pets/{petId}/medical-records | 主人 | 只返回 SUBMITTED；不泄露草稿 |
| GET | /medical-records/{recordId} | 主人或授权医生/审计管理员 | 当前可见正式版本；医生本人可见草稿 |
| GET | /medical-records/{recordId}/versions | 同上 | 修订号、原因、医生、时间、前后差异；不返回与权限无关资料 |

PetInput：`{name,speciesCode,breed?,sex,birthDate?,neutered,avatarFileId?,ownerHealthNotes?}`。

HealthLogInput：`{observedAt,weightKg?,dietNote?,stoolNote?,activityNote?,symptomNote?,attachmentIds:[]}`。至少一项记录有值；附件 ID 须预先上传且可绑定。PetDetail 返回 `ownerReported` 与带来源日期的 `clinicalSummary`，两者不合并为无来源结论。

## 4 医生、时段与预约 F10/F11/F12

### 4.1 用户查询与提交

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET | /doctors | 用户 | 启用医生摘要，支持 specialty；不含证件附件和手机号 |
| GET | /doctors/{doctorId} | 用户 | 公开个人介绍 |
| GET | /doctors/{doctorId}/slots | 用户 | fromDate/toDate，最大 14 天；返回未来时段及剩余容量 |
| GET/POST | /appointments | 用户 | 本人预约分页 / CreateAppointment |
| GET | /appointments/{appointmentId} | 本人 | AppointmentDetail |
| POST | /appointments/{appointmentId}/cancel | 本人 | version、reason；仅开始前的 PENDING/CONFIRMED |
| POST | /appointments/{appointmentId}/change-requests | 本人 | version、newSlotId、reason |
| GET | /appointments/{appointmentId}/change-requests | 本人 | 改期历史与处理结果 |
| POST | /change-requests/{changeRequestId}/accept | 另一业务方 | version；按申请归属允许 MINI 主人或 WEB 对应医生 |
| POST | /change-requests/{changeRequestId}/reject | 另一业务方 | version、reason |
| POST | /change-requests/{changeRequestId}/withdraw | 提出者 | version；只能 OPEN |

“另一业务方”表示用户提出需医生接受，医生提出需用户接受，不能自提自批。医生发起与查看申请使用下节 staff 路径，回应共用端点仍校验 WEB audience。

CreateAppointment：

```json
{
  "petId": "101",
  "doctorId": "201",
  "slotId": "301",
  "chiefComplaint": "猫咪近两天食欲下降",
  "aiSessionId": "401"
}
```

响应 201：

```json
{
  "code": "OK",
  "message": "预约已提交，等待医生确认",
  "data": {
    "id": "501",
    "appointmentNo": "APT202610230001",
    "status": "PENDING",
    "scheduledStartAt": "2026-10-23T09:00:00+08:00",
    "scheduledEndAt": "2026-10-23T09:30:00+08:00",
    "confirmDeadlineAt": "2026-10-23T09:00:00+08:00",
    "version": 0
  },
  "traceId": "trace-example"
}
```

doctorId 与 slot 所属不一致返回 400；满员 409。aiSessionId 可省略，存在时必须同用户、同宠物，且只关联这一会话供医生接诊参考。待确认只占一次名额。

SlotView：`{id,doctorId,startAt,endAt,capacity,availableCount,status}`。前端显示余量仅供参考，提交时后端再次验证。AppointmentDetail 返回当前状态、医生、宠物、时间、确认/开始/完成时间、取消原因、关联会话、version、允许动作 allowedActions；allowedActions 为 UI 提示，后端仍重新校验。

### 4.2 医生及管理员预约操作

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET | /staff/appointments | 医生本人/管理员全院 | status、date、petId；分页脱敏摘要 |
| GET | /staff/appointments/{appointmentId} | 对应医生/管理员 | 待确认仅主诉和必要预约信息；确认后按授权开放档案 |
| POST | /staff/appointments/{appointmentId}/confirm | 对应医生 | version；校验未超时 |
| POST | /staff/appointments/{appointmentId}/reject | 对应医生 | version、reason；仅 PENDING |
| POST | /staff/appointments/{appointmentId}/cancel | 对应医生/管理员 | version、reason；仅 PENDING/CONFIRMED，记录医院取消方 |
| POST | /staff/appointments/{appointmentId}/start | 对应医生 | version；仅 CONFIRMED，按时间窗口校验 |
| POST | /staff/appointments/{appointmentId}/complete | 对应医生 | version、recordVersion、medicalRecord、attachmentIds；同事务提交病历 |
| POST | /staff/appointments/{appointmentId}/no-show | 对应医生 | version、reason；仅 CONFIRMED 且超过配置宽限 |
| GET | /staff/appointments/{appointmentId}/status-logs | 对应医生/管理员 | 操作轨迹；用户详情可只展示简化进度 |
| GET/POST | /staff/appointments/{appointmentId}/change-requests | 对应医生；管理员只读 | version、newSlotId、reason；不得跨医生改期 |
| GET/PUT | /staff/appointments/{appointmentId}/medical-draft | 对应医生 | 草稿 / version、medicalRecord、attachmentIds；仅诊疗中 |
| POST | /staff/medical-records/{recordId}/revisions | 原署名医生 | version、reason、medicalRecord、attachmentIds；提交新正式版本 |

MedicalRecordInput：`{chiefComplaint,examination,diagnosis,treatment,medications:[],allergies:[],chronicConditions:[],followupAdvice,followupAt?}`；主诉、检查、诊断、治疗/建议必填，medications 可空。complete 初次无草稿时 recordVersion=0；已有草稿必须匹配 version。响应 `{appointment,medicalRecordId,revisionNo}`。没有单独“提交正式病历却保持诊疗中”的接口，避免与完成动作分离。

### 4.3 排班与医生工作台 F10/F13

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET | /staff/dashboard | 医生 | 本人今日接诊、待处理、近期完成摘要 |
| GET/PATCH | /staff/doctor-profile | 医生 | 本人公开介绍/专长、version；资格启用由管理员管理 |
| GET | /staff/slots | 医生本人/管理员全院 | doctorId、fromDate、toDate |
| POST | /staff/slots | 医生本人/管理员 | doctorId、date、startAt、endAt、capacity；逐时段建立 |
| PATCH | /staff/slots/{slotId} | 对应医生/管理员 | version、capacity；有占用不能改时间，容量不能小于占用 |
| POST | /staff/slots/{slotId}/close | 对应医生/管理员 | version、reason；仅关闭新预约入口 |
| POST | /staff/slots/{slotId}/open | 对应医生/管理员 | version；未来有效时段 |
| GET | /staff/pets | 医生 | 仅授权范围内宠物分页，不返回全院所有主人 |
| GET | /staff/pets/{petId} | 授权医生 | 必要宠物资料及临床摘要 |
| GET | /staff/pets/{petId}/health-logs | 授权医生 | 自述日志分页 |
| GET | /staff/pets/{petId}/medical-records | 授权医生 | 已提交的历史病历 |

要调整已有时段的医生或起止时间：新建时段，逐单改期/取消后关闭旧时段。首版不提供批量无审计迁移。

## 5 AI 问诊与医生协同 F05/F06

首版采用异步提交加轮询，避免小程序网络超时造成重复外呼；不要求首版实现 SSE。服务端从 MySQL QUEUED 消息领取任务，进程重启后可恢复。

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET/POST | /ai/sessions | 用户 | 本人会话列表 / petId、title?，返回 SessionView |
| GET | /ai/sessions/{sessionId} | 主人 | 会话、摘要更新时间、风险摘要；不返回内部 system prompt |
| POST | /ai/sessions/{sessionId}/close | 主人 | version；未结束生成不能关闭 |
| POST | /ai/sessions/{sessionId}/messages | 主人且 AI 授权有效 | MessageInput；202 返回用户消息及状态；规则急症同步处理也返回同一结构 |
| GET | /ai/sessions/{sessionId}/messages | 主人 | afterSeq、limit≤100，按 seq 升序；返回消息及各自状态 |
| GET | /ai/messages/{messageId} | 主人 | 当前状态、errorCode、reply、references |
| POST | /ai/messages/{messageId}/retry | 主人且 AI 授权有效 | version；仅 FAILED/FAILED_UNKNOWN 用户轮；显式启动新 generationNo |
| GET | /ai/messages/{messageId}/references | 主人 | 该 AI 回答的引用快照 |
| GET | /staff/appointments/{appointmentId}/ai-session | 对应且授权医生 | 只读该单关联会话；不开放整个用户聊天列表 |

MessageInput：

```json
{
  "clientMessageId": "client-generated-unique-id",
  "content": "猫咪食欲下降，今天没有呕吐，也没有抽搐。",
  "screeningAnswers": {
    "consciousness": "RESPONSIVE",
    "breathingDifficulty": "NO",
    "uncontrolledBleeding": "NO",
    "suspectedPoisoning": "UNKNOWN"
  }
}
```

content 长度 1～4000 字；首轮基础问卷可随消息提交，未知允许 UNKNOWN，不强迫用户判断医学指标。问卷和原文矛盾时按疑似高风险处理或追问；明确高危不等待问卷全部填写。petId 固定取 session，不能每条消息更换宠物。

响应 202 的 data：`{userMessageId,processingStatus:"QUEUED",generationNo:0,pollAfterMs:1000}`。客户端首版建议 1～3 秒轮询，离开页面后可重新拉取，不自动调用 retry。若已同步急症响应，则 processingStatus=COMPLETED 并附 reply。

最终 reply 示例：

```json
{
  "id": "602",
  "role": "ASSISTANT",
  "source": "MODEL",
  "riskLevel": "UNKNOWN",
  "content": "目前信息不足，建议补充精神状态、饮水情况以及症状持续时间。",
  "answer": {
    "riskReasons": ["目前无法判断症状严重程度"],
    "possibleCauses": [],
    "followupQuestions": ["精神和饮水情况如何？"],
    "careAdvice": [],
    "avoidActions": ["不要自行给宠物服用人用药物"],
    "appointmentRecommended": true
  },
  "references": [],
  "disclaimer": "AI 生成可能有误，请以线下诊断为主"
}
```

`content` 为后端组合后的可见正文；所有建议含固定 disclaimer，前端必须展示，导出/历史也保留。RULE 回复使用相同 DTO，显示 EMERGENCY、fixedAdvice、医院联系方式；不让模型降级。模型 JSON 先做 Schema/枚举/长度和引用校验，再对外返回。结构不合格返回受控 FALLBACK 或 FAILED，绝不把原始未校验 JSON 直接展示。

失败状态带安全 errorCode 和 userMessage，供应商是否完成未知时 FAILED_UNKNOWN，明确提示“结果未能保存，重新生成可能再次产生模型调用”。已经成功的 userMessage 不能重试覆盖；用户要新建议发送新消息。调用次数/用量只后台展示，用户流程不暴露 API Key、Embedding 或索引配置。

## 6 RAG 知识管理与分诊规则 F07/F06

### 6.1 知识版本与发布

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET/POST | /staff/knowledge/documents | 医生只读/管理员编辑 | 列表 / title、speciesCode、categoryCode、tags |
| GET/PATCH | /staff/knowledge/documents/{documentId} | 医生只读/管理员编辑 | 元数据、version；曾发布文档的物种/分类不可改，需新建文档重新审核 |
| GET/POST | /staff/knowledge/documents/{documentId}/versions | 医生只读/管理员编辑 | 版本列表 / content、sourceTitle、sourceUrl?、sourceFileId? |
| GET/PATCH | /staff/knowledge/versions/{knowledgeVersionId} | 医生只读/管理员编辑 | 详情 / 仅 DRAFT 或 REJECTED 内容编辑+version |
| POST | /staff/knowledge/versions/{knowledgeVersionId}/submit-review | 管理员 | version；有正文与可核查来源 |
| POST | /staff/knowledge/versions/{knowledgeVersionId}/review | 医生 | version、decision=APPROVE/REJECT、reviewNote；审核者有执业资料 |
| POST | /staff/knowledge/versions/{knowledgeVersionId}/index-jobs | 管理员 | version、embeddingProfile；仅 APPROVED；202 返回 jobId |
| GET | /staff/knowledge/index-jobs/{jobId} | 医生/管理员 | status、expectedChunks、indexedChunks、errorCode |
| POST | /staff/knowledge/documents/{documentId}/publish | 管理员 | version、knowledgeVersionId；已审核且当前 profile 索引 READY |
| POST | /staff/knowledge/documents/{documentId}/offline | 管理员 | version、reason；立即禁止新检索 |
| POST | /staff/knowledge/search-preview | 医生/管理员 | query、speciesCode、topK≤10；返回真实检索候选，不调用生成模型 |

首版管理员录入/粘贴知识正文，PDF 等可作为来源附件；不承诺自动 OCR 或任意文档解析。审核通过正文冻结，编辑必须新增版本。索引任务异步，失败时重新提交新 job；同一版本/profile 已有执行中任务返回其 ID，不创建并行重复任务。

发布时 MySQL 指针原子切换，旧版本继续保留供引用审计。模型 profile 迁移是后台配置工作，需要全部重建、校验后切换，不通过普通用户参数选择模型。检索分数只表示相关性，不作为医学可信度或急症排除概率。

### 6.2 分诊规则

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET/POST | /staff/triage-rules | 医生只读/管理员编辑 | 筛选、规则版本 / code、speciesCode、predicate、fixedAdvice、sourceUrl |
| GET/PATCH | /staff/triage-rules/{ruleId} | 医生只读/管理员编辑 | 草稿详情 / 仅未批准版本可编辑+version |
| POST | /staff/triage-rules/{ruleId}/submit-review | 管理员 | version |
| POST | /staff/triage-rules/{ruleId}/review | 医生 | decision、reviewNote、version |
| POST | /staff/triage-rules/{ruleId}/enable | 管理员 | version；仅 APPROVED，同 code 旧版本同时停用 |
| POST | /staff/triage-rules/{ruleId}/disable | 管理员 | version、reason |
| POST | /staff/triage-rules/test | 医生/管理员 | speciesCode、content、screeningAnswers；返回命中证据，不保存为真实问诊 |

规则 schema 在后端限制运算符、物种与字段，无任意脚本执行。规则测试至少覆盖肯定、否定、假设、历史已结束、当前发生和未知情境。

## 7 健康指南与标准 F08/F09

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET | /health/guides | 用户 | published 列表，speciesCode/categoryCode/keyword |
| GET | /health/guides/{guideId} | 用户 | 已发布正文；按日去重记录浏览 |
| GET | /health/standards | 用户 | speciesCode/ageMonths/metricCode；只返回已发布适用标准 |
| GET/POST | /staff/health/guides | 医生只读/管理员编辑 | 列表 / title、body、speciesCode、categoryCode、sourceNote |
| GET/PATCH | /staff/health/guides/{guideId} | 医生只读/管理员编辑 | 详情 / GuideInput+version |
| POST | /staff/health/guides/{guideId}/publish | 管理员 | version |
| POST | /staff/health/guides/{guideId}/offline | 管理员 | version、reason |
| GET/POST | /staff/health/standards | 医生/管理员只读；医生创建 | StandardInput，服务端关联 editorDoctorId |
| GET/PATCH | /staff/health/standards/{standardId} | 医生/管理员只读；医生编辑 | StandardInput+version，变更审计且状态转 DRAFT 等管理员重新发布 |
| POST | /staff/health/standards/{standardId}/publish | 管理员 | version；正文由医生维护 |
| POST | /staff/health/standards/{standardId}/offline | 管理员 | version、reason |

StandardInput 对应 name/speciesCode/ageMinMonths?/ageMaxMonths?/metricCode/unit?/referenceMin?/referenceMax?/description/conditionsNote/sourceNote。参考区间不驱动自动确诊。

## 8 文件与站内消息 F15/F16

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| POST | /files | 登录且有 purpose 对应上传权限 | multipart file/purpose；校验后 201 返回 FileView |
| GET | /files/{fileId} | 上传者或业务授权者 | 元数据、校验状态，不返回 objectKey |
| POST | /files/{fileId}/download-ticket | 业务授权者 | businessType/businessId；返回短时 URL 和 expiresAt，记访问审计 |
| POST | /files/{fileId}/discard | 上传者 | 仅未绑定文件；转 DELETED 并异步清理物理内容 |
| GET | /notices | 本人 | unread、分页；仅脱敏提醒 |
| POST | /notices/{noticeId}/read | 本人 | 标记已读，重复调用无副作用 |

FileView：`{id,originalName,mimeType,sizeBytes,purpose,status}`。默认 JPEG/PNG ≤5MB，临床与知识来源 PDF ≤10MB；禁止 SVG、可执行文件和压缩包。实际类型校验、图片重编码去除 EXIF、PDF 隔离处理后才能 READY；没有接入完整扫描服务时不得声称文件绝对安全。

附件绑定通过业务 DTO 的 attachmentIds 在业务事务中完成，不提供可任意指向 businessId 的公共绑定接口。病历/知识历史版本附件不可直接解绑。下载必须存在指定业务绑定并通过该业务对象权限；未绑定上传者可预览本人文件。票据默认 60 秒，私有存储无公开读权限，短时 URL 也不应写入普通日志。

## 9 管理后台 F14/F17

| 方法 | 路径 | 权限 | 请求/响应要点 |
|---|---|---|---|
| GET/POST | /admin/users | 管理员 | 用户列表 / 创建后台账号 username、初始密码、角色；不返回 hash |
| GET/PATCH | /admin/users/{userId} | 管理员 | 必要账号资料+version；不接收临床内容 |
| POST | /admin/users/{userId}/disable | 管理员 | version、reason；撤销会话，有未来预约返回冲突并列待处理数量 |
| POST | /admin/users/{userId}/enable | 管理员 | version |
| POST | /admin/users/{userId}/reset-password | 管理员 | version、新临时密码、reason；审计不记录密码 |
| GET/PUT | /admin/users/{userId}/roles | 管理员 | roleIds+userVersion；角色移除若有预约需先处理；撤销会话 |
| GET/POST | /admin/doctors | 管理员 | 医生资料列表 / 为具有 DOCTOR 角色的账号建 doctor_profile |
| PATCH | /admin/doctors/{doctorId} | 管理员 | title、资质附件、version；停用走专门动作 |
| POST | /admin/doctors/{doctorId}/disable | 管理员 | version、reason；先关闭新预约入口，未终结预约须先处理 |
| POST | /admin/doctors/{doctorId}/enable | 管理员 | version；账号和角色均启用 |
| GET/POST | /admin/roles | 管理员 | 列表 / code、name；不允许覆盖内置 code |
| PATCH | /admin/roles/{roleId} | 管理员 | name、version；停用角色前处理关联账号 |
| GET/PUT | /admin/roles/{roleId}/menus | 管理员 | menuIds+roleVersion；立即撤销受影响会话 |
| GET/POST | /admin/menus | 管理员 | 树 / parentId、name、kind、routePath、permissionCode、sortNo |
| PATCH | /admin/menus/{menuId} | 管理员 | 展示字段+version；permissionCode 不允许绑定不存在的后端能力 |
| GET | /admin/pets | 管理员 | 全院宠物摘要，敏感字段脱敏 |
| GET/PATCH | /admin/pets/{petId} | 具纠错权限管理员 | 基础资料纠错+version+reason；不能改 owner/诊断 |
| GET | /admin/pets/{petId}/health-logs | 具审计权限管理员 | 自述日志分页，只读 |
| GET | /admin/ai/sessions | 具问诊审计权限管理员 | 专门授权查看，普通管理员无此默认权限 |
| GET | /admin/ai/sessions/{sessionId} | 同上 | 消息和去标识调用摘要；读取也审计 |
| GET | /admin/audit-logs | 具审计权限管理员 | actor/resource/action/time 过滤；只读分页 |
| GET | /admin/ai/call-logs | 具模型运维权限管理员 | 仅模型、状态、用量、耗时、errorCode；上下文快照需额外审计权限 |

sys_user、sys_role、sys_menu 的 version 必须纳入数据库字段（见数据库设计）。首版不开放删除角色/菜单/账号的通用硬删除接口，不允许停用或移除最后一个有效管理员。涉及医生停用先关闭其未来时段，逐单取消/改期后再停用；以上动作用现有时段和预约接口完成。

## 10 报表 F18

| 方法 | 路径 | 权限 | 返回数据 |
|---|---|---|---|
| GET | /admin/reports/overview | 管理员 | 用户、宠物、新建预约、当前待处理、AI 概览 |
| GET | /admin/reports/appointments | 管理员 | createdCohort、scheduledCohort、状态分布、趋势与分母 |
| GET | /admin/reports/ai | 管理员 | 会话/人数/轮数、急症提示、成熟 7 日转化批次 |
| GET | /admin/reports/doctors | 管理员 | 每医生完成数、待处理数、平均确认耗时 |
| GET | /admin/reports/pets | 管理员 | 物种、年龄、规范症状分布；未知单列 |
| GET | /admin/reports/content | 管理员 | 指南按日去重浏览与排行 |
| GET | /admin/reports/model-usage | 模型运维权限管理员 | 调用成功率、时延 P50/P95、Token；按模型/调用类型区分 |
| GET | /staff/reports/workload | 医生 | 本人完成数和待处理量，不接受 doctorId 越权切换 |

共同参数 `from,to,granularity=DAY|WEEK|MONTH`，最大区间 366 天；排行榜 limit≤20。响应含 timezone、from、to、generatedAt、cohortBasis、numerator、denominator 和 value；分母为零时 value=null，不能返回 NaN 或伪造 0%。医生个人接口不返回其他医生数据。

## 11 调用顺序与实现分层

1. 账号登录 → `/me` 与 `/me/consents` → 创建宠物及日志。
2. 创建 AI 会话 → 提交消息 → 后端规则初筛 → RAG 检索/DeepSeek → 客户端轮询，展示引用和固定提示。
3. 查医生和时段 → 用户提交预约 → 医生确认 → 双方通知；需改期时走独立申请。
4. 医生开始 → 保存病历草稿 → complete 同事务提交临床初版 → 用户查看病历；修订另走 revisions。
5. 管理员知识录入 → 医生审核 → 索引任务完成 → 管理员发布 → 新问诊才可检索。

Controller 负责 DTO/身份入口，Service 负责状态机与对象级权限，Repository 负责条件更新与锁，AI/Embedding/向量存储/文件各用独立 Adapter。首版持久任务用 MySQL 领取与 lease，无需额外引入消息队列；运行时密钥来自服务端环境配置。

前两份：[功能清单](01-functional-spec.md)、[数据库设计](02-database-design.md)。
