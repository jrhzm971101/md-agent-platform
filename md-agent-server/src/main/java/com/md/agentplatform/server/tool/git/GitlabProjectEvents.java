package com.md.agentplatform.server.tool.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.md.agentplatform.server.util.JsonUtil;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitLab 项目事件查询工具 —— 调用 GitLab REST API（GET /projects/:id/events）查询项目的事件流。
 *
 * <p>与同目录下查询本地快照的 git-* 工具不同：本工具走 GitLab 服务端 API，能查到
 * <b>所有成员</b>在该项目上的操作（推送代码、建删分支、合并 MR、评论等），
 * 不依赖本地是否克隆过仓库，适合做团队日报/周报的行为统计。
 *
 * <p>两个容易踩坑的点在工具内部已处理：
 * <ul>
 *   <li>GitLab 的 {@code after}/{@code before} 是按 <b>UTC 自然日</b> 且 <b>不含当天</b> 的半开区间，
 *       与调用方传入的本地时间窗口并不一致，因此这里统一换算后再请求，并做本地精确二次过滤。</li>
 *   <li>MR 合并事件的 {@code action_name} 是 {@code accepted} 而不是 {@code merged}，
 *       建分支/删分支信息在 {@code push_data} 里（非 push 事件没有该字段）。</li>
 * </ul>
 */
@Component
public class GitlabProjectEvents extends AbstractGitlabTool {

    /** 默认返回条数 */
    private static final int DEFAULT_MAX_COUNT = 100;

    /** 返回条数硬上限 */
    private static final int HARD_MAX_COUNT = 1000;

    /** 翻页安全阀，防止事件量过大时长时间占用 */
    private static final int MAX_PAGES = 20;

