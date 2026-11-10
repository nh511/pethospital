# AI 问诊宠物医院系统

已验收 73/136 个小任务，完整模块 0/16。[技术基线和总体进度](doc/tasks/progress.md)

运行时与依赖精确版本见[版本锁](tools/versions.lock.md)，环境验收见[M01-01证据](doc/evidence/M01-01.md)。`tools/check-env.ps1`支持显式传入Java、Node、pnpm和Docker路径；本机没有Python3.11时使用已锁digest的官方Python容器，不修改系统PATH。

## 运行后端

使用 Java21 设置当前终端的 JAVA_HOME，不修改系统全局环境。首次构建可运行 Maven3.9.9；Wrapper生成后使用 server/mvnw.cmd。后端默认连接 `127.0.0.1:13306/pethospital`，从进程环境读取 `MYSQL_USER`、`MYSQL_PASSWORD`；不同地址通过 `DB_URL` 覆盖。根目录 `.env` 由Docker Compose读取，直接启动Java前仍需由启动终端或部署工具把对应值注入进程环境。启动时Flyway会校验并执行未应用迁移，已应用脚本不可修改。

首次本地启动先生成不会被Git跟踪的持久RSA密钥；脚本发现任一目标文件已存在时会拒绝覆盖，也不会打印私钥：

```powershell
.\tools\generate-jwt-key.ps1
mvn -f server/pom.xml test
mvn -f server/pom.xml spring-boot:run
```

默认从项目根目录`.local/jwt-private.pem`和`.local/jwt-public.pem`加载。部署环境应把密钥置于外部secret目录，并通过`JWT_PRIVATE_KEY_LOCATION`、`JWT_PUBLIC_KEY_LOCATION`传入Spring Resource位置（如`file:E:/secrets/jwt-private.pem`）；`JWT_ALLOW_EPHEMERAL`只允许自动化测试显式开启。

验证 http://127.0.0.1:8080/actuator/health 与 /api/v1/hospital。三个M03-01认证POST、`GET/PATCH /api/v1/me`、`GET /api/v1/me/menus`、四个管理员账号查询/创建/纠错端点、十个M03-07角色/菜单/关联管理端点、三个M03-08账号生命周期端点及三个M03-09政策/授权端点已有实现；其他尚未实现的业务接口仍由认证层保护且没有占位Controller。

迁移文件在 `server/src/main/resources/db/migration/`，版本编排见该目录README。`clean`被禁用，checksum冲突必须新增前向迁移处理，不能用`repair`掩盖脚本被修改。

数据库33表的归属、主要查询索引及跨表组合校验见`doc/database-dictionary.md`。普通FK负责目标存在性和历史RESTRICT保护；宠物主人、预约医生/时段、病历/预约、知识activeVersion及多态附件等组合关系，由`CrossTableRelationGuard`在业务事务中完成相关行锁后、写入前校验。该守卫不能代替角色授权或文件下载权限检查。

### 用户注册与密码登录

已实现`POST /api/v1/auth/user/register`、`POST /api/v1/auth/user/login`和`POST /api/v1/auth/web/login`。注册只能获得USER角色；小程序密码登录要求USER，Web登录要求DOCTOR或ADMIN。密码使用BCrypt强度12，refresh只以SHA-256保存；Web refresh通过`__Host-pet_refresh`安全Cookie返回。默认5分钟内5次失败后锁定15分钟，参数可由`identity.auth.login-*`配置。

M03-03已接入Spring Security OAuth2 Resource Server/Nimbus：每个Bearer请求校验RS256签名、issuer、时间、MINI/WEB audience，并从MySQL核对session所属用户/客户端、撤销时间、会话过期、账号状态和`token_version`；roles与aud分别进入`ROLE_*`、`AUD_*`权限上下文。`/staff/**`、`/admin/**`要求WEB，当前用户业务前缀要求MINI；未登录/无效token统一401 Envelope，合法token但aud不符统一403。

M03-04仍未实现refresh轮换/重用检测、退出和改密；其他标记为PLANNED的业务接口仍无Controller，不能把“认证层已通过”误报为业务可用。注册登录验收见[M03-01证据](doc/evidence/M03-01.md)，资源服务器验收见[M03-03证据](doc/evidence/M03-03.md)。

### 本人资料与权限菜单

