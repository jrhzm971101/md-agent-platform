# Git 工具全 GitLab API 化改造说明

## 一、结论

本次改造把原先依赖 JGit 的 Git 工具全部改为 **GitLab REST API 工作区物化模式**：

- 不再依赖 JGit，不再需要本地 `.git` 目录。
- 仍然支持本地读取、检索、分析代码。
- 支持同一仓库同时准备多条分支的独立目录，可以并行分析、互不干扰。
- 本地布局为“一个仓库一个目录，目录下按分支分子目录”。
- 可从部署镜像 tag 反查流水线，并按流水线精确 commit 物化代码。
- 保留原有工具名，但所有工具新增必填参数 `environment`；GitLab 地址和鉴权信息从环境目录读取，不再使用全局固定配置。

需要明确的是：GitLab 11.5 **没有**本地 checkout / worktree / merge / commit 这类工作区 API。
因此本方案不是“远程执行 git checkout”，而是：

> 用 GitLab API 解析分支最新 commit，下载该 commit 的 archive.zip，
> 解压成独立目录。一个分支一个目录，以目录隔离代替 Git 工作区。

这正好满足“本地读取分析 + 并行”的目标，但不支持本地提交、推送、合并冲突处理等写操作。

---

## 二、改造目标与边界

### 已支持

- 通过项目 ID、项目路径、仓库 URL 解析 GitLab 项目。
- 获取默认分支、指定分支、标签、commit SHA。
- 下载任意 commit 的完整文件树到本地独立目录。
- 同一仓库多个分支并行下载、并行读取。
- 查询提交历史、单次提交详情、提交 diff、区间 diff。
- 查询远程分支/标签元数据。
- 刷新工作区到远端最新 commit。
- 列出、删除、清理工作区。

### 不支持或不再支持

- 本地 commit / push / merge / rebase / reset / stash。
- 基于本地未提交改动的 diff。
- 真正的 Git 分支跟踪关系和 `.git/worktrees` 元数据。
- 使用 GitLab API 修改远端仓库。
- 通过 `context` 自定义 diff 上下文行数（服务端固定）。
- 通过 `depth` 做浅克隆（archive 总是完整文件树）。
- 通过 `unshallow` 补全本地历史（API 查询历史不需要本地历史）。

---

## 三、总体架构

```text
Agent 调用 git-* 工具（必传 environment）
        |
        v
EnvironmentRegistry.gitlab(environment) -> url/apiUrl + token
        |
        v
GitlabWorkspaceManager
        |
        +-- 解析 repoRef：alias / projectId / path / URL / 工作区路径
        +-- 解析 ref：branch / tag / commit SHA
        +-- GET /projects/:id                         项目信息
        +-- GET /repository/branches/:branch          最新 commit SHA
        +-- GET /repository/tags/:tag                 tag commit SHA
        +-- GET /repository/commits/:sha              精确 commit
        +-- GET /repository/archive.zip?sha=<sha>     下载文件树
        +-- GET /projects/:id/pipelines/:pipeline_id 镜像 tag -> ref/sha
        |
        v
git.base-dir/<alias>/                  仓库目录（一个仓库一个）
git.base-dir/<alias>/<branch>/         分支/标签快照（仓库目录下按分支隔离）
        |
        +-- .md-agent-workspace.json   每个工作区的清单
        |
        v
filesystemTool / 本地 Java 文件读取
```

### 3.1 仓库标识解析顺序

`repoDir` / `repoUrl` 参数可以是以下任意一种：

1. `git-clone` 登记过的仓库别名，如 `lms`。
2. 已存在的分支目录绝对路径，如 `.../workspace/code/lms/master`。
3. GitLab 项目 ID，如 `1367`。
4. GitLab 项目路径，如 `mtn/lms`。
5. Git 仓库 URL，如 `https://git.huojintech.com/mtn/lms.git` 或 `git@git.huojintech.com:mtn/lms.git`。

### 3.2 分支与版本解析

`ref` 支持：

