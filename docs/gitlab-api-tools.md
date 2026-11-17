# GitLab API 工具能力说明

> 位置: `md-agent-server/src/main/java/com/md/agentplatform/server/tool/git/`
> 公共基类: `AbstractGitlabTool`（鉴权头、超时、路径编码、时间口径统一在这里）
> 配置: 每个环境目录的 `gitlab` 分组（`url` / `apiUrl` / 静态 `token`，或 `username`/`password` 动态鉴权）；所有工具必须传 `environment`
> 服务端版本: GitLab 11.5 EE，接口地址 `https://git.huojintech.com/api/v4`

> 更新：当前所有 `git-*` 工具也都已改为 GitLab API 快照模式，不再依赖 JGit。
> 仓库物化、分支并行、提交查询的完整说明见 [gitlab-api-workspace-tools.md](./gitlab-api-workspace-tools.md)。
> 从部署镜像反查流水线与代码见 [gitlab-pipeline-image-to-code.md](./gitlab-pipeline-image-to-code.md)。

本组工具走 GitLab 服务端 API：**不需要先克隆仓库**，能看到**所有成员、所有分支**的数据，
只受该环境静态 `gitlab.token` 或动态鉴权账号的权限限制。

## 一、工具清单

| 工具名（Bean） | 对应 GitLab API | 说明 |
| --- | --- | --- |
| `gitlab-project-events` | `GET /projects/:id/events` | 项目操作事件流（推送、建删分支、合并 MR、评论、议题） |
| `gitlab-merge-requests` | `GET /merge_requests`、`/groups/:id/merge_requests`、`/projects/:id/merge_requests` | MR 列表与统计，支持项目/组/全局三种范围 |
| `gitlab-merge-request-detail` | `GET /projects/:id/merge_requests/:iid/changes`、`/commits`、`/versions` | 单个 MR 的完整改动、提交与版本快照 |
| `gitlab-project-commits` | `GET /projects/:id/repository/commits` | 提交明细与开发量统计（次数、增删行数、提交信息字数） |
| `gitlab-commit-diff` | `GET /projects/:id/repository/commits/:sha/diff`、`/repository/compare` | 单次提交或提交区间的完整改动 |
| `gitlab-pipelines` | `GET /projects/:id/pipelines` | 流水线列表，支持 ref / sha / status / username 过滤 |
| `gitlab-pipeline-detail` | `GET /projects/:id/pipelines/:pipeline_id` | 单条流水线的 ref、精确 SHA、状态与作者 |
| `git-pipeline-checkout` | `GET /projects/:id/pipelines/:id` + `archive.zip?sha=` | 按流水线精确提交物化本地代码目录 |

所有工具都必须传 `environment`（如 `cameroon-uat`），GitLab 地址和鉴权信息从该环境的 `gitlab` 分组读取。
静态 `token` 优先；为空时按 `authMode` 用 `username`/`password` 动态换取并缓存，过期或 401 后自动重新获取。
`projectId` 支持数字 ID 或仓库路径（`1367` / `mtn/lms`，内部自动 URL 编码）。

## 二、实测得到的口径与坑（GitLab 11.5）

1. **建分支不是 `action=created`**：`GET /projects/:id/events?action=created` 实测返回 0 条，
   建分支在事件里的 `action_name` 是 `pushed new`，因此工具用客户端 `eventType=BRANCH_CREATED` 过滤。
2. **事件接口的 `after` / `before` 按 UTC 自然日切分且不含当天**：北京 9/11 一整天 = UTC 9/10 16:00 ~ 9/11 15:59，
   横跨两个 UTC 日；工具按两侧各放宽一天取超集后再本地精确过滤。
3. **MR 合并事件是 `accepted` 而不是 `merged`**。
4. **MR 的合并时间没有服务端过滤参数**（11.5 无 `merged_after`，传了被静默忽略）：
   工具用 `updated_after` 收窄（合并不可能早于最后更新），再按 `merged_at` 本地精确过滤。
5. **`GET /merge_requests` 必须带 `scope=all`**：否则只返回当前账号创建的 MR（实测 21 条 vs 908 条）。
   跨项目统计用这一个接口即可，不必逐项目循环。
6. **提交接口必须 `all=true`**：否则只返回默认分支（实测同一时间窗 6 条 vs 0 条）；`with_stats=true` 才有增删行数。
7. **提交接口的 `author` 参数在 11.5 不生效**（静默忽略），按人过滤由工具在本地完成；
   `path` 参数有效（`path=pom.xml` → 0 条，`path=lms-business` → 命中）。
8. **`since` / `until` 是带时区的完整时间戳**，直接传北京时间即可（`2026-09-11T00:00:00+08:00`，
   与 `2026-09-10T16:00:00Z` 等价），不需要像事件接口那样做 ±1 天换算。
9. **diff 接口不返回行数**：11.5 的 diff / changes 只给 `diff` 文本，增删行数由工具按文本统计
   （排除 `+++` / `---` 文件头）。
10. **分支创建时间拿不到**：branches 接口没有 `created_at`，只有 head commit 的时间，
    所以"今日新增分支"只能用事件流（`push_data`）。

## 三、典型调用链

```text
1) 每日报表
   gitlab-project-events environment=cameroon-uat projectId=1367 date=2026-09-11        # 分支创建数、合并动作、推送
   gitlab-merge-requests environment=cameroon-uat scope=all     date=2026-09-11        # 全公司当天合并的 MR
   gitlab-project-commits environment=cameroon-uat projectId=1367 date=2026-09-11        # 提交次数、增删行数、提交信息字数

2) 从 MR 到具体代码
   gitlab-merge-requests environment=cameroon-uat scope=project projectId=1367 state=merged targetBranch=master
   gitlab-merge-request-detail environment=cameroon-uat projectId=1367 mergeRequestIid=57 include=all
   gitlab-commit-diff environment=cameroon-uat projectId=1367 commitId=<mergeCommitSha>

3) 从提交列表到改动内容
   gitlab-project-commits environment=cameroon-uat projectId=1367 date=2026-09-11 maxCount=50
   gitlab-commit-diff environment=cameroon-uat projectId=1367 commitId=<上一步的 id>

4) 一段区间的整体改动
   gitlab-commit-diff environment=cameroon-uat projectId=1367 from=master to=MTN-Feature/Ghana

5) 从部署镜像到代码
   gitlab-pipeline-detail environment=cameroon-uat projectId=1367 image=harbor.example.com/mtn/lms:109478
   git-pipeline-checkout environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
   # 目录：workspace/code/lms/pipeline-109478
```

## 四、尚未封装但可用的能力

- `GET /projects/:id/repository/branches`（分支清单与 `search`、是否已合并、保护状态）——
  可做"分支存活/已合并分支清理"类需求，但拿不到分支创建时间
- `GET /projects/:id/repository/commits/:sha/refs` —— 某次提交被推到了哪些分支/标签
- `GET /projects/:id/repository/commits/:sha/statuses` —— 某次提交的 CI 任务状态
- `GET /projects/:id/repository/merge_requests/:iid/notes` 等评论类接口（本次未接）
- MR 的写接口（创建/合并/关闭）与 `approvals`：**本组工具只读，不接写操作**
