# 从部署镜像反查流水线与代码

## 一、结论

可以完成。GitLab 11.5 虽然不支持远程 checkout，但流水线接口能直接给出：

- 流水线 ID
- 分支/标签 ref
- 该流水线构建的精确 commit SHA
- 状态、触发人、时间、耗时

因此可以形成完整链路：

```text
环境镜像 tag
  -> pipeline id
  -> GET /projects/:id/pipelines/:pipeline_id
  -> ref + sha
  -> GET /projects/:id/repository/archive.zip?sha=<sha>
  -> 本地分支快照目录
  -> 代码检索与分析
```

本项目约定**镜像版本号 = 流水线号**，所以镜像 tag 可以直接当 pipeline id 使用。

---

## 二、接口实测

### 2.1 查询单条流水线

```http
GET /api/v4/projects/1367/pipelines/109478
PRIVATE-TOKEN: ***
```

实测返回：

```json
{
  "id": 109478,
  "sha": "5ec23276f56b4a76db1d84017556e178a739c65b",
  "ref": "mtn-main/cameroon-airtime",
  "status": "failed",
  "web_url": "http://git.huojintech.com/mtn/lms/pipelines/109478",
  "before_sha": "5ddb2ef016d3e88cf3827a778ecf1abfc932fd1c",
  "tag": false,
  "user": {
    "name": "陈尤俊",
    "username": "chenyoujun"
  },
  "created_at": "2026-11-04T14:09:04.718+08:00",
  "started_at": "2026-11-04T14:09:32.240+08:00",
  "finished_at": "2026-11-04T14:11:10.639+08:00",
  "duration": 75
}
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `ref` | 流水线来自哪条分支/标签 |
| `sha` | 流水线实际构建的精确提交 |
| `status` | success / failed / running 等 |
| `web_url` | 可直接打开流水线页面 |
| `before_sha` | 上一次构建的提交，可用于对比 |

### 2.2 查询流水线列表

```http
GET /api/v4/projects/1367/pipelines?ref=mtn-main/cameroon-airtime&per_page=2
GET /api/v4/projects/1367/pipelines?sha=5ec23276f56b4a76db1d84017556e178a739c65b
GET /api/v4/projects/1367/pipelines?status=failed&per_page=2
```

GitLab 11.5 实测支持 `ref`、`sha`、`status`、`username` 过滤。

---

## 三、新增工具

### 3.1 gitlab-pipeline-detail

从 pipeline id、pipeline URL 或镜像名查询单条流水线。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取（静态 token 优先，缺失时动态换取） |
| `projectId` | 是 | 项目 ID / 路径 / 已登记别名，如 `1367`、`mtn/lms`、`lms` |
| `pipelineId` | 否 | 流水线 ID 或 URL，如 `109478` |
| `image` | 否 | 镜像名，如 `harbor.example.com/mtn/lms:109478` |

`pipelineId` 与 `image` 至少填一个；同时填时以 `pipelineId` 为准。

注意：`image` 只用于解析 pipeline id，不会根据镜像仓库路径自动推断 GitLab 项目；`projectId` 必须显式传项目 ID、路径或当前环境已登记的别名。

示例：

```text
gitlab-pipeline-detail environment=cameroon-uat projectId=1367 pipelineId=109478
gitlab-pipeline-detail environment=cameroon-uat projectId=mtn/lms image=harbor.example.com/mtn/lms:109478
```

返回：

```json
{
  "success": true,
  "projectId": 1367,
  "projectPath": "mtn/lms",
  "pipelineId": 109478,
  "ref": "mtn-main/cameroon-airtime",
  "sha": "5ec23276f56b4a76db1d84017556e178a739c65b",
  "shortSha": "5ec23276",
  "status": "failed",
  "webUrl": "http://git.huojintech.com/mtn/lms/pipelines/109478",
  "suggestedCheckout": "git-pipeline-checkout environment=cameroon-uat repoDir=mtn/lms pipelineId=109478"
}
```

### 3.2 gitlab-pipelines

按条件查询流水线列表，适合不知道 pipeline id、只知道分支或提交的场景。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取（静态 token 优先，缺失时动态换取） |
| `projectId` | 是 | 项目 ID / 路径 / 别名 |
| `ref` | 否 | 分支或标签 |
| `sha` | 否 | commit SHA |
| `status` | 否 | success / failed / running 等 |
| `username` | 否 | 触发人用户名 |
| `maxCount` | 否 | 默认 20，最大 200 |

示例：

```text
gitlab-pipelines environment=cameroon-uat projectId=1367 ref=mtn-main/cameroon-airtime maxCount=5
gitlab-pipelines environment=cameroon-uat projectId=1367 sha=5ec23276f56b4a76db1d84017556e178a739c65b
```

### 3.3 git-pipeline-checkout

按流水线物化代码。它不会用分支 HEAD，而是用流水线的精确 `sha`，保证代码与镜像构建提交一致。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `environment` | 是 | 环境名，如 `cameroon-uat`；GitLab 地址和鉴权信息从该环境目录的 `gitlab` 分组读取（静态 token 优先，缺失时动态换取） |
| `repoDir` | 是 | 仓库别名 / 仓库目录 / 项目路径 / ID，如 `lms` |
| `pipelineId` | 否 | 流水线 ID 或 URL |
| `image` | 否 | 镜像名 |
| `path` | 否 | 分支子目录名，默认 `pipeline-<id>` |

示例：

```text
git-pipeline-checkout environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
git-pipeline-checkout environment=cameroon-uat repoDir=lms pipelineId=109478
```

生成目录：

```text
workspace/code/lms/pipeline-109478/
```

返回中会包含流水线号、ref、精确 SHA、状态和本地绝对路径。

---

## 四、完整使用流程

```text
1. 从环境拿到镜像名，例如 harbor.example.com/mtn/lms:109478
2. 调用 git-pipeline-checkout environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
3. 工具内部：
   - 解析 tag 109478
   - GET /projects/1367/pipelines/109478
   - 得到 ref=mtn-main/cameroon-airtime, sha=5ec23276...
   - GET archive.zip?sha=5ec23276...
   - 解压到 workspace/code/lms/pipeline-109478