- 分支：`master`、`MTN-Feature/Cameroon`
- 标签：`v1.0.0`
- commit SHA：完整或 GitLab 接受的短 SHA
- `origin/xxx`：会去掉 `origin/` 前缀
- `HEAD`、`HEAD~1`：会转换为当前工作区分支的对应版本

### 3.3 工作区物化流程

```text
1. 解析项目与 ref
2. 查询 ref 对应的 commit SHA
3. 下载 GET /repository/archive.zip?sha=<commitSha>
4. 解压到 <target>.tmp-<uuid>
5. 写入 .md-agent-workspace.json
6. 若目标目录已存在，先移动到 .old-<uuid>
7. 原子移动到 <target>
8. 删除 backup 与临时文件
```

这保证了：

- 下载/解压失败不会破坏已有工作区。
- 多个分支并行执行时不会共享临时目录。
- 不会出现 Git index.lock 或 worktree 元数据冲突。
- 每个目录的内容都与清单中的 commit SHA 一一对应。

### 3.4 并发控制

- 每个目标目录使用独立 `ReentrantLock`，防止同一目录被并发写坏。
- 不同分支目录使用不同锁，可以并行下载。
- 仓库别名注册表 `.md-agent-repos.json` 使用独立锁保护。
- `git-worktree-prune` 只清理超过 10 分钟的临时项，避免误删正在执行的下载。

---

## 四、目录结构与清单

假设配置：

```yaml
git:
  base-dir: D:\jinran\code\Md-Agent-Platform\workspace\code
```

执行：

```text
git-clone        environment=cameroon-uat repoUrl=mtn/lms repoDir=lms
git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/Cameroon
git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/CongoB
```

本地目录：

```text
workspace/code/
  .md-agent-repos.json
  lms/                                  # 仓库目录：一个仓库一个
    master/                             # 分支目录
      .md-agent-workspace.json
      ...master 文件树...
    MTN-Feature-Cameroon/               # 另一条分支，与 master 并列
      .md-agent-workspace.json
      ...Cameroon 分支文件树...
    MTN-Feature-CongoB/
      .md-agent-workspace.json
      ...CongoB 分支文件树...

这样 `repoDir=lms` 永远指向仓库根目录，`repoDir=lms/master`、
`repoDir=lms/MTN-Feature-Cameroon` 分别指向具体分支。分支目录互不共享文件，
可以并行读取和分析。
```

### .md-agent-workspace.json

```json
{
  "version": "3",
  "environment": "cameroon-uat",
  "projectId": 1367,
  "projectPath": "mtn/lms",
  "alias": "lms",
  "layout": "repo/branch",
  "branch": "MTN-Feature/Cameroon",
  "commitSha": "5df9d2d19294dee4f5cecd6932fc2b4ad080b53d",
  "commitDate": "2026-09-18T18:17:14+08:00",
  "refType": "branch",
  "kind": "workspace",
  "source": "gitlab-rest-archive",
  "fetchedAt": "2026-11-04T04:05:00Z"
}
```

| 字段 | 说明 |
| --- | --- |
| `environment` | 物化时使用的环境名；不同环境不能复用同一目录 |
| `projectId` | GitLab 项目 ID |
| `projectPath` | 项目路径，如 `mtn/lms` |
| `alias` | 仓库目录名，即 `git-clone` 的 repoDir 别名 |
| `layout` | 固定为 `repo/branch`，表示仓库目录下按分支隔离 |
| `branch` | 实际解析后的分支/标签/commit 名称 |
| `commitSha` | 目录对应的精确 commit |
| `refType` | `branch` / `tag` / `commit` |
| `kind` | `base`（clone 基准目录）或 `workspace`（分支快照） |
| `fetchedAt` | 本地物化时间 |

### .md-agent-repos.json

仓库别名注册表，用于让 `repoDir=lms` 能解析回 GitLab 项目。别名按 `environment|alias` 隔离，避免不同 GitLab 实例的同名仓库互相串用：

