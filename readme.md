# DocManager - 智能文档管理平台

基于 Spring Boot 3 的单体应用，提供文档管理、文件存储、AI 知识库问答与 PPT 生成等能力。

## 技术栈

| 层次 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2.0 + Spring Security + Spring AOP |
| 数据库 | MySQL + MyBatis |
| 缓存 | Redis + Caffeine（二级缓存） |
| 消息队列 | Kafka |
| 文件存储 | MinIO |
| AI | Spring AI + DashScope（向量嵌入 / Rerank） + Redis 向量索引 |
| 文档解析 | Apache POI + PDFBox |
| 前端 | Vue 3（单文件 SPA，静态资源） |

## 前置依赖

启动前需要确保以下服务已运行：

- **MySQL** — 创建数据库，执行 `src/main/resources/` 下的建表 SQL（如有）
- **Redis** — 用于缓存和向量存储
- **Kafka** — 消息队列
- **MinIO** — 对象存储

## 配置

将 `application-example.yml` 复制为 `application.yml`，填写各服务连接信息：

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

主要配置项：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/docmanager
    username: root
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092

minio:
  endpoint: http://localhost:9000
  access-key: your_access_key
  secret-key: your_secret_key

dashscope:
  api-key: your_dashscope_api_key
```

## 启动

```bash
# 构建
mvn clean package -DskipTests

# 运行
java -jar target/docAI-1.0.0.jar

# 或开发模式
mvn spring-boot:run
```

启动后访问 `http://localhost:8080` 即可打开前端页面。

API 文档（Swagger）：`http://localhost:8080/swagger-ui.html`

## 模块说明

### 用户模块

- 注册、登录、JWT 鉴权、Token 刷新
- 接口：`/api/users/**`
- 对应：`user/controller/UserController.java`

### 文件管理

- 通用文件上传/下载/删除/重命名，存储至 MinIO
- 支持图片、压缩包等非文档类型
- 接口：`/api/files/**`
- 对应：`file/controller/FileController.java`

### 文档管理

- 结构化文档（PDF / Word / PPT / CSV / Markdown）的上传与版本管理
- 支持版本历史查看、版本恢复
- 接口：`/api/documents/**`
- 对应：`doc/controller/DocumentController.java`

### RAG 知识库问答

- 上传文档后自动分块、向量化，存入 Redis 向量索引
- 支持混合检索（向量 + BM25）和 Rerank 重排序
- 用户提问后检索相关片段，拼接上下文交由大模型回答
- 接口：`/api/ai/rag/**`
- 对应：`ai/controller/RagController.java`，`ai/rag/` 目录下的核心类：
  - `DocumentChunker` — 文档分块（按标题 / 段落 / 固定长度）
  - `DocumentVectorizer` — DashScope 向量化
  - `KnowledgeBase` — 向量索引构建与检索
  - `Reranker` — DashScope Rerank 精排
  - `TextExtractor` — PDF / Word 文本提取

### PPT 生成

- 用户描述需求，AI 多轮对话确认后生成 HTML 翻页 PPT
- 基于 guizang-ppt-skill 模板系统，支持瑞士风和电子杂志风
- 生成的 HTML 文件可在线预览或下载
- 接口：`/api/ai/chat/**`
- 对应：`ai/controller/AiChatController.java`，`ai/service/AiChatService.java`

### AI Agent

- 支持 ReAct 对话式 Agent 和 Plan-Execute 计划执行 Agent
- 工具注册框架：RAG 检索、文本处理、文件管理、PPT 生成等
- 接口：`/api/ai/agent/**`
- 对应：`ai/controller/AgentController.java`，`ai/agent/` 目录下的 Agent 类

### AI Skills

- 内置技能：文档摘要、文本纠错、关键词提取
- 技能注册与执行框架，支持参数校验和失败重试
- 接口：`/api/ai/skills/**`
- 对应：`ai/controller/SkillController.java`，`ai/skills/` 目录

### AIOps 监控

- 追踪 RAG 切片数、PPT 生成数、Token 用量等指标
- AI 智能分析系统使用情况
- 接口：`/api/ai/aiops/**`
- 对应：`ai/controller/AIOpsController.java`

## 项目结构

```
src/main/java/com/javaee/docmanager/
├── ai/                    # AI 相关
│   ├── agent/             #   Agent 执行引擎
│   ├── aiops/             #   运维监控
│   ├── config/            #   AI 配置
│   ├── controller/        #   AI 控制器（RAG / Chat / Agent / Skill / AIOps）
│   ├── conversation/      #   对话上下文管理
│   ├── rag/               #   RAG 核心（分块 / 向量化 / 检索 / Rerank）
│   ├── service/           #   AI 服务层
│   └── skills/            #   技能注册与执行
├── cache/                 # 缓存（Redis + Caffeine）
├── common/                # 公共模块（配置 / 异常 / 工具类）
├── config/                # 应用配置（Kafka / Security / Caffeine）
├── doc/                   # 文档管理模块
├── file/                  # 文件管理模块
└── user/                  # 用户模块
```