`GET /api/v1/me`从数据库返回当前ACTIVE角色和permission，不盲信JWT中的旧角色声明；`PATCH /api/v1/me`只接受displayName、avatarFileId、phone和version，使用version条件更新。头像必须是本人上传且`purpose=AVATAR/status=READY`，后台菜单仅允许WEB audience并只返回当前角色关联的ACTIVE菜单树。

手机号原文使用AES-256-GCM加密，数据库只保存带随机IV的认证密文和脱敏值。部署时通过secret管理器注入32字节密钥的标准Base64编码：`PROFILE_PHONE_KEY_BASE64`。该值不得提交、打印或写入数据库；缺少密钥时读取资料仍可用，但任何手机号更新会以503失败关闭。M03-05已通过普通25项、真实MySQL M0305IT 5项以及M0301IT/M0303IT安全回归，证据见[M03-05验收记录](doc/evidence/M03-05.md)。

### 管理员账号开通与资料纠错

`GET/POST /api/v1/admin/users`与`GET/PATCH /api/v1/admin/users/{userId}`同时要求WEB audience和ADMIN角色，并按查询/写入分别校验数据库 `admin:users:view/manage` 权限。创建只绑定DOCTOR或ADMIN后台角色，初始密码使用BCrypt cost 12且不进入响应/审计；资料纠错只接受username、displayName、phone和version，不接受角色、状态、密码或诊疗结论字段。列表和详情只返回脱敏手机号。M03-06证据见[M03-06验收记录](doc/evidence/M03-06.md)。

### 角色、菜单与关联管理

已实现 `/api/v1/admin/roles`、`/api/v1/admin/menus`、用户角色集合和角色菜单集合共10个操作。请求除WEB audience和ADMIN角色外，还校验数据库中的ACTIVE动作权限；集合更新执行version检查，菜单树拒绝循环，内置角色/核心权限和最后有效管理员受到保护。任何影响有效权限的变更都会在事务内递增相关用户tokenVersion并撤销旧会话。M03-07真实MySQL及回归证据见[M03-07验收记录](doc/evidence/M03-07.md)。

### 账号启停与密码重置

已实现 `POST /api/v1/admin/users/{userId}/disable`、`enable` 和 `reset-password`。三个动作要求WEB audience、ADMIN角色及数据库`admin:users:manage`权限，校验version和`Idempotency-Key`；成功后递增tokenVersion并撤销旧会话。停用账号或移除DOCTOR角色前会通过预约领域查询拒绝仍有未终结预约的操作，并在409响应中返回待处理数量；最后有效管理员不能停用。密码重置使用BCrypt，临时密码不进入响应、审计或日志。证据见[M03-08验收记录](doc/evidence/M03-08.md)。

### 政策与授权事件

已实现公开 `GET /api/v1/policies/current` 以及登录后的 `GET/POST /api/v1/me/consents`。PRIVACY和AI_SERVICE正文/版本来自服务端部署配置，`noticeHash`由服务端计算；授权记录只追加不覆盖，同一用户的`Idempotency-Key`同请求可重放、不同请求返回409。旧版本或错误hash不会落库。注册时校验当前隐私版本并同事务记录PRIVACY接受事件；`AiConsentEvaluator`只认可最新AI_SERVICE事件为ACCEPT且仍匹配当前政策，撤回不影响非AI接口。证据见[M03-09验收记录](doc/evidence/M03-09.md)。

### 统一对象授权策略

`ResourceAccessPolicy`统一判断宠物/健康日志、预约、病历/版本和AI会话的数据范围。MINI主人只访问本人资源；WEB医生在预约待确认时只能获得必要预约视图，确认后或完成30天内才能读取被分配宠物数据；超过30天仅原署名医生保留本人病历与版本权限。普通ADMIN没有临床写权限，健康/病历审计读取要求`audit:read`，AI问诊会话另需`ai-consult:audit`，两项均不默认授予。该组件只判断对象范围，业务用例仍须叠加动作权限、version、幂等和状态机。证据见[M03-10验收记录](doc/evidence/M03-10.md)。

### 医生资料管理