```json
{
  "cameroon-uat|lms": {
    "environment": "cameroon-uat",
    "projectId": 1367,
    "pathWithNamespace": "mtn/lms",
    "defaultBranch": "master",
    "httpUrlToRepo": "http://git.huojintech.com/mtn/lms.git",
    "webUrl": "https://git.huojintech.com/mtn/lms",
    "updatedAt": "2026-11-04T04:00:00Z"
  }
}
```

---

## 五、配置

GitLab 地址和鉴权信息 **不再放在全局 `gitlab` 配置里**，而是按环境维护。应用配置只保留与实例无关的超时和工作区根目录：

```yaml
git:
  base-dir: D:\jinran\code\Md-Agent-Platform\workspace\code

gitlab:
  timeout-ms: 30000
  archive-timeout-ms: 300000
```

平台只从数据库环境目录读取。升级后启动时，`EnvironmentSeeder` 会自动补齐缺失的内置分组模板和缺失的内置键声明（包括 `gitlab.authMode` / `password` 等）；已有键定义和配置值不会被覆盖。随后在环境管理页（`/environments`）给每个环境填写 `url` 和鉴权信息。`application.yml` 不再提供环境数据，数据库不可用时也不会回退到 yml。

> 升级提示：启动播种会为内置分组补齐缺失的键声明，只增不删、不改任何值；如果环境表尚未初始化或初始化失败，服务仍可启动，但环境目录读取会明确报数据库不可用。请在执行 `db/env-schema.sql` 后到环境管理页维护环境和 `authMode` / `password`（以及可选的 OAuth 键）。已有库里的 `token` 键可能仍标记为必填，这只影响环境完整度显示，不阻断保存和使用。

每个环境在环境目录里增加 `gitlab` 分组。静态 PAT 写法：

```yaml
# 环境目录 → cameroon-uat → gitlab 分组
gitlab:
  url: https://git.huojintech.com        # 站点根地址，工具自动补 /api/v4
  # apiUrl: https://git.huojintech.com/api/v4  # 可选；填了优先于 url
  token: ${GITLAB_TOKEN:}
  username: jinran                       # 静态 token 模式下仅展示
```

没有静态 token 时，用用户名/密码动态换取（session 模式）：

```yaml
gitlab:
  url: https://cmr-gitlab.mindigitaltech.com
  authMode: session
  username: dev_read
  password: ${CMR_GITLAB_PASSWORD:}
  tokenCacheSeconds: 3600                # 可选；0 表示每次调用都重新获取
```

GitLab 已开启 OAuth password grant 时可用 oauth 模式：

```yaml
gitlab:
  url: https://git.example.com
  authMode: oauth
  username: dev_read
  password: ${GITLAB_PASSWORD:}
  oauthClientId: ${GITLAB_OAUTH_CLIENT_ID:}
  oauthClientSecret: ${GITLAB_OAUTH_CLIENT_SECRET:}
  oauthScope: read_api
  # oauthTokenUrl: https://git.example.com/oauth/token  # 子路径部署时可选
```

页面维护时的分组键：

| key | 必填 | 说明 |
| --- | --- | --- |
| `url` | 是 | GitLab 站点根地址；带不带 `/api/v4` 都可以 |
| `apiUrl` | 否 | 完整 API 根地址；存在时优先使用 |
| `authMode` | 否 | `auto` / `token` / `session` / `oauth`，默认 `auto`；auto 会先试 session，端点不存在时回退 oauth |
| `token` | 否 | 静态 Personal Access Token；非空时优先使用。必须是 API PAT，`glft-` 开头的 Feed Token 不可用于 API |
| `username` | 否 | 动态鉴权登录名；静态 token 模式下仅作备注 |
| `password` | 否 | 动态鉴权密码；`session` / `oauth` 模式使用 |
| `oauthClientId` | 否 | OAuth 应用 client_id；password grant 需要时填写 |
| `oauthClientSecret` | 否 | OAuth 应用 client_secret |
| `oauthScope` | 否 | OAuth scope，如 `read_api` |
| `oauthTokenUrl` | 否 | 默认 `<站点根>/oauth/token`；子路径部署时填写 |
| `tokenCacheSeconds` | 否 | 动态 token 缓存秒数，默认 3600；0 表示每次调用都重新获取 |

