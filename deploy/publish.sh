#!/usr/bin/env bash
# Travel OS · 本地一键发布（零成本流水线）
#
# 与 .github/workflows/deploy.yml 同一套流程：构建 → 备份 → 传 jar → 数据库迁移 →
# 发布前端 → 重启 + 健康检查（失败自动回滚）。区别只在于从本机执行：
# 本机在国内、服务器也在国内，44MB jar 走同国链路通常几秒到几十秒，不产生任何外购费用。
#
# 用法：
#   DEPLOY_HOST=<服务器IP> ./deploy/publish.sh            # 完整发布
#   DEPLOY_HOST=<IP> ./deploy/publish.sh --build-only    # 只构建不部署（自检）
#   SKIP_TESTS=1 DEPLOY_HOST=<IP> ./deploy/publish.sh    # 跳过测试（应急提速）
#
# 配置（优先级：环境变量 > deploy/.deploy.env > 默认值）：
#   DEPLOY_HOST   服务器 IP（必填）
#   DEPLOY_PORT   SSH 端口（默认 22）
#   DEPLOY_USER   SSH 用户（默认 ubuntu，见 deploy/服务器初始化手册.md）
#   SSH_KEY       部署私钥（默认 ~/.ssh/travel_os_deploy，见初始化手册）
#   MAVEN_BIN     mvn 路径（默认 Maven 3.9.11，3.6.3 有 -source 8 内插 bug，勿用）
#   JAVA_HOME     JDK 17 路径
set -euo pipefail

# ---- 载入本地配置（deploy/.deploy.env，gitignore 掉，勿提交） ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$SCRIPT_DIR/.deploy.env" ]; then
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.deploy.env"
fi

DEPLOY_USER="${DEPLOY_USER:-ubuntu}"
DEPLOY_PORT="${DEPLOY_PORT:-22}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/travel_os_deploy}"
MAVEN_BIN="${MAVEN_BIN:-C:/Users/15003/.workbuddy/binaries/java/maven/apache-maven-3.9.11/bin/mvn.cmd}"
JAVA_HOME="${JAVA_HOME:-C:/Users/15003/.workbuddy/binaries/java/jdk/jdk-17.0.20.1+1}"
export JAVA_HOME

BUILD_ONLY=0
if [ "${1:-}" = "--build-only" ]; then
  BUILD_ONLY=1