已实现管理员医生列表、创建、修改，以及医生本人资料查询、修改共5个端点。只有有效DOCTOR账号可创建医生资料；医生本人仅能修改公开介绍字段，管理员负责资质附件和管理字段。账号ID与医生ID分离，更新要求version并写审计，创建支持幂等重放；资质文件继续受READY、QUALIFICATION用途、上传者归属和事务绑定约束。真实MySQL/S3联合回归证据见[M06-01验收记录](doc/evidence/M06-01.md)。

MINI用户还可通过`GET /api/v1/doctors`和`GET /api/v1/doctors/{doctorId}`查询公开医生。查询同时要求资料、账号和DOCTOR角色有效，支持专长字面过滤与分页；公开摘要/详情不返回账号ID、手机号、资质附件、内部状态或version。证据见[M06-02验收记录](doc/evidence/M06-02.md)。

医生与管理员可通过`GET/POST /api/v1/staff/slots`查询和逐时段创建排班。创建先锁医生资料行，再检查全部既有时段的区间重叠；相邻边界允许，并发重叠只有一笔可提交。带偏移时间转UTC入库，`date`按Asia/Shanghai核对；医生只能操作本人，管理员可统筹。证据见[M06-03验收记录](doc/evidence/M06-03.md)。

时段容量可通过`PATCH /api/v1/staff/slots/{slotId}`调整，且不得低于真实占用；close/open使用version、幂等和审计。关闭只阻止新预约，不取消已有预约或重算占用，过去时段不得重开；医生仍只能操作本人时段。证据见[M06-04验收记录](doc/evidence/M06-04.md)。

MINI用户可通过`GET /api/v1/doctors/{doctorId}/slots`查询最多14个上海自然日内的可预约时段。接口只返回尚未开始、OPEN且有真实余量的记录，余量由服务端实时计算；跨午夜时段按上海开始日期归属，响应不暴露占用数、version和内部日期。证据见[M06-05验收记录](doc/evidence/M06-05.md)。

管理员可通过`POST /api/v1/admin/doctors/{doctorId}/disable`和`enable`管理医生资料状态。停用在锁内检查未终结预约并以409返回待处理数量，处理完成后才允许停用；启用重新校验账号和DOCTOR角色有效。停用不会暗改预约或占用，但公开入口即时隐藏，旧医生会话也无法继续获得临床对象范围或创建排班。证据见[M06-06验收记录](doc/evidence/M06-06.md)。

有效 WEB 医生可通过 `GET /api/v1/staff/dashboard` 查看本人今日接诊、待确认与近期完成摘要。通过 `GET /api/v1/staff/pets` 及其详情、健康日志端点，只读查看本人已确认、诊疗中及完成后默认 30 天内的宠物资料。待确认卡片只含必要预约字段。[M06-07验收记录](doc/evidence/M06-07.md)

### 预约创建与查询

MINI 主人通过 `POST /api/v1/appointments` 提交宠物、医生、时段和主诉，可关联本人与该宠物的 AI 会话。请求携带 `Idempotency-Key`，创建成功返回 PENDING 预约和确认截止时间。预约、占位、状态轨迹、审计和医生站内通知同事务提交。[M07-02验收记录](doc/evidence/M07-02.md)

MINI 主人可查询本人预约列表与详情。WEB 医生可查询本人预约，管理员可查询全院预约及状态轨迹。WEB 列表支持状态、宠物与医院自然日筛选，待确认视图只含必要预约信息，详情提供主诉。[M07-03验收记录](doc/evidence/M07-03.md)

对应医生可确认或拒绝待确认预约。请求携带版本和幂等键，拒绝另需原因。确认沿用原名额，拒绝释放一次，状态变更同步记录轨迹、审计和主人通知。[M07-04验收记录](doc/evidence/M07-04.md)

主人可在开始前取消本人预约，医院侧由对应医生或管理员取消。待确认预约到期由定时任务以 SYSTEM 身份取消。取消同事务释放名额、关闭 OPEN 改期并通知双方。扫描默认每30秒运行，可通过 `appointment.timeout-scan.enabled` 和 `appointment.timeout-scan.interval-ms` 配置。[M07-05验收记录](doc/evidence/M07-05.md)

对应医生可开始诊疗或人工标记爽约，两项均校验版本和允许时窗。提前开始和爽约宽限默认分别为15和30分钟。爽约保留历史名额占用。[M07-06验收记录](doc/evidence/M07-06.md)

