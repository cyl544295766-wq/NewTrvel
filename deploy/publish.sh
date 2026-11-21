#!/usr/bin/env bash
# Travel OS · 本地一键发布（零成本流水线）· 发布状态机 v2
#
# 部署唯一入口（CI 只做构建验证，见 .github/workflows/ci.yml）。阶段与失败行为：
#   S1 预检+验证（本地）：工具/路径/磁盘/SSH/服务器磁盘与目录/互斥锁预检 → 前端测试
#      → mvn -B verify（应急 SKIP_TESTS=1 显式跳过并记录进 release.json 与发布结果）
#      失败：中止，不触碰服务器任何文件。
#   S2 staging 上传：jar / www tar（内含 release.json）/ SHA256SUMS → 服务器 staging，
#      sha256 -c 校验。失败：线上仍是上一版本组合。
#   S3 备份：先备份当前 jar / www / 上一版本 ID（保留 3 份，只动本项目备份路径），
#      才允许覆盖；HAS_PREVIOUS 记录是否存在旧版（首次部署=0）。
#   S4 迁移：按文件名序全量重放（服务器 flock 内，ON_ERROR_STOP=1）。
#      失败：中止——线上仍是上一版本组合；已执行部分靠幂等重放（非事务回滚），修复后重跑。
#   S5 切换（同一 flock 内）：systemd unit + release.env（TRAVELOS_RELEASE_ID=目标版本）
#      → 原子替换 jar → rsync 前端（--delete，保留 /ci/）→ current-release.txt → restart。
#   S6 验证（服务器本地有界轮询 12×5s）：/api/health（服务标识 + release=目标）
#      + /api/health/ready（200，库可连+关键结构齐）+ :8082/release.json（release=目标）。
#   S7 回滚：验证失败或 S5 中断 → 恢复同一次备份的 jar+www+release.env（前后端一致）
#      → restart → 重新验证；回滚失败 → 打印人工恢复信息，退出 1，绝不误报成功。
#      首次部署（HAS_PREVIOUS=0）失败：如实报告已切换产物、无旧版可回滚，不假装恢复。
#
# 边界（如实声明）：单机部署无原子切换——S5 重启窗口（数秒）内前端静态页可访问、API 短暂
# 不可用；若本机或连接在 S5 中途断开，远端可能停在半切换状态（staging 还在），按
# docs/发布流水线.md「人工恢复」节处理。数据库不自动回滚：迁移必须与上一应用版本向后兼容
# （先扩展、后迁移、最后清理），应用回滚不能替代数据恢复。
#
# 用法：
#   DEPLOY_HOST=<服务器IP> ./deploy/publish.sh            # 完整发布
#   DEPLOY_HOST=<IP> ./deploy/publish.sh --build-only    # 只构建不部署（自检）
#   SKIP_TESTS=1 DEPLOY_HOST=<IP> ./deploy/publish.sh    # 应急跳过验证（显式启用，默认关）
#
# 配置（优先级：环境变量 > deploy/.deploy.env > 默认值）：
#   DEPLOY_HOST   服务器 IP（必填）
#   DEPLOY_PORT   SSH 端口（默认 22）
#   DEPLOY_USER   SSH 用户（默认 ubuntu，见 deploy/服务器初始化手册.md）
#   SSH_KEY       部署私钥（默认 ~/.ssh/travel_os_deploy）
#   MAVEN_BIN     mvn 路径（默认 Maven 3.9.11，3.6.3 有 -source 8 内插 bug，勿用）
#   JAVA_HOME     JDK 17 路径
#   NODE_BIN      node 路径（前端测试用，默认本机托管版；缺失时回退 PATH 上的 node）
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
NODE_BIN="${NODE_BIN:-C:/Users/15003/.workbuddy/binaries/node/versions/22.22.2-6/node.exe}"
export JAVA_HOME

BUILD_ONLY=0
if [ "${1:-}" = "--build-only" ]; then
  BUILD_ONLY=1
fi
SKIP_TESTS="${SKIP_TESTS:-0}"
[ "$SKIP_TESTS" = "1" ] || [ "$SKIP_TESTS" = "0" ] || die "SKIP_TESTS 仅允许 0/1"