说明：

- 所有 Git 工具必须传 `environment`，例如 `git-clone environment=cameroon-uat ...`。
- 鉴权优先级：`token` 非空直接用；为空时按 `authMode` 动态换取并缓存。`auto` 先尝试 session，若该 GitLab 版本没有 `/session` 端点（404/405），自动回退 OAuth 密码模式。
- `session` 调 `POST <apiUrl>/session`，用 `private_token` + `PRIVATE-TOKEN` 头。
- `oauth` 调 `POST <站点根>/oauth/token`，用 Bearer 头；过期时优先用 `refresh_token`，刷新失败回退密码登录。
- 动态 token 按“环境 + API 地址 + 模式 + 用户名”缓存；距离过期不足 60 秒视为失效，接口返回 401 时清缓存并重试一次。
- 同一个环境可以指向不同 GitLab 实例；换环境就换地址和凭据，不需要重启。
- `timeout-ms` 用于分支、提交、diff 等普通 API，也用于动态换取 token。
- `archive-timeout-ms` 用于大仓库 archive 下载，默认 5 分钟。
- 静态 token 需要至少具备 `read_api` / `read_repository`（GitLab 11.5 对应 `api` 或 `read_repository` 权限）；动态账号需要同等项目权限。
- 工作区别名按环境隔离；同一个 `repoDir` 已绑定环境 A 后，环境 B 再使用会提示换别名，避免不同实例的同名仓库互相覆盖。

---

## 六、API 映射

| 能力 | GitLab API |
| --- | --- |
| 项目解析 | `GET /projects/:id` |
| 项目路径解析 | `GET /projects/:encoded_path` |
| 分支列表 | `GET /projects/:id/repository/branches` |
| 分支详情 | `GET /projects/:id/repository/branches/:branch` |
| 标签列表 | `GET /projects/:id/repository/tags` |
| 标签详情 | `GET /projects/:id/repository/tags/:tag` |
| commit 详情 | `GET /projects/:id/repository/commits/:sha` |
| commit 历史 | `GET /projects/:id/repository/commits` |
| 单提交 diff | `GET /projects/:id/repository/commits/:sha/diff` |
| 区间 diff | `GET /projects/:id/repository/compare?from=&to=` |
| 流水线列表 | `GET /projects/:id/pipelines` |
| 流水线详情 | `GET /projects/:id/pipelines/:pipeline_id` |
| 文件树下载 | `GET /projects/:id/repository/archive.zip?sha=<sha>` |

---

## 七、工具说明

以下工具名保持不变，便于现有 Agent 配置和技能直接迁移。

### 7.1 git-clone

**作用**：创建“仓库目录 + 默认分支子目录”，并登记 `repoDir` 到 GitLab 项目的别名。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoUrl` | 是 | 项目 ID / 路径 / URL，如 `1367`、`mtn/lms`、`https://git.huojintech.com/mtn/lms.git` |
| `repoDir` | 是 | 仓库目录名/别名，如 `lms`；仓库目录为 `<git.base-dir>/lms`，分支目录为 `<git.base-dir>/lms/<branch>` |
| `branch` | 否 | 默认分支或指定分支/标签/SHA |
| `depth` | 否 | 兼容旧参数，API 快照模式忽略 |

返回：绝对路径、分支、commit SHA、项目路径。

```text
git-clone environment=cameroon-uat repoUrl=mtn/lms repoDir=lms
git-clone environment=cameroon-uat repoUrl=1367 repoDir=lms branch=master
# 结果示例：workspace/code/lms/master
```

说明：目录没有 `.git`；不要在该目录执行 git commit/push。

---

### 7.2 git-checkout

