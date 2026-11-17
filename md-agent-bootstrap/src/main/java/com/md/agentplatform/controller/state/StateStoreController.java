package com.md.agentplatform.controller.state;

import com.md.agentplatform.server.state.AgentStateAdminService;
import com.md.agentplatform.server.state.AgentStateViews;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 状态存储管理接口 —— 与前端「状态存储」页（{@code /storage}）一一对应。
 *
 * <ul>
 *   <li>GET    /api/state/tables                      状态表清单（含孤儿表与尚未建的表）</li>
 *   <li>GET    /api/state/sessions?table=&keyword=    某个 agent 的会话列表（分页）</li>
 *   <li>GET    /api/state/sessions/detail?table=&slotId=  会话详情（解析后的摘要）</li>
 *   <li>GET    /api/state/sessions/raw?table=&slotId=     原始 state_data JSON（有体积上限）</li>
 *   <li>DELETE /api/state/sessions?table=&slotId=         删除该会话的状态（只删状态）</li>
 * </ul>
 *
 * <h2>一、为什么 {@code table} / {@code slotId} 是查询参数而不是路径段</h2>
 * {@code slotId} 的真实形态是 {@code <userId>:<sessionId>}，含冒号。放进路径段要先编码、后端
 * 再解码，而 {@code encodeURIComponent} 与 Spring 的路径匹配在冒号上的行为要试才知道 ——
 * 换成一个不会有歧义的位置（查询参数）比赌一次好。{@code table} 跟它成对出现，
 * 分开放会让人以为两者可以来自不同来源。
 *
 * <h2>二、为什么每个方法都 {@code subscribeOn(boundedElastic)}</h2>
 * 本项目是 <b>WebFlux</b>（请求跑在 Netty 事件循环线程上），而这一层下面全是
 * 阻塞的 JDBC（MyBatis + HikariCP）。直接在事件循环线程上做阻塞查询，会把整个
 * 事件循环卡住 —— 表现是"查一次状态，所有接口一起变慢"。
 * {@code boundedElastic} 是专为包装阻塞调用准备的线程池，把阻塞挪出事件循环。
 * 已有的控制面接口没做这一步（它们写在 WebFlux 升级之前），本页是新接口，按正确方式写。
 *
 * <h2>三、这里没有写接口</h2>
 * 只有删除。{@code state_data} 是框架内部对象的序列化结果，让页面写回去的风险
 * 见 {@code AgentStateAdminService} 的类注释。
 */
@RestController
@RequestMapping("/api/state")
public class StateStoreController {

    private final AgentStateAdminService service;

    public StateStoreController(AgentStateAdminService service) {
        this.service = service;
    }

    /** 状态库名。页面顶部要显示"这些表在哪个库"，否则运维时无法确认自己看的是不是同一个地方。 */
    @GetMapping("/database")
    public Mono<Map<String, String>> database() {
        return blocking(() -> Map.of("database", service.database()));
    }

    @GetMapping("/tables")
    public Mono<List<AgentStateViews.TableView>> tables() {
        return blocking(service::listTables);
    }

    @GetMapping("/sessions")
    public Mono<AgentStateViews.SessionPage> sessions(
            @RequestParam("table") String table,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return blocking(() -> service.listSessions(table, keyword, limit, offset));
    }

    @GetMapping("/sessions/detail")
    public Mono<AgentStateViews.SessionDetail> detail(
            @RequestParam("table") String table,
            @RequestParam("slotId") String slotId) {
        return blocking(() -> service.detail(table, slotId));
    }

    /**
     * 原始 JSON。用 {@code TEXT_PLAIN} 而不是 {@code APPLICATION_JSON}：
     * 返回的是"库里存着的那段文本"，不是解析后的对象 —— 标成 JSON 会让前端以为
     * 拿到的是结构化数据，而它的价值恰恰在于"未经加工"。
     */
    @GetMapping(value = "/sessions/raw", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Mono<String> raw(
            @RequestParam("table") String table,
            @RequestParam("slotId") String slotId) {
        return blocking(() -> service.raw(table, slotId));
    }

    /**
     * 一个会话的完整消息清单 —— 对话页左侧「最近会话」点进去之后回放用。
     *
     * <p>与 {@code /sessions/detail} 的区别：那个返回的是<b>摘要</b>
     * （最后 20 条、每条截到 400 字、正文与思考拼成一行），回答的是"这个会话现在处在什么状态"；
     * 这个返回的是<b>原文</b>（正文 / 思考 / 工具名分开，不截到 400 字），
     * 供页面把回答按 Markdown 原样渲染。两份形状不同是刻意的，见
     * {@code AgentStateAdminService#messages} 的注释。
     */
    @GetMapping("/sessions/messages")
    public Mono<List<AgentStateViews.Message>> messages(
            @RequestParam("table") String table,
            @RequestParam("slotId") String slotId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return blocking(() -> service.messages(table, slotId, limit));
    }

    /**
     * 只删状态，不碰调用链路。返回删除行数，页面据此给出"删了几行"的确认。
     *
     * <p>DELETE 带查询参数而不是 body：WebFlux 下 DELETE 带 body 的客户端支持差异很大
     * （浏览器 fetch 可以，但部分代理会丢），而这里只有两个短参数。
     */
    @DeleteMapping("/sessions")
    public Mono<Map<String, Object>> delete(
            @RequestParam("table") String table,
            @RequestParam("slotId") String slotId) {
        return blocking(() -> {
            int deleted = service.deleteSession(table, slotId);
            return Map.of("deleted", deleted, "slotId", slotId, "table", table);
        });
    }

    /**
     * 把阻塞调用挪到弹性线程池。
     *
     * <p>{@code fromCallable} 的另一个好处：服务层抛的异常会变成 {@code onError} 而不是
     * 直接冒到事件循环上，于是 {@link StateStoreExceptionHandler} 能按统一规则翻译状态码
     * —— 异常从响应式管道里出来时，{@code @ExceptionHandler} 依然会生效。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }
}