    @Tool(
            name = "gitlab-project-events",
            description = """
                    查询 GitLab 项目的事件流（等价 GitLab API: GET /projects/:id/events），
                    按时间倒序返回事件列表，每条含：时间、操作人、操作类型、被操作目标的类型与名称。

                    适用场景:
                    - 用户说"今天这个项目有哪些操作"、"谁建了分支"、"谁合并了 MR"、"谁推送了代码"
                    - 做团队日报/周报：按项目、按天汇总推送、建删分支、合并请求等行为

                    参数说明:
                    - environment: 环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录的 gitlab 分组读取
                    - projectId: 项目 ID 或路径（必填），如 1367 或 mtn/lms（内部自动 URL 编码）
                    - date: 只查某一天（选填），格式 yyyy-MM-dd，按服务器时区整天
                    - since / until: 自定义时间范围（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss；
                      只给 since 表示"从该时刻到今天"，只给 until 表示"该日当天"
                    - eventType: 按事件类型过滤（选填，推荐用它代替 action），多个用逗号分隔，
                      如 BRANCH_CREATED、BRANCH_CREATED,MR_MERGED、PUSHED
                    - action: GitLab 服务端动作过滤（选填，一般不用），可选 created/updated/closed/
                      reopened/pushed/commented/merged/joined/left/destroyed/expired
                    - targetType: 目标类型过滤（选填），可选 issue/milestone/merge_request/note/
                      project/snippet/user
                    - author: 按操作人过滤（选填），账号或显示名子串，如 maruiheng
                    - maxCount: 返回条数上限（选填，默认 100，最大 1000）

                    返回: JSON（projectId / window / apiWindow / count / summary / events[]）。
                    events[] 每条包含 time(本地时间)、timeUtc、operator(账号)、operatorName(显示名)、
                    action(归一化类型)、actionDesc(中文描述)、actionName(GitLab 原始值)、
                    targetType、targetName、targetIid、detail 等字段。
                    summary 按操作类型与操作人给出条数汇总。

                    事件类型(action / eventType 可选值):
                    BRANCH_CREATED(新建分支) BRANCH_DELETED(删除分支) TAG_CREATED(新建标签)
                    TAG_DELETED(删除标签) TAG_PUSHED(推送标签) PUSHED(推送代码)
                    MR_MERGED(合并合并请求) MR_OPENED(新建合并请求) MR_CLOSED(关闭合并请求)
                    MR_REOPENED(重新打开合并请求) ISSUE_OPENED(新建议题) ISSUE_CLOSED(关闭议题)
                    ISSUE_REOPENED(重新打开议题) COMMENTED(评论) MEMBER_JOINED(加入项目)
                    MEMBER_LEFT(离开项目) DESTROYED(删除)

                    注意:
                    - 时间过滤按服务器时区；GitLab 侧按 UTC 自然日切分，工具内部已换算
                    - "新建分支"不能用 action=created 过滤（会返回 0 条）：建分支在 GitLab 侧
                      属于 pushed 动作，只显示为 pushed new。要按类型过滤请用 eventType
                    - 不要传 action，默认返回全部类型再按 eventType/author 过滤，结果最全
                    - 可见范围受配置 token 的项目权限限制；GitLab 只保留 2 年内的事件
                    """
    )
    public String events(
            @ToolParam(name = "environment", description = "环境名（必填），如 cameroon-uat；GitLab 地址和鉴权信息从环境目录读取") String environment,
            @ToolParam(name = "projectId", description = "项目 ID 或路径（必填），如 1367 或 mtn/lms") String projectId,
            @ToolParam(name = "date", description = "只查某一天（选填），格式 yyyy-MM-dd，按服务器时区整天") String date,
            @ToolParam(name = "since", description = "起始时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String since,
            @ToolParam(name = "until", description = "结束时间（选填），格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss") String until,
            @ToolParam(name = "eventType", description = "按事件类型过滤（选填，推荐），多个用逗号分隔，如 BRANCH_CREATED,MR_MERGED") String eventType,
            @ToolParam(name = "action", description = "GitLab 服务端动作过滤（选填，一般不用），如 pushed、merged") String action,
            @ToolParam(name = "targetType", description = "目标类型过滤（选填），如 merge_request、issue") String targetType,
            @ToolParam(name = "author", description = "按操作人过滤（选填），账号或显示名子串，如 maruiheng") String author,
            @ToolParam(name = "maxCount", description = "返回条数上限（选填，默认 100，最大 1000）") String maxCount
    ) {
        logger.info("gitlab-project-events called, environment:{}, projectId:{}, date:{}, since:{}, until:{}, eventType:{}, action:{}, targetType:{}, author:{}",
                environment, projectId, date, since, until, eventType, action, targetType, author);

        String configError = checkConfig("gitlab-project-events", environment);
        if (configError != null) {
            return configError;
        }
        if (!notBlank(projectId)) {
            return "[gitlab-project-events] 参数缺失: projectId 必填";
        }

        ZoneId zone = ZoneId.systemDefault();
        Date from;
        Date to;
        try {
            if (notBlank(date)) {
                from = parseDate(date, false);
                to = parseDate(date, true);
            } else if (notBlank(since) || notBlank(until)) {
                String today = LocalDate.now(zone).toString();
                from = parseDate(notBlank(since) ? since : until, false);
                to = parseDate(notBlank(until) ? until : today, true);
            } else {
                String today = LocalDate.now(zone).toString();
                from = parseDate(today, false);
                to = parseDate(today, true);
            }
        } catch (IllegalArgumentException e) {
            return "[gitlab-project-events] 参数错误: " + e.getMessage();
        }
        if (from == null || to == null) {
            return "[gitlab-project-events] 参数错误: 时间范围解析失败（date/since/until）";
        }
        if (from.after(to)) {
            return "[gitlab-project-events] 参数错误: 起始时间晚于结束时间（since=" + since + ", until=" + until + "）";
        }

        int limit = parseInt(maxCount, DEFAULT_MAX_COUNT, 1, HARD_MAX_COUNT);
        String[] apiWindow = apiDateWindow(from, to);
        String project = projectId.trim();

        try {
            ArrayNode events = JsonUtil.newArray();
            Map<String, Integer> byAction = new LinkedHashMap<>();
            Map<String, Integer> byOperator = new LinkedHashMap<>();
            boolean truncated = false;
            int scannedPages = 0;

            scan:
            for (int page = 1; page <= MAX_PAGES; page++) {
                scannedPages = page;
                JsonNode pageNode = fetchPage(environment, project, apiWindow[0], apiWindow[1], action, targetType, page);
                if (pageNode == null || !pageNode.isArray() || pageNode.isEmpty()) {
                    break;
                }
                boolean olderThanWindow = false;
                for (JsonNode node : pageNode) {
                    Instant created = parseInstant(text(node, "created_at"));
                    if (created == null) {
                        continue;
                    }
                    if (created.isBefore(from.toInstant())) {
                        // 事件按时间倒序返回，出现早于窗口的记录说明后面都不需要了
                        olderThanWindow = true;
                        continue;
                    }
                    if (created.isAfter(to.toInstant())) {
                        continue;
                    }
                    if (notBlank(author) && !matchesAuthor(node, author)) {
                        continue;
                    }
                    ObjectNode event = toEvent(node, zone);
                    if (!matchesEventType(event.path("action").asText(), eventType)) {
                        continue;
                    }
                    if (events.size() >= limit) {
                        truncated = true;
                        break scan;
                    }
                    events.add(event);
                    byAction.merge(event.path("actionDesc").asText("未知操作"), 1, Integer::sum);
                    byOperator.merge(event.path("operator").asText("未知"), 1, Integer::sum);
                }
                if (olderThanWindow || pageNode.size() < PAGE_SIZE) {
                    break;
                }
            }

            ObjectNode window = JsonUtil.newObject();
            window.put("from", formatLocal(from.toInstant(), zone));
            window.put("to", formatLocal(to.toInstant(), zone));
            window.put("timezone", zone.getId());

            ObjectNode api = JsonUtil.newObject();
            api.put("after", apiWindow[0]);
            api.put("before", apiWindow[1]);

            ObjectNode summary = JsonUtil.newObject();
            summary.set("byAction", toCountNode(byAction));
            summary.set("byOperator", toCountNode(byOperator));

            ObjectNode result = JsonUtil.newObject();
            result.put("success", true);
            result.put("projectId", project);
            result.set("window", window);
            result.set("apiWindow", api);
            result.put("count", events.size());
            result.put("scannedPages", scannedPages);
            result.put("truncated", truncated);
            result.set("summary", summary);
            result.set("events", events);
            return JsonUtil.stringify(result);
        } catch (HttpStatusCodeException e) {
            return describeHttpError("gitlab-project-events", e, "projectId=" + project);
        } catch (IllegalArgumentException e) {
            return "[gitlab-project-events] 参数错误: " + e.getMessage();
        } catch (Exception e) {
            logger.error("gitlab-project-events 异常, environment:{}, projectId:{}, error:{}", environment, projectId, e.getMessage(), e);
            return "[gitlab-project-events] 查询异常: " + e.getMessage();
        }
    }

    // ==================== 请求 ====================

    /** 拉取单页事件；返回 JSON 数组节点。 */
    private JsonNode fetchPage(String environment, String project, String after, String before,
                               String action, String targetType, int page) {
        List<String> query = new ArrayList<>();
        query.add("after=" + after);
        query.add("before=" + before);
        query.add("per_page=" + PAGE_SIZE);
        query.add("page=" + page);
        if (notBlank(action)) {
            query.add("action=" + urlEncode(action.trim()));
        }
        if (notBlank(targetType)) {
            query.add("target_type=" + urlEncode(targetType.trim()));
        }

        // 路径已手动编码，交给基类用 URI.create 发出，避免二次编码（%2F 被转义成 %252F）
        return get(environment, uri(environment, "/projects/" + encodeProject(project) + "/events", query));
    }

    // ==================== 事件解析 ====================

    /**
     * 单个事件 → 精简后的输出结构。
     *
     * <p>归类规则：带 {@code push_data} 的按 push 动作归类（建/删分支、建/删标签、推送代码），
     * 目标名称就是被操作的分支/标签；其余按 {@code action_name} + {@code target_type} 归类，
     * 目标名称取 {@code target_title}。
     */
    static ObjectNode toEvent(JsonNode node, ZoneId zone) {
        String actionName = text(node, "action_name");
        String targetType = text(node, "target_type");
        String targetIid = node.hasNonNull("target_iid") ? node.get("target_iid").asText() : "";

        ObjectNode event = JsonUtil.newObject();
        Instant created = parseInstant(text(node, "created_at"));
        if (created != null) {
            event.put("time", formatLocal(created, zone));
            event.put("timeUtc", created.toString());
        }
        event.put("operator", text(node, "author_username"));
        String operatorName = node.path("author").path("name").asText("");
        if (notBlank(operatorName)) {
            event.put("operatorName", operatorName);
        }

        JsonNode push = node.path("push_data");
        String action;
        String actionDesc;
        String targetName;
        int commitCount = 0;

        if (push.isObject()) {
            String ref = text(push, "ref");
            String refType = text(push, "ref_type");
            String pushAction = text(push, "action");
            boolean tag = "tag".equalsIgnoreCase(refType);

            if ("created".equals(pushAction)) {
                action = tag ? "TAG_CREATED" : "BRANCH_CREATED";
                actionDesc = tag ? "新建标签" : "新建分支";
            } else if ("removed".equals(pushAction)) {
                action = tag ? "TAG_DELETED" : "BRANCH_DELETED";
                actionDesc = tag ? "删除标签" : "删除分支";
            } else {
                action = tag ? "TAG_PUSHED" : "PUSHED";
                actionDesc = tag ? "推送标签" : "推送代码";
            }

            targetName = ref;
            if (!notBlank(targetType)) {
                targetType = tag ? "Tag" : "Branch";
            }
            if (notBlank(ref)) {
                event.put("ref", ref);
            }
            if (notBlank(refType)) {
                event.put("refType", refType);
            }
            commitCount = push.path("commit_count").asInt(0);
            if (commitCount > 0) {
                event.put("commitCount", commitCount);
            }
            String commitTitle = text(push, "commit_title");
            if (notBlank(commitTitle)) {
                event.put("commitTitle", commitTitle);
            }
            String commitTo = text(push, "commit_to");
            if (commitTo.length() >= 8) {
                event.put("commitTo", commitTo.substring(0, 8));
            }
        } else {
            String[] mapped = mapObjectAction(actionName, targetType);
            action = mapped[0];
            actionDesc = mapped[1];
            targetName = text(node, "target_title");
        }

        event.put("action", action);
        event.put("actionDesc", actionDesc);
        if (notBlank(actionName)) {
            event.put("actionName", actionName);
        }
        if (notBlank(targetType)) {
            event.put("targetType", targetType);
        }
        if (notBlank(targetName)) {
            event.put("targetName", targetName);
        }
        if (notBlank(targetIid)) {
            event.put("targetIid", targetIid);
        }
        event.put("detail", buildDetail(action, actionDesc, targetName, targetIid, commitCount));
        return event;
    }

    /** 非 push 事件的动作归类。返回 {@code [归一化动作, 中文描述]}。 */
    static String[] mapObjectAction(String actionName, String targetType) {
        String name = actionName == null ? "" : actionName.trim().toLowerCase();
        String type = targetType == null ? "" : targetType.trim().toLowerCase().replace("_", "");
        boolean mr = "mergerequest".equals(type);
        boolean issue = "issue".equals(type);

        switch (name) {
            case "accepted":
            case "merged":
                return new String[]{"MR_MERGED", "合并合并请求"};
            case "opened":
            case "created":
                if (mr) {
                    return new String[]{"MR_OPENED", "新建合并请求"};
                }
                if (issue) {
                    return new String[]{"ISSUE_OPENED", "新建议题"};
                }
                return new String[]{"OPENED", "创建"};
            case "closed":
                if (mr) {
                    return new String[]{"MR_CLOSED", "关闭合并请求"};
                }
                if (issue) {
                    return new String[]{"ISSUE_CLOSED", "关闭议题"};
                }
                return new String[]{"CLOSED", "关闭"};
            case "reopened":
                if (mr) {
                    return new String[]{"MR_REOPENED", "重新打开合并请求"};
                }
                if (issue) {
                    return new String[]{"ISSUE_REOPENED", "重新打开议题"};
                }
                return new String[]{"REOPENED", "重新打开"};
            case "commented":
            case "commented on":
                return new String[]{"COMMENTED", "评论"};
            case "joined":
                return new String[]{"MEMBER_JOINED", "加入项目"};
            case "left":
                return new String[]{"MEMBER_LEFT", "离开项目"};
            case "destroyed":
                return new String[]{"DESTROYED", "删除"};
            case "pushed new":
                return new String[]{"BRANCH_CREATED", "新建分支"};
            case "pushed to":
                return new String[]{"PUSHED", "推送代码"};
            default:
                return new String[]{
                        name.isEmpty() ? "UNKNOWN" : name.toUpperCase().replace(' ', '_'),
                        notBlank(actionName) ? actionName : "未知操作"};
        }
    }

    /** 组装一句话描述，便于直接给用户看。 */
    private static String buildDetail(String action, String actionDesc, String targetName,
                                      String targetIid, int commitCount) {
        String suffix = notBlank(targetName) ? targetName : "";
        return switch (action) {
            case "BRANCH_CREATED" -> "新建分支 " + suffix;
            case "BRANCH_DELETED" -> "删除分支 " + suffix;
            case "TAG_CREATED" -> "新建标签 " + suffix;
            case "TAG_DELETED" -> "删除标签 " + suffix;
            case "TAG_PUSHED" -> "推送标签 " + suffix;
            case "PUSHED" -> "推送代码到分支 " + suffix
                    + (commitCount > 0 ? "（" + commitCount + " 个提交）" : "");
            case "MR_MERGED" -> "合并合并请求 " + objectRef(targetIid, "!") + suffix;
            case "MR_OPENED" -> "新建合并请求 " + objectRef(targetIid, "!") + suffix;
            case "MR_CLOSED" -> "关闭合并请求 " + objectRef(targetIid, "!") + suffix;
            case "MR_REOPENED" -> "重新打开合并请求 " + objectRef(targetIid, "!") + suffix;
            case "ISSUE_OPENED" -> "新建议题 " + objectRef(targetIid, "#") + suffix;
            case "ISSUE_CLOSED" -> "关闭议题 " + objectRef(targetIid, "#") + suffix;
            case "ISSUE_REOPENED" -> "重新打开议题 " + objectRef(targetIid, "#") + suffix;
            case "COMMENTED" -> "发表评论" + (notBlank(suffix) ? "：" + suffix : "");
            default -> actionDesc + (notBlank(suffix) ? " " + suffix : "");
        };
    }

    private static String objectRef(String iid, String prefix) {
        return notBlank(iid) ? prefix + iid + (notBlank(iid) ? "：" : "") : "";
    }

    // ==================== 时间 / 通用工具 ====================

    /**
     * 本地时间窗口 → GitLab 日期参数。
     *
     * <p>GitLab 的 {@code after}/{@code before} 按 UTC 自然日切分且不含当天：
     * {@code after=D} 返回 UTC 日期 &gt; D 的事件，{@code before=D} 返回 UTC 日期 &lt; D 的事件。
     * 因此向两侧各放宽一天取超集，再由调用方按精确时间二次过滤。
     *
     * @return {@code [after, before]}
     */
    static String[] apiDateWindow(Date from, Date to) {
        LocalDate fromUtc = from.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toUtc = to.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        return new String[]{fromUtc.minusDays(1).toString(), toUtc.plusDays(1).toString()};
    }

    /** 操作人匹配：账号精确（忽略大小写）或显示名/账号包含关键字。 */
    private static boolean matchesAuthor(JsonNode node, String author) {
        String keyword = author.trim().toLowerCase();
        String username = text(node, "author_username").toLowerCase();
        String name = node.path("author").path("name").asText("").toLowerCase();
        return username.equals(keyword) || username.contains(keyword) || name.contains(keyword);
    }

    /** 归一化事件类型匹配：filter 为逗号分隔的类型码（忽略大小写），空表示不过滤。 */
    private static boolean matchesEventType(String action, String filter) {
        if (!notBlank(filter)) {
            return true;
        }
        for (String part : filter.split(",")) {
            if (part.trim().equalsIgnoreCase(action)) {
                return true;
            }
        }
        return false;
    }

}