**作用**：为指定分支/标签/commit 准备独立目录。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 已 clone 的别名/目录，如 `lms` |
| `branch` | 是 | 分支/标签/SHA，如 `MTN-Feature/CDI` |

返回：独立目录绝对路径、分支、commit SHA。

```text
git-checkout environment=cameroon-uat repoDir=lms branch=MTN-Feature/Cameroon
```

行为：

- 不修改 `lms` 仓库目录下的其它分支目录。
- 目录固定为 `lms/MTN-Feature-Cameroon`。
- 同一分支重复调用会复用或刷新该目录。
- 这是“只读快照切换”，不是本地 Git HEAD 切换。

---

### 7.3 git-pull

**作用**：查询目标 ref 的最新 commit，必要时刷新工作区。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 基准目录、分支目录或别名 |
| `branch` | 否 | 不传则使用目录已绑定分支/当前分支 |

```text
git-pull environment=cameroon-uat repoDir=lms
git-pull environment=cameroon-uat repoDir=lms/MTN-Feature-Cameroon
git-pull environment=cameroon-uat repoDir=lms/MTN-Feature-Cameroon branch=MTN-Feature/Cameroon
```

行为：

- 调用分支详情接口获取最新 SHA。
- SHA 相同：不重复下载，返回“已是最新”。
- SHA 不同：重新下载 archive 并原子替换目标目录。
- 不存在 merge/rebase，也不会产生冲突。

---

### 7.4 git-fetch

**作用**：查询远程分支和标签元数据。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 别名/项目路径/项目 ID |
| `remote` | 否 | 兼容旧参数，默认 `origin` |
| `allBranches` | 否 | 兼容旧参数；API 返回可见分支 |
| `unshallow` | 否 | 兼容旧参数；无作用 |
| `tags` | 否 | 是否返回标签，默认 `false` |
| `prune` | 否 | 兼容旧参数；无作用 |

返回 JSON：`success`、`projectId`、`projectPath`、`branches[]`、`tags[]`、`branchCount`、`tagCount`。

说明：新模式下 fetch 不再是必要步骤。`git-checkout` / `git-worktree-add` 会直接查询最新 SHA。

---

### 7.5 git-log

**作用**：通过 GitLab commits API 查询提交历史。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 别名/工作区/项目路径/ID |
| `ref` | 否 | 分支/标签/commit，默认当前分支 |
| `path` | 否 | 只看影响该路径的提交 |
| `author` | 否 | 作者姓名/邮箱子串，本地过滤 |
| `keyword` | 否 | 标题/正文关键字，本地过滤 |
| `since` / `until` | 否 | `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm:ss` |
| `maxCount` | 否 | 默认 50，最大 1000 |
| `skip` | 否 | 分页偏移 |
| `noMerges` | 否 | 排除合并提交 |

返回 JSON：`success`、`projectId`、`ref`、`currentBranch`、`count`、`scannedPages`、`truncated`、`commits[]`。

`commits[]` 字段：`commitId`、`shortId`、`author`、`authorEmail`、`authorTime`、`committer`、`commitTime`、`subject`、`message`、`merge`、`parents`。

```text
git-log environment=cameroon-uat repoDir=lms ref=master maxCount=20
git-log environment=cameroon-uat repoDir=lms path=src/main/java since=2026-08-01 author=zhangsan
```

说明：API 支持 `ref_name`、`path`、`since`、`until`；作者/关键字/排除 merge/分页偏移由本地过滤。

---

### 7.6 git-show-commit

**作用**：查看单次提交元信息和改动文件清单。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 别名/工作区/项目路径/ID |
| `commitId` | 是 | SHA/分支/标签，支持 HEAD、HEAD~1 |
| `path` | 否 | 只看指定文件/目录 |
| `maxFiles` | 否 | 默认 200，最大 2000 |
| `detectRenames` | 否 | 兼容旧参数；API 自带 `renamed_file` |

返回 JSON 核心结构：

