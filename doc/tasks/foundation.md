# M01 工程与运行环境

更新：2026-11-02。状态：实施中；任务 6/7。模块是单体项目的工作包，不代表独立微服务。

## 输入与边界

- 需求依据：[功能清单](../../docs/01-functional-spec.md)、[数据库设计](../../docs/02-database-design.md)、[接口设计](../../docs/03-api-design.md)。
- 技术组件、版本冻结、通用验收和执行提示词见[总体进度](progress.md)。
- 功能映射：工程基础，不新增业务范围。
- 主要产物：server/、web/、miniapp/ 和 infra/ 骨架。
- 表责任：无独占业务表；读取或使用既有表。
- 模块级前置：无，可从 M01-01 开始。具体任务还须满足下面列出的前置任务。

## 最小任务清单

完成表示实现、对应验证和证据均已齐备；以每项checkbox和证据为准。当前6项已验证，其余保持未完成。

### M01-01

- [x] M01-01 固定运行时与依赖清单

  - 前置任务：本模块前置满足即可。
  - 本次范围：按总体进度中的已定组件建立 tools/versions.lock.md、环境检查脚本与 .env.example；记录镜像 digest、Python/npm 精确版本、模型 revision；检查本机 Java8 与目标21的差异，不自动覆盖系统 PATH。
  - 交付产物：tools/check-env.ps1、版本锁与脱敏配置模板。
  - 验收条件：运行环境检查，未满足的工具逐项报错；任何镜像/依赖不得使用 latest 或无界版本范围；确认并记录真实可下载版本。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：[M01-01](../evidence/M01-01.md)：Java/Maven/Node/pnpm/Docker/Compose/固定Python容器全绿，缺失工具逐项失败，镜像digest、Python依赖和BGE revision已实测锁定。

### M01-02

- [x] M01-02 建立 Spring Boot 单体骨架

  - 前置任务：[M01-01](foundation.md#m01-01) 中 Java21/Maven 运行时已验证即可；不依赖尚未锁定的Python模型和容器digest。
  - 本次范围：创建 server/、Maven Wrapper、Boot3.5.16、Java21；业务包随对应实现建立，加入 Actuator 与测试 profile。
  - 交付产物：可构建后端、/actuator/health、JUnit/Failsafe 配置。
  - 验收条件：Wrapper -version 显示 Java21；打包成功；健康接口200；测试默认不依赖外部 DeepSeek。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：[M01-02](../evidence/M01-02.md)：Wrapper3.9.9/Java21；package成功；JAR启动及真实HTTP健康检查通过。

### M01-03

- [x] M01-03 启动三个有状态依赖

  - 前置任务：[M01-01](foundation.md#m01-01)中的Docker引擎、Compose、三个镜像及本地随机凭据可用；镜像digest已记录即可，不依赖Java/Python3.11模型环境全部冻结。健康脚本只使用Python标准库，已用本机3.13.7运行。
  - 本次范围：创建 infra/compose.yaml，固定 MySQL8.4.13、Qdrant1.20.0、SeaweedFS4.51；命名卷；S3启用凭据、Qdrant启用API Key，默认仅本机/内部网络可见。
  - 交付产物：Compose、健康检查和初始化脚本。
  - 验收条件：docker compose config 校验；三服务健康；匿名 S3 读和未带key向量请求失败；重启数据保留，不使用 down -v。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：[M01-03](../evidence/M01-03.md)：三服务真实读写、S3/Qdrant匿名拒绝、重启后无写入持久化复查均通过；三个镜像digest已记录。

### M01-04

- [x] M01-04 建立 Web 骨架

  - 前置任务：[M01-01](foundation.md#m01-01)。
  - 本次范围：创建 web/，Node24、pnpm10、Vue3.5、Vite8、TS5、Element Plus2、Router4、Pinia3、Axios1、ECharts6；精确锁依赖及脚本。仅要求M01-01中的Node/pnpm可用，无需等待模型环境。
  - 交付产物：Web空壳、pnpm-lock.yaml、Vitest/Playwright 基础配置。
  - 验收条件：pnpm --dir web install --frozen-lockfile、typecheck、build 成功；空壳浏览器打开；不实现业务页。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：[M01-04](../evidence/M01-04.md)：TypeScript、Vite生产构建及无头Chrome的Playwright验收通过。

### M01-05

- [ ] M01-05 建立微信小程序骨架

  - 前置任务：[M01-01](foundation.md#m01-01)。
  - 本次范围：创建 miniapp/ 原生TS+WXML+WXSS、类型定义、独立 package.json、Vitest 微信API替身；私有 AppID 配置不入库。
  - 交付产物：小程序首页与开发者工具导入说明。
  - 验收条件：类型检查通过；开发者工具可编译预览；无 AppSecret/模型Key；如无 AppID 单列手动验证受阻。
  - 验证方法：按上述验收步骤执行真实命令或开发者工具检查，将实际命令、退出码、截图/结果及阻塞原因写入证据；不能以口头确认代替。
  - 证据：实施时填写实际改动文件、执行命令/结果；推荐 `doc/evidence/M01-05.md`。目前无实现证据。

### M01-06

- [x] M01-06 统一后端基础契约

  - 前置任务：[M01-02](foundation.md#m01-02)。
  - 本次范围：实现 /api/v1、Envelope、分页、字段校验、traceId、异常映射、UTC时刻与字符串ID；JDK Clock 可注入；加入 springdoc2.10.0。；提供配置驱动的公开 GET /hospital，不新增医院表。
  - 交付产物：公共DTO、ExceptionAdvice、OpenAPI入口。
  - 验收条件：正常200、非法400和不存在404均符合契约；大整数ID不丢精度；Clock可固定，未发生依赖调用。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0106IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M01-06](../evidence/M01-06.md)：8项基础契约测试通过，含400/404/401/traceId与ID精度。

### M01-07

- [x] M01-07 建立持久任务和事务测试工具

  - 前置任务：[M01-02](foundation.md#m01-02)、[M01-03](foundation.md#m01-03)。
  - 本次范围：配置 MySQL8.4 Testcontainers、HTTP stub、事务故障注入/并发屏障夹具；定义固定的 5xx/429 测试响应；禁止以H2代替并发验收。
  - 交付产物：-Pit集成测试profile、工具测试和命令说明。
  - 验收条件：无 Docker 时明确失败/受阻；有Docker时单个IT能启动隔离数据库；真实服务与stub测试标签可区分。
  - 验证方法：`.\server\mvnw.cmd -f server/pom.xml -Pit "-Dit.test=M0107IT" verify`；本任务建立同名真实断言用例，需隔离MySQL；纯规则用例可同时运行单元测试。
  - 证据：[M01-07](../evidence/M01-07.md)：固定MySQL8.4.13 Testcontainers、HTTP stub、事务故障注入/并发屏障及M0107IT均已真实验证。

## API责任清单

以下列出此模块负责实现的后端动作；完整DTO/错误见接口设计，读写组合已展开。一个动作只设一个主责任任务。

| 方法 | 路径（基路径 /api/v1） | 责任任务 |
|---|---|---|
| GET | `/hospital` | [M01-06](foundation.md#m01-06) |

## 模块完成标准

全部任务勾选、有可复现证据、前置模块完成；相关后端动作与客户端流程真实联调通过。外部凭据/模型/微信工具等缺失时，在[总体进度](progress.md)写明阻塞，保持本模块未完成。回填时同步任务数与模块checkbox，不把本文件创建视作模块完成。