主人和对应医生可以申请同医生的其他未来时段，并查询改期历史。每单最多一条 OPEN 申请，提交申请期间沿用原预约时间和占位。[M07-07验收记录](doc/evidence/M07-07.md)

改期申请的另一方可接受申请，事务内占用新时段、释放旧时段、更新预约快照并通知双方。[M07-08验收记录](doc/evidence/M07-08.md)

另一方可拒绝改期，发起方可撤回，定时任务负责到期处理。上述操作结束申请并释放 OPEN 标记，原预约和容量保持原值。[M07-09验收记录](doc/evidence/M07-09.md)

预约多轮竞争与五类写入故障均已在真实 MySQL 验收，覆盖容量、轨迹、通知、审计及故障后的同键重试。[M07-10验收记录](doc/evidence/M07-10.md)

### 病历草稿

对应医生可在诊疗中读取和保存病历草稿。正文和附件通过草稿版本控制和同一事务更新，主人只能从既有入口读取正式提交的病历摘要。[M08-01验收记录](doc/evidence/M08-01.md)

接诊医生可正式提交病历。病历初版、附件、预约完成状态、操作轨迹、审计和给主人发送的站内通知同事务落库。[M08-02验收记录](doc/evidence/M08-02.md)

原署名医生可修订已提交病历。修订按当前版本追加不可变快照并保留历史附件，主人可凭旧版附件绑定申请下载。[M08-03验收记录](doc/evidence/M08-03.md)

主人和有接诊授权的医生可以分页查看正式病历。详情和版本会重新核验对象授权，超期后原署名医生仍可按病历 ID 查看本人记录。[M08-04验收记录](doc/evidence/M08-04.md)

宠物归档后，主人仍可查看病历历史并下载旧版附件。停用医生的修订权限即时收回。[M08-05验收记录](doc/evidence/M08-05.md)

### 宠物资料创建、列表、详情编辑与归档

MINI用户可通过`POST/GET /api/v1/pets`创建并分页查询本人宠物，通过`GET/PATCH /api/v1/pets/{petId}`读取详情并按version编辑基础资料。创建请求不接受owner、status或version等服务端字段，物种/枚举/未来生日均严格校验；可选头像复用READY/PET附件绑定，宠物、附件和审计同事务提交。列表和详情均严格按当前主人隔离，大整数ID始终以字符串返回。详情将主人自述与临床摘要分栏，体重带来源时间，临床摘要只读取最近已提交病历而不泄漏草稿；主人不能写入医生诊断字段。`POST /api/v1/pets/{petId}/archive`先锁宠物并检查未终结预约和QUEUED/PROCESSING问诊，归档只改变状态、不删除历史；后续预约/问诊创建须复用同一ACTIVE宠物锁守卫。证据见[M05-01](doc/evidence/M05-01.md)、[M05-02](doc/evidence/M05-02.md)和[M05-03](doc/evidence/M05-03.md)。

MINI主人还可通过`POST/GET /api/v1/pets/{petId}/health-logs`新增和按观察时间半开范围稳定分页查询自述健康日志，并通过详情端点读取单条记录；`PATCH /api/v1/pets/{petId}/health-logs/{logId}`按version修订并保存改前改后审计，同版本并发仅一次成功。日志至少包含体重或一项饮食/排便/活动/症状自述，固定标识为`OWNER_REPORTED`，医生不能修改；附件必须为本人READY/HEALTH_LOG文件，可变绑定整组替换但不删除文件或临床历史引用，附件越权时内容、审计与绑定整笔回滚。归档宠物保留历史只读，但不能新增或修订日志。证据见[M05-04](doc/evidence/M05-04.md)和[M05-05验收记录](doc/evidence/M05-05.md)。

WEB管理员可通过`GET /api/v1/admin/pets`查看全院宠物脱敏摘要；详情和基础资料纠错需要独立`admin:pets:correct`权限，PATCH必须携带version与reason并记录改前改后审计，owner、状态和诊断不可修改。完整主人健康日志只允许`audit:read`权限读取且读取本身留痕，纠错权限不能替代审计权限。证据见[M05-06验收记录](doc/evidence/M05-06.md)。

