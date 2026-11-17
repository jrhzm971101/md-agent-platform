# SkyWalking 链路耗时分析报告：POST /customers/{encryptPhone}

> 分析对象：open-api `POST:/customers/{encryptPhone}` 全链路
> 客户 usage id：`237677550443`（三条链路同一客户）
> 分析日期：2026-11-10
> 数据来源：SkyWalking trace 查询（step=SECOND）

## 一、Trace 清单

| # | traceId | 发生时间 | 总耗时 | 状态 | 备注 |
|---|---------|----------|--------|------|------|
| 1 | `ac2ba5a7e67f44cf8ffc4844219e1202.37809.17942790461110063` | 2026-11-10 02:50:46 | 4706ms | ❌ HTTP 500 | 外部抖动最严重 |
| 2 | `ac2ba5a7e67f44cf8ffc4844219e1202.37823.17942836745450063` | 2026-11-10 04:07:54 | 2480ms | ✅ 200 | risk 自身出现 356ms 空白区间 |
| 3 | `ac2ba5a7e67f44cf8ffc4844219e1202.36819.17942888773160117` | 2026-11-10 05:34:37 | 2323ms | ✅ 200 | 新增 oauth 换 token 546ms |

> 发生时间由 traceId 尾部毫秒时间戳解码得出，查询窗口均为 `±20min`、`step=SECOND`。

## 二、核心结论

1. **瓶颈唯一且稳定**：`risk-open-api → https://preprod.api.mtn.com` 的外部 HTTP 调用，占全链路 **70% ~ 90%**。
2. **调用形态为严格串行**：`oauth → flags → history` 三步首尾相接，无任何并行。
3. **外部耗时极不稳定**：flags 在三小时内为 2038ms → 935ms → 549ms，波动超 4 倍。
4. **无结果缓存**：同一 usage id 在 2h44m 内被完整拉取三次，全部打穿到外部。
5. **SQL 完全不是瓶颈**：38~55ms，占比 0.8%~2.2%。
6. **500 是耗时偶发问题**：三条链路同输入，仅最慢那条（4.7s）报 500，压缩耗时后大概率自然消失。

## 三、耗时拆解（以链路 3 为例，2323ms）

| 层 / Span | 区间(ms) | 耗时 | 自身耗时 | 说明 |
|---|---|---|---|---|
| **open-api** `POST /customers/{encryptPhone}` | 0 → 2316 | 2316ms | ~18ms | 入口 |
| open-api → lms `POST /lms/quota/credit` | 16 → 2314 | 2298ms | 0ms | 内网等待 |
| **lms-backend** `POST /quota/credit` | 18 → 2314 | 2296ms | ~104ms | 前置 76ms + 收尾 28ms，共 19 次 ShardingSphere 分片 SQL |
| lms → risk `POST /api/v1/de/execute` | 94 → 2284 | 2190ms | 0ms | Hutool |
| **risk-open-api** `POST /execute` | 99 → 2283 | 2184ms | ~209ms | 前置 109ms + 收尾 100ms |
| risk 异步线程池 `embeddedWorkerExecutor-10` | 208 → 2183 | **1975ms** | ~62ms | 关键路径 |
| └ `/v1/oauth/access_token` | 215 → 761 | **546ms** | — | token 缓存 miss |
| └ `/usageManagement/.../flags` | 785 → 1334 | **549ms** | — | 依赖 token |
| └ `/usageManagement/.../history` | 1346 → 2164 | **818ms** | — | 30 天窗口 |
| open-api 异步日志 `/lms/api/v1/log/inbound-call` | 2316 → 2323 | 7ms | — | 异步，不阻塞 |

**关键路径**：入口 → lms → risk → oauth → flags → history，全串行。

### 三条链路横向对比

| 项 | 02:50 | 04:07 | 05:34 |
|---|---|---|---|
| 总耗时 | 4706ms | 2480ms | 2323ms |
| 状态 | ❌ 500 | ✅ 200 | ✅ 200 |
| 凭证获取 | login 105 + secrets 41 | 无（缓存命中） | oauth 546ms（miss） |
| flags | 2038ms | 935ms | 549ms |
| history | 2205ms | 802ms | 818ms |
| preprod 外部合计 | 4243ms（90%） | 1737ms（70%） | 1913ms（82%） |
| risk 自身耗时 | 139ms | 521ms | 209ms |
| SQL | 38ms | 55ms | 42ms |

## 四、慢节点 Top

| 排名 | Span | 耗时 | 原因 |
|---|---|---|---|
| 1 | risk `GET .../history` @preprod | 818ms | 30 天窗口大区间查询，串行在最后 |
| 2 | risk `GET .../flags` @preprod | 549ms | 超 300ms 门槛，串行在 oauth 后 |
| 3 | risk `GET /v1/oauth/access_token` @preprod | 546ms | 纯鉴权开销，可完全消除 |
| 4 | risk worker 整体 | 1975ms | ≈ 三个外部调用之和 + 62ms 自身 |
| 5 | risk 收尾 18 条 MySQL 单行 execute | ~18ms | 三次链路同样形态，循环未 batch |

### 关于 04:07 的 356ms 空白区间

risk-open-api `POST /execute` 在 04:07 存在 ~356ms 无子 span 覆盖的区间（`139→179`、`273→319`、`422→459` 等），而 05:34 同类区域仅 109ms。**同为一条代码路径耗时差 3 倍**，判断为 JIT 预热 / GC 停顿 / CPU 争抢，而非稳定业务计算 —— 需 GC 日志或补充规则引擎埋点确认。

