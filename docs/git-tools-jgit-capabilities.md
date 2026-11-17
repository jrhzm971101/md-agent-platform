# JGit 代码仓库工具能力说明（已废弃）

> 本文件描述的是旧版 JGit 实现，代码与依赖均已移除。
> 当前实现请阅读 [GitLab API 工作区工具说明](./gitlab-api-workspace-tools.md)。

## 变更摘要

- JGit 依赖已从根 `pom.xml` 和 `md-agent-server/pom.xml` 删除。
- `git-clone` / `git-checkout` / `git-pull` / `git-fetch` / `git-log` /
  `git-show-commit` / `git-show-diff` / `git-worktree-*` 全部改为 GitLab REST API。
- 分支不再通过本地 checkout 切换，而是按分支下载 archive.zip 到独立目录。
- 多分支并行依靠目录隔离，不再依赖 Git worktree 元数据。
- 工具名和主要参数保持兼容，详见新文档。
