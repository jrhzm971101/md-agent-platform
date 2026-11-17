# 控制器归一化到 bootstrap + 项目结构审查（2026-09-21）

## 1. 结论

全项目 **3 个控制器 + 1 个控制器测试**已全部落在 `md-agent-bootstrap`，`md-agent-server`
主代码里不再出现任何 Web 注解（已用 rg 全量验证，`RestController`/`Controller`/
`ControllerAdvice`/`WebFilter`/`RequestMapping` 在 `md-agent-server/src` 下命中数为 0）。

依赖方向仍是 **bootstrap → server → model** 单向：搬运的只有「HTTP 外壳」，
业务对象（`AgentManager`、`AgentTraceRegistry` 等）一行未动，因此没有出现倒置。

## 2. 搬迁清单（前 → 后）

| 旧位置 | 新位置 |
|---|---|
| `md-agent-server/src/main/java/com/md/agentplatform/server/agent/AgentController.java` | `md-agent-bootstrap/src/main/java/com/md/agentplatform/controller/agent/AgentController.java` |
| `md-agent-server/src/main/java/com/md/agentplatform/server/trace/AgentTraceController.java` | `md-agent-bootstrap/src/main/java/com/md/agentplatform/controller/trace/AgentTraceController.java` |
| `md-agent-bootstrap/src/main/java/com/md/agentplatform/controller/TestController.java` | `md-agent-bootstrap/src/main/java/com/md/agentplatform/controller/test/TestController.java` |
| `md-agent-server/src/test/java/com/md/agentplatform/server/trace/AgentTraceControllerTest.java` | `md-agent-bootstrap/src/test/java/com/md/agentplatform/controller/trace/AgentTraceControllerTest.java` |

### 为什么格式上做了这些处理

- **包名随模块走，不留旧包**：搬走后 `package` 行改成新包（`controller.agent` /
  `controller.trace` / `controller.test`）。旧的 `server.agent` / `server.trace` 包名保留会在
  「类在哪」这件事上自相矛盾，review 时无法一眼判断。
- **补显式 import 而不是留着同包**：这些控制器原本靠「和被引用类同包」省掉 import。
  跨模块后必须写全 `com.md.agentplatform.server.*`，顺带让「控制器依赖 server 的哪些类」
  在文件头部一眼可见 —— 这正是搬迁想暴露的信息。
- **`TestController` 一并归入子包**：`controller` 根包下若只裸放这一个类，
  和旁边新出现的 `agent/`、`trace/` 不成体系；归入 `controller/test` 与类名一致，
  且它没有任何外部引用、URL 映射不变，零风险。
- **测试跟着被测类走**：契约测试留在 bootstrap，改控制器却忘改测试时在模块边界上直接可见。
- **`spring-boot-starter-test` 无需新增声明**：根 pom 的公共 `<dependencies>` 已含它，
  三个模块都继承，所以测试搬到 bootstrap 不需要动任何 pom。

## 3. 结构审查结果

### 3.1 判定为合理、维持现状

- **三层模块职责清晰**：`md-agent-model`（纯 DTO）、`md-agent-server`（领域与业务组件）、
  `md-agent-bootstrap`（启动 + HTTP 边界）。依赖单向无环。
- **`md-agent-model` 不是空转模块**：被 server 引用 27 处（req/rsp/feishu/agent.tool 等），有存在价值。
- **测试目录与主代码严格同构**：server 侧 24 个测试文件与包路径一一对应。
- **`certs/` 放在 resources 是正确的**：`application.yml:8` 用 `classpath:certs/http_ca.crt` 读取，属真正的 classpath 资源。
- **`server/service` + `service/impl`、`messaging` 分子包、`tool` 下 12 个工具子包**：分类合理。

### 3.2 判定为不合理、**本次未动**（等拍板）