4. 用 filesystemTool / grep 在 pipeline-109478 目录分析代码
5. 结论标注 pipeline=109478、ref、sha
```

如果只想先确认流水线信息：

```text
gitlab-pipeline-detail environment=cameroon-uat projectId=1367 image=harbor.example.com/mtn/lms:109478
```

---

## 五、本地演示结果

已用真实 GitLab 跑通 pipeline 109478：

```text
workspace/code/lms/
  master/
  MTN-Feature-Cameroon/
  pipeline-109478/
    .md-agent-workspace.json
    pom.xml
    lms-business/
    lms-common/
    lms-dal/
    ...
```

`pipeline-109478/.md-agent-workspace.json` 关键内容：

```json
{
  "branch": "5ec23276f56b4a76db1d84017556e178a739c65b",
  "commitSha": "5ec23276f56b4a76db1d84017556e178a739c65b",
  "refType": "commit",
  "kind": "workspace",
  "layout": "repo/branch"
}
```

这说明本地目录对应的就是 pipeline 109478 的构建提交，而不是该分支当前 HEAD。

---

## 六、环境参数

三个工具都必须先传 `environment`，GitLab 地址和鉴权信息从该环境的 `gitlab` 分组读取；静态 token 为空时用 `username`/`password` 动态换取并缓存：

```text
gitlab-pipeline-detail environment=cameroon-uat projectId=1367 image=harbor.example.com/mtn/lms:109478
git-pipeline-checkout   environment=cameroon-uat repoDir=lms image=harbor.example.com/mtn/lms:109478
```

同一镜像名在不同 GitLab 实例可能对应不同 pipeline，因此 `environment` 不能省略。

## 七、限制与注意事项

1. **只适用于 tag 可解析为 pipeline id 的镜像**。当前约定 tag 是纯数字或包含数字编号（`v109478`、`release-109478`、`109478-1` 也支持，取 tag 中最长的数字段）。
2. **digest 无法反查**：`image@sha256:...` 不含 pipeline id，需要直接传 `pipelineId`。
3. **failed 流水线仍可物化**：工具会警告，但代码仍可拉取用于排查；请确认该镜像是否真的部署。
4. **镜像名里的 registry 端口不会被误判**：`harbor:5000/mtn/lms:109478` 会取最后一个冒号后的 tag。
5. **代码精确性**：`git-pipeline-checkout` 使用 `sha`，不是 `ref`。分支后来前进不会影响已物化目录。
6. **仍然是只读快照**：没有 `.git`，不支持 commit / push / merge / rebase。
