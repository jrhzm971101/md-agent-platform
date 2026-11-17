package com.md.agentplatform.controller.controlplane;

import com.md.agentplatform.server.controlplane.store.ControlPlaneValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 控制面写接口的异常翻译 —— 把领域异常翻译成 HTTP 状态码与页面能直接渲染的 body。
 *
 * <h2>为什么校验失败必须是 400 + 逐条列表</h2>
 * {@link ControlPlaneValidationException} 是"收集式"的（一次带全部问题，见它的类注释）。
 * 如果这里只回一句 {@code e.getMessage()}，那个收集能力就白费了 ——
 * 页面想逐条标红就要自己去拆分号。所以原样把 {@code violations} 数组带回去。
 *
 * <h2>为什么不在每个 handler 里 try/catch</h2>
 * 写接口有十几个，逐个包 try 必然漏。集中在这里翻译，控制器里就只剩"业务分支"
 * （404 / 409 那些要看具体状态的分支留在控制器里，它们不是异常，是正常返回值）。
 */
@RestControllerAdvice
public class ControlPlaneExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneExceptionHandler.class);

    /** 校验失败 → 400，body 里带全部问题条目。 */
    @ExceptionHandler(ControlPlaneValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ControlPlaneValidationException e) {
        List<String> violations = e.getViolations();
        log.warn("[ControlPlane] 校验失败({} 条): {}", violations.size(), violations);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "校验未通过（" + violations.size() + " 条）",
                "violations", violations));
    }
}
