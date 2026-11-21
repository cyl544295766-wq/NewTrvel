#!/usr/bin/env bash
# Travel OS · E2E 本地批量回归入口（串行执行，正确汇总退出码）
#
# 用法：
#   bash tools/e2e/run-all.sh                    # 全量（排除 verify-prod）
#   bash tools/e2e/run-all.sh verify-home verify-map   # 指定脚本
#   E2E_ORIGIN=http://127.0.0.1:3001 E2E_SHOTS_BASE=D:/tmp/e2e-shots bash tools/e2e/run-all.sh
#
# 行为：
# - 目标守卫：E2E_ORIGIN 非本地回环直接拒绝（防误打生产）。
# - 串行执行（避免共享状态、高德限流、注册限流互相干扰）。
# - 每脚本独立记录退出码与尾行结果（ALL PASS / FAILED / ENV FAILURE / UNDETERMINED / 清理失败）。
# - 注册限流（429 Retry-After）：识别输出中的 Retry-After，有界等待后重试一次；仍失败记为 RATE-LIMITED（不算业务通过，也不算产品缺陷）。
# - 任一脚本失败/受阻都不会让汇总打印「全部通过」；最终以退出码区分 0=全部通过 / 1=存在失败。
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ORIGIN="${E2E_ORIGIN:-http://127.0.0.1:3001}"
SHOTS_BASE="${E2E_SHOTS_BASE:-D:/tmp/e2e-shots}"
WORK_DIR="${E2E_WORK_DIR:-$(mktemp -d /d/tmp/e2e-run-XXXX)}"
NODE_BIN="${NODE_BIN:-node}"

export NODE_PATH="${NODE_PATH:-C:/Users/15003/.workbuddy/binaries/node/workspace/node_modules}"

host="$(printf '%s' "$ORIGIN" | sed -E 's#^[a-z]+://([^/:]+).*#\1#')"
case "$host" in
  127.0.0.1|localhost|::1) ;;
  *) echo "[REFUSE] 仅允许本地回环目标（当前 $ORIGIN）——本入口会创建/删除数据"; exit 1;;
esac

