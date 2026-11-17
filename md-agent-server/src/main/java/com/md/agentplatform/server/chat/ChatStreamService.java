package com.md.agentplatform.server.chat;

import com.md.agentplatform.server.agent.AgentManager;
import com.md.agentplatform.server.trace.AgentTraceRegistry;
import com.md.agentplatform.server.trace.AgentTraceRun;
import com.md.agentplatform.server.trace.TraceRunIdGenerator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 页面对话：把某个 agent 的一次调用变成一串 {@link ChatStreamEvent}。
 *
 * <p>为什么单独一层而不是直接写在控制器里：控制器是 HTTP 边界（bootstrap 模块），
 * 只该做协议适配（状态码、SSE 帧、心跳），而「哪些 AgentEvent 值得转成帧、怎么转」
 * 属于领域逻辑。官方 service 也是这个切法（web 层只翻译，dataplane 层产事件）。
 *
 * <p><b>与企微 / 飞书那条链路的区别</b>：那两条是「平台主动推消息」（renderer 自己
 * 管分片、节流、卡片更新），本类不管这些 —— 它只把事件转成帧，谁来消费是控制器的事。
 * 换句话说：本类是<b>只读观察者 + 翻译器</b>，不产生任何副作用，也不改 agent 的行为。
 *
 * <p><b>观测是白送的</b>：所有 agent 在装配时都无条件挂了运行链路 middleware
 * （见 ConfigurableAgentBuilder#applyTrace），所以从这里发起的每一次对话
 * 都会在执行链路页留下一条完整 run —— 本类不需要为此做任何事。
 */
