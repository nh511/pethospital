# M11 知识库、Embedding 与向量检索

更新：2026-11-10。知识文档、医生审核、本地 Embedding 和分块已验收，任务 4/10。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：F07。
- 主要产物：server/ 对应业务包及测试，另含 embedding/ 本地推理适配服务。
- 表责任：`knowledge_document`、`knowledge_version`、`knowledge_chunk`、`knowledge_index_job`。
- 模块级前置：[M04](shared-services.md)、[M06](doctors-scheduling.md)。具体任务还须满足下面列出的前置任务。

Embedding 服务只有内部推理/tokenize职责，Java负责审核、索引任务、权限和业务数据。它是本地推理适配服务，不拆业务微服务。

## 最小任务清单

每次只执行一个任务。完成表示实现、对应验证和证据均已齐备。预估一个任务控制在一次可审查改动，若实现过大，先增加子编号并同步总体进度。

### M11-01

- [x] M11-01 实现知识文档及版本CRUD

  - 前置任务：[M04-04](shared-services.md#m04-04)、[M06-01](doctors-scheduling.md#m06-01)。
  - 本次范围：实现staff/knowledge documents/versions列表详情创建编辑；来源、hash、物种/分类；曾发布标签不可原地改。
  - 交付产物：知识管理端点。
  - 验收条件：版本归属校验；批准正文冻结；无合法来源不能提交；修改另建版本；不启用任意URL抓取或OCR。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1101IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M11-01验收记录](../evidence/M11-01.md)。普通58/58、真实MySQL M1101IT 4/4、五类联合23/23，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M11-02

