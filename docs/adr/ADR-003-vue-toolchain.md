# ADR-003：Vue 与前端工具链

- 状态：接受
- 日期：2026-09-03

## 决策

生产前端使用 Vue 3 + TypeScript + Vite 8，Node.js 24 LTS，包管理器 npm 并提交 `package-lock.json`。UI 使用 Element Plus 2.x，图表使用 Apache ECharts 6，谱系使用 AntV X6。

## 原因

- Vue 官方建议新项目使用 Vue 3，并使用官方 `create-vue`/Vite 路线。
- Vite 8 支持当前现代浏览器基线，Node 24 当前处于 LTS。
- Element Plus 适合中文 B 端表格、表单、步骤和时间线；X6面向 DAG/血缘图，ECharts满足温控曲线和分段规则。

依据：[Vue 快速开始](https://vuejs.org/guide/quick-start)、[Vite 8 发布说明](https://vite.dev/blog/announcing-vite8)、[Node.js 版本状态](https://nodejs.org/en/about/previous-releases)、[Element Plus 安装](https://element-plus.org/en-US/guide/installation.html)、[AntV X6 介绍](https://x6.antv.antgroup.com/en/tutorial/about)、[Apache ECharts](https://echarts.apache.org/en/)。

## 后果

- 不使用已进入维护历史的 Vue CLI 新建项目，不支持 IE11。
- 原型目录使用 Product Design 自带 React 运行时，仅作 Phase 1 交互验证，不是生产前端技术选型。
- 精确补丁版本在 Phase 2 初始化时由 lockfile 固定，依赖升级单独走 PR。