# ---- 发布版本标识：时间 + 源码短 SHA（贯穿 release.env / release.json / current-release.txt） ----
# RELEASE_ID 环境变量可覆盖（重放/模拟测试用；常规发布保持默认，勿手工指定）
GIT_SHA_FULL="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || echo norev)"
RELEASE_ID="${RELEASE_ID:-$(date +%Y%m%d%H%M%S)-$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo norev)}"

# 远端固定路径（本项目专属，绝不触碰其他项目目录）
RROOT=/opt/travel-os
RJAR=$RROOT/travel-os-api.jar
RSTAGE=$RROOT/staging-$RELEASE_ID   # 每次发布独立 staging，不同工作目录并发不互相覆盖
RBACKUP=$RROOT/backups
RMTMP=$RROOT/tmp   # 远端临时目录（与远端脚本 TMPR 一致，scp/ssh 桩统一映射）
RWWW=/var/www/travel-os
RLOCK=$RROOT/publish.lock
RENV=$RROOT/release.env
RCUR=$RROOT/current-release.txt

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
warn() { printf '\033[1;33m⚠ %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31m错误：%s\033[0m\n' "$*" >&2; exit 1; }

# ---- 状态机变量（中断/错误陷阱据此决定是否回滚） ----
STAGE=init            # init→preflight→build→upload→backup→migrate→switch→verify→verified
HAS_PREVIOUS=0
PREV_ID=""
BACKUP_TS=""
ROLLBACK_DONE=0
LOCK_ACQUIRED=0       # 只有成功获取锁的进程才能释放自己的锁

cleanup_local() {
  # 仅释放本进程持有的锁；未获取锁的进程退出时不得删除其他进程的锁
  if [ "$LOCK_ACQUIRED" = "1" ]; then
    rm -rf "$SCRIPT_DIR/.publish.lock" 2>/dev/null || true
  fi
}

# ============ 远端验证脚本（随 staging 上传；在服务器本地执行轮询） ============
# 用法：verify.sh <期望release> | verify.sh --legacy（旧版 JAR 无 release 上报，只验存活+就绪）
make_verify_script() {
  cat > "$1" <<'VERIFY_EOF'
#!/usr/bin/env bash
# 轮询参数可用环境变量覆盖（生产默认 12 轮 × 5s；模拟测试用短间隔加速）
ROUNDS="${VERIFY_ROUNDS:-12}"
INTERVAL="${VERIFY_INTERVAL:-5}"
WANT="${1:-}"
RB=0
for i in $(seq 1 "$ROUNDS"); do
  sleep "$INTERVAL"
  H="$(curl -sf -m 5 http://127.0.0.1:3001/api/health 2>/dev/null || true)"
  case "$H" in
    *travel-os-api*)
      if [ "$WANT" = "--legacy" ]; then
        if curl -sf -m 5 http://127.0.0.1:3001/api/health/ready >/dev/null 2>&1; then
          echo "  round $i: legacy verify ok (identity+ready)"; RB=1; break
        fi
        echo "  round $i: legacy identity ok, ready pending"
      else
        case "$H" in
          *"\"release\":\"$WANT\""*)
            if curl -sf -m 5 http://127.0.0.1:3001/api/health/ready >/dev/null 2>&1 \
               && curl -sf -m 5 http://127.0.0.1:8082/release.json 2>/dev/null | grep -q "\"release\": *\"$WANT\""; then
              echo "  round $i: verify ok (health + ready + frontend release all match)"; RB=1; break
            fi
            echo "  round $i: health/release ok, ready or frontend pending"
            ;;
          *)
            echo "  round $i: service up but release mismatch"
            ;;
        esac
      fi
      ;;
    *)
      echo "  round $i: service not ready"
      ;;
  esac
done
[ "$RB" = 1 ]
VERIFY_EOF
}