```json
{
  "success": true,
  "commit": {
    "commitId": "...",
    "shortId": "...",
    "author": "...",
    "authorTime": "...",
    "subject": "...",
    "message": "...",
    "merge": false,
    "parents": ["..."]
  },
  "files": [
    {
      "changeType": "MODIFY",
      "path": "src/App.java",
      "oldPath": "src/App.java",
      "newPath": "src/App.java",
      "additions": 10,
      "deletions": 2,
      "binary": false,
      "oldMode": "100644",
      "newMode": "100644"
    }
  ],
  "stats": {
    "filesChanged": 1,
    "additions": 10,
    "deletions": 2,
    "truncated": false
  }
}
```

说明：GitLab 11.5 的 diff 接口不返回文件级增删行数，工具按 `diff` 文本统计。

---

### 7.7 git-show-diff

**作用**：输出单提交或区间提交的标准 unified diff 文本。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 别名/工作区/项目路径/ID |
| `commitId` | 是 | SHA/分支/标签，支持 HEAD、HEAD~1 |
| `baseCommitId` | 否 | 不传走单提交 diff；传了走 compare |
| `path` | 否 | 本地路径过滤 |
| `context` | 否 | 兼容旧参数；API 固定上下文 |
| `detectRenames` | 否 | 兼容旧参数；API 自带重命名标记 |
| `maxChars` | 否 | 默认 200000 |

返回：标准 diff 文本，包含 `diff --git`、`---`、`+++`、`@@`。

```text
git-show-diff environment=cameroon-uat repoDir=lms commitId=ac26ce73
git-show-diff environment=cameroon-uat repoDir=lms commitId=MTN-Feature/Ghana baseCommitId=master path=pom.xml
```

说明：

- 单提交调用 `/commits/:sha/diff`。
- 指定 `baseCommitId` 调用 `/repository/compare`。
- GitLab 返回的 diff 只有 `@@` 片段，本工具补齐文件头。
- 合并提交默认相对第一个父提交。

---

### 7.8 git-worktree-add

**作用**：为分支创建独立 API 快照工作区；工具名保留 worktree 以兼容 Agent。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 仓库别名/基准目录/项目路径/ID |
| `branch` | 是 | 分支/标签/SHA |
| `path` | 否 | 分支子目录名，默认安全化后的分支名 |
| `force` | 否 | 是否覆盖已有非本分支目录 |
| `detach` | 否 | 兼容旧参数；API 快照无需 detach |
| `update` | 否 | 是否刷新最新 SHA，默认 `true` |

返回：工作区路径、分支、commit SHA。

并行用法：

```text
git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/Cameroon
git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/Uganda
# 实际目录：lms/MTN-Feature-Cameroon、lms/MTN-Feature-Uganda
```

两个调用可以并行执行。

---

### 7.9 git-worktree-list

**作用**：列出某个项目在 `git.base-dir` 下的所有分支快照目录。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 仓库别名/任意工作区/项目路径/ID |

返回：每行包含路径、仓库、类型（`base` / `workspace`）、分支、commit、获取时间、是否最新。

```text
git-worktree-list environment=cameroon-uat repoDir=lms
```

---

### 7.10 git-worktree-remove

**作用**：删除一个 API 快照工作区。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 仓库别名/项目路径/ID |
| `path` | 是 | 工作区目录名或绝对路径 |
| `force` | 否 | 删除 `base` 基准目录时必须传 `true` |

```text
git-worktree-remove environment=cameroon-uat repoDir=lms path=lms/MTN-Feature-Uganda
```

说明：只允许删除 `git.base-dir` 下、清单属于当前项目的 API 工作区。

---

### 7.11 git-pipeline-checkout

**作用**：根据流水线 ID 或部署镜像名，把流水线的精确提交物化到 `<repo>/pipeline-<id>/`。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 仓库别名/目录/项目路径/ID，如 `lms` |
| `pipelineId` | 否 | 流水线 ID 或 URL，如 `109478` |
| `image` | 否 | 镜像名，如 `harbor.example.com/mtn/lms:109478` |
| `path` | 否 | 子目录名，默认 `pipeline-<id>` |

