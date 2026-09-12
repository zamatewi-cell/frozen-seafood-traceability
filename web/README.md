# 冷冻海产品溯源系统 - 消费者查询 Web 前端

面向消费者的冷冻海产品公开追溯与履历查验生产前端应用。基于 Vue 3 + TypeScript + Vite 8 构建，遵循 [ADR-003 前端技术选型决策](../docs/adr/ADR-003-vue-toolchain.md)，严格对接已合入的 FR-TRACE-002 公开追溯接口。

> **提示**：本目录为正式生产前端工程。根目录下的 `prototype/` 原型工程为 Phase 1 阶段交互走查参考基线，保持冻结不作修改。

---

## 一、技术栈与工程特性

- **核心框架**：Vue 3.5+ (Composition API, `<script setup lang="ts">`)
- **语言标准**：TypeScript 5.7+，开启严格空值检查与全类型约束
- **构建工具**：Vite 8.2+（内置生产 ESNext 摇树与按需打包）
- **路由管理**：Vue Router 4.5+（HTML5 History 模式）
- **代码规范**：ESLint 9+ Flat Config + typescript-eslint + eslint-plugin-vue
- **类型检查**：vue-tsc
- **测试框架**：
  - 单元与组件测试：Vitest 5.0+ 与 @vue/test-utils (jsdom 环境)
  - 端到端浏览器测试：Playwright (支持 360px, 390px, 桌面视口及真实交互模拟)
  - 冒烟验证：真实浏览器经 Vue/Vite 代理访问 Spring Boot 与 MySQL 8.4 的隔离夹具验证脚本

---

## 二、视觉设计与交互规范

- **视觉色彩基准**：
  - 顶部导航：沉稳深蓝 Navy (`#0f2742`)，搭配冰雪蓝点缀 (`#38bdf8`, `#bae6fd`)；
  - 强调色：海洋蓝 (`#0284c7`，悬停 `#0369a1`)；
  - 页面画布：冷灰浅底 (`#f8fafc`)；
  - 卡片容器：白底高对比卡片 (`#ffffff`)，配合细微边框 (`#e2e8f0`) 与轻量阴影；
  - 状态徽标：双重呈现（语义文本 + 矢量图标），绝不单纯依赖颜色区分。
- **响应式视口适配**：
  - 紧凑移动优先（Mobile-First）：针对 360px (Android)、390px (iPhone)、480px 等小屏竖屏深度适配，触控区域均 ≥ 44px；
  - 意图清晰的桌面端布局（Desktop）：在宽屏下采用主副两列流式组合（左侧产品属性与温控、右侧流转时间线），避免手机卡片单纯横向拉伸变形。
- **路由定义**：
  - `/trace`：手动输入查验页（含表单、RFC 4648 Base32 格式实时提示与清空功能）；
  - `/trace/:publicTraceId`：直接查询路由（支持扫码一码直达，自动执行参数校验与异步加载）。

---

## 三、安全边界与真实性声明

1. **绝对白名单渲染**：
   - 仅展示服务端 `/api/public/v1/public/traces/{publicTraceId}` 返回的安全白名单字段（产品名称、品类、规格、脱敏批次号、脱敏产地、有效事件时间线、温控摘要、批次状态与声明）；
   - 绝不泄露、解析或推导内部数据库自增 ID、原始批次号、原始产地全称、哈希索引、租户标识或操作员敏感信息。
2. **零前端持久化**：
   - 遵循最小必要原则，绝不在 `localStorage` 或 `sessionStorage` 中缓存追溯码、用户信息或查询响应。
3. **禁止动态 HTML 注入**：
   - 全组件严禁使用 `v-html` 指令，彻底防范 XSS 跨站脚本攻击。
4. **不可探测性与中性提示**：
   - 针对格式非法码（未通过 `^[A-Z2-7]{26}$`）、未知码、已停用码，页面对外展示完全一致的中性“未找到该追溯码或该码已失效”提示，杜绝攻击者探测码的生命周期状态。
5. **真实性诚实披露 (Truthfulness)**：
   - `SIMULATED` 数据源明确展示为“教学演练与仿真模拟数据”；
   - `DEVICE` 明确标注为“标准预留设备标识（未接入真实硬件）”，绝不虚假宣传真实物联网采集；
   - `INSUFFICIENT_DATA` 温控结论如实提示当前切片尚未接入连续采集流，不伪造温控合规图表或防伪背书。

---

## 四、本地启动与开发联调

### 1. 前置依赖
- Node.js 24 LTS (推荐 v24.13+)
- npm 11+
- 本地 MySQL 8.4 容器（默认通过 `deploy/docker-compose.yml` 运行在 `3307` 端口）

### 2. 安装依赖
```bash
cd web
npm install
```

首次或锁文件更新后可使用 `npm install`；日常验收与 CI 请使用 `npm ci` 保证依赖完全按锁文件安装。

### 3. 本地开发服务器启动
```bash
npm run dev
```
启动后访问 `http://localhost:5173/trace`。Vite 已配置本地反向代理：
- 将 `/api` 请求自动转发至 `http://localhost:8080`；
- 无需修改服务端 CORS 配置，安全隔离开发环境。

### 4. 环境变量配置
可在 `web/` 根目录下创建 `.env.local` 覆盖默认配置：
```ini
# 覆盖后端代理目标地址（默认 http://localhost:8080）
VITE_BACKEND_PROXY_TARGET=http://localhost:8080

# 生产或测试环境直接指定绝对 API 根路径（可选）
# VITE_API_BASE_URL=http://localhost:8080
```

---

## 五、质量保障与测试命令

| 命令 | 说明 | 门禁标准 |
|---|---|---|
| `npm run lint` | ESLint 静态代码风格与 Vue 规范检查 | 0 error, 0 warning |
| `npm run typecheck` | vue-tsc 完整类型检查 | 0 error |
| `npm run test` | Vitest 单元与组件交互测试 | 全部绿色通过 |
| `npm run build` | Vite 生产包编译构建 | 产物输出至 `dist/` |
| `npm run test:e2e` | Playwright 多端视口 (360/390/desktop) 浏览器自动化测试 | 全部绿色通过 |
| `npm run test:smoke` | 真实 Vue → Vite proxy → Spring Boot → MySQL 8.4 冒烟 | 成功后夹具残留 0 行 |

---

## 六、生产部署

执行构建命令生成纯静态前端资产包：
```bash
npm run build
```
编译生成的所有静态 HTML、CSS、JS 文件位于 `web/dist/`。部署时需将 `/trace/**` 配置为回退到 `index.html`，并将 `/api` 反向代理到 Spring Boot 服务。

### 真实联调冒烟

`npm run test:smoke` 会读取仓库根目录未提交的 `.env`（或同名环境变量），要求 `seafood-mysql` 容器可用。脚本会分配并确认无碰撞的随机夹具标识，启动独立的 `18081` 端口 Spring Boot 进程，再由 Playwright 驱动页面经过 Vite 代理完成查询。无论成功或失败，脚本都会按精确主键物理清理夹具并验证残留为 0；缺少后端或数据库时测试会失败，不会降级为假成功。
