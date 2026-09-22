# PolyHermes 开发文档

本文档介绍 PolyHermes 项目的开发指南，包括项目结构、开发环境配置、代码规范、API 接口等。

## 📋 目录

- [项目结构](#项目结构)
- [开发环境配置](#开发环境配置)
- [代码规范](#代码规范)
- [API 接口文档](#api-接口文档)
- [数据库设计](#数据库设计)
- [前端开发指南](#前端开发指南)
- [后端开发指南](#后端开发指南)
- [常见问题](#常见问题)

## 📦 项目结构

```
polyhermes/
├── backend/                    # 后端服务
│   ├── src/main/kotlin/
│   │   └── com/wrbug/polymarketbot/
│   │       ├── api/            # API 接口定义（Retrofit）
│   │       ├── config/         # 配置类
│   │       ├── controller/     # REST 控制器
│   │       ├── dto/            # 数据传输对象
│   │       ├── entity/         # 数据库实体
│   │       ├── repository/     # 数据访问层
│   │       ├── service/        # 业务逻辑服务
│   │       ├── util/           # 工具类
│   │       └── websocket/      # WebSocket 处理
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/       # Flyway 数据库迁移脚本
├── frontend/                   # 前端应用
│   ├── src/
│   │   ├── components/        # 公共组件
│   │   ├── pages/              # 页面组件
│   │   ├── services/           # API 服务
│   │   ├── store/              # 状态管理（Zustand）
│   │   ├── types/              # TypeScript 类型定义
│   │   ├── utils/              # 工具函数
│   │   ├── hooks/              # React Hooks
│   │   ├── locales/            # 多语言资源
│   │   └── styles/             # 样式文件
│   └── public/                 # 静态资源
├── docs/                       # 文档
│   ├── DEPLOYMENT.md           # 部署文档
│   ├── VERSION_MANAGEMENT.md  # 版本号管理文档
│   ├── copy-trading-requirements.md  # 跟单系统需求文档
│   └── ...                     # 其他文档
├── .github/workflows/          # GitHub Actions 工作流
└── README.md                   # 项目说明
```

## 🛠️ 开发环境配置

### 前置要求

- **JDK**: 17+
- **Node.js**: 18+
- **MySQL**: 8.0+
- **Gradle**: 7.5+（或使用 Gradle Wrapper）
- **Docker**: 20.10+（可选，用于容器化部署）

### 后端开发环境

1. **克隆仓库**

```bash
git clone https://github.com/WrBug/PolyHermes.git
cd PolyHermes
```

2. **配置数据库**

创建 MySQL 数据库：

```sql
CREATE DATABASE polyhermes CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

3. **配置环境变量**

编辑 `backend/src/main/resources/application.properties` 或使用环境变量：

```properties
# 数据库配置
spring.datasource.url=jdbc:mysql://localhost:3306/polyhermes?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${DB_PASSWORD:password}

# 服务器端口
server.port=${SERVER_PORT:8000}

# JWT 密钥
jwt.secret=${JWT_SECRET:change-me-in-production}

# 加密密钥（用于加密存储私钥和 API Key；兼容旧变量 CRYPTO_SECRET_KEY）
encryption.key=${ENCRYPTION_KEY:${CRYPTO_SECRET_KEY:${jwt.secret}}}
```

4. **启动后端服务**

```bash
cd backend
./gradlew bootRun
```

后端服务将在 `http://localhost:8000` 启动。

### 前端开发环境

1. **安装依赖**

```bash
cd frontend
npm install
```

2. **配置环境变量（可选）**

创建 `.env` 文件：

```env
VITE_API_URL=http://localhost:8000
VITE_WS_URL=ws://localhost:8000
```

3. **启动开发服务器**

```bash
npm run dev
```

前端应用将在 `http://localhost:3000` 启动。

## 📝 代码规范

### 后端开发规范

详细规范请参考：[后端开发规范](.cursor/rules/backend.mdc)

**核心规范**：
- 使用 Kotlin 编码规范
- Controller 方法**禁止**使用 `suspend`
- 实体类 ID 字段使用 `Long? = null`
- 所有时间字段使用 `Long` 时间戳（毫秒）
- 数值计算使用 `BigDecimal`
- 使用 `ErrorCode` 枚举定义错误码和消息
- **禁止**在代码中添加 TODO 注释
- **禁止**直接返回 mock 数据

### 前端开发规范

详细规范请参考：[前端开发规范](.cursor/rules/frontend.mdc)

**核心规范**：
- 使用 TypeScript 类型定义
- 使用函数式组件和 Hooks
- **禁止**使用 `any` 类型
- **必须**使用多语言（i18n）进行所有文本显示
- **必须**使用 `formatUSDC` 函数格式化 USDC 金额
- **必须**支持移动端和桌面端
- **禁止**在代码中添加 TODO 注释

### 提交规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：

- `feat`: 新功能
- `fix`: 修复 bug
- `docs`: 文档更新
- `style`: 代码格式调整
- `refactor`: 代码重构
- `test`: 测试相关
- `chore`: 构建/工具相关

示例：
```bash
git commit -m "feat: 添加版本号显示功能"
git commit -m "fix: 修复订单状态更新问题"
```

## 📡 API 接口文档

### 统一响应格式

所有 API 接口统一使用 POST 方法，响应格式如下：

```json
{
  "code": 0,
  "data": {},
  "msg": ""
}
```

- `code`: 响应码，0 表示成功，非 0 表示失败
- `data`: 响应数据，可以是任意类型
- `msg`: 响应消息，成功时通常为空，失败时包含错误提示

### 错误码规范

- `0`: 成功
- `1001-1999`: 参数错误
- `2001-2999`: 认证/权限错误
- `3001-3999`: 资源不存在
- `4001-4999`: 业务逻辑错误
- `5001-5999`: 服务器内部错误

### 主要 API 接口

#### 账户管理

- `POST /api/accounts/list` - 获取账户列表
- `POST /api/accounts/import` - 导入账户（通过私钥）
- `POST /api/accounts/detail` - 获取账户详情
- `POST /api/accounts/edit` - 编辑账户
- `POST /api/accounts/delete` - 删除账户
- `POST /api/accounts/balance` - 获取账户余额

#### Leader 管理

- `POST /api/leaders/list` - 获取 Leader 列表
- `POST /api/leaders/add` - 添加 Leader
- `POST /api/leaders/edit` - 编辑 Leader
- `POST /api/leaders/delete` - 删除 Leader

#### 跟单模板

- `POST /api/templates/list` - 获取模板列表
- `POST /api/templates/add` - 添加模板
- `POST /api/templates/edit` - 编辑模板
- `POST /api/templates/delete` - 删除模板

#### 跟单配置

- `POST /api/copy-trading/list` - 获取跟单配置列表
- `POST /api/copy-trading/add` - 添加跟单配置
- `POST /api/copy-trading/edit` - 编辑跟单配置
- `POST /api/copy-trading/delete` - 删除跟单配置
- `POST /api/copy-trading/enable` - 启用跟单
- `POST /api/copy-trading/disable` - 禁用跟单

#### 订单管理

- `POST /api/copy-trading/orders/buy` - 获取买入订单列表
- `POST /api/copy-trading/orders/sell` - 获取卖出订单列表
- `POST /api/copy-trading/orders/matched` - 获取匹配订单列表

#### 统计分析

- `POST /api/statistics/global` - 获取全局统计
- `POST /api/statistics/leader` - 获取 Leader 统计
- `POST /api/statistics/category` - 获取分类统计
- `POST /api/copy-trading/statistics` - 获取跟单关系统计

#### 仓位管理

- `POST /api/positions/list` - 获取仓位列表
- `POST /api/positions/sell` - 卖出仓位
- `POST /api/positions/redeem` - 赎回仓位

#### 系统管理

- `POST /api/system-settings/proxy` - 配置代理
- `POST /api/system-settings/api-health` - 获取 API 健康状态
- `POST /api/users/list` - 获取用户列表
- `POST /api/users/add` - 添加用户
- `POST /api/users/edit` - 编辑用户
- `POST /api/users/delete` - 删除用户

详细 API 接口文档请参考：[跟单系统需求文档](copy-trading-requirements.md)

## 🗄️ 数据库设计

### 主要数据表

- `accounts` - 账户表
- `leaders` - Leader 表
- `templates` - 跟单模板表
- `copy_trading` - 跟单配置表
- `copy_orders` - 跟单订单表
- `positions` - 仓位表
- `users` - 用户表
- `system_settings` - 系统设置表

数据库迁移脚本位于 `backend/src/main/resources/db/migration/`，使用 Flyway 管理。

## 🎨 前端开发指南

### 项目结构

```
frontend/src/
├── components/          # 公共组件
│   ├── Layout.tsx      # 布局组件（支持移动端）
│   └── Logo.tsx        # Logo 组件
├── pages/              # 页面组件
│   ├── AccountList.tsx
│   ├── LeaderList.tsx
│   ├── CopyTradingList.tsx
│   └── ...
├── services/           # API 服务
│   ├── api.ts         # API 服务定义
│   └── websocket.ts   # WebSocket 服务
├── store/             # 状态管理（Zustand）
├── types/             # TypeScript 类型定义
├── utils/             # 工具函数
│   ├── index.ts       # 统一导出
│   ├── ethers.ts      # 以太坊相关工具
│   ├── auth.ts        # 认证相关工具
│   └── version.ts     # 版本号工具
├── hooks/             # React Hooks
├── locales/           # 多语言资源
│   ├── zh-CN/
│   ├── zh-TW/
│   └── en/
└── styles/            # 样式文件
```

### 多语言支持

项目支持多语言（中文简体、中文繁体、英文），使用 `react-i18next`。

**添加新翻译**：
1. 在 `src/locales/{locale}/common.json` 中添加翻译
2. 在组件中使用 `useTranslation` Hook：

```typescript
import { useTranslation } from 'react-i18next'

const MyComponent: React.FC = () => {
  const { t } = useTranslation()
  return <div>{t('key')}</div>
}
```

### 移动端适配

- 使用 `react-responsive` 检测设备类型
- 断点设置：移动端 < 768px，桌面端 >= 768px
- 使用响应式布局和组件

### 工具函数

**USDC 金额格式化**：
```typescript
import { formatUSDC } from '../utils'

const balance = formatUSDC('1.23456')  // "1.2345"
```

**以太坊地址验证**：
```typescript
import { isValidWalletAddress } from '../utils'

if (isValidWalletAddress(address)) {
  // 地址有效
}
```

## ⚙️ 后端开发指南

### 项目结构

```
backend/src/main/kotlin/com/wrbug/polymarketbot/
├── api/                # API 接口定义（Retrofit）
│   ├── PolymarketClobApi.kt
│   ├── PolymarketGammaApi.kt
│   └── GitHubApi.kt
├── controller/         # REST 控制器
├── service/            # 业务逻辑服务
├── entity/             # 数据库实体
├── repository/         # 数据访问层
├── dto/                # 数据传输对象
├── util/               # 工具类
│   ├── CryptoUtils.kt  # 加密工具
│   ├── RetrofitFactory.kt  # Retrofit 工厂
│   └── ...
└── websocket/          # WebSocket 处理
```

### 创建新 API 接口

1. **定义 Retrofit 接口**（在 `api/` 目录）：

```kotlin
interface MyApi {
    @POST("/endpoint")
    suspend fun myMethod(@Body request: MyRequest): Response<MyResponse>
}
```

2. **创建 Service**（在 `service/` 目录）：

```kotlin
@Service
class MyService(
    private val myApi: MyApi
) {
    suspend fun doSomething(): Result<MyResponse> {
        // 业务逻辑
    }
}
```

3. **创建 Controller**（在 `controller/` 目录）：

```kotlin
@RestController
@RequestMapping("/api/my")
class MyController(
    private val myService: MyService,
    private val messageSource: MessageSource
) {
    @PostMapping("/list")
    fun list(@RequestBody request: MyListRequest): ResponseEntity<ApiResponse<MyListResponse>> {
        return try {
            val data = runBlocking { myService.getList(request) }
            ResponseEntity.ok(ApiResponse.success(data))
        } catch (e: Exception) {
            logger.error("获取列表失败", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ERROR, messageSource = messageSource))
        }
    }
}
```

### 数据库操作

使用 Spring Data JPA：

```kotlin
@Repository
interface MyRepository : JpaRepository<MyEntity, Long> {
    fun findByCode(code: String): MyEntity?
    fun findByCategory(category: String): List<MyEntity>
}
```

### 加密存储

使用 `CryptoUtils` 加密敏感数据：

```kotlin
@Autowired
private lateinit var cryptoUtils: CryptoUtils

// 加密
val encrypted = cryptoUtils.encrypt("sensitive-data")

// 解密
val decrypted = cryptoUtils.decrypt(encrypted)
```

## 🔧 常见问题

### Q1: 如何添加新的页面？

1. 在 `frontend/src/pages/` 创建页面组件
2. 在 `frontend/src/App.tsx` 添加路由
3. 在 `frontend/src/components/Layout.tsx` 添加菜单项（如需要）

### Q2: 如何添加新的 API 接口？

1. 在 `backend/src/main/kotlin/.../controller/` 创建 Controller
2. 在 `backend/src/main/kotlin/.../service/` 创建 Service
3. 在 `frontend/src/services/api.ts` 添加 API 调用方法

### Q3: 如何添加数据库表？

1. 创建 Entity 类（在 `entity/` 目录）
2. 创建 Repository 接口（在 `repository/` 目录）
3. 创建 Flyway 迁移脚本（在 `resources/db/migration/`）

### Q4: 如何测试 WebSocket？

使用浏览器控制台或 WebSocket 客户端工具连接到 `ws://localhost:8000/ws`

### Q5: 如何调试后端代码？

1. 使用 IDE 的调试功能（IntelliJ IDEA、VS Code 等）
2. 在代码中添加日志：`logger.debug("调试信息")`
3. 查看日志输出：`./gradlew bootRun` 或查看日志文件

## 📚 相关文档

- [部署文档](DEPLOYMENT.md) / [English](../en/DEPLOYMENT.md) - 详细的部署指南
- [版本号管理文档](VERSION_MANAGEMENT.md) / [English](../en/VERSION_MANAGEMENT.md) - 版本号管理和自动构建
- [开发文档](DEVELOPMENT.md) / [English](../en/DEVELOPMENT.md) - 开发指南
- [跟单系统需求文档](copy-trading-requirements.md) - 后端 API 接口文档
- [前端需求文档](copy-trading-frontend-requirements.md) - 前端功能文档

## 🤝 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 遵循代码规范
4. 提交更改 (`git commit -m 'feat: Add some AmazingFeature'`)
5. 推送到分支 (`git push origin feature/AmazingFeature`)
6. 开启 Pull Request

---

**Happy Coding! 🚀**
