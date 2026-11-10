# 实施工具链记录

2026-10-28：实际本地编译使用 JetBrains JBR 21.0.6（IDE随附），没有修改全局JAVA_HOME/PATH。部署目标仍为Temurin21。Maven3.9.9已验证，Wrapper属性固定3.9.9。

Node实际可用24.22.1，比设计24.22.0高一个补丁；pnpm使用10.20.0。npm直接依赖均在package.json固定，传递依赖见web/pnpm-lock.yaml。

后端Boot3.5.16、springdoc2.10.0已下载并编译。

Python运行时固定为官方`python:3.11.14-slim-bookworm`，本机未安装3.11时由环境检查脚本使用固定digest容器执行，绝不覆盖系统PATH。2026-11-02已真实拉取并运行，镜像digest为`sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d`。

Embedding服务的直接Python依赖固定如下。M11-03已生成并实装 `embedding/requirements.lock`，包含 Linux x86_64 CPython 3.11 的 55 个包及分发文件哈希。

| 依赖 | 固定版本 | 核验 |
|---|---|---|
| Python | 3.11.14 | 固定官方容器真实运行 |
| FastAPI | 0.162.0 | PyPI发布元数据，`Requires-Python >=3.10` |
| Uvicorn | 0.54.0 | PyPI发布元数据 |
| Sentence Transformers | 6.2.0 | PyPI发布元数据，`Requires-Python >=3.10`、`torch>=2.2`、`transformers>=5,<6` |
| Transformers | 5.22.1 | PyPI发布元数据，`Requires-Python >=3.11` |
| PyTorch CPU | 2.15.0+cpu | PyTorch官方CPU索引存在cp311/manylinux x86_64 wheel，SHA256 `cee15855a32ad89a9bb27b8a9d6e07a859dcd2e11ed1b5561f3d6206e31a103b` |

Embedding模型固定为`BAAI/bge-small-zh-v1.5` revision `7999e1d3359715c523056ef9478215996d62a620`。已从该不可变revision读取公开`config.json`，确认BERT `hidden_size=512`、`max_position_embeddings=512`；M11-03必须按revision下载真实权重并验证512维归一化输出，不能使用随机向量或只凭config宣称模型已运行。

2026-11-09 M11-03 已实测同 revision 的 safetensors，SHA-256 `354763b9b1357bc9c44f62c6be2276321081ed2567773608c0d0785b61d5a026`。禁网 Python 镜像真实 CPU 推理 4/4，验证了 512 维归一化输出和 token 上限。

Spring Boot3.5.16 BOM 管理 Flyway11.7.2；M02-01使用同版本`flyway-core`与`flyway-mysql`，实际可用性以M0201IT证据为准。

M03-03新增Boot管理的`spring-boot-starter-oauth2-resource-server`3.5.16；实际解析Spring Security OAuth2 Resource Server/JOSE6.5.11及Nimbus JOSE JWT9.37.4。签发和验签均使用Nimbus，算法固定RS256，实际可用性以M0303IT证据为准。

M04-02新增AWS SDK for Java v2 `s3`与`url-connection-client` 2.57.4；版本来自AWS官方2026-10-26稳定发布，使用显式endpoint/region/path-style连接SeaweedFS，不启用AWS云服务发现或默认凭据链。真实可用性以M0402IT证据为准。

M04-03新增Apache Tika Core 4.2.0（仅做实际媒体类型探测）和Apache PDFBox 3.0.8（受限解析PDF结构）；两者均按Apache官方当前稳定发布固定。图片由JDK ImageIO解码并重编码，不把Tika/PDFBox或特征拒绝误称为完整恶意软件扫描。

Spring Boot3.5.16 BOM 管理 Testcontainers1.21.4 与 MySQL Connector/J9.7.0；M01-07 测试依赖按该BOM解析，Testcontainers镜像仍显式固定为 `mysql:8.4.13`。M0107IT已真实运行通过。

M02-05持久层直接依赖固定为MyBatis-Plus Boot3 starter3.5.18和同版本`mybatis-plus-jsqlparser`，实际解析MyBatis3.5.19、mybatis-spring3.0.6及JSqlParser5.4；未引入普通MyBatis Spring Boot starter。M0205IT已在MySQL8.4.13验证Mapper、分页、乐观锁和ASSIGN_ID。

Docker Desktop4.86.0（236216），Linux Engine29.7.2，Compose插件5.3.1，amd64；Windows完整重启后已实际连接。环境检查接受Compose插件主版本2以上，并在本文件锁定本次实测5.3.1，不依赖已过时的`docker-compose`独立程序。以下镜像均已下载并运行，RepoDigests来自本机image inspect；Python镜像于2026-11-02补充：

| 镜像 | RepoDigest |
|---|---|
| mysql:8.4.13 | mysql@sha256:a0225d1ea6631c8bc2385dc4c2faba17bbf1834ecf369e1659a9a382bd8d5dd7 |
| qdrant/qdrant:v1.20.0 | qdrant/qdrant@sha256:9bfc89f9914fc14876a88c05270685921a55b8877b254a98bcc1cc2f0a424cd2 |
| chrislusf/seaweedfs:4.51 | chrislusf/seaweedfs@sha256:f7a49e3f3972ebed5be2ed4befa4a0c68eb7bdddba6e692d42729ceb545134b8 |
| python:3.11.14-slim-bookworm | python@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d |

Testcontainers1.21.4本次使用固定辅助清理镜像 `testcontainers/ryuk:0.12.0`，本机RepoDigest为 `testcontainers/ryuk@sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298`。它只管理临时测试容器，不属于项目 `pet-hospital` 容器组。
