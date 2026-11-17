package com.md.agentplatform.controller.session;

import com.md.agentplatform.server.session.AgentSessionAdminService;
import com.md.agentplatform.server.session.AgentSessionViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 会话视图接口 —— 与前端「会话记录」页（{@code /sessions}）一一对应。
 *
 * <ul>
 *   <li>GET /api/sessions?agentId=&amp;source=&amp;from=&amp;to=&amp;keyword=&amp;hasState=&amp;limit=&amp;offset=
 *       会话列表（筛选 + 分页）</li>
 *   <li>GET /api/sessions/detail?userId=&amp;sessionId=
 *       一条会话的执行时间线 + 记忆清单</li>
 * </ul>
 *
 * <h2>一、为什么 userId / sessionId 是查询参数而不是路径段</h2>
 * 与状态存储页同一个理由（见 {@code StateStoreController} 的类注释）：{@code sessionId}
 * 由各入口自己算，形态不受本项目控制（可能含冒号、斜杠、中文），
 * 而 {@code userId} <b>允许为空</b>（库里有 user_id 为 NULL 的会话）——
 * 路径段表达不了"这一段是空的"，查询参数可以。
 *
 * <h2>二、为什么这一页没有写接口</h2>
 * 会话不是一张表，而是 {@code md_agent_run} 按 (user_id, session_id) 的聚合结果。
 * 改一条会话等于改它底下的一批 run，而 run 是历史事实、只增不改。
 * 要清记忆请去「状态存储」页（删的是状态，不是链路）；要清链路请用执行链路页的"清空"。
 * 两件事分开，是这一页能保持只读的原因。
 *
 * <h2>三、为什么每个方法都 subscribeOn(boundedElastic)</h2> 
 * 本项目是 <b>WebFlux</b>（请求跑在 Netty 事件循环线程上），而下面全是阻塞的 JDBC
 * （MyBatis + HikariCP）。在事件循环线程上做阻塞查询会把整条事件循环卡住 ——
 * 表现是"查一次会话，所有接口一起变慢"。{@code boundedElastic} 就是为包装阻塞调用准备的。
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final AgentSessionAdminService service;

    public SessionController(AgentSessionAdminService service) {
        this.service = service;
    }

    /**
     * 会话列表，按最近活动倒序。
     *
     * <p>全部筛选参数都可选：不给就是"全部会话"。{@code from} / {@code to} 是
     * <b>epoch millis</b>（与 run 表的时间列同一个口径）—— 页面把日期选择器的结果转成毫秒再传，
     * 后端不再解析日期字符串，也就不会出现"时区按谁算"的争议。
     */
    @GetMapping
    public Mono<AgentSessionViews.SessionPage> list(
            @RequestParam(name = "agentId", required = false) String agentId,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "from", required = false) Long from,
            @RequestParam(name = "to", required = false) Long to,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "hasState", required = false) Boolean hasState,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return blocking(() -> service.list(agentId, source, from, to, keyword, hasState, limit, offset));
    }

    /**
     * 一条会话的详情：执行时间线（每次 run 都能跳到链路详情）+ 记忆清单。
     *
     * <p>{@code userId} 可以不传：库里确实存在 user_id 为 NULL 的会话
     * （老数据、非渠道入口），用空值查询走的是 NULL 安全等值，能查得到。
     */
    @GetMapping("/detail")
    public Mono<AgentSessionViews.SessionDetail> detail(
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "sessionId") String sessionId) {
        return blocking(() -> service.detail(userId, sessionId));
    }

    /**
     * 把阻塞调用挪到弹性线程池。
     *
     * <p>{@code fromCallable} 的另一个好处：服务层抛的异常会变成 {@code onError}
     * 而不是直接冒到事件循环上，异常从响应式管道里出来时 {@code @ExceptionHandler} 依然生效。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }
}
