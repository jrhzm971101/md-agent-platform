package com.md.agentplatform.controller.workspace;

import com.md.agentplatform.server.workspace.WorkspaceAdminService;
import com.md.agentplatform.server.workspace.WorkspaceViews;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工作区文件管理接口 —— 与前端「工作区」页一一对应。
 *
 * <ul>
 *   <li>GET    /api/workspace/agents                               有工作区的 agent（含实际 filesystem 类型）</li>
 *   <li>GET    /api/workspace/users?agentName=                     「隔离维度」下拉框的候选值（这个 agent 用过的身份）</li>
 *   <li>GET    /api/workspace/files?agentName=&amp;userId=&amp;path=     列目录（path 留空 = 工作区根）</li>
 *   <li>GET    /api/workspace/file?agentName=&amp;userId=&amp;path=      读单个文件</li>
 *   <li>PUT    /api/workspace/file                                 覆盖写（body：agentName/userId/path/content）</li>
 *   <li>DELETE /api/workspace/file?agentName=&amp;userId=&amp;path=      删除文件或空目录</li>
 * </ul>
 *
 * <h2>一、为什么 userId 是查询参数而不是路径段，且允许留空</h2>
 * 工作区是<b>按用户隔离</b>的（{@code agents/&lt;id&gt;/users/&lt;uid&gt;}）。留空表示看"基线层"——
 * 那是模板文件所在的地方，不是任何某个用户的实际内容。把这两者合成一个入口，
 * 是为了让页面既能看"agent 出厂长什么样"，也能看"某个用户现在长什么样"。
 * 放进查询参数则是因为它可空，而可空的路径段在 Spring 里要靠 {@code /**} 兜，反而更难读。
 *
 * <h2>二、为什么逐个方法都 {@code subscribeOn(boundedElastic)}</h2>
 * 本项目是 WebFlux，但下面这一层可能是磁盘 IO（LOCAL）、也可能是阻塞 JDBC（REMOTE 的 KV），
 * 两者都会卡住 Netty 事件循环。{@code boundedElastic} 是专为包装阻塞调用准备的线程池。
 *
 * <h2>三、这里为什么只有"覆盖写"，没有"新建"和"重命名"</h2>
 * 覆盖写天然覆盖"新建"（文件不存在时创建）；重命名可以在页面上用"另存 + 删除"两步完成，
 * 而多一个接口就多一处需要过 {@code WorkspacePathGuard} 的入口。安全边界上的入口越少越好。
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private final WorkspaceAdminService service;

    public WorkspaceController(WorkspaceAdminService service) {
        this.service = service;
    }

    /** 有工作区的 agent。页面左侧的 agent 选择器读它。 */
    @GetMapping("/agents")
    public Mono<List<WorkspaceViews.AgentRow>> agents() {
        return blocking(service::listAgents);
    }

    /**
     * 「隔离维度」下拉框的候选值。
     *
     * <p>单独一个接口而不是把名单塞进 {@code /files} 的响应里：那棵树是随每次选择重拉的，
     * 而候选值只在换 agent 时变。合并之后，每次下钻目录都会顺带查一遍 run 表，
     * 而它的答案与这次下钻毫无关系。
     *
     * <p>返回完整候选集，不分页：一个 agent 用过的身份通常是个位数
     * （页面入口 + 若干渠道用户），真有几百个的时候下拉框本身也已经不可用了。
     */
    @GetMapping("/users")
    public Mono<List<WorkspaceViews.UserRow>> users(@RequestParam("agentName") String agentName) {
        return blocking(() -> service.listUsers(agentName));
    }

    /** 列目录。{@code path} 留空表示工作区根。 */
    @GetMapping("/files")
    public Mono<WorkspaceViews.DirView> files(
            @RequestParam("agentName") String agentName,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "path", required = false) String path) {
        return blocking(() -> service.listDir(agentName, userId, path));
    }

    /**
     * 读文件内容。返回 JSON 而不是纯文本：页面还要同时拿到 {@code writable} 与
     * {@code truncated} 两个判定 —— 内容本身无法携带这两个信息。
     */
    @GetMapping("/file")
    public Mono<WorkspaceViews.FileView> file(
            @RequestParam("agentName") String agentName,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam("path") String path) {
        return blocking(() -> service.readFile(agentName, userId, path));
    }

    /** 覆盖写。用 body 而不是查询参数：内容可能很长，URL 长度不是拿来放正文的。 */
    @PutMapping("/file")
    public Mono<Map<String, Object>> save(@RequestBody SaveRequest request) {
        return blocking(() -> {
            String path = service.writeFile(request.agentName(), request.userId(),
                    request.path(), request.content());
            return Map.<String, Object>of("path", path, "saved", true);
        });
    }

    /**
     * 删除文件或空目录。
     *
     * <p>DELETE 带查询参数而不是 body：WebFlux 下 DELETE 带 body 的客户端支持差异很大
     * （浏览器 fetch 可以，部分代理会丢），而这里只有三个短参数。
     */
    @DeleteMapping("/file")
    public Mono<WorkspaceViews.DeleteResult> delete(
            @RequestParam("agentName") String agentName,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam("path") String path) {
        return blocking(() -> service.delete(agentName, userId, path));
    }

    /**
     * 把阻塞调用挪到弹性线程池。
     *
     * <p>{@code fromCallable} 的另一个好处：服务层抛的异常会变成 {@code onError} 而不是
     * 直接冒到事件循环上，于是 {@link WorkspaceExceptionHandler} 能按统一规则翻译状态码。
     */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }

    /** 写入请求体。 */
    public record SaveRequest(String agentName, String userId, String path, String content) {
    }
}