## 五、外部调用

| 调用 | 耗时 | 问题 |
|---|---|---|
| `http://10.3.40.76:48081/lms/quota/credit` | 2298ms | 内网，慢在下游服务 |
| `http://risk-open-api.cmr-prod:8080/api/v1/de/execute` | 2190ms | 内网，慢在下游服务 |
| `https://**preprod**.api.mtn.com/v1/oauth/access_token` | 546ms | ⚠️ 鉴权同步阻塞，token 未预热 |
| `https://**preprod**.api.mtn.com/.../flags` | 549ms | ⚠️ 生产调 preprod；超 300ms |
| `https://**preprod**.api.mtn.com/.../history?startDate=20261011&endDate=20261109` | 818ms | ⚠️ 30 天窗口；串行在最后 |
| RabbitMQ `exchange.smpp.sms.taskQueue` | 0ms | 异步发短信，正常 |
| `http://10.3.40.76:48081/lms/api/v1/log/inbound-call` | 7ms | 异步日志，正常 |

**三个明确问题**

1. **串行**：`oauth → flags → history` 严格首尾相接；flags/history 共用同一 token，可并行。
2. **环境**：三条链路跨 2h44m 全部指向 `preprod.api.mtn.com`，长期配置问题，且是最大耗时来源。
3. **鉴权**：三条链路出现三种不同凭证获取方式，缓存策略不统一，过期后同步阻塞换取。

> ⚠️ 局限：链路仅记录客户端整体等待时长，**无法拆分服务端处理时间与网络/TLS 建连时间**。546ms 中建连+证书握手的占比为推断结论，需 TCP/TLS 指标或 MTN 侧耗时坐实。

## 六、SQL

- 总计 **38~55 个 span / 42~55ms**，占全链路 **1.8%~2.2%**，**不是瓶颈**，无需展开。
- 单体最慢 3ms，包含：`INSERT/UPDATE b_risk_pricing_11`、`INSERT b_customer_quota_flow_11`、`INSERT b_customer_history_11`、`INSERT b_api_call_log`。
- **唯一值得优化的点（三次链路均复现）**：
  - risk-open-api 收尾阶段 18 条独立 `PreparedStatement/execute`，应改为 `addBatch / executeBatch`，省 ~15ms + 18 次网络往返。
- 低优先级：`b_customer` 的 SELECT 达 40+ 列（`...firebase_token,...`），可收敛字段。
- `select * from ACT_RE_PROCDEF` / `ACT_RU_*` 为 Activiti 框架自带查询，忽略。

## 七、错误

| # | 服务 | Span | 耗时 | HTTP | 说明 |
|---|---|---|---|---|---|
| 1 | open-api | `Entry POST /customers/{encryptPhone}` | 4706ms | **500** | 仅 02:50 出现 |
| 2 | lms-backend | `Entry POST /quota/credit` | 4672ms | 200 + ERROR 标记 | 异常被 catch 吞掉 |

**推断**：02:50 的 500 属耗时偶发问题 —— 后续 04:07、05:34 同输入均成功，且所有下游均返回 200，失败发生在 open-api 拿到结果后的处理阶段（超时判定 / 降级分支 / 组装 NPE）。

**待办**：按 `2026-11-10 02:50:46` 捞 open-api 日志确认堆栈；性能优化落地后该类错误预期自然消失。

## 八、优化建议（按收益排序）

| 优先级 | 建议 | 预估节省 | 累计总耗时 |
|---|---|---|---|
| **P0** | oauth token 提前异步续期 + HTTPS 连接池复用，业务请求不阻塞换 token | ≈ 546ms | ~1.78s |
| **P0** | `flags` 与 `history` 并行（独立线程池，勿占 `embeddedWorkerExecutor`） | ≈ 549ms | ~1.23s |
| **P0** | 确认 `preprod.api.mtn.com` 是否误配（长期配置 + 波动 4 倍） | 不确定 | — |
| **P1** | usage 结果按 usageId 加 5~15min 短 TTL 缓存；history 改增量拉取 | ≈ 800ms | **~400ms** |
| **P1** | 三个 MTN 调用统一加 connect/read 超时 + 降级兜底 | 长尾收敛 | — |
| **P2** | 排查 risk 356ms 空白区间（GC 日志 / 规则引擎补埋点） | 100~200ms | — |
| **P2** | risk 收尾 18 条单行 execute 改 batch | ≈ 15ms | — |
| ✅ 已完成 | lms 侧 `universal-auth/login` + `secrets` 缓存（146ms → 0） | 已省 146ms | — |

**优化后预估**

| 场景 | 总耗时 | 相对当前(2323ms) | 相对最差(4706ms) |
|---|---|---|---|
| 现状 | 2323ms | — | — |
| token 预热 + 并行化 | ~1.23s | ↓ 47% | ↓ 74% |
| 再加 usage 缓存命中 | **~400ms** | **↓ 83%** | **↓ 91%** |

## 九、附录：分析方法说明

- **自身耗时** = span 耗时 − 直接子 span 耗时之和；自身耗时高 → 卡在本地逻辑 / 序列化 / 线程等待 / 未埋点调用。
- **关键路径** = 沿最长父子链单链追踪，并行兄弟 span 不累加。
- **时间窗口** = traceId 尾部毫秒时间戳解码，前后各留 20min 余量，`step=SECOND`。
- **工具调用参数**：`skywalking-trace(traceId, startTime, endTime, step=SECOND)`。