# ============ 远端发布事务脚本（一次 flock 内执行完整流程） ============
# 备份 → 迁移 → 切换 → 验证 → 失败则回滚，全程持有同一把服务器锁。
# 参数通过位置传入：$1=RELEASE_ID $2=BACKUP_TS
# PREV_ID / HAS_PREVIOUS 在锁内读取（与本次备份一致，避免其他发布交错导致过期信息）
# 阶段退出码约定：
#   10/11/12 = JAR/前端/版本备份失败（尚未切换，无需回滚）
#   20       = 迁移失败（尚未切换，无需回滚）
#   30       = 切换中途失败（已/部分切换 → 尝试回滚）
#   40       = 验证失败（已切换 → 尝试回滚）
#   50/51    = 回滚恢复失败 / 回滚后验证失败（需人工介入）
#   0        = 验证通过
make_remote_script() {
  cat > "$1" <<'REMOTE_EOF'
#!/usr/bin/env bash
# Travel OS 远端发布事务（由 publish.sh 生成，在一次 flock 内执行）
# 不用 set -e：验证失败是预期分支，必须显式处理才能进入回滚
# 根路径可通过环境变量覆盖（模拟器注入 FAKE_STATE 映射后的路径）
RELEASE_ID="$1"; BACKUP_TS="$2"
RROOT="${DEPLOY_ROOT:-/opt/travel-os}"
RJAR="$RROOT/travel-os-api.jar"
RSTAGE="$RROOT/staging-$RELEASE_ID"
RBACKUP="$RROOT/backups"
RWWW="${WWW_ROOT:-/var/www/travel-os}"
RENV="$RROOT/release.env"
RCUR="$RROOT/current-release.txt"
TMPR="${DEPLOY_ROOT:-/opt/travel-os}/tmp"

echo "=== 读取上一版本信息（锁内，与本次备份一致） ==="
PREV_ID=""
HAS_PREVIOUS=0
if [ -f "$RJAR" ]; then HAS_PREVIOUS=1; fi
if [ -f "$RCUR" ]; then PREV_ID="$(cat "$RCUR")"; fi
if [ "$HAS_PREVIOUS" = "1" ] && [ -z "$PREV_ID" ]; then PREV_ID="unknown-legacy"; fi
echo "PREV_ID=$PREV_ID HAS_PREVIOUS=$HAS_PREVIOUS"

echo "=== 备份 ==="
if [ "$HAS_PREVIOUS" = "1" ]; then
  cp "$RJAR" "$RBACKUP/travel-os-api.jar.bak-$BACKUP_TS" || { echo "BACKUP_ERROR:JAR" >&2; exit 10; }
fi
if [ -f "$RWWW/index.html" ]; then
  tar czf "$RBACKUP/www.bak-$BACKUP_TS.tar.gz" -C "$RWWW" . || { echo "BACKUP_ERROR:WWW" >&2; exit 11; }
fi
if [ -f "$RCUR" ]; then
  cp "$RCUR" "$RBACKUP/release-id-$BACKUP_TS.txt" || { echo "BACKUP_ERROR:VER" >&2; exit 12; }
fi
ls -1t "$RBACKUP"/travel-os-api.jar.bak-* 2>/dev/null | tail -n +4 | xargs -r rm -f
ls -1t "$RBACKUP"/www.bak-*.tar.gz 2>/dev/null | tail -n +4 | xargs -r rm -f
ls -1t "$RBACKUP"/release-id-*.txt 2>/dev/null | tail -n +4 | xargs -r rm -f

echo "=== 迁移 ==="
rm -rf "$TMPR/mig-$RELEASE_ID" && mkdir -p "$TMPR/mig-$RELEASE_ID"
tar xzf "$TMPR/mig-$RELEASE_ID.tgz" -C "$TMPR/mig-$RELEASE_ID"
for f in $(ls "$TMPR/mig-$RELEASE_ID"/migration/*.sql 2>/dev/null | sort); do
  echo "applying: $f"
  sudo docker exec -i travel-os-postgres psql -U travel_os -d travel_os -v ON_ERROR_STOP=1 -q -f - < "$f"
  if [ $? -ne 0 ]; then echo "MIGRATE_ERROR" >&2; exit 20; fi
done

echo "=== 切换 ==="
SWITCH_OK=0
SWITCH_RC=0
do_switch() {
  sudo cp "$TMPR/travel-os-api-$RELEASE_ID.service" /etc/systemd/system/travel-os-api.service || { echo "SWITCH_ERROR:unit" >&2; return 30; }
  sudo systemctl daemon-reload || { echo "SWITCH_ERROR:daemon-reload" >&2; return 30; }
  printf 'TRAVELOS_RELEASE_ID=%s\n' "$RELEASE_ID" > "$RENV.new" && mv "$RENV.new" "$RENV" || { echo "SWITCH_ERROR:release.env" >&2; return 30; }
  rm -rf "$TMPR/web-new-$RELEASE_ID" && mkdir -p "$TMPR/web-new-$RELEASE_ID"
  tar xzf "$RSTAGE/www.tar.gz" -C "$TMPR/web-new-$RELEASE_ID" || { echo "SWITCH_ERROR:untar-www" >&2; return 30; }
  rsync -a --delete --exclude "/ci/" "$TMPR/web-new-$RELEASE_ID/" "$RWWW/" || { echo "SWITCH_ERROR:rsync-www" >&2; return 30; }
  rm -rf "$TMPR/web-new-$RELEASE_ID"
  mv "$RSTAGE/travel-os-api.jar" "$RJAR" || { echo "SWITCH_ERROR:mv-jar" >&2; return 30; }
  printf '%s\n' "$RELEASE_ID" > "$RCUR.new" && mv "$RCUR.new" "$RCUR" || { echo "SWITCH_ERROR:current-release" >&2; return 30; }
  sudo systemctl restart travel-os-api || { echo "SWITCH_ERROR:restart" >&2; return 30; }
  return 0
}
do_switch || SWITCH_RC=$?
if [ "$SWITCH_RC" -eq 0 ]; then SWITCH_OK=1; fi

RCODE=$SWITCH_RC
if [ "$RCODE" -eq 0 ]; then
  echo "=== 验证 ==="
  RCODE=0
  bash "$RSTAGE/verify.sh" "$RELEASE_ID" || RCODE=$?
fi
if [ "$RCODE" -eq 0 ]; then
  echo "VERIFY_OK"
  exit 0
fi

echo "=== 失败（RCODE=$RCODE），进入回滚 ==="
if [ "$HAS_PREVIOUS" != "1" ]; then
  echo "ROLLBACK_NO_PREV"
  exit "$RCODE"
fi

# 回滚关键操作：禁止 || true 静默吞错——恢复失败必须暴露
cp "$RBACKUP/travel-os-api.jar.bak-$BACKUP_TS" "$RJAR"
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:jar-restore" >&2; exit 50; }
rm -rf "$TMPR/web-restore-$BACKUP_TS" && mkdir -p "$TMPR/web-restore-$BACKUP_TS"
tar xzf "$RBACKUP/www.bak-$BACKUP_TS.tar.gz" -C "$TMPR/web-restore-$BACKUP_TS"
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:untar-www" >&2; exit 50; }
rsync -a --delete --exclude "/ci/" "$TMPR/web-restore-$BACKUP_TS/" "$RWWW/"
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:rsync-www" >&2; exit 50; }
rm -rf "$TMPR/web-restore-$BACKUP_TS"
printf 'TRAVELOS_RELEASE_ID=%s\n' "$PREV_ID" > "$RENV.new" && mv "$RENV.new" "$RENV"
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:release.env" >&2; exit 50; }
printf '%s\n' "$PREV_ID" > "$RCUR.new" && mv "$RCUR.new" "$RCUR"
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:current-release" >&2; exit 50; }
sudo systemctl restart travel-os-api
[ $? -eq 0 ] || { echo "ROLLBACK_ERROR:restart" >&2; exit 50; }

# 回滚后重新验证：版本 + 就绪都通过才输出 ROLLBACK_DONE
RB_RCODE=0
bash "$RSTAGE/verify.sh" "$PREV_ID" || RB_RCODE=$?
if [ "$RB_RCODE" -eq 0 ]; then
  echo "ROLLBACK_DONE"
  exit 51   # 发布验证失败但回滚成功——本地脚本据此报告"回滚完成并已验证"
fi
echo "ROLLBACK_VERIFY_FAILED"
exit 51
REMOTE_EOF
}

# ============ 中断处理（远端事务在一次 flock 内自行处理回滚） ============
on_error() {
  local code="$1"
  if [ "$STAGE" = "transaction" ] || [ "$STAGE" = "verify" ]; then
    warn "阶段=$STAGE 意外失败/中断（exit=$code）——远端事务脚本已/将处理回滚"
  else
    warn "阶段=$STAGE 意外失败/中断（exit=$code）——尚未切换线上产物，无需回滚"
  fi
  exit "$code"
}
trap 'on_error 130' INT TERM
trap 'on_error 1' ERR

# 本地互斥：同一工作目录只允许一个发布进程
if ! mkdir "$SCRIPT_DIR/.publish.lock" 2>/dev/null; then
  die "已有发布在运行（deploy/.publish.lock 存在）。若确认无并发发布，删除该目录后重试。"
fi
LOCK_ACQUIRED=1
trap cleanup_local EXIT

SSHOPTS=(-i "$SSH_KEY" -o "Port=$DEPLOY_PORT" -o StrictHostKeyChecking=accept-new)

# ============ S1 预检（全部发生在修改线上文件之前） ============
STAGE=preflight
log "1/8 预检（工具 / 配置 / 权限 / 磁盘 / 互斥）"
if [ "$BUILD_ONLY" = "0" ]; then
  [ -n "${DEPLOY_HOST:-}" ] || die "缺 DEPLOY_HOST（服务器 IP）。示例：DEPLOY_HOST=1.2.3.4 ./deploy/publish.sh；或写入 deploy/.deploy.env"
  [ -f "$SSH_KEY" ] || die "部署私钥不存在：$SSH_KEY（见 deploy/服务器初始化手册.md 第 2 节）"
fi
[ -f "$MAVEN_BIN" ] || die "MAVEN_BIN 不存在：$MAVEN_BIN（Maven 3.9.11 必需，勿用 3.6.3）"
command -v tar >/dev/null || die "缺 tar"
command -v sha256sum >/dev/null || die "缺 sha256sum（git-bash 自带）"
if [ "$SKIP_TESTS" != "1" ]; then
  if [ ! -f "$NODE_BIN" ]; then
    NODE_BIN="$(command -v node || true)"
  fi
  [ -n "${NODE_BIN:-}" ] || die "缺 node：NODE_BIN 不存在且 PATH 上无 node（前端测试需要）"
fi
FREE_KB="$(df -k "$ROOT_DIR" | awk 'NR==2 {print $4}')"
[ "${FREE_KB:-0}" -ge 2097152 ] || die "本地磁盘不足 2GB（剩余约 $((FREE_KB/1024))MB）：$ROOT_DIR"
MVN_VERSION="$("$MAVEN_BIN" -v 2>/dev/null | head -1 || echo 'unknown')"
echo "  Maven: $MVN_VERSION"
echo "  源码: $GIT_SHA_FULL"
if [ "$BUILD_ONLY" = "0" ]; then
  SRV_FREE_KB="$(ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
    "mkdir -p $RROOT $RBACKUP $RWWW 2>/dev/null; df -k $RROOT | awk 'NR==2 {print \$4}'")" \
    || die "无法连接服务器（$DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_PORT）"
  [ "${SRV_FREE_KB:-0}" -ge 2097152 ] || die "服务器磁盘不足 2GB（剩余约 $((SRV_FREE_KB/1024))MB）：$RROOT"
  ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "test -w $RROOT && test -w $RWWW" \
    || die "服务器目录不可写（$RROOT / $RWWW；检查属主）"
  ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "flock -n $RLOCK -c true" \
    || die "服务器发布锁被占用（$RLOCK）——可能有另一场发布正在执行"
fi

# ============ S2 验证与构建（本地；失败绝不触碰服务器） ============
STAGE=build
log "2/8 验证与构建"
if [ "$SKIP_TESTS" = "1" ]; then
  warn "SKIP_TESTS=1：显式跳过全部测试验证（应急通道）——将记录进 release.json 与发布结果"
fi
if [ "$SKIP_TESTS" != "1" ]; then
  log "  2a 前端测试（通知流 / AI 发送策略 / 文档预览 / E2E 纯分类回归）"
  ( cd "$ROOT_DIR" \
    && "$NODE_BIN" --test apps/web/tests/notifications-stream.test.cjs apps/web/tests/ai-send-policy.test.cjs \
    && "$NODE_BIN" apps/web/tests/doc-preview.test.cjs \
    && "$NODE_BIN" tools/e2e/selftest-map-classify.cjs --classify-only ) \
    || die "前端测试失败——中止发布"
  echo "  前端测试通过"
fi
log "  2b 后端构建（$( [ "$SKIP_TESTS" = "1" ] && echo 'package -DskipTests（应急通道）' || echo 'verify = 编译+测试+打包' )）"
cd "$ROOT_DIR/apps/server"
# 不停止本地开发服务；若 3001 被占用，使用隔离构建目录避免 JAR 文件锁
BUILD_DIR_NAME="target"
if command -v netstat >/dev/null 2>&1 && netstat -ano 2>/dev/null | grep ':3001 ' | grep -q LISTENING; then
  warn "检测到本地开发服务占用 3001，使用隔离构建目录 target-isolated/ 避免 JAR 文件锁"
  BUILD_DIR_NAME="target-isolated"
  mkdir -p "$BUILD_DIR_NAME"
fi
if [ "$SKIP_TESTS" = "1" ]; then
  "$MAVEN_BIN" -B package -DskipTests "-Dproject.build.directory=$BUILD_DIR_NAME" \
    || die "后端构建失败——中止发布"
else
  "$MAVEN_BIN" -B verify "-Dproject.build.directory=$BUILD_DIR_NAME" \
    || die "后端验证失败（mvn verify）——中止发布"
fi
JAR="$(ls $BUILD_DIR_NAME/*.jar 2>/dev/null | head -1)"
[ -f "$JAR" ] || die "构建产物缺失（$BUILD_DIR_NAME/*.jar）"
echo "  构建完成：$JAR（$(du -h "$JAR" | cut -f1)）"

BUILD_DIR="$ROOT_DIR/apps/server/target/release-$RELEASE_ID"
mkdir -p "$BUILD_DIR"
JAR_SHA="$(sha256sum "$JAR" | awk '{print $1}')"
if [ "$BUILD_ONLY" = "1" ]; then
  log "仅构建模式，结束（release=$RELEASE_ID，未部署）"
  exit 0
fi

# ============ S3 staging 上传 + 完整性校验（线上未动） ============
STAGE=upload
log "3/8 staging 上传与完整性校验"
WWW_TAR="$BUILD_DIR/www.tar.gz"
cat > "$BUILD_DIR/release.json" <<EOF
{
  "release": "$RELEASE_ID",
  "source": "$GIT_SHA_FULL",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "jarSha256": "$JAR_SHA",
  "validation": "$( [ "$SKIP_TESTS" = "1" ] && echo skipped || echo full )",
  "maven": "$MVN_VERSION"
}
EOF
# release.json 与前端同包（nginx 按静态文件直接服务 /release.json）
tar czf "$WWW_TAR" -C "$BUILD_DIR" release.json -C "$ROOT_DIR/apps/web" .
WWW_SHA="$(sha256sum "$WWW_TAR" | awk '{print $1}')"
printf '%s  travel-os-api.jar\n%s  www.tar.gz\n' "$JAR_SHA" "$WWW_SHA" > "$BUILD_DIR/SHA256SUMS"
make_verify_script "$BUILD_DIR/verify.sh"

ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "mkdir -p $RSTAGE"
scp "${SSHOPTS[@]}" "$JAR" "$DEPLOY_USER@$DEPLOY_HOST:$RSTAGE/travel-os-api.jar"
scp "${SSHOPTS[@]}" "$WWW_TAR" "$DEPLOY_USER@$DEPLOY_HOST:$RSTAGE/www.tar.gz"
scp "${SSHOPTS[@]}" "$BUILD_DIR/SHA256SUMS" "$DEPLOY_USER@$DEPLOY_HOST:$RSTAGE/SHA256SUMS"
scp "${SSHOPTS[@]}" "$BUILD_DIR/verify.sh" "$DEPLOY_USER@$DEPLOY_HOST:$RSTAGE/verify.sh"
ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "cd $RSTAGE && sha256sum -c SHA256SUMS" \
  || die "上传完整性校验失败（staging 与本地构建不一致）——线上未改动"

# ============ S4-S7 统一发布事务（一次 flock 内：读版本→备份→迁移→切换→验证→失败回滚） ============
STAGE=transaction
log "4/8 发布事务（读版本→备份→迁移→切换→验证→失败回滚，全程持有服务器锁）"
BACKUP_TS="$(date +%Y%m%d%H%M%S)"
make_remote_script "$BUILD_DIR/publish-remote.sh"
scp "${SSHOPTS[@]}" "$BUILD_DIR/publish-remote.sh" "$DEPLOY_USER@$DEPLOY_HOST:$RSTAGE/publish-remote.sh"
ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "mkdir -p $RMTMP"
scp "${SSHOPTS[@]}" "$ROOT_DIR/deploy/systemd/travel-os-api.service" "$DEPLOY_USER@$DEPLOY_HOST:$RMTMP/travel-os-api-$RELEASE_ID.service"
cd "$ROOT_DIR"
tar czf /tmp/mig-$RELEASE_ID.tgz -C apps/server/db migration
scp "${SSHOPTS[@]}" /tmp/mig-$RELEASE_ID.tgz "$DEPLOY_USER@$DEPLOY_HOST:$RMTMP/"
rm -f /tmp/mig-$RELEASE_ID.tgz

# 一次 flock 内执行完整远端事务（PREV_ID/HAS_PREVIOUS 由远端脚本在锁内读取，与本次备份一致）
REMOTE_RC=0
REMOTE_LOG="$BUILD_DIR/remote-out.log"
ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" \
  "flock -n $RLOCK -c 'bash $RSTAGE/publish-remote.sh $RELEASE_ID $BACKUP_TS'" \
  2>&1 | tee "$REMOTE_LOG" || REMOTE_RC=$?

STAGE=verify
if [ "$REMOTE_RC" -eq 0 ]; then
  STAGE=verified
  PREV_ID="$(sed -n 's/^PREV_ID=//p' "$REMOTE_LOG" | tail -1)"
else
  warn "发布事务失败（exit=$REMOTE_RC）——远端脚本已处理回滚"
  PREV_ID="$(sed -n 's/^PREV_ID=//p' "$REMOTE_LOG" | tail -1)"
  if [ "$REMOTE_RC" -eq 10 ] || [ "$REMOTE_RC" -eq 11 ] || [ "$REMOTE_RC" -eq 12 ]; then
    die "备份失败（exit=$REMOTE_RC）——已停止发布，不继续迁移或切换。线上仍是上一版本组合。"
  fi
  if [ "$REMOTE_RC" -eq 20 ]; then
    die "数据库迁移失败——已中止，线上仍是上一版本组合。已执行的脚本靠幂等重放，修复后重跑本发布即可。"
  fi
  if [ "$REMOTE_RC" -eq 50 ]; then
    die "回滚恢复操作失败（exit=50）——人工恢复信息：备份目录 $RBACKUP（travel-os-api.jar.bak-$BACKUP_TS / www.bak-$BACKUP_TS.tar.gz）；恢复步骤见 docs/发布流水线.md「人工回滚」。"
  fi
  if grep -q "ROLLBACK_DONE" "$REMOTE_LOG" 2>/dev/null; then
    warn "回滚完成并已验证：线上恢复为上一版本（release=${PREV_ID:-unknown}），发布仍以失败结束。"
    exit 1
  fi
  if grep -q "ROLLBACK_VERIFY_FAILED" "$REMOTE_LOG" 2>/dev/null; then
    die "回滚后验证未通过——人工恢复信息：备份目录 $RBACKUP（travel-os-api.jar.bak-$BACKUP_TS / www.bak-$BACKUP_TS.tar.gz）；恢复步骤见 docs/发布流水线.md「人工回滚」。"
  fi
  if grep -q "ROLLBACK_NO_PREV" "$REMOTE_LOG" 2>/dev/null; then
    warn "首次部署（无上一版本 JAR）——无可回滚目标。可修复后重跑发布（fix-forward）。"
    exit 1
  fi
  die "发布事务失败且无法确认回滚状态——人工恢复信息：备份目录 $RBACKUP（travel-os-api.jar.bak-$BACKUP_TS / www.bak-$BACKUP_TS.tar.gz）；恢复步骤见 docs/发布流水线.md「人工回滚」。"
fi

# ============ S8 完成 ============
log "8/8 发布完成"
ssh "${SSHOPTS[@]}" "$DEPLOY_USER@$DEPLOY_HOST" "rm -rf $RSTAGE" || true
printf '\n--- 发布结果 ---\n'
echo "release     = $RELEASE_ID"
echo "源码        = $GIT_SHA_FULL"
echo "validation  = $( [ "$SKIP_TESTS" = "1" ] && echo 'SKIPPED（应急通道：未跑后端 verify 与前端测试）' || echo 'full（mvn verify + 前端测试 + 分类回归）' )"
echo "jar sha256  = $JAR_SHA"
echo "发布验证    = 通过（/api/health 服务标识+版本、/api/health/ready 200、:8082/release.json 三者一致）"
echo "回滚点      = $RBACKUP/travel-os-api.jar.bak-$BACKUP_TS（release-id-$BACKUP_TS.txt=$PREV_ID）"
echo "入口        = http://$DEPLOY_HOST:8082/"