@Component
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /**
     * 请求没带 userId 时的默认值。
     *
     * <p>userId 不能省：框架按 (userId, sessionId) 两维分区，它决定「这条对话和谁共享记忆」。
     * 本项目用的是 MySQL 状态存储（{@code MysqlAgentStateStore}），两者会拼成
     * {@code <userId>:<sessionId>} 存进 per-agent 状态表的 {@code session_id} 列
     * （已上线数据里能看到 {@code wo7U1Z...:sub-72aea1e9-...} 这种形态）；
     * 若换成 JSON 文件状态存储，userId 还会直接变成目录名
     * （{@code <root>/<agentId>/<userId>/<sessionId>/}），所以它同样是路径片段、必须消毒。
     * 给这个固定默认值的好处是：页面上的对话与企微 / 飞书来的对话在磁盘上天然分开，
     * 排查时一眼能认出「这条是控制台发起的」。
     */
    static final String CONSOLE_USER_ID = "console";

    /**
     * 路径片段长度上限。框架自己的上限是 255，这里收紧到 120：
     * 这两个值都可能落到文件名 / 目录名上（sessionId → {@code tasks/<sessionId>.json}；
     * 文件状态存储下 userId → 一级目录；MySQL 下两者拼成 {@code session_id} 列，
     * 框架对该列的上限是 255），留出余量，免得带上前缀后触到限制。
     */
    private static final int PATH_SEGMENT_MAX_LEN = 120;

    private final AgentManager agentManager;

    public ChatStreamService(AgentManager agentManager) {
        this.agentManager = agentManager;
    }

    /** agent 是否已装配。控制器要在开始写响应体之前用它决定 404。 */
    public boolean hasAgent(String agentId) {
        return agentId != null && !agentId.isBlank() && agentManager.exists(agentId);
    }

    /**
     * 一次对话。
     *
     * <p>整段包在 {@code Flux.defer} 里：帧的组装依赖订阅时才该产生的状态
     * （开始时刻、「有没有真的流过文本增量」）。写成 defer 之后，
     * 同一个返回的 Flux 被订阅两次也是两次独立调用，不会互相串味。
     *
     * @param agentId            已装配的 agent 名
     * @param message            用户这一轮说的话
     * @param requestedSessionId 请求方给的会话键；为空则现生成一个并回执在 run 帧里
     * @param requestedUserId    请求方给的用户标识；为空则用 {@link #CONSOLE_USER_ID}
     */
    public Flux<ChatStreamEvent> chat(String agentId, String message,
                                     String requestedSessionId, String requestedUserId) {
        if (!hasAgent(agentId)) {
            // 控制器已经先判过一次，这里再判是为了守住「谁调用都必须给对 id」这个不变式，
            // 而不是靠调用方自觉。抛出去比返回空流好：空流会让页面显示一个空回答。
            throw new IllegalStateException("Agent 不存在: " + agentId);
        }

        String sessionId = resolveSessionId(requestedSessionId);
        String userId = resolveUserId(requestedUserId);

        return Flux.defer(() -> {
            long startedAt = System.currentTimeMillis();
            // 先自己定下这次 run 的 id，再放进上下文（见 AgentTraceRegistry.attachRunId）。
            //
            // 为什么要"先定"而不是等链路跑完再回查：runId 是中间件在订阅那一刻现生成的，
            // 翻译层拿不到它（详见 docs/develop/2026-09-21_chat-page-and-sse.md 的缺口表）。
            // 而页面上「这条回答对应哪条链路」必须能点得动，所以反过来由这里定 id、中间件照用。
            // 放在 defer 内生成：两次订阅是两个独立调用，各拿各的 id。
            String runId = TraceRunIdGenerator.next();
            List<Msg> msgs = List.of(Msg.builder().role(MsgRole.USER).textContent(message).build());
            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .put(AgentTraceRegistry.RUN_ID_ATTR, runId)
                    // 来源只影响观测页的筛选与展示，不参与任何业务判断（见 AgentTraceRegistry.SOURCE_ATTR）。
                    .put(AgentTraceRegistry.SOURCE_ATTR, AgentTraceRun.SOURCE_CONSOLE)
                    .build();

            // 「这一轮有没有流过文本增量」——决定 AgentResultEvent 要不要兜底补发整段。
            // 必须在 defer 内创建：放到方法级会让两次订阅共用同一个标志。
            AtomicBoolean streamedText = new AtomicBoolean(false);
            // 收口状态由错误分支改写，done 帧在流末尾才读它，所以不能是局部 boolean。
            AtomicReference<String> status = new AtomicReference<>(ChatStreamEvent.STATUS_DONE);

            // run= 放在最前面：这一轮的所有后续日志（中间件的 RUN_START / RUN_END）都带同一个 id，
            // 排查时按它 grep 就能把"页面发的一次对话"和"链路里那一棵树"串起来。
            log.info("[Chat] 开始 run={} agent={} session={} user={} messageLen={}",
                    runId, agentId, sessionId, userId, message == null ? 0 : message.length());

            Flux<ChatStreamEvent> body = agentManager.stream(agentId, msgs, ctx)
                    // 泛型点心必须写在 handle 上：handle 的返回类型由 sink.next 的实参推断，
                    // 而下面每个分支都在不同的 if 里 next，编译器推不出统一类型时会退回 Object，
                    // 链上要求的是 Flux<ChatStreamEvent>，于是整条链在 concatWith 处报"无法转换"。
                    // （这个错报在链条末端，不在 handle 这一行，容易找错地方。）
                    .<ChatStreamEvent>handle((event, sink) -> {
                        if (event instanceof TextBlockDeltaEvent td) {
                            String delta = td.getDelta();
                            if (delta != null && !delta.isEmpty()) {
                                streamedText.set(true);
                                sink.next(ChatStreamEvent.delta(delta));
                            }
                            return;
                        }
                        if (event instanceof ThinkingBlockDeltaEvent td) {
                            if (td.getDelta() != null && !td.getDelta().isEmpty()) {
                                sink.next(ChatStreamEvent.thinking(td.getDelta()));
                            }
                            return;
                        }
                        if (event instanceof ToolCallStartEvent tc) {
                            sink.next(ChatStreamEvent.tool(tc.getToolCallId(), tc.getToolCallName(),
                                    ChatStreamEvent.TOOL_PHASE_START));
                            return;
                        }
                        if (event instanceof ToolResultEndEvent te) {
                            sink.next(ChatStreamEvent.tool(te.getToolCallId(), te.getToolCallName(),
                                    ChatStreamEvent.TOOL_PHASE_END));
                            return;
                        }
                        if (event instanceof AgentResultEvent result) {
                            // 兜底：不是所有模型 / 配置都会产生 TextBlockDelta（有的只在最后
                            // 一次性给出全文）。只认 delta 的话，这类情况页面会一直空白到 done，
                            // 看起来像「agent 没回话」。所以没流过增量时把整段结果补发一次。
                            if (!streamedText.get() && result.getResult() != null) {
                                String text = result.getResult().getTextContent();
                                if (text != null && !text.isEmpty()) {
                                    streamedText.set(true);
                                    sink.next(ChatStreamEvent.delta(text));
                                }
                            }
                        }
                        // 其余事件（推理 / 模型调用的边界事件、数据块等）在此刻意丢弃：
                        // 页面上没有对应的展示位，转成帧只会让前端多写一堆没有出口的分支。
                        // 它们并没有丢 —— 执行链路页照常记录，要排查去那边看。
                    })
                    // 异常不往外抛，转成 error 帧后照常收口。理由与链路 SSE 刚好相反：
                    // 那边把异常抛出去是为了让前端重连补齐；这里是「一问一答」，
                    // 重连等于把 agent 再跑一遍（有副作用、要花 token），必须禁止。
                    .onErrorResume(err -> {
                        status.set(ChatStreamEvent.STATUS_ERROR);
                        String summary = err.getClass().getSimpleName()
                                + (err.getMessage() == null ? "" : ": " + err.getMessage());
                        log.error("[Chat] 失败 run={} agent={} session={} err={}",
                                runId, agentId, sessionId, summary, err);
                        // 泛型必须写出来：lambda 里只有 Map 与 String，类型推断会给出
                        // Flux<Object>，而链上要求的是 Flux<ChatStreamEvent>。
                        return Flux.<ChatStreamEvent>just(ChatStreamEvent.error(summary));
                    })
                    // 这里同样要把泛型写出来：defer 的 lambda 体只返回一个 just，
                    // 推断不出元素类型就会得到 Flux<Object>，与链上要求的类型对不上。
                    .concatWith(Flux.<ChatStreamEvent>defer(() -> {
                        long elapsedMs = System.currentTimeMillis() - startedAt;
                        log.info("[Chat] 结束 run={} agent={} session={} status={} elapsedMs={}",
                                runId, agentId, sessionId, status.get(), elapsedMs);
                        return Flux.<ChatStreamEvent>just(ChatStreamEvent.done(status.get(), elapsedMs));
                    }));

            // 首帧先回执：前端拿它确认这次调用真的开始了，并记住生效的会话键。
            // userId 也一并回执：它是"这次落在哪个用户分区"的唯一凭据，
            // 请求没带时前端要能看到后端到底用了什么。
            // runId 更是必须回执：它是页面上"跳去执行链路"那一步唯一的凭据，
            // 而且必须与中间件记下的 runId 一致，否则点进去是一个 404 的详情页。
            return Flux.concat(
                    Flux.just(ChatStreamEvent.run(agentId, sessionId, userId, runId, startedAt)), body);
        });
    }

    /**
     * 会话键：请求没带就现生成一个。
     *
     * <p><b>为什么必须过 {@link #sanitize}</b>：harness 把会话键直接当成任务文件名
     * （见下方 tasks 路径）。框架自己的校验只拦斜杠，冒号能过校验，
     * 但在 Windows 下会让 {@code Path.of} 抛 InvalidPathException ——
     * 表现是「harness 一进推理就崩」，且只有 harness 会中招。
     * 这条路在企微那条链路上已经踩过一次
     * （见 WeComThinkRenderer#resolveSessionId 的注释与 HarnessSessionIdTest），
     * 这里不重复踩。
     */
    static String resolveSessionId(String requested) {
        String raw = requested == null ? "" : requested.trim();
        if (raw.isEmpty() || isDotsOnly(sanitize(raw))) {
            // 短随机串而不是 UUID 全量：它会出现在磁盘路径上，短一点肉眼好看也够唯一。
            raw = "console-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return clip(sanitize(raw));
    }

    /**
     * 用户标识：请求没带就用默认值。
     *
     * <p><b>它同样是路径片段，消毒标准不能松</b>：在文件状态存储下 userId 就是工作区里的一级目录，
     * 在 MySQL 状态存储下它与 sessionId 用 {@code :} 拼成同一列 ——
     * 不消毒的话，前者能让对话产物写到别的目录去，后者会让带 {@code :} 的值把
     * 「谁」和「哪个会话」的边界搅乱。
     *
     * <p>点号序列单独挡一道：{@code .} 与 {@code ..} 能通过字符集校验（点号是允许字符），
     * 但作为目录名会让路径解析跳到上一层 —— 那是唯一一条"洗完之后仍然危险"的值，
     * 直接回落到默认用户，不做静默改写（改写后 {@code ..} 会变成别的东西，更难排查）。
     */
    static String resolveUserId(String requested) {
        String raw = requested == null ? "" : requested.trim();
        if (raw.isEmpty()) {
            return CONSOLE_USER_ID;
        }
        String safe = sanitize(raw);
        if (isDotsOnly(safe)) {
            log.warn("[Chat] userId 是全点号序列，已回落到默认用户: raw={}", raw);
            return CONSOLE_USER_ID;
        }
        return clip(safe);
    }

    /**
     * 全点号（含空串）：作为目录名一律是"跳到上一层"或"空名字"，
     * 不能出现在路径片段的位置上。
     */
    private static boolean isDotsOnly(String value) {
        return value.isEmpty() || value.chars().allMatch(c -> c == '.');
    }

    /** 截到路径片段长度上限。 */
    private static String clip(String value) {
        return value.length() <= PATH_SEGMENT_MAX_LEN
                ? value
                : value.substring(0, PATH_SEGMENT_MAX_LEN);
    }

    /** 收敛成文件名安全字符集：点、下划线、连字符保留，其余一律替换成连字符。 */
    static String sanitize(String raw) {
        return raw.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
