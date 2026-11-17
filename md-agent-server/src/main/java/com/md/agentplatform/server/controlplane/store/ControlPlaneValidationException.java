package com.md.agentplatform.server.controlplane.store;

import java.util.List;

/**
 * 控制面写入前的校验失败 —— 一次带上<b>全部</b>问题，而不是抛第一个。
 *
 * <h2>为什么是"收集式"而不是"快速失败"</h2>
 * 页面上一次提交可能同时踩中好几个：prompt-key 拼错、某个 skill 已被删、子 agent 自引用。
 * 只报第一条的话，用户要来回提交三次才能把表单改对；一次给全，一次就能改完。
 * 这也是它跟 {@code IllegalArgumentException}（参数本身不合法）分开的原因：
 * 后者是代码写错，前者是<b>数据内容</b>不合法，属于业务反馈。
 *
 * <h2>谁来翻译成 HTTP</h2>
 * 控制器（Step 5）捕获本异常返回 400（删除被引用时 409），body 里带上 {@link #getViolations()}，
 * 页面直接逐条列出。Store 自己不碰 HTTP —— 删掉被引用的 prompt 时它是"拒绝执行"，
 * 至于回 409 还是 400 是接口层的决定。
 */
public class ControlPlaneValidationException extends RuntimeException {

    /** 全部校验问题，顺序即后端检查顺序，非空。 */
    private final List<String> violations;

    public ControlPlaneValidationException(List<String> violations) {
        super(String.join("；", violations));
        this.violations = List.copyOf(violations);
    }

    /** 单条问题的便捷构造。 */
    public ControlPlaneValidationException(String violation) {
        this(List.of(violation));
    }

    public List<String> getViolations() {
        return violations;
    }
}
