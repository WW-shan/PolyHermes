# 本地 Fork 部署说明

## 为什么不能直接用生产镜像配置

仓库里的 `docker-compose.prod.yml` 默认拉取：

```text
wrbug/polyhermes:latest
```

这是上游作者发布的旧镜像，不包含本 fork 里的以下修复和功能：

- Deposit Wallet 账户支持。
- 账户链上赎回按 `txHash` 幂等。
- 手动卖出 neg-risk Exchange 修复。
- Crypto Tail 60 秒 TWAP、150ms 延迟和新费率。
- Leader 池只读优化排名。
- Builder Relayer 认证修复。

如果直接运行：

```bash
docker compose -f docker-compose.prod.yml up -d
```

系统会继续运行上游旧代码，本地提交不会生效。

## 推荐启动方式

在仓库根目录执行：

```bash
# 先准备环境变量文件
cp docker-compose.prod.env.example .env
# 编辑 .env，至少设置 JWT_SECRET、ADMIN_RESET_PASSWORD_KEY、DB_PASSWORD

# 从当前本地源码构建并启动
docker compose -f docker-compose.yml up -d --build
```

`docker-compose.yml` 会本地构建前后端镜像，因此包含当前分支代码。

## 更新本地部署

每次 `git pull` 或本地提交后：

```bash
docker compose -f docker-compose.yml build app
docker compose -f docker-compose.yml up -d app
docker compose -f docker-compose.yml logs -f app
```

启动时 Flyway 会自动执行未应用的数据库迁移。生产数据库升级前仍建议先做完整备份。

## 健康检查

```bash
curl -fsS http://localhost/api/system/health
docker compose -f docker-compose.yml ps
docker compose -f docker-compose.yml logs --tail=200 app
```

看到应用启动完成且健康检查返回成功后，再验证账户余额、手动卖出和跟单页面。

## 什么时候可以使用官方镜像

只有在确认上游 `wrbug/polyhermes:latest` 已经包含你需要的全部修复后，才使用 `docker-compose.prod.yml`。使用前先在测试环境验证版本和变更内容，不要直接覆盖生产数据库。
