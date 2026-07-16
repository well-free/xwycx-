# 后端

Spring Boot 后端包含接口、业务服务、持久化实现、数据库迁移和自动化测试。

从仓库根目录运行：

```powershell
mvn -f backend/pom.xml spring-boot:run
mvn -pl backend test
```

Web 前端源码位于 `../frontend/`，Maven 构建时会自动执行 Vite 构建，并把 `frontend/dist/` 复制到 JAR 的 `static/` 目录。
