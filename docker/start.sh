#!/bin/bash

# 启动脚本：启动更新服务、后端服务和 Nginx

set -e

echo "========================================="
echo "  PolyHermes 容器启动"
echo "========================================="

# 公开默认值/示例值黑名单（与后端 SecretKeyValidator 保持一致）
INSECURE_SECRETS="change-me-in-production your-secret-key-change-in-production your-secret-key changeme change-me secret password"
MIN_SECRET_BYTES=32

# 检查单个密钥：不能为空、不能是公开默认值、长度不少于 32 字节
check_secret() {
    local name="$1"
    local value="$2"
    local lower
    lower=$(printf '%s' "$value" | tr '[:upper:]' '[:lower:]')
    if [ -z "$value" ]; then
        echo "❌ 错误: $name 未配置"
        return 1
    fi
    for bad in $INSECURE_SECRETS; do
        if [ "$lower" = "$bad" ]; then
            echo "❌ 错误: $name 不能使用公开默认值 '$value'"
            return 1
        fi
    done
    case "$lower" in
        *change-me*|*change-in-production*)
            echo "❌ 错误: $name 不能使用示例值 '$value'"
            return 1
            ;;
    esac
    if [ "$(printf '%s' "$value" | wc -c | tr -d ' ')" -lt "$MIN_SECRET_BYTES" ]; then
        echo "❌ 错误: $name 长度不足 ${MIN_SECRET_BYTES} 字节"
        return 1
    fi
    return 0
}

# 检查安全配置
check_security_config() {
    local errors=0
    # 与后端 encryption.key 的取值顺序一致：ENCRYPTION_KEY → CRYPTO_SECRET_KEY → JWT_SECRET
    local effective_encryption_key="${ENCRYPTION_KEY:-${CRYPTO_SECRET_KEY:-$JWT_SECRET}}"

    check_secret "JWT_SECRET" "$JWT_SECRET" || errors=$((errors + 1))
    check_secret "ADMIN_RESET_PASSWORD_KEY" "$ADMIN_RESET_PASSWORD_KEY" || errors=$((errors + 1))
    check_secret "ENCRYPTION_KEY" "$effective_encryption_key" || errors=$((errors + 1))

    if [ $errors -gt 0 ]; then
        echo ""
        echo "⚠️  安全配置检查失败，容器将不会启动"
        echo "   请在 .env 文件中设置随机密钥（生成方式：openssl rand -hex 32）"
        echo "   注意：已有部署请保留原 ENCRYPTION_KEY / CRYPTO_SECRET_KEY / JWT_SECRET，修改后已加密的私钥将无法解密"
        exit 1
    fi

    echo "✅ 安全配置检查通过"
}

# 执行安全配置检查
check_security_config

# 函数：清理进程
cleanup() {
    echo "收到退出信号，清理进程..."
    if [ -n "$UPDATE_SERVICE_PID" ]; then
        kill $UPDATE_SERVICE_PID 2>/dev/null || true
    fi
    if [ -n "$BACKEND_PID" ]; then
        kill $BACKEND_PID 2>/dev/null || true
    fi
    nginx -s quit 2>/dev/null || true
    exit 0
}

# 注册信号处理
trap cleanup SIGTERM SIGINT

# 1. 启动更新服务（后台运行，端口 9090）
echo "🚀 启动更新服务..."
python3 /app/update-service.py &
UPDATE_SERVICE_PID=$!
echo "✅ 更新服务已启动 (PID: $UPDATE_SERVICE_PID, Port: 9090)"

# 等待更新服务就绪
sleep 2

# 2. 启动后端服务（后台运行，端口 8000）
echo "🚀 启动后端服务..."
# 后端以非 root 用户 appuser 运行（保留环境变量）；更新服务需要替换前端文件并 reload nginx，仍以 root 运行
if [ "$(id -u)" = "0" ] && id appuser > /dev/null 2>&1 && command -v runuser > /dev/null 2>&1; then
    runuser -u appuser -- java -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} &
else
    java -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} &
fi
BACKEND_PID=$!
echo "✅ 后端服务已启动 (PID: $BACKEND_PID, Port: 8000)"

# 3. 等待后端服务启动
echo "⏳ 等待后端服务就绪..."
for i in {1..60}; do
    if curl -f http://localhost:8000/api/system/health > /dev/null 2>&1; then
        echo "✅ 后端服务健康检查通过"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "❌ 后端服务启动超时"
        exit 1
    fi
    sleep 1
done

# 4. 启动 Nginx（前台运行，保持容器存活）
echo "🚀 启动 Nginx..."
echo "========================================="
echo "  容器启动完成"
echo "  - 更新服务: http://localhost:9090"
echo "  - 后端服务: http://localhost:8000"
echo "  - 前端服务: http://localhost:80"
echo "========================================="

exec nginx -g "daemon off;"