MINI 主人可通过 `GET /api/v1/pets/{petId}/timeline` 查看健康日志、已提交病历、AI 会话和预约摘要。接口支持 `type` 分类过滤，并按业务时间、数值 ID 和类别降序。翻页沿用相同分类及上一页的 `nextCursor`，归档宠物历史仍可读取。[M05-07验收记录](doc/evidence/M05-07.md)

### 健康指南后台录入与编辑

医生和管理员可通过`GET /api/v1/staff/health/guides`及详情读取指南，只有管理员可创建和PATCH编辑。正文经结构白名单清洗，链接仅允许带主机的HTTPS；来源必填，封面复用READY/GUIDE文件绑定边界，创建和更新分别具备幂等、version和审计保护。真实MySQL/S3验收见[M09-01验收记录](doc/evidence/M09-01.md)。

管理员还可通过`publish`和`offline`动作发布/下架指南。发布重新校验正文、来源及当前精确绑定的READY/GUIDE封面；下架要求原因。两个动作均受version和全局幂等保护，下架后可编辑再发布，发布不会自动创建知识库或RAG索引记录。证据见[M09-02验收记录](doc/evidence/M09-02.md)。

### 急症纯规则评估器

M10-01已提供封闭的predicate JSON Schema和`TriageEvaluator`。规则只支持ALL/ANY、当前症状、筛查答案及持续分钟数的固定运算；未知字段、脚本和SQL样式内容在求值前拒绝。明确发生、否定、假设、历史已结束和未知使用三值逻辑区分，信息不足保留UNKNOWN，非猫犬物种标记覆盖限制。证据见[M10-01验收记录](doc/evidence/M10-01.md)。

后台已实现急症规则列表、详情、草稿/新修订、PATCH、提交审核和有效医生批准/驳回；正文通过封闭predicate、HTTPS来源、version、幂等和事务审计保护，APPROVED规则不可原地修改。管理员可启用/停用已批准规则，同code新修订启用会在同一事务停用旧版。管理员和医生可调用无持久化预览，复用生产评估器且不会写问诊事件、审计或报表。问诊前置`TriageService`会在DeepSeek/Qdrant之前执行同一规则链：EMERGENCY同步返回固定建议、证据、医院联系和免责声明并短路外部服务；无命中保持UNKNOWN且明确不等于排除急症。版本化回归fixture以非临床验证演示数据覆盖肯定、否定、假设、历史、未知和物种错配，风险合并策略保证模型不能降低规则风险；真实规则仍须有效医生审核后启用。问诊消息与`triage_event`的持久化仍由后续M12事务负责。证据见[M10-02](doc/evidence/M10-02.md)、[M10-03](doc/evidence/M10-03.md)、[M10-04](doc/evidence/M10-04.md)、[M10-05](doc/evidence/M10-05.md)和[M10-06](doc/evidence/M10-06.md)。

### 统一报表时间口径

M13-01提供`ReportRange`、指标DTO和安全查询窗口。请求时间使用带偏移ISO 8601并转UTC查询，响应固定以Asia/Shanghai展示；所有范围为开始含、结束不含且最长366天。预约created与scheduled批次分别映射固定数据库列，不能由请求拼接SQL列名；比例分母为0时返回null。当前尚未实现八个具体报表端点。证据见[M13-01验收记录](doc/evidence/M13-01.md)。

### DeepSeek HTTP 适配器

M12-03提供服务端`DeepSeekAdapter`，通过`/chat/completions`请求`deepseek-flash`，显式关闭thinking并要求JSON object响应。手机号、邮箱和身份证号在出站前替换；连接/读取超时、429、5xx和坏JSON映射为稳定错误，传输层不自动重发可能计费的调用；未知Token用量保持null。API Key只从`DEEPSEEK_API_KEY`读取，不进入客户端或日志。当前仅通过本机HTTP stub验收，未配置真实Key、未声称真实模型联调或医疗准确性；证据见[M12-03验收记录](doc/evidence/M12-03.md)。

### 通用审计与写幂等

M04-01提供`AuditService`、`IdempotencyService`、确定性请求哈希和审计脱敏器。用户写请求以actor+operationKey唯一约束并把method、path、规范化body纳入requestHash；首请求的业务写、资源引用和审计同事务提交，并发重放返回原资源结果，异常不留占位或业务残留。请求正文不落库，审计快照会去除密码、令牌、密钥、Cookie与私钥并掩码手机号。证据见[M04-01验收记录](doc/evidence/M04-01.md)。

