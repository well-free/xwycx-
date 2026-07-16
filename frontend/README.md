# Web 前端

这里是 React + Vite 前端。页面入口仍然保留为 `index.html`、`products.html`、`orders.html`、`admin.html` 和 `login.html`，因此 Spring Boot 的登录跳转和支付回跳地址不需要改变。

开发：

```powershell
npm install
npm run dev
```

生产构建：

```powershell
npm run build
```

前端单元测试：

```powershell
npm test
```

Maven 会在构建后端时自动安装 Node 依赖并执行 `npm run build`，再把 `dist/` 复制到 Spring Boot JAR 的 `static/` 目录。前端继续通过 `/api/**` 调用后端接口。公共组件位于 `src/components/`，请求服务位于 `src/services/`，可复用 Hook 位于 `src/hooks/`。