```text
git-pipeline-checkout environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
```

说明：使用 pipeline 的 `sha` 而不是分支 `ref`，因此即使分支 HEAD 已前进，代码仍与流水线构建提交一致。
完整说明见 [gitlab-pipeline-image-to-code.md](./gitlab-pipeline-image-to-code.md)。

---

### 7.12 git-worktree-prune

**作用**：清理下载/解压失败留下的临时文件。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取 |
| `repoDir` | 是 | 仓库别名/项目路径/ID，用于校验 |
| `dryRun` | 否 | 只查看不删除，默认 `false` |

清理对象：

- `*.zip` / `*.zip.tmp-*`
- `*.tmp-*`
- `*.old-*`

说明：只清理超过 10 分钟的临时项；不会删除有 `.md-agent-workspace.json` 的正常工作区。

---

## 八、典型调用链

### 8.1 单分支代码分析

```text
1. git-clone environment=cameroon-uat repoUrl=mtn/lms repoDir=lms branch=master
   # 代码目录：workspace/code/lms/master
2. filesystemTool list_files / grep_files / read_file
3. git-log environment=cameroon-uat repoDir=lms/master ref=master maxCount=20
4. git-show-commit environment=cameroon-uat repoDir=lms/master commitId=<sha>
5. git-show-diff environment=cameroon-uat repoDir=lms/master commitId=<sha>
```

### 8.2 多分支并行分析

```text
1. git-clone environment=cameroon-uat repoUrl=mtn/lms repoDir=lms
2. 并行执行：
   - git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/Cameroon
   - git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/CongoB
3. 并行用 filesystemTool 读取：
   - lms/MTN-Feature-Cameroon
   - lms/MTN-Feature-CongoB
4. 输出结论时标注：分支 + commit SHA
5. git-worktree-remove environment=cameroon-uat repoDir=lms path=lms/MTN-Feature-CongoB 清理不再需要的目录
```

### 8.3 分支差异对比

```text
1. git-worktree-add environment=cameroon-uat repoDir=lms branch=MTN-Feature/Ghana
2. filesystemTool 读取 lms/MTN-Feature-Ghana 目录
3. git-show-diff environment=cameroon-uat repoDir=lms/MTN-Feature-Ghana commitId=MTN-Feature/Ghana baseCommitId=master
4. 需要逐文件分析时，用 git-show-commit environment=cameroon-uat repoDir=lms/MTN-Feature-Ghana commitId=<sha> 先看文件清单
```

### 8.4 从部署镜像定位代码

```text
1. gitlab-pipeline-detail environment=cameroon-uat projectId=1367 image=harbor.example.com/mtn/lms:109478
2. git-pipeline-checkout environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
   # 产物：workspace/code/lms/pipeline-109478
3. filesystemTool 读取该目录，结论标注 pipeline=109478 + ref + sha
```

### 8.5 历史查询

```text
git-log environment=cameroon-uat repoDir=lms/master ref=master path=src/main/java since=2026-08-01
git-show-commit environment=cameroon-uat repoDir=lms/master commitId=HEAD~1
git-show-diff environment=cameroon-uat repoDir=lms/master commitId=HEAD~1
```

---

## 九、与旧 JGit 实现的行为差异

| 能力 | 旧 JGit | 新 API 快照 |
| --- | --- | --- |
| 本地目录 | 完整 `.git` 仓库 | 无 `.git` 的源码目录 |
| checkout | 修改当前目录 HEAD | 新建/刷新独立分支目录 |
| pull | fetch + merge/rebase | 查询 SHA，必要时重新下载 |
| fetch | 更新本地 refs | 查询远程元数据 |
| worktree | 共享对象库 | 每分支独立文件树 |
| 并发 | 受 Git 锁/工作区限制 | 目录隔离，可并行 |
| 历史查询 | 依赖本地历史 | 直接查 GitLab API |
| 未提交改动 | 支持 | 不支持（只读分析） |
| 磁盘占用 | 共享对象库，较小 | 每分支一份文件树，较大 |