### 私有 S3 存储适配器

M04-02提供基于AWS SDK for Java v2的`ObjectStorageAdapter`，只在`S3_ENABLED=true`时创建，显式使用内部endpoint、用户可达公网endpoint、region、私有bucket、静态应用凭据和path-style。上传/读取不返回对象键给客户端；下载票据固定60秒，并拒绝把非本机容器内部域名直接作为公网签名域名。部署凭据应在SeaweedFS/IAM侧限制到`pet-private`对象读写，不能授予桶管理权限。真实SeaweedFS验收见[M04-02验收记录](doc/evidence/M04-02.md)。

### 安全上传与文件元数据

M04-03提供鉴权后的`POST /api/v1/files` multipart上传和`GET /api/v1/files/{fileId}`元数据查询。服务端按实际魔数而非声明类型识别JPEG、PNG和PDF；图片限制尺寸并重编码去除EXIF/尾随数据，PDF限制大小、页数并拒绝加密、脚本、自动动作、嵌入文件等危险结构。文件元数据按`QUARANTINED`到`READY`/`REJECTED`流转，客户端响应不返回对象键；这是有限的格式与危险特征校验，不等同于完整杀毒。真实MySQL/SeaweedFS验收见[M04-03验收记录](doc/evidence/M04-03.md)。

### 业务附件绑定

M04-04提供仅供业务 Service 调用的`FileBindingService`，不开放任意绑定公共端点。服务端从业务表派生owner和操作者，要求文件为`READY`、用途匹配且来源用户有权；宠物、日志、草稿、指南、资质和头像附件按业务更新整组替换，病历版本与知识版本附件只允许追加。绑定与业务写入共享事务，回滚不会留下孤立关联。真实MySQL验收见[M04-04验收记录](doc/evidence/M04-04.md)。

### 授权下载与未绑定清理

M04-05提供`POST /api/v1/files/{fileId}/download-ticket`和`POST /api/v1/files/{fileId}/discard`。绑定文件必须提交精确业务类型/ID并重新校验当前对象范围，未绑定文件仅上传者可预览；60秒签名URL不会写入审计。丢弃仅允许本人无绑定的`READY`文件，S3删除成功后才标记`DELETED`，同一幂等键重放不会重复删除；历史附件不可丢弃。真实MySQL/SeaweedFS验收见[M04-05验收记录](doc/evidence/M04-05.md)。

### 同事务站内消息

M04-06提供`GET /api/v1/notices`和`POST /api/v1/notices/{noticeId}/read`。业务Service只能在现有事务中发布通知，业务回滚时通知同步回滚；同一收件人和稳定eventKey只产生一条消息，冲突复用会被拒绝。消息使用服务端固定模板，仅包含提醒与资源引用，不携带诊断详情；列表和已读严格限定本人，打开目标资源时仍由目标业务接口再次鉴权。真实MySQL验收见[M04-06验收记录](doc/evidence/M04-06.md)。

### 审计与模型运维查询

M04-07提供`GET /api/v1/admin/audit-logs`和`GET /api/v1/admin/ai/call-logs`。审计、模型元数据和模型输入快照分别受`audit:read`、`ai:call-logs:read`和额外`ai-consult:audit`控制，普通ADMIN没有默认读取权。查询默认7天、最长31天；审计响应不返回快照或幂等字段，模型元数据不返回供应商请求ID且默认不读取输入快照。成功读取会追加最小化访问审计，两个端点均无修改或删除方法。真实MySQL验收见[M04-07验收记录](doc/evidence/M04-07.md)。

### 导入固定演示数据

