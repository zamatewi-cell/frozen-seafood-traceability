# 参与项目开发

完整规则见 [开发流程与协作规范](docs/DEVELOPMENT_PROCESS.md)。日常开发至少遵守以下要求：

1. 先选择已进入 `Ready` 的 Issue，确认其中有 PRD 编号和可测试验收条件。
2. 从最新 `main` 创建带 Issue 编号的短分支，例如 `feat/23-batch-create`。
3. 小步实现并同步测试、接口、迁移和必要文档。
4. 本地检查通过后提交 PR，使用 `Closes #23` 关联 Issue。
5. 至少一名非作者成员 Review，CI 全绿、对话解决后再 Squash Merge。
6. 合并后删除功能分支，并在集成环境复核验收场景。

提交示例：

```text
feat(batch): add batch creation flow
fix(trace): reject cyclic batch relation
docs(prd): clarify MVP exclusions
test(auth): cover cross-organization access
```

禁止提交 `.env`、密钥、真实个人/企业资料、日志、构建产物，以及当前目录中的肉类溯源 DOCX、PNG 和 Axure 参考材料。