兼容参数（语义已变化）：

- `depth`：忽略。
- `unshallow`：忽略。
- `prune`（git-fetch）：忽略。
- `detach`：忽略。
- `context`：忽略（API 固定上下文）。
- `detectRenames`：忽略（API 自带重命名标记）。
- `force`：保留，用于覆盖目录/删除 base。

---

## 十、GitLab 11.5 限制

1. 没有完整 OpenAPI spec，按 REST 文档和实测字段实现。
2. 分支接口没有 `created_at`。
3. commit 列表的 `author` 参数不生效，本地过滤。
4. diff 接口不返回文件级增删行数，本地按文本统计。
5. `compare` 的 diff 同样需要本地统计行数。
6. archive 是文件树快照，不提供 Git 历史对象。
7. 没有 checkout / worktree / status / stash / rebase API。
8. `context` 不能自定义；需要不同上下文只能本地二次处理。
9. archive 下载耗时受仓库大小影响，建议配置足够的 `archive-timeout-ms`。

---

## 十一、测试与验证

### 11.1 编译

```powershell
mvn -pl md-agent-server -am -DskipTests compile
```

### 11.2 单元测试

```powershell
mvn -pl md-agent-server -am "-Dtest=GitlabWorkspaceManagerTest,GitlabPipelineTest,GitlabProjectCommitsTest,GitlabCommitDiffTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

覆盖：目录名扁平化、项目/分支 URL 编码、ZIP 单根目录剥离、ZIP 路径穿越防护、
工作区清单读取、递归删除、流水线 ID/镜像 tag 解析、GitLab API 映射逻辑。

### 11.3 真实 GitLab 全链路测试

默认跳过，设置环境变量后执行：

```powershell
$env:GITLAB_LIVE_TEST="true"
$env:GITLAB_TOKEN="<token>"
# 可选：让测试直接写入项目工作区，便于观察目录结构
$env:GITLAB_LIVE_BASE_DIR="D:\jinran\code\Md-Agent-Platform\workspace\code"
mvn -pl md-agent-server -am "-Dtest=GitlabApiToolsLiveTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

实测覆盖：

- `git-clone` 物化 `mtn/lms` master
- `git-fetch` 查询分支/标签
- `git-log` 查询提交
- `git-show-commit` 查看文件清单
- `git-show-diff` 输出 patch
- 两个分支并行 `git-worktree-add`
- `git-checkout` 复用/创建分支目录
- `git-worktree-list`
- `git-pull`
- `git-worktree-remove`
- `git-worktree-prune`

本次实测结果：`Tests run: 1, Failures: 0, Errors: 0`，两条分支并行物化成功。

产物目录：

```text
workspace/code/lms/
  master/                         # git-clone 创建的默认分支目录
    .md-agent-workspace.json
    pom.xml
    lms-business/
    lms-domain/
    ...
  MTN-Feature-Cameroon/           # 并行 add 的分支目录
    .md-agent-workspace.json
    pom.xml
    lms-api-contract/
    lms-business/
    ...

# MTN-Feature-CongoB/ 在测试中创建，随后被 git-worktree-remove 删除，用于验证删除工具。
# 实际保留的是 master 与 MTN-Feature-Cameroon 两个分支目录。
```

---

## 十二、运维建议

1. **磁盘**：每个分支目录都是一份完整文件树，长期运行需定期 `git-worktree-remove`。
2. **超时**：大仓库首次 clone 可能超过 30 秒，必须依赖 `archive-timeout-ms`。
3. **token**：只读分析建议使用最小权限 token；不要在前端或日志中暴露。
4. **并发**：并行分支数受 GitLab 服务端带宽和本地磁盘限制，建议按需 2~4 个。
5. **清理**：异常中断后先 `git-worktree-prune environment=cameroon-uat repoDir=lms dryRun=true` 查看，再执行清理。
6. **版本锚定**：分析结论必须标注分支和 commit SHA，避免“代码已更新但结论未更新”。
