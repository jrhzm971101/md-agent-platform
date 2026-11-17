package com.md.agentplatform.controller.state;

import com.md.agentplatform.server.state.StateStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

/**
 * 状态存储接口的异常翻译。
 *
 * <p>形态与 {@code ControlPlaneExceptionHandler} 一致（{@code {error, violations}}），
 * 页面因此可以复用同一套错误展示组件，不必为这一页再写一遍解析。
 *
 * <p>三种结果各自对应一个真实场景，不能合并：
 * <ul>
 *   <li><b>400</b> 表名非法 —— 请求本身有问题；</li>
 *   <li><b>404</b> 表或会话不存在 —— 请求没问题，是"你要找的东西不在了"。
 *       列表页放久了、另一个窗口刚删过，都会走到这里，页面应当提示"刷新后重试"
 *       而不是"参数错误"；</li>
 *   <li><b>413</b> 单会话状态过大 —— 这是资源上限，不是参数错误。
 *       页面应当提示"该会话过大，请用其它方式查看"，这条消息本身就是给用户的结论。</li>
 * </ul>
 */
@RestControllerAdvice
public class StateStoreExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StateStoreExceptionHandler.class);

    @ExceptionHandler(StateStoreException.class)
    public ResponseEntity<Map<String, Object>> handle(StateStoreException e) {
        HttpStatus status = switch (e.getKind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
        };
        // 400/413 是"调用方该改"的问题，打成 warn 便于发现有人乱调；404 属正常竞态，只记 debug。
        if (status == HttpStatus.NOT_FOUND) {
            log.debug("[State] {}", e.getMessage());
        } else {
            log.warn("[State] {} → {}", status.value(), e.getMessage());
        }
        return ResponseEntity.status(status).body(Map.of(
                "error", e.getMessage(),
                "violations", List.of(e.getMessage())));
    }
}
