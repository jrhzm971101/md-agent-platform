package com.md.agentplatform.controller.workspace;

import com.md.agentplatform.server.workspace.WorkspaceAdminException;
import com.md.agentplatform.server.workspace.WorkspacePathException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 工作区接口的异常翻译。
 *
 * <p>响应形态与 {@code ControlPlaneExceptionHandler} / {@code StateStoreExceptionHandler} 一致
 * （{@code {error, violations}}），页面因此能复用同一套错误展示组件。
 *
 * <p>三类结果分开，是因为它们对用户的含义不同：
 * <ul>
 *   <li><b>400 PATH_GUARD</b> 路径没过安全边界 —— 请求本身有问题，且必须留痕：
 *       这不可能是页面正常操作产生的，出现就意味着有人在手工构造请求；</li>
 *   <li><b>400</b> 业务拒绝（不是 HARNESS / 目录非空 / userId 非法）—— 用户改一下输入即可；</li>
 *   <li><b>404</b> agent 未装配 —— 请求没问题，是"东西不在了"。页面应提示刷新，
 *       而不是让用户去改参数。</li>
 * </ul>
 */
@RestControllerAdvice
public class WorkspaceExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceExceptionHandler.class);

    @ExceptionHandler(WorkspacePathException.class)
    public ResponseEntity<Map<String, Object>> handle(WorkspacePathException e) {
        // 路径被拒一律 warn：正常页面操作产不出这种请求。
        log.warn("[Workspace] 路径校验拒绝 → {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "violations", List.of(e.getMessage()),
                "kind", "PATH_GUARD"));
    }

    @ExceptionHandler(WorkspaceAdminException.class)
    public ResponseEntity<Map<String, Object>> handle(WorkspaceAdminException e) {
        HttpStatus status = e.isNotFound() ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        if (status == HttpStatus.NOT_FOUND) {
            log.debug("[Workspace] {}", e.getMessage());
        } else {
            log.warn("[Workspace] {} → {}", status.value(), e.getMessage());
        }
        return ResponseEntity.status(status).body(Map.of(
                "error", e.getMessage(),
                "violations", List.of(e.getMessage())));
    }
}