演示数据不会随应用启动自动创建。只允许对本机MySQL显式执行导入脚本；执行前在当前进程环境设置`DB_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`DEMO_PASSWORD`，并设置`DEMO_FIXTURE_CONFIRM=REBUILD_LOCAL_DEMO`。脚本拒绝`prod`/`production` profile和非localhost地址，不打印密码：

```powershell
.\tools\import-demo.ps1 -JavaHome $env:JAVA_HOME
```

命令先执行 Flyway 校验和迁移，再在一个事务内重建固定保留 ID 的演示数据。数据包括两名用户、两名医生、一名管理员、宠物、健康日志、时段、预约、AI 会话、健康内容，以及一份带初版和修订快照的正式病历。重复执行按固定 ID 替换演示行，密码由本次 `DEMO_PASSWORD` 生成新的 BCrypt 哈希。人物、宠物和内容均为虚构。

### 本地 Embedding

固定版本 BGE 模型提供 CPU 分词和 512 维归一化向量。依赖、权重预下载、回环运行及离线测试命令见 [Embedding 本地运行说明](embedding/README.md)。

## OpenAPI 契约

`doc/openapi.yaml` 冻结详细设计中的 158 个唯一方法与路径，采用 JSON 兼容的 YAML 1.2 语法。`doc/api-coverage.md` 登记主责任任务和实现状态，当前 130 项为 `IMPLEMENTED`，28 项为 `PLANNED`。任务验收状态和验证证据另见总体进度。

使用Node24确定性生成或校验契约与两端类型，不需要联网：

```powershell
node tools/openapi-contract.mjs generate
node tools/openapi-contract.mjs check
pnpm --dir web contract:check
pnpm --dir miniapp contract:check
```

生成类型分别位于`web/src/generated/api-types.ts`和`miniapp/generated/api-types.ts`，禁止手工修改。校验会同时检查158项设计与任务责任清单精确一致、本地引用、示例、字符串ID、鉴权、写操作幂等键、version和统一错误响应；业务实现新增或调整接口时必须同步详细设计、责任清单并重新生成。

## Web

在 Node24 环境安装 web/ 依赖，运行 pnpm --dir web dev。开发端口默认5173，API代理到8080。

## 小程序

微信开发者工具导入 `miniapp/`，项目类型选择“小程序”，根目录保持当前目录。仓库中的 `touristappid` 仅供骨架预览；真实 AppID 应写入开发者工具生成且不提交的私有项目配置，真机登录必须使用真实 AppID。AppSecret 只置于后端环境变量，绝不能写入小程序源码或项目配置。可注入的微信API边界位于 `miniapp/platform/wechat.ts`，本地Vitest使用替身验证临时code和请求包装，不伪造微信服务端换码成功。

## 本地依赖

先生成本地随机凭据（已有 .env 时拒绝覆盖），再启动三个服务：

```powershell
.\tools\init-env.ps1
docker compose --env-file .env -f infra/compose.yaml up -d
python tools/check-infra.py --seed
docker compose --env-file .env -f infra/compose.yaml restart
python tools/check-infra.py
```

检查脚本只需Python标准库；`--docker`可指定Docker可执行文件的绝对路径。首次`--seed`会在独立infra_probe数据库、向量集合和S3测试对象中留下标记，重启后的检查不再写入，用于证明数据保留。验证向量使用固定512维探针，仅测试存储，不能作为真实Embedding验收。

MySQL使用13306避免覆盖本机3306，Qdrant6333、S3端点8333，均绑定127.0.0.1。数据使用命名卷，不执行 down -v。.env不要提交或分享；AI/微信真实凭据仍待单独配置。SeaweedFS通过环境变量启用S3凭据并预创建私有桶，依据[官方mini配置说明](https://github.com/seaweedfs/seaweedfs/wiki/Quick-Start-with-weed-mini)。

## 后端测试工具

默认 `test` 运行纯单元/契约测试及本机 HTTP stub，不需要 Docker。stub 测试使用 JUnit 标签 `http-stub`；以后需要真实外部凭据的测试使用 `real-service`，不得把 stub 结果记作真实服务通过。

M01-07 数据库集成测试使用 Testcontainers 启动隔离的 `mysql:8.4.13`，测试表只存在于临时容器。没有可访问的 Docker 引擎时命令会明确失败，不回退到 H2，也不跳过：

```powershell
& .\tools\run-maven.ps1 -JavaHome $env:JAVA_HOME -MavenArgs @('-o','test')
& .\tools\run-maven.ps1 -JavaHome $env:JAVA_HOME -MavenArgs @('-Pit','-Dit.test=M0107IT','verify')
```

可用 `-Dgroups=http-stub` 或 `-Dgroups=mysql-it` 按标签筛选；`M0107IT` 验证真实 InnoDB 回滚、并发任务唯一领取及 worker token/generation fencing。

