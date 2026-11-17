package com.md.agentplatform.controller.workflow;

import com.md.agentplatform.server.workflow.WorkflowEngine;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunEntity;
import com.md.agentplatform.server.workflow.store.entity.WorkflowRunNodeEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工作流<b>运行层</b>的读接口。
 *
 * <h2>一、为什么与 WorkflowController 分开</h2>
 * 两个理由：
 * <ol>
 *   <li><b>路径</b>：运行详情的自然形状是 {@code /api/workflow-runs/{runId}}
 *       （run 的唯一身份就是 runId）。若把它挂在 {@code /api/workflows} 前缀下，
 *       就会与"某个工作流下的运行列表" {@code /api/workflows/{key}/runs} 构成
 *       段数与变量数都相同的歧义模式，匹配结果依赖 Spring 内部的模式排序 —— 不值得赌；</li>
 *   <li><b>分层</b>：本类只碰运行表（{@code md_workflow_run} / {@code md_workflow_run_node}），
 *       一次治理字段都不读。与"定义与运行分属两个 Store"是同一条界线
 *       （见 WorkflowStoreConfig 的类注释）。将来运行层要加"取消 / 重跑"，
 *       改的也只是这一个类。</li>
 * </ol>
 *
 * <h2>二、为什么读接口也要 subscribeOn(boundedElastic)</h2>
 * 同 WorkflowController：本项目是 WebFlux，而这里是阻塞的 JDBC 读。
 * 事件循环线程上做阻塞 JDBC 会拖慢所有请求，不只是这一个。
 */
@RestController
@RequestMapping("/api/workflow-runs")
public class WorkflowRunController {

    @Autowired
    private WorkflowEngine engine;

    /**
     * 一次执行的详情：run 行 + 全部节点行。
     *
     * <p>刻意合成一个响应体而不是让页面请求两次：节点明细单独拿没有意义
     *（页面永远要跟 run 一起显示），拆开只多一次往返和一次"run 到了、节点还没到"的中间态。
     *
     * <p>run 不存在时回 404 而不是空 body：页面上"打开一个已被清理掉的运行"
     * 与"这个运行一个节点都还没跑"必须能区分开，后者是正常状态（刚提交的瞬间）。
     */
    @GetMapping("/{runId}")
    public Mono<ResponseEntity<?>> detail(@PathVariable String runId) {
        return blocking(() -> {
            WorkflowRunEntity run = engine.findRun(runId);
            if (run == null) {
                return ResponseEntity.status(404).body(Map.of("error", "运行记录不存在: " + runId));
            }
            List<WorkflowRunNodeEntity> nodes = engine.nodesOf(runId);
            return ResponseEntity.ok(Map.of("run", run, "nodes", nodes));
        });
    }

    /** 与 ModelController 的同名方法一致：把阻塞调用挪到弹性线程池。 */
    private static <T> Mono<T> blocking(Supplier<T> work) {
        return Mono.fromCallable(work::get).subscribeOn(Schedulers.boundedElastic());
    }
}