- [x] M11-02 实现医生审核流程

  - 前置任务：[M11-01](knowledge-rag.md#m11-01)。
  - 本次范围：submit-review/review；APPROVED才可发索引任务，驳回后修改再提审。
  - 交付产物：知识审核流程。
  - 验收条件：非医生批准403；未审核版本不能进入检索；已发布旧版在新草稿编辑时仍可服务。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1102IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据 [M11-02验收记录](../evidence/M11-02.md)。普通58/58、真实MySQL及SeaweedFS M1102IT 3/3、五类联合22/22，OpenAPI、双端类型、小程序Vitest和敏感扫描通过。

### M11-03

- [x] M11-03 建立本地 Embedding 服务

  - 前置任务：[M01-01](foundation.md#m01-01)。
  - 本次范围：Python3.11+FastAPI+Sentence Transformers，CPU缓存已锁revision的BAAI/bge-small-zh-v1.5；提供内部health、tokenize和embeddings，query/passages模式区分。
  - 交付产物：embedding/服务、锁依赖、内部鉴权和API schema。
  - 验收条件：真实输出512维且L2范数约1；查询加前缀，文档不加；模型只加载一次；超长拒绝而非静默截断；无公网暴露。
  - 验证方法：按 [Embedding 本地运行说明](../../embedding/README.md) 准备固定 Python 3.11 镜像和模型缓存，再在禁网容器中执行同名真实推理测试。
  - 证据 [M11-03验收记录](../evidence/M11-03.md)。真实 CPU 推理 4/4、回环 HTTP 鉴权、哈希依赖锁和内部 OpenAPI 核对通过。

### M11-04

- [x] M11-04 实现按token分块与profile指纹

  - 前置任务：[M11-03](knowledge-rag.md#m11-03)、[M11-02](knowledge-rag.md#m11-02)。
  - 本次范围：用同模型tokenizer分块，初始正文320 token、重叠48；边界≤510正文token，包含特殊token后≤512；profile含revision/维度/前缀/归一化/splitter。
  - 交付产物：内部token-aware分块契约、边界测试和确定性hash；业务片段持久化由M11-06承担。
  - 验收条件：长中文/英文混合/段落文本无内容丢失；超限不被截断；同版本分块不能改写；切splitter必须新版本。
  - 验证方法：按 [Embedding 本地运行说明](../../embedding/README.md) 在固定 Python 3.11 禁网容器执行 M1104，并与 M1103 联合回归。
  - 证据 [M11-04验收记录](../evidence/M11-04.md)。真实 tokenizer 与 CPU 验证 4/4，和 M11-03 联合 8/8。

### M11-05

- [ ] M11-05 实现Qdrant集合和适配器

  - 前置任务：[M01-03](foundation.md#m01-03)、[M11-03](knowledge-rag.md#m11-03)。
  - 本次范围：建立512维Cosine集合；pointId由chunkId/profile确定性UUID，payload含species/version/doc/chunk/profile；建立过滤索引。
  - 交付产物：VectorStoreAdapter及真实Qdrant IT。
  - 验收条件：同point upsert不重复；跨profile/物种过滤正确；错误维度明确失败；API Key保护。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1105IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-05.md`。目前无实现证据。

### M11-06

- [ ] M11-06 实现持久索引任务

  - 前置任务：[M11-04](knowledge-rag.md#m11-04)、[M11-05](knowledge-rag.md#m11-05)。
  - 本次范围：index-jobs POST/GET、数据库领取/lease/token、分块→批量embedding→Qdrant upsert；完整核验后READY。；在Java侧保存不可变knowledge_chunk，不由Python越权写业务MySQL。
  - 交付产物：索引worker与任务接口。
  - 验收条件：失败一批不得READY；重复key/并发只一活跃job；崩溃恢复不会覆盖新worker；Embedding调用记元数据。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1106IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-06.md`。目前无实现证据。

### M11-07

- [ ] M11-07 实现知识发布及下架

  - 前置任务：[M11-06](knowledge-rag.md#m11-06)。
  - 本次范围：document publish/offline，READY且APPROVED才能切activeVersion；MySQL作为可见性权威。
  - 交付产物：知识发布接口。
  - 验收条件：未READY拒绝；故障下旧版仍在；下架即使向量残留也不参与新检索；旧chunk不删。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1107IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-07.md`。目前无实现证据。

### M11-08

- [ ] M11-08 实现检索预览与生成检索器

  - 前置任务：[M11-07](knowledge-rag.md#m11-07)。
  - 本次范围：search-preview及共享retrieve，初始候选20补足Top5，species COMMON/profile过滤后回MySQL验证当前发布指针；分数门槛由样例校准。
  - 交付产物：RagRetriever、preview端点及候选DTO。
  - 验收条件：下架、非当前版本、未批准、维度错均剔除；不足明确返回资料不足；禁止将相似度称医学准确率。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1108IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-08.md`。目前无实现证据。

### M11-09

- [ ] M11-09 建立RAG离线效果评估集

  - 前置任务：[M11-08](knowledge-rag.md#m11-08)。
  - 本次范围：准备经来源标注的猫犬护理演示资料和至少20个问答，标相关chunk；计算Recall@5和错物种/未发布泄漏数，不外呼LLM。
  - 交付产物：doc/evidence/rag-evaluation.md和可重复脚本。
  - 验收条件：真实BGE+Qdrant跑出数据；门槛/召回未达预定目标如Recall@5≥0.8则继续调优；泄漏数必须0；不是临床效果声明。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1109IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-09.md`。目前无实现证据。

### M11-10

- [ ] M11-10 验证真实向量链路与恢复

  - 前置任务：[M11-09](knowledge-rag.md#m11-09)。
  - 本次范围：覆盖模型缓存缺失、服务断开、模型profile切换、任务部分失败后重跑；记录真实模型revision和镜像digest。
  - 交付产物：RagIntegrationIT与真实服务验收证据。
  - 验收条件：停掉网络后缓存模型仍可推理；重跑无重复point；旧引用可追溯；不能以mock向量冒充模块通过。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M1110IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M11-10.md`。目前无实现证据。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/staff/knowledge/documents` | [M11-01](knowledge-rag.md#m11-01) |
| POST | `/staff/knowledge/documents` | [M11-01](knowledge-rag.md#m11-01) |
| GET | `/staff/knowledge/documents/{documentId}` | [M11-01](knowledge-rag.md#m11-01) |
| PATCH | `/staff/knowledge/documents/{documentId}` | [M11-01](knowledge-rag.md#m11-01) |
| GET | `/staff/knowledge/documents/{documentId}/versions` | [M11-01](knowledge-rag.md#m11-01) |
| POST | `/staff/knowledge/documents/{documentId}/versions` | [M11-01](knowledge-rag.md#m11-01) |
| GET | `/staff/knowledge/versions/{knowledgeVersionId}` | [M11-01](knowledge-rag.md#m11-01) |
| PATCH | `/staff/knowledge/versions/{knowledgeVersionId}` | [M11-01](knowledge-rag.md#m11-01) |
| POST | `/staff/knowledge/versions/{knowledgeVersionId}/submit-review` | [M11-02](knowledge-rag.md#m11-02) |
| POST | `/staff/knowledge/versions/{knowledgeVersionId}/review` | [M11-02](knowledge-rag.md#m11-02) |
| POST | `/staff/knowledge/versions/{knowledgeVersionId}/index-jobs` | [M11-06](knowledge-rag.md#m11-06) |
| GET | `/staff/knowledge/index-jobs/{jobId}` | [M11-06](knowledge-rag.md#m11-06) |
| POST | `/staff/knowledge/documents/{documentId}/publish` | [M11-07](knowledge-rag.md#m11-07) |
| POST | `/staff/knowledge/documents/{documentId}/offline` | [M11-07](knowledge-rag.md#m11-07) |
| POST | `/staff/knowledge/search-preview` | [M11-08](knowledge-rag.md#m11-08) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