fi

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\033[1;31m错误：%s\033[0m\n' "$*" >&2; exit 1; }

if [ "$BUILD_ONLY" = "0" ] && [ -z "${DEPLOY_HOST:-}" ]; then
  die "缺 DEPLOY_HOST（服务器 IP）。示例：DEPLOY_HOST=1.2.3.4 ./deploy/publish.sh；或写入 deploy/.deploy.env"
fi

# ---- 1. 构建（与 CI 相同：mvn verify = 编译 + 测试 + 打包） ----
log "1/6 构建后端 jar"
cd "$ROOT_DIR/apps/server"
# Windows 文件锁：若本地开发服务（3001）占用旧 jar，先停掉，否则打包会失败
if command -v taskkill >/dev/null 2>&1; then
  DEV_PID=$(netstat -ano | grep ':3001 ' | grep LISTENING | head -1 | awk '{print $NF}' || true)
  if [ -n "${DEV_PID:-}" ]; then
    echo "停止占用 jar 的本地开发服务（PID $DEV_PID）"
    taskkill //F //PID "$DEV_PID" >/dev/null 2>&1 || true
    sleep 1
  fi
fi
if [ "${SKIP_TESTS:-0}" = "1" ]; then
  "$MAVEN_BIN" -B package -DskipTests
else
  "$MAVEN_BIN" -B verify
fi
JAR="$(ls target/*.jar | head -1)"
[ -f "$JAR" ] || die "构建产物缺失"
echo "构建完成：$JAR（$(du -h "$JAR" | cut -f1)）"

if [ "$BUILD_ONLY" = "1" ]; then
  log "仅构建模式，结束"
  exit 0
fi

# 部署连接参数（仅部署阶段需要）
[ -f "$SSH_KEY" ] || die "部署私钥不存在：$SSH_KEY（见 deploy/服务器初始化手册.md 第 2 节）"
SSH_CMD=(ssh -i "$SSH_KEY" -p "$DEPLOY_PORT" -o StrictHostKeyChecking=accept-new "$DEPLOY_USER@$DEPLOY_HOST")
SCP_CMD=(scp -i "$SSH_KEY" -P "$DEPLOY_PORT" -o StrictHostKeyChecking=accept-new)

# ---- 2. 备份服务器现有版本（各保留最近 3 份，与 CI 一致） ----
log "2/6 备份服务器现有版本"
"${SSH_CMD[@]}" '
  mkdir -p /opt/travel-os/backups
  TS=$(date +%Y%m%d%H%M%S)
  [ -f /opt/travel-os/travel-os-api.jar ] && cp /opt/travel-os/travel-os-api.jar "/opt/travel-os/backups/travel-os-api.jar.bak-$TS" || true
  [ -f /var/www/travel-os/index.html ] && tar czf "/opt/travel-os/backups/www.bak-$TS.tar.gz" -C /var/www/travel-os . || true
  ls -1t /opt/travel-os/backups/travel-os-api.jar.bak-* 2>/dev/null | tail -n +4 | xargs -r rm -f
  ls -1t /opt/travel-os/backups/www.bak-*.tar.gz 2>/dev/null | tail -n +4 | xargs -r rm -f'

# ---- 3. 直传 jar（scp，零外购成本） ----
log "3/6 上传 jar（scp 直传）"
"${SCP_CMD[@]}" "$JAR" "$DEPLOY_USER@$DEPLOY_HOST:/opt/travel-os/travel-os-api.jar.new"
"${SSH_CMD[@]}" 'mv /opt/travel-os/travel-os-api.jar.new /opt/travel-os/travel-os-api.jar'

# ---- 4. 数据库迁移（幂等重放，与 CI 一致） ----
log "4/6 应用数据库迁移"
cd "$ROOT_DIR"
tar czf /tmp/travel-os-migrations.tar.gz -C apps/server/db migration
"${SCP_CMD[@]}" /tmp/travel-os-migrations.tar.gz "$DEPLOY_USER@$DEPLOY_HOST:/tmp/"
"${SSH_CMD[@]}" 'set -e
  rm -rf /tmp/mig && mkdir -p /tmp/mig
  tar xzf /tmp/travel-os-migrations.tar.gz -C /tmp/mig
  for f in $(ls /tmp/mig/migration/*.sql 2>/dev/null | sort); do
    echo "applying: $f"
    sudo docker exec -i travel-os-postgres psql -U travel_os -d travel_os -v ON_ERROR_STOP=1 -q -f - < "$f"
  done'
rm -f /tmp/travel-os-migrations.tar.gz

# ---- 5. 发布前端静态站（tar + 服务器端 rsync --delete，保留 /ci/ 状态页） ----
log "5/6 发布前端静态站点"
tar czf /tmp/travel-os-web.tar.gz -C apps/web .
"${SCP_CMD[@]}" /tmp/travel-os-web.tar.gz "$DEPLOY_USER@$DEPLOY_HOST:/tmp/"
"${SSH_CMD[@]}" 'set -e
  rm -rf /tmp/web-new && mkdir -p /tmp/web-new
  tar xzf /tmp/travel-os-web.tar.gz -C /tmp/web-new
  mkdir -p /var/www/travel-os
  rsync -a --delete --exclude "/ci/" /tmp/web-new/ /var/www/travel-os/'
rm -f /tmp/travel-os-web.tar.gz

# ---- 6. 重启 + 健康检查（失败自动回滚，与 CI 一致） ----
log "6/6 重启后端 + 健康检查"
"${SSH_CMD[@]}" '
  sudo systemctl restart travel-os-api
  ok=0
  for i in $(seq 1 12); do
    sleep 5
    if curl -sf -m 5 http://127.0.0.1:3000/api/health > /dev/null; then ok=1; break; fi
  done
  if [ "$ok" = "1" ]; then echo "health check OK"; exit 0; fi
  echo "!! 健康检查失败，最近 50 行日志："
  sudo journalctl -u travel-os-api --no-pager -n 50 || true
  echo "!! 自动回滚到最近备份 jar"
  latest=$(ls -1t /opt/travel-os/backups/travel-os-api.jar.bak-* | head -1)
  cp "$latest" /opt/travel-os/travel-os-api.jar
  sudo systemctl restart travel-os-api
  exit 1'

log "发布完成：http://$DEPLOY_HOST/"