# ---- 脚本名校验：先检查原始参数，再处理 .cjs 后缀 ----
# 拒绝 /、反斜杠、父目录、绝对路径、Windows 盘符、verify-prod
validate_raw_arg() {
  local raw="$1"
  # 拒绝路径分隔符
  case "$raw" in
    */*|*\\*) return 1;;
  esac
  # 拒绝父目录
  case "$raw" in
    *..*) return 1;;
  esac
  # 拒绝 Windows 盘符
  case "$raw" in
    [A-Za-z]:*) return 1;;
  esac
  return 0
}

strip_cjs() {
  local n="$1"
  n="${n%.cjs}"
  printf '%s' "$n"
}

is_valid_script() {
  local name="$1"
  # 拒绝生产脚本
  case "$name" in
    verify-prod) return 1;;
  esac
  # 只允许 verify-* 格式
  case "$name" in
    verify-[a-z]*) ;;
    *) return 1;;
  esac
  # 必须是实际存在的脚本文件
  [ -f "$SCRIPT_DIR/$name.cjs" ] || return 1
  return 0
}

if [ $# -gt 0 ]; then
  SCRIPTS=()
  for arg in "$@"; do
    # 第一步：校验原始参数（拒绝路径穿越/绝对路径/盘符）
    if ! validate_raw_arg "$arg"; then
      echo "[REFUSE] 拒绝脚本：$arg（不允许路径分隔符/父目录/绝对路径/盘符）"
      echo "  生产测试请单独手动执行：PROD_ORIGIN=... node tools/e2e/verify-prod.cjs"
      exit 1
    fi
    # 第二步：去掉 .cjs 后缀
    name="$(strip_cjs "$arg")"
    # 第三步：校验脚本名合法性
    if ! is_valid_script "$name"; then
      echo "[REFUSE] 拒绝脚本：$arg（verify-prod 不走本地批量入口；仅允许 verify-* 脚本）"
      echo "  生产测试请单独手动执行：PROD_ORIGIN=... node tools/e2e/verify-prod.cjs"
      exit 1
    fi
    SCRIPTS+=("$name.cjs")
  done
else
  SCRIPTS=()
  for f in "$SCRIPT_DIR"/verify-*.cjs; do
    base="$(basename "$f")"
    [ "$base" = "verify-prod.cjs" ] && { echo "（跳过 verify-prod：生产目标，手动验收用）"; continue; }
    SCRIPTS+=("$base")
  done
fi

# 所有校验通过后才开始执行
if [ ${#SCRIPTS[@]} -eq 0 ]; then
  echo "[REFUSE] 无有效脚本可执行"
  exit 1
fi

export E2E_ORIGIN="$ORIGIN"
mkdir -p "$WORK_DIR"

declare -a NAMES=() CODES=() RESULTS=()
FAIL_COUNT=0; PASS_COUNT=0; RATE_COUNT=0
OVERALL_OK=1

for base in "${SCRIPTS[@]}"; do
  name="${base%.cjs}"
  out="$WORK_DIR/$name.log"
  # 截图目录隔离（各自子目录）
  export E2E_SHOTS="$SHOTS_BASE/$name"
  mkdir -p "$E2E_SHOTS"

  echo "──────────────────────────────────────────────────────────"
  echo "▶ $name"
  attempt=0; code=1
  while :; do
    attempt=$((attempt + 1))
    "$NODE_BIN" "$SCRIPT_DIR/$base" > "$out" 2>&1
    code=$?
    if [ $code -eq 0 ]; then result="PASS"; break; fi
    if grep -aq "Retry-After" "$out" 2>/dev/null && [ $attempt -le 2 ]; then
      # 滚动窗口语义：等待完整窗口（605s > 600s）才能保证本次所有尝试过期；
      # 仅等 Retry-After 会因窗口滑动导致重试继续 429
      wait_s=605
      echo "  （注册/登录限流：等待完整窗口 ${wait_s}s 后重试，第 $attempt 次）"
      sleep "$wait_s"
      continue
    fi
    if grep -aq "Retry-After" "$out" 2>/dev/null; then
      result="RATE-LIMITED（限流窗口未清，重试后仍 429）"
    elif grep -aq "=== ALL PASS ===" "$out"; then
      result="PASS-with-noise"
    elif grep -aq "UNDETERMINED" "$out"; then
      result="UNDETERMINED"
    elif grep -aq "ENV FAILURE" "$out"; then
      result="ENV-FAILURE"
    elif grep -aq "清理失败" "$out"; then
      result="CLEANUP-FAILED"
    else
      result="FAILED"
    fi
    break
  done

  NAMES+=("$name"); CODES+=("$code"); RESULTS+=("$result")
  case "$result" in
    PASS) PASS_COUNT=$((PASS_COUNT+1));;
    RATE-LIMITED*) RATE_COUNT=$((RATE_COUNT+1)); OVERALL_OK=0;;
    *) FAIL_COUNT=$((FAIL_COUNT+1)); OVERALL_OK=0;;
  esac
  echo "  ↳ exit=$code result=$result（日志 $out）"
done

echo "══════════════════════════════════════════════════════════"
echo "E2E 批量回归汇总（工作目录 $WORK_DIR）"
printf '%-26s %-8s %s\n' "脚本" "退出码" "结果"
for i in "${!NAMES[@]}"; do
  printf '%-26s %-8s %s\n' "${NAMES[$i]}" "${CODES[$i]}" "${RESULTS[$i]}"
done
echo "──────────────────────────────────────────────────────────"
echo "通过 $PASS_COUNT / 失败 $FAIL_COUNT / 限流受阻 $RATE_COUNT（共 ${#NAMES[@]}）"
if [ "$OVERALL_OK" -eq 1 ]; then
  echo "=== BATCH ALL PASS ==="
  exit 0
fi
echo "=== BATCH FAILED（存在失败或受阻；明细见各日志）==="
exit 1
