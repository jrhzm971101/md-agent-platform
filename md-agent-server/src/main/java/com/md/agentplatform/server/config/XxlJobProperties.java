package com.md.agentplatform.server.config;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "xxl.job")
public class XxlJobProperties {

    private String adminAddresses;
    private String appName;
    private String accessToken;

    /** 执行器内嵌 HTTP 端口，Admin 通过该端口回调触发任务 */
    private int executorPort = 9999;

    /** 执行器注册到 Admin 的 IP；留空则由框架自动探测（K8s 跨网络场景需显式指定） */
    private String executorIp;

    /** 任务日志文件目录 */
    private String logPath = "logs/xxl-job";

    /** 任务日志保留天数 */
    private int logRetentionDays = 30;

    /** pageList 分页默认参数 */
    private Page page = new Page();

    /**
     * pageList 查询默认参数，供 select-job-by-executor 等工具复用。
     */
    @Getter
    @Data
    public static class Page {
        /** 执行器组 ID */
        private String jobGroup = "2";
        /** 任务状态过滤：-1 全部、0 停止、1 运行 */
        private String triggerStatus = "-1";
        /** 分页起始下标 */
        private String start = "0";
        /** 每页返回条数 */
        private String length = "10";

        public void setJobGroup(String jobGroup) {
            this.jobGroup = jobGroup;
        }

        public void setTriggerStatus(String triggerStatus) {
            this.triggerStatus = triggerStatus;
        }

        public void setStart(String start) {
            this.start = start;
        }

        public void setLength(String length) {
            this.length = length;
        }
    }
}