1. **`server/tools/` 与 `server/tool/` 并存** —— 真实的结构瑕疵。
   `tools/` 只有 3 个类（`AiTool`、`DateUtils`、`JsonSchemaConstant`），
   `tool/` 才是完整工具体系（container/git/lark/log/magic/nacos/rabbitmq/rag/sql/workday/workwx/xxljob）。
   单复数一字之差、语义完全不同，检索时极易走错。
   建议：`AiTool`、`JsonSchemaConstant` 并入 `tool/`；`DateUtils` 是通用工具，更适合 `util/`。
   改这个要连带移动 `server/tools/DateUtilsTest.java`，属于跨包重构，故本次未执行。

2. **`TestController` 名不副实** —— 9 个端点里 6 个是 workspace 文件 CRUD
   （`/test/workspace/{agent}/tree|list|read|write|create|move|delete`），
   另 2 个是 demo（`/test`、`/test/wecom-mcp`）、1 个是 Nacos AI 连接样例。
   名为 Test、实为「workspace 文件服务 + 调试入口」。建议按职责拆分并改名，
   迁移到 `controller/workspace` 与 `controller/dev`（或直接删掉 demo 端点）。
   本次只做了归位（`controller/test`），未改名 —— 改名会影响前端既有调用路径。

3. **`bootstrap/src/main/resources` 下混入非 classpath 资源**：
   `k8s/*.yaml`、`es/Mapping.json`、`xxljob/xxljob.sql` 经 rg 验证**没有任何代码读取**，
   却会随 `src/main/resources` 被打进 jar。部署清单与建表脚本属于仓库级交付物，
   建议移到仓库根的 `deploy/`（或归入 `docs/`）。`certs/` 不在此列（见 3.1）。

4. **bootstrap 未显式声明 web 依赖**：controller 搬过来后，bootstrap 直接使用
   `@RestController` / `@RequestMapping`，依赖却来自 server 的传递依赖
   （`md-agent-server/pom.xml:51` 的 `spring-boot-starter-webflux`）。
   规范做法是在 bootstrap 显式声明。**但不能简单地把它从 server 挪走**：
   server 主代码仍有 34 处 `org.springframework.http.*` 与 `reactor.core.publisher.*`
   引用（RestTemplate 出站调用、middleware 的 Flux/Mono），webflux 对 server 仍是真实依赖。
   所以是「bootstrap 增加显式声明」，而不是「从 server 搬走」。

5. **`WebFluxStackTest` 的位置待重新评估**：它断言最终 classpath 上没有 servlet 容器，
   而最终 classpath 形态由 bootstrap（可运行产物）决定，现在却留在 server。
   本次未动，避免与依赖归属的决策混在一起。

6. **`md-agent-server/target/surefire-reports/` 残留旧报告**：里面有
   `com.md.agentplatform.server.trace.AgentTraceControllerTest` 的历史 XML，
   是搬迁前构建的遗留物。看这份报告会误以为该测试还在 server。
   跑一次 `mvn clean` 即可清除。

### 3.3 搬迁的额外收益

`server/trace` 包在这次搬迁后变成**纯领域/组件包**（8 个类：`AgentRunEvent`、
`AgentRunEventTranslator`、`AgentTraceRegistry`、`AgentTraceRun`、`AgentTraceStep`、
`TraceEventPage`、`TraceRunInfo`、`TraceRunSummary`），不再混入带 Web 注解的类。

同理 `server/agent` 包只剩装配与治理逻辑：HTTP 动词、路径、状态码这些传输层概念
已随 `AgentController` 整体移出，领域包不再对外泄漏 Web 语义。

## 4. 验证

```
JAVA_HOME=C:\Users\User\.jdks\ms-21.0.11
D:\jinran\maven\apache-maven-3.9.10-bin\apache-maven-3.9.10\bin\mvn.cmd -o test
```

- 结果：**BUILD SUCCESS**
- `md-agent-server`：Tests run 196, Failures 0, Errors 0
- `md-agent-bootstrap`：Tests run 7, Failures 0, Errors 0
- 合计 **203**，与搬迁前基线完全一致（原 203 = server 203；现 203 = 196 + 7），无测试丢失。

## 5. 同步更新的文档

- `docs/agent-definition-nacos-migration.md`：`AgentController` 路径改为 bootstrap。
- `docs/develop/2026-09-20_runtime-trace-observability.md`：`AgentTraceController`
  与其测试的路径改为 bootstrap。
