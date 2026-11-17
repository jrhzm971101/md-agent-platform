package com.md.agentplatform.controller.overview;

import com.md.agentplatform.server.overview.OverviewService;
import com.md.agentplatform.server.overview.OverviewViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.function.Supplier;

/**
 * 概览接口 —— 与前端「概览」页（{@code /}）一一对应。
 *
 * <ul>
 *   <li>GET /api/overview —— 一屏所需的全部数据（指标 / 健康度 / 需要关注 / 最近变更 / 最近执行）</li>
 * </ul>
 *
 * <h2>一、为什么只有一个端点</h2>
 * 理由见 {@link OverviewService} 的类注释：这一屏上的数字必须来自<b>同一个时刻</b>的快照，
 * 否则会出现"Agent 14 个、已发布 15 个"这种自相矛盾。拆成多个端点不仅做不到原子性，
 * 还会让页面自己去做 join。
 *
 * <h2>二、为什么 subscribeOn(boundedElastic)</h2>
 * 本项目是 WebFlux，而这次调用背后是十几条阻塞的 JDBC 查询（六张控制面表 + run 表的两次聚合）。
 * 在 Netty 事件循环线程上跑它们会把整条事件循环卡住（同 SessionController 的约定）。
 * 这一页的查询量在单次请求里是最大的，所以这条尤其不能省。
 */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    public Mono<OverviewViews.Overview> overview() {
        return blocking(overviewService::snapshot);
    }

    /** 把阻塞调用挪到弹性线程池（异常会转成 onError，仍然走全局异常处理）。 */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }
}