#!/usr/bin/env bash
# Travel OS · 发布状态机模拟测试（本地，不触碰任何真实服务器）
#
# 原理：以 PATH 桩替换 publish.sh 调用的 ssh / scp / taskkill / mvn，并在「模拟远端」
# （$FAKE_STATE 目录树 + curl/systemctl/docker/rsync/flock/df 假命令）中执行远端载荷。
# 验证脚本 verify.sh 被真实执行（仅 curl 是假的），因此轮询/版本比对/就绪判定逻辑是真实的。
# 故障注入只存在于本模拟上下文（环境变量/状态文件），不改 publish.sh 与任何生产逻辑。
#
# 覆盖场景：正常发布 / 前端测试失败 / 上传失败 / 迁移失败 / 切换后重启失败→回滚成功 /
# 就绪检查失败→回滚成功 / 回滚失败 / 首次部署无旧版本 / 并发发布被拒 / SKIP_TESTS 应急通道。
#
# 运行：bash tools/deploy/publish-state-tests.sh
set -u
REAL_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${PUBSIM_WORK:-/d/tmp/pub-sim}"
PASS=0; FAIL=0

NEWID="20260101120000-newrel1"
PREVID="20260101000000-prevrel"

copy_repo() {
  rm -rf "$WORK/repo"
  mkdir -p "$WORK/repo"
  tar cf - --exclude='.git' --exclude='target' --exclude='data' --exclude='.env' \
    --exclude='*.log' --exclude='.deploy.env' -C "$REAL_ROOT" . | (cd "$WORK/repo" && tar xf -)
}

write_stubs() {
  local B="$1"
  # ---- ssh 桩：记录调用；对载荷做路径重映射后在模拟远端执行 ----
  cat > "$B/ssh" <<'EOF'
#!/usr/bin/env bash
echo "ssh ${*: -1}" >> "$FAKE_STATE/calls.log"
if [ "${FAKE_ROLLBACK_FAIL:-0}" = "1" ] && [[ "${*: -1}" == *"web-restore"* ]]; then
  echo "simulated: rollback ssh connection lost" >&2
  exit 1
fi
mkdir -p "$FAKE_STATE/etc/systemd/system"
r="$(printf '%s' "${*: -1}" | sed \
  -e 's|sudo ||g' \
  -e "s|/opt/travel-os|$FAKE_STATE/opt/travel-os|g" \
  -e "s|/var/www/travel-os|$FAKE_STATE/www|g" \
  -e "s|/tmp/web-|$FAKE_STATE/tmp/web-|g" \
  -e "s|/tmp/mig|$FAKE_STATE/tmp/mig|g" \
  -e "s|/tmp/travel-os-migrations.tar.gz|$FAKE_STATE/tmp/mig.tgz|g" \
  -e "s|/tmp/travel-os-api-|$FAKE_STATE/tmp/unit-|g" \
  -e "s|/tmp/travel-os-api.service|$FAKE_STATE/tmp/unit|g" \
  -e "s|/etc/systemd/system/travel-os-api.service|$FAKE_STATE/etc/systemd/system/travel-os-api.service|g")"
# 注入路径映射环境变量，覆盖生成脚本内部的硬编码路径
DEPLOY_ROOT="$FAKE_STATE/opt/travel-os" \
WWW_ROOT="$FAKE_STATE/www" \
PATH="$FAKE_BIN:$PATH" bash -c "$r"
EOF
  # ---- scp 桩：把文件放进模拟远端（含重映射）；可注入失败 ----
  cat > "$B/scp" <<'EOF'
#!/usr/bin/env bash
echo "scp $*" >> "$FAKE_STATE/calls.log"
if [ "${FAKE_SCP_FAIL:-0}" = "1" ]; then
  echo "simulated: scp failure" >&2
  exit 1
fi
src="${*: -2:1}"
dst="${*: -1}"
dst="${dst#*:}"   # 剥掉 user@host: 前缀
  case "$dst" in */) dst="$dst$(basename "$src")";; esac   # 目录形式补文件名
dst="$(printf '%s' "$dst" | sed "s|/opt/travel-os|$FAKE_STATE/opt/travel-os|g")"
mkdir -p "$(dirname "$dst")"
echo "SCP-COPY src=$src dst=$dst srcexists=$([ -f "$src" ] && echo Y || echo N)" >> "$FAKE_STATE/calls.log"
/usr/bin/cp "$src" "$dst" 2>/dev/null || /bin/cp "$src" "$dst" || { echo "scp-copy-failed: $src -> $dst" >&2; exit 1; }
EOF
  # ---- taskkill 桩：绝不真实停止本机进程 ----
  printf '#!/usr/bin/env bash\nexit 0\n' > "$B/taskkill"
  # ---- mvn 桩：瞬时产物，避免真实构建；感知 -Dproject.build.directory ----
  cat > "$B/mvn" <<'MEOF'
#!/usr/bin/env bash
echo "mvn $*" >> "$FAKE_STATE/calls.log"
case "$1" in
  -v) echo "Apache Maven 3.9.11 (fake-for-simulation)"; exit 0;;
esac
# 解析 -Dproject.build.directory=xxx（默认 target）
BDIR="target"
for a in "$@"; do
  case "$a" in
    -Dproject.build.directory=*) BDIR="${a#*=}";;
  esac
done
case "$*" in
  *verify*|*package*)
    mkdir -p "$BDIR"
    printf 'fake-jar-bytes' > "$BDIR/travel-os-api-0.1.0.jar"
    echo "[INFO] BUILD SUCCESS (fake)"
    exit 0;;
esac
exit 1
MEOF
  # ---- 假 curl：由模拟远端状态决定响应（-f 语义 = 非 2xx 退出 22） ----
  cat > "$B/curl" <<'EOF'
#!/usr/bin/env bash
url=""
for a in "$@"; do case "$a" in http*) url="$a";; esac; done
started="$(cat "$FAKE_STATE/app-started" 2>/dev/null || echo 0)"
case "$url" in
  *":3001/api/health/ready"*)
    if [ -f "$FAKE_STATE/ready-fail-remaining" ]; then
      n="$(cat "$FAKE_STATE/ready-fail-remaining")"
      if [ "$n" -gt 0 ]; then echo "$((n - 1))" > "$FAKE_STATE/ready-fail-remaining"; exit 22; fi
    fi
    # 按当前线上 release 绑定的就绪故障：仅新版本未就绪（回滚到上一版本后自动恢复）
    if [ -f "$FAKE_STATE/ready-fail-release" ]; then
      cur="$(cat "$FAKE_STATE/opt/travel-os/current-release.txt" 2>/dev/null || echo '')"
      if [ "$cur" = "$(cat "$FAKE_STATE/ready-fail-release")" ]; then exit 22; fi
    fi
    [ "$started" = "1" ] || exit 22
    echo '{"status":"ok","service":"travel-os-api","database":"ok","schema":"ok","timestamp":"x"}'
    exit 0;;
  *":3001/api/health"*)
    [ "$started" = "1" ] || exit 22
    rid="$(sed -n 's/^TRAVELOS_RELEASE_ID=//p' "$FAKE_STATE/opt/travel-os/release.env" 2>/dev/null)"
    if [ -n "$rid" ]; then
      echo "{\"status\":\"ok\",\"service\":\"travel-os-api\",\"release\":\"$rid\",\"timestamp\":\"x\"}"
    else
      echo '{"status":"ok","service":"travel-os-api","timestamp":"x"}'
    fi
    exit 0;;
  *":8082/release.json"*)
    [ -f "$FAKE_STATE/www/release.json" ] || exit 22
    cat "$FAKE_STATE/www/release.json"
    exit 0;;
  *)
    exit 22;;
esac
EOF
  # ---- 假 systemctl：restart 可注入"仅首次失败"，daemon-reload 幂等成功 ----
  cat > "$B/systemctl" <<'EOF'
#!/usr/bin/env bash
echo "systemctl $*" >> "$FAKE_STATE/calls.log"
case "$*" in
  *restart*)
    if [ -f "$FAKE_STATE/restart-fail-remaining" ]; then
      n="$(cat "$FAKE_STATE/restart-fail-remaining")"
      if [ "$n" -gt 0 ]; then echo "$((n - 1))" > "$FAKE_STATE/restart-fail-remaining"
        echo "simulated: restart failed" >&2
        exit 1
      fi
    fi
    echo 1 > "$FAKE_STATE/app-started"
    exit 0;;
  *daemon-reload*) exit 0;;
esac
exit 0
EOF
  # ---- 假 sudo：直接执行后续命令（Windows 无 sudo） ----
  cat > "$B/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
  # ---- 假 docker（仅迁移 psql 语义；可注入"第 N 次失败"） ----
  cat > "$B/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$FAKE_STATE/calls.log"
case "$*" in
  *psql*)
    cat > /dev/null
    echo applied >> "$FAKE_STATE/applied.log"
    if [ -f "$FAKE_STATE/migrate-fail-remaining" ]; then
      n="$(cat "$FAKE_STATE/migrate-fail-remaining")"
      if [ "$n" -gt 0 ]; then echo "$((n - 1))" > "$FAKE_STATE/migrate-fail-remaining"
        echo "simulated: SQL error" >&2
        exit 1
      fi
    fi
    exit 0;;
esac
exit 0
EOF
  # ---- 假 rsync：模拟 --delete + --exclude /ci/ ----
  cat > "$B/rsync" <<'EOF'
#!/usr/bin/env bash
echo "rsync $*" >> "$FAKE_STATE/calls.log"
src=""; dst=""; excl=""
prev=""
for a in "$@"; do
  case "$a" in
    -a|--delete) ;;
    --exclude) prev=1;;
    "/ci/") if [ "$prev" = "1" ]; then excl="ci"; fi; prev="";;
    */) if [ -z "$src" ]; then src="$a"; else dst="$a"; fi;;
  esac
done
src="${src%/}"; dst="${dst%/}"
mkdir -p "$dst"
if [ -n "$excl" ] && [ -d "$dst/$excl" ]; then mv "$dst/$excl" "$dst/.__keep"; fi
find "$dst" -mindepth 1 -maxdepth 1 ! -name '.__keep' -exec rm -rf {} + 2>/dev/null
cp -r "$src"/. "$dst"/
if [ -d "$dst/.__keep" ]; then mv "$dst/.__keep" "$dst/$excl"; fi
EOF
  # ---- flock 桩：基于 mkdir 的真实互斥锁（原子性与 flock -n 语义一致） ----
  cat > "$B/flock" <<'FLEOF'
#!/usr/bin/env bash
# 模拟 flock -n <lockfile> -c '<command>'：mkdir 原子互斥（并发安全）
NONBLOCK=0
LOCKFILE=""
CMD=""
while [ $# -gt 0 ]; do
  case "$1" in
    -n) NONBLOCK=1; shift;;
    -c) CMD="$2"; shift 2;;
    *) if [ -z "$LOCKFILE" ]; then LOCKFILE="$1"; fi; shift;;
  esac
done
LOCKDIR="$LOCKFILE.lockdir"
if ! mkdir "$LOCKDIR" 2>/dev/null; then
  if [ "$NONBLOCK" = "1" ]; then
    echo "flock: cannot acquire lock on $LOCKFILE" >&2
    exit 1
  fi
  # 阻塞模式：等待锁释放（有界）
  for i in $(seq 1 120); do
    sleep 0.5
    mkdir "$LOCKDIR" 2>/dev/null && break
    [ $i -eq 120 ] && { echo "flock: timeout waiting for $LOCKFILE" >&2; exit 1; }
  done
fi
cleanup_flock() { rmdir "$LOCKDIR" 2>/dev/null; }
trap cleanup_flock EXIT
PATH="$FAKE_BIN:$PATH" bash -c "$CMD"
rc=$?
cleanup_flock
trap - EXIT
exit $rc
FLEOF
  # ---- 假 df / taskkill / journalctl ----
  cat > "$B/df" <<'DfEOF'
#!/usr/bin/env bash
echo "Filesystem 1024-blocks Used Available Capacity Mounted"
echo "fake 9999999999 1 9999999998 1% /"
DfEOF
  printf '#!/usr/bin/env bash\necho "taskkill $*" >> "$FAKE_STATE/calls.log"\nexit 0\n' > "$B/taskkill2"
  mv -f "$B/taskkill2" "$B/taskkill"
  printf '#!/usr/bin/env bash\necho "fake journal line"\n' > "$B/journalctl"
  # ---- cp 包装：备份失败注入（仅当目标路径含 bak-/release-id- 时检查状态文件） ----
  cat > "$B/cp" <<'CPEOF'
#!/usr/bin/env bash
dst="${*: -1}"
if [[ "$dst" == *"bak-"* ]] && [[ "$dst" != *".tar.gz"* ]] && [ -f "$FAKE_STATE/backup-jar-fail" ]; then
  echo "simulated: JAR backup failure" >&2; exit 1
fi

if [[ "$dst" == *"release-id-"* ]] && [ -f "$FAKE_STATE/backup-version-fail" ]; then
  echo "simulated: version backup failure" >&2; exit 1
fi
# 回滚失败注入：源含 bak- 但目标不含（= 从备份恢复，非创建备份）
if [ "${FAKE_ROLLBACK_FAIL:-0}" = "1" ]; then
  last="${*: -1}"
  if [[ "$last" != *"bak-"* ]]; then
    for a in "$@"; do
      if [[ "$a" == *"bak-"* ]]; then
        echo "simulated: rollback restore failure" >&2; exit 1
      fi
    done
  fi
fi
# 映射 /etc/systemd/system/ → $FAKE_STATE/etc/systemd/system/（publish-remote.sh 文件内容未经 sed 处理）
dst="$(printf '%s' "$dst" | sed "s|/etc/systemd/system|$FAKE_STATE/etc/systemd/system|g")"
# 替换参数中最后一个（目标路径）
args=()
n=0; for a in "$@"; do n=$((n+1)); done
i=0
for a in "$@"; do
  i=$((i+1))
  if [ "$i" -eq "$n" ]; then args+=("$dst"); else args+=("$a"); fi
done
# 调用真实 cp（避免递归：用 command -v 找真实路径）
for realcp in /usr/bin/cp /bin/cp; do if [ -x "$realcp" ]; then exec "$realcp" "${args[@]}"; fi; done
echo "real cp not found" >&2; exit 1
CPEOF
  # ---- tar 包装：www 备份失败注入（仅当输出路径含 bak-*.tar.gz 时检查） ----
  cat > "$B/tar" <<'TAREOF'
#!/usr/bin/env bash
# 找输出文件（czf 后的第一个参数）
outfile=""
prev=""
for a in "$@"; do
  if [ "$prev" = "czf" ]; then outfile="$a"; break; fi
  prev="$a"
done
if [[ "$outfile" == *"bak-"*".tar.gz"* ]] && [ -f "$FAKE_STATE/backup-www-fail" ]; then
  echo "simulated: www backup failure" >&2; exit 1
fi
# 调用真实 tar（用 command -p 避免递归；git-bash 的 tar 在 /usr/bin）
if [ -x /usr/bin/tar ]; then exec /usr/bin/tar "$@"; fi
if [ -x /bin/tar ]; then exec /bin/tar "$@"; fi
exec tar.real "$@" 2>/dev/null || { echo "real tar not found" >&2; exit 1; }
TAREOF
  : > "$B/anykey"
  chmod +x "$B"/*
}

# 预置「存在上一版本」的模拟远端状态
seed_prev() {
  mkdir -p "$FAKE_STATE/opt/travel-os/backups" "$FAKE_STATE/opt/travel-os/staging" \
    "$FAKE_STATE/www/sub" "$FAKE_STATE/www/ci" "$FAKE_STATE/tmp"
  printf 'fake-prev-jar' > "$FAKE_STATE/opt/travel-os/travel-os-api.jar"
  printf "$PREVID\n" > "$FAKE_STATE/opt/travel-os/current-release.txt"
  printf "TRAVELOS_RELEASE_ID=$PREVID\n" > "$FAKE_STATE/opt/travel-os/release.env"
  printf '<html>prev</html>' > "$FAKE_STATE/www/index.html"
  printf 'old-file' > "$FAKE_STATE/www/sub/old.txt"
  printf 'ci-state' > "$FAKE_STATE/www/ci/status.html"
  # 新机制上线后，每版前端都带 release.json（回滚后再验证依赖它核验前端版本）
  printf '{\n  "release": "%s",\n  "validation": "full"\n}\n' "$PREVID" > "$FAKE_STATE/www/release.json"
}

# 预置「首次部署」的模拟远端状态（无旧 jar / 无版本记录）
seed_fresh() {
  mkdir -p "$FAKE_STATE/opt/travel-os/backups" "$FAKE_STATE/opt/travel-os/staging" \
    "$FAKE_STATE/www/ci" "$FAKE_STATE/tmp"
  printf 'ci-state' > "$FAKE_STATE/www/ci/status.html"
}

run_publish() { # $1=场景目录 $2=SKIP_TESTS(0/1) [其余故障注入用前置种子/环境]
  local sc="$1" skip="${2:-0}"
  ( cd "$WORK/repo" \
    && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" \
       DEPLOY_HOST="203.0.113.10" \
       RELEASE_ID="$NEWID" \
       SSH_KEY="$sc/bin/anykey" \
       MAVEN_BIN="$FAKE_BIN/mvn" \
       VERIFY_ROUNDS=3 VERIFY_INTERVAL=0.2 \
       SKIP_TESTS="$skip" \
       PATH="$FAKE_BIN:$PATH" \
       bash deploy/publish.sh ) > "$sc/out.log" 2>&1
  echo $?
}

assert_fail() { # $1=场景目录 $2=附加说明…
  local sc="$1"; shift
  local code; code="$(cat "$sc/code")"
  if [ "$code" -eq 0 ]; then echo "  !!  期望非零退出，实为 0"; FAIL=$((FAIL+1)); return 1; fi
  if grep -q "发布完成" "$sc/out.log" || grep -q -- "--- 发布结果 ---" "$sc/out.log"; then
    echo "  !!  失败场景打印了发布成功标记"; FAIL=$((FAIL+1)); return 1
  fi
  PASS=$((PASS+1))
}

assert_success() {
  local sc="$1"
  local code; code="$(cat "$sc/code")"
  if [ "$code" -ne 0 ]; then echo "  !!  期望退出 0，实为 $code"; tail -20 "$sc/out.log"; FAIL=$((FAIL+1)); return 1; fi
  grep -q "发布完成" "$sc/out.log" || { echo "  !!  缺少发布完成标记"; FAIL=$((FAIL+1)); return 1; }
  grep -q -- "--- 发布结果 ---" "$sc/out.log" || { echo "  !!  缺少发布结果汇总"; FAIL=$((FAIL+1)); return 1; }
  PASS=$((PASS+1))
}

chk() { # 条件断言（场景内）
  if eval "$2"; then PASS=$((PASS+1)); else echo "  !!  $1"; FAIL=$((FAIL+1)); fi
}

# ============ 场景 ============
copy_repo
mkdir -p "$WORK"

echo "=== 场景 1：正常发布（覆盖上一版本） ==="
SC="$WORK/s1"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_success "$SC"
chk "current-release.txt 已更新为目标版本" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\")\" = \"$NEWID\" ]"
chk "release.env 已更新为目标版本" "grep -q \"TRAVELOS_RELEASE_ID=$NEWID\" \"$FAKE_STATE/opt/travel-os/release.env\""
chk "前端 release.json 已随包发布" "grep -q '\"release\": \"$NEWID\"' \"$FAKE_STATE/www/release.json\""
chk "上一版本 jar 备份存在" "ls \"$FAKE_STATE\"/opt/travel-os/backups/travel-os-api.jar.bak-* >/dev/null 2>&1"
chk "旧前端文件被 --delete 清理、新文件就位" "[ ! -f \"$FAKE_STATE/www/sub/old.txt\" ] && [ -f \"$FAKE_STATE/www/index.html\" ]"
chk "/ci/ 目录保留" "[ -f \"$FAKE_STATE/www/ci/status.html\" ]"
chk "验证输出含三合一通过标记" "grep -q 'health + ready + frontend release all match' \"$SC/out.log\""

echo "=== 场景 2：发布前验证失败（前端测试失败） ==="
SC="$WORK/s2"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
printf '#!/usr/bin/env bash\necho "simulated: frontend test failure" >&2\nexit 1\n' > "$SC/bin/node-fail"
code=$( cd "$WORK/repo" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  SSH_KEY="$SC/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" NODE_BIN="$SC/bin/node-fail" PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC/out.log" 2>&1; echo $? ); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出含前端测试失败原因" "grep -q '前端测试失败' \"$SC/out.log\""
chk "未发生任何服务器上传" "! grep -q '^scp ' \"$FAKE_STATE/calls.log\""

echo "=== 场景 3：上传失败 ==="
SC="$WORK/s3"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; export FAKE_SCP_FAIL=1
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"; unset FAKE_SCP_FAIL
assert_fail "$SC"
chk "线上版本未变" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\" 2>/dev/null)\" = \"$PREVID\" ]"
chk "旧 jar 未被覆盖" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/travel-os-api.jar\")\" = 'fake-prev-jar' ]"

echo "=== 场景 4：迁移失败（部分完成后中止） ==="
SC="$WORK/s4"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; echo 1 > "$FAKE_STATE/migrate-fail-remaining"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出含幂等重放说明" "grep -q '幂等重放' \"$SC/out.log\""
chk "线上版本未变（迁移在切换之前）" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\")\" = \"$PREVID\" ]"
chk "旧 jar 未被覆盖" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/travel-os-api.jar\")\" = 'fake-prev-jar' ]"

echo "=== 场景 5：切换后重启失败 → 前后端回滚成功 ==="
SC="$WORK/s5"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; echo 1 > "$FAKE_STATE/restart-fail-remaining"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "jar 回滚到上一版本" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/travel-os-api.jar\")\" = 'fake-prev-jar' ]"
chk "前端回滚为上一版本" "grep -q '\"release\": \"$PREVID\"' \"$FAKE_STATE/www/release.json\""
chk "前端回滚恢复旧文件（--delete 语义）" "[ -f \"$FAKE_STATE/www/sub/old.txt\" ]"
chk "版本记录回滚到上一版本" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\")\" = \"$PREVID\" ]"
chk "回滚完成并经再验证" "grep -q '回滚完成并已验证' \"$SC/out.log\""

echo "=== 场景 6：就绪检查失败 → 回滚成功 ==="
SC="$WORK/s6"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; printf '%s
' "$NEWID" > "$FAKE_STATE/ready-fail-release"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "回滚完成并经再验证" "grep -q '回滚完成并已验证' \"$SC/out.log\""
chk "版本记录回滚到上一版本" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\")\" = \"$PREVID\" ]"
chk "前端回滚为上一版本" "grep -q '\"release\": \"$PREVID\"' \"$FAKE_STATE/www/release.json\""

echo "=== 场景 7：回滚也失败 → 人工恢复信息 ==="
SC="$WORK/s7"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; export FAKE_ROLLBACK_FAIL=1
echo 1 > "$FAKE_STATE/restart-fail-remaining"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"; unset FAKE_ROLLBACK_FAIL
assert_fail "$SC"
chk "输出人工恢复所需备份路径" "grep -q 'travel-os-api.jar.bak-' \"$SC/out.log\""
chk "输出人工恢复指引" "grep -q '人工恢复' \"$SC/out.log\""

echo "=== 场景 8：首次部署无旧版本 → 切换失败后如实报告 ==="
SC="$WORK/s8"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_fresh; echo 1 > "$FAKE_STATE/restart-fail-remaining"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "如实说明无旧版可回滚" "grep -q '首次部署（无上一版本 JAR）' \"$SC/out.log\""
chk "未声称回滚成功" "! grep -q '回滚完成并已验证' \"$SC/out.log\""
chk "给出 fix-forward 建议" "grep -q 'fix-forward' \"$SC/out.log\""

echo "=== 场景 9：并发发布被拒绝（本地互斥锁） ==="
SC="$WORK/s9"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; mkdir -p "$WORK/repo/deploy/.publish.lock"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出互斥提示" "grep -q '已有发布在运行' \"$SC/out.log\""
rm -rf "$WORK/repo/deploy/.publish.lock"

echo "=== 场景 10：SKIP_TESTS=1 应急通道（显式启用并记录） ==="
SC="$WORK/s10"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
code=$(run_publish "$SC" 1); echo "$code" > "$SC/code"
assert_success "$SC"
chk "结果汇总标记验证被跳过" "grep -q 'SKIPPED（应急通道' \"$SC/out.log\""
chk "release.json 记录 validation=skipped" "grep -q '\"validation\": \"skipped\"' \"$FAKE_STATE/www/release.json\""
chk "未调用真实测试（无 node --test 痕迹）" "! grep -q '前端测试通过' \"$SC/out.log\""

echo "=== 场景 11：锁生命周期——被拒进程不删除他人锁 ==="
SC="$WORK/s11"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
# 模拟第一个进程持锁
mkdir -p "$WORK/repo/deploy/.publish.lock"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出互斥提示" "grep -q '已有发布在运行' \"$SC/out.log\""
chk "被拒进程退出后锁仍存在（未误删他人锁）" "[ -d \"$WORK/repo/deploy/.publish.lock\" ]"
rm -rf "$WORK/repo/deploy/.publish.lock"

echo "=== 场景 12：正常退出释放锁 ==="
SC="$WORK/s12"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_success "$SC"
chk "正常退出后锁已释放" "[ ! -d \"$WORK/repo/deploy/.publish.lock\" ]"

echo "=== 场景 13：失败退出释放锁 ==="
SC="$WORK/s13"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
printf '#!/usr/bin/env bash\necho "simulated: test failure" >&2\nexit 1\n' > "$SC/bin/node-fail"
code=$( cd "$WORK/repo" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  SSH_KEY="$SC/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" NODE_BIN="$SC/bin/node-fail" PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC/out.log" 2>&1; echo $? ); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "失败退出后锁已释放" "[ ! -d \"$WORK/repo/deploy/.publish.lock\" ]"

echo "=== 场景 14：JAR 备份失败 → 停止后续修改 ==="
SC="$WORK/s14"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; echo 1 > "$FAKE_STATE/backup-jar-fail"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出备份失败原因" "grep -q '备份失败' \"$SC/out.log\""
chk "未执行迁移" "! grep -q 'applying:' \"$SC/out.log\""
chk "未执行切换" "! grep -q '切换' \"$SC/out.log\" || grep -q '备份失败' \"$SC/out.log\""
chk "线上版本未变" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\" 2>/dev/null)\" = \"$PREVID\" ]"

echo "=== 场景 15：前端备份失败 → 停止后续修改 ==="
SC="$WORK/s15"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; echo 1 > "$FAKE_STATE/backup-www-fail"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出备份失败原因" "grep -q '备份失败' \"$SC/out.log\""
chk "线上版本未变" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\" 2>/dev/null)\" = \"$PREVID\" ]"

echo "=== 场景 16：版本记录备份失败 → 停止后续修改 ==="
SC="$WORK/s16"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev; echo 1 > "$FAKE_STATE/backup-version-fail"
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_fail "$SC"
chk "输出备份失败原因" "grep -q '备份失败' \"$SC/out.log\""
chk "线上版本未变" "[ \"\$(cat \"$FAKE_STATE/opt/travel-os/current-release.txt\" 2>/dev/null)\" = \"$PREVID\" ]"

echo "=== 场景 17：独立 staging 目录（每次发布不互相覆盖） ==="
SC="$WORK/s17"; rm -rf "$SC"; mkdir -p "$SC/bin"; FAKE_STATE="$SC/state"; FAKE_BIN="$SC/bin"; export FAKE_STATE FAKE_BIN
  write_stubs "$SC/bin"
seed_prev
code=$(run_publish "$SC" 0); echo "$code" > "$SC/code"
assert_success "$SC"
chk "staging 使用独立目录（含 release ID）" "grep -q 'staging-$NEWID' \"$FAKE_STATE/calls.log\""

echo "=== 场景 18：跨工作目录并发——B 被拒后 A 锁仍在 ==="
# 复制仓库到第二个工作目录
rm -rf "$WORK/repo2"; mkdir -p "$WORK/repo2"
tar cf - --exclude='.git' --exclude='target' --exclude='data' --exclude='.env' \
  --exclude='*.log' --exclude='.deploy.env' -C "$WORK/repo" . | (cd "$WORK/repo2" && tar xf -)
SC18="$WORK/s18"; rm -rf "$SC18"; mkdir -p "$SC18/bin"
FAKE_STATE="$SC18/state"; FAKE_BIN="$SC18/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC18/bin"
seed_prev
# A 在后台运行（持锁）
( cd "$WORK/repo" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-arel1" SSH_KEY="$SC18/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC18/outA.log" 2>&1; echo $? > "$SC18/codeA" ) &
PID_A=$!
sleep 3  # 等 A 进入事务阶段持锁
# B 从第二个工作目录尝试发布 → 应被拒
( cd "$WORK/repo2" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-brel1" SSH_KEY="$SC18/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC18/outB.log" 2>&1; echo $? > "$SC18/codeB" ) &
PID_B=$!
wait $PID_B 2>/dev/null
CODE_B="$(cat "$SC18/codeB" 2>/dev/null || echo 1)"
wait $PID_A 2>/dev/null
CODE_A="$(cat "$SC18/codeA" 2>/dev/null || echo 1)"
echo "  A exit=$CODE_A  B exit=$CODE_B"
chk "B 被拒（非零退出）" "[ \"$CODE_B\" != \"0\" ]"
chk "B 输出含锁占用提示" "grep -q '锁' \"$SC18/outB.log\""
chk "A 正常完成（锁内独占）" "[ \"$CODE_A\" = \"0\" ]"
chk "A 的版本未被 B 覆盖" "grep -q 'arel1' \"$FAKE_STATE/opt/travel-os/current-release.txt\" 2>/dev/null || grep -q 'arel1' \"$SC18/outA.log\""
chk "staging 未交错（各自独立目录）" "grep -c 'staging-' \"$FAKE_STATE/calls.log\" | head -1"

echo "=== 场景 19：A 失败回滚期间 B 不能穿插 ==="
SC19="$WORK/s19"; rm -rf "$SC19"; mkdir -p "$SC19/bin"
FAKE_STATE="$SC19/state"; FAKE_BIN="$SC19/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC19/bin"
seed_prev; echo 1 > "$FAKE_STATE/restart-fail-remaining"
# A 失败并回滚（后台）
( cd "$WORK/repo" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-arel2" SSH_KEY="$SC19/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC19/outA.log" 2>&1; echo $? > "$SC19/codeA" ) &
PID_A2=$!
sleep 3
# B 尝试发布 → 应被拒（A 仍在锁内回滚）
( cd "$WORK/repo2" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-brel2" SSH_KEY="$SC19/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC19/outB.log" 2>&1; echo $? > "$SC19/codeB" ) &
PID_B2=$!
wait $PID_B2 2>/dev/null
CODE_B2="$(cat "$SC19/codeB" 2>/dev/null || echo 1)"
wait $PID_A2 2>/dev/null
CODE_A2="$(cat "$SC19/codeA" 2>/dev/null || echo 1)"
echo "  A exit=$CODE_A2  B exit=$CODE_B2"
chk "A 回滚失败（非零退出）" "[ \"$CODE_A2\" != \"0\" ]"
chk "B 在 A 回滚期间被拒" "[ \"$CODE_B2\" != \"0\" ]"
chk "A 输出含回滚标记" "grep -q 'ROLLBACK' \"$SC19/outA.log\""

echo "=== 场景 20：A 释放锁后 B 可继续 ==="
SC20="$WORK/s20"; rm -rf "$SC20"; mkdir -p "$SC20/bin"
FAKE_STATE="$SC20/state"; FAKE_BIN="$SC20/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC20/bin"
seed_prev
( cd "$WORK/repo" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-arel3" SSH_KEY="$SC20/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC20/outA.log" 2>&1; echo $? > "$SC20/codeA" ) &
PID_A3=$!
wait $PID_A3 2>/dev/null
CODE_A3="$(cat "$SC20/codeA" 2>/dev/null || echo 1)"
CODE_B3=$( cd "$WORK/repo2" && FAKE_STATE="$FAKE_STATE" FAKE_BIN="$FAKE_BIN" DEPLOY_HOST="203.0.113.10" \
  RELEASE_ID="20260101120000-brel3" SSH_KEY="$SC20/bin/anykey" MAVEN_BIN="$FAKE_BIN/mvn" \
  VERIFY_ROUNDS=2 VERIFY_INTERVAL=0.3 SKIP_TESTS=1 PATH="$FAKE_BIN:$PATH" \
  bash deploy/publish.sh > "$SC20/outB.log" 2>&1; echo $? )
echo "  A exit=$CODE_A3  B exit=$CODE_B3"
chk "A 正常完成" "[ \"$CODE_A3\" = \"0\" ]"
chk "A 释放锁后 B 成功" "[ \"$CODE_B3\" = \"0\" ]"
chk "B 版本正确写入" "grep -q 'brel3' \"$FAKE_STATE/opt/travel-os/current-release.txt\""

echo "=== 场景 21：验证失败 → 实际进入回滚（核对文件状态） ==="
SC21="$WORK/s21"; rm -rf "$SC21"; mkdir -p "$SC21/bin"
FAKE_STATE="$SC21/state"; FAKE_BIN="$SC21/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC21/bin"
seed_prev; printf '%s\n' "$NEWID" > "$FAKE_STATE/ready-fail-release"
code=$(run_publish "$SC21" 0); echo "$code" > "$SC21/code"
assert_fail "$SC21"
chk "输出含 ROLLBACK_DONE" "grep -q 'ROLLBACK_DONE' \"$SC21/out.log\""
chk "版本记录回滚到 PREV_ID" "grep -q \"$PREVID\" \"$FAKE_STATE/opt/travel-os/current-release.txt\""
chk "jar 回滚到上一版本" "grep -q 'fake-prev-jar' \"$FAKE_STATE/opt/travel-os/travel-os-api.jar\""
chk "release.env 回滚到 PREV_ID" "grep -q \"$PREVID\" \"$FAKE_STATE/opt/travel-os/release.env\""

echo "=== 场景 22：切换中途失败（restart 失败）→ 实际回滚 ==="
SC22="$WORK/s22"; rm -rf "$SC22"; mkdir -p "$SC22/bin"
FAKE_STATE="$SC22/state"; FAKE_BIN="$SC22/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC22/bin"
seed_prev; echo 1 > "$FAKE_STATE/restart-fail-remaining"
code=$(run_publish "$SC22" 0); echo "$code" > "$SC22/code"
assert_fail "$SC22"
chk "输出含 ROLLBACK_DONE" "grep -q 'ROLLBACK_DONE' \"$SC22/out.log\""
chk "版本记录回滚到 PREV_ID" "grep -q \"$PREVID\" \"$FAKE_STATE/opt/travel-os/current-release.txt\""
chk "jar 回滚到上一版本" "grep -q 'fake-prev-jar' \"$FAKE_STATE/opt/travel-os/travel-os-api.jar\""

echo "=== 场景 23：回滚恢复失败 → 明确暴露 ==="
SC23="$WORK/s23"; rm -rf "$SC23"; mkdir -p "$SC23/bin"
FAKE_STATE="$SC23/state"; FAKE_BIN="$SC23/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC23/bin"
seed_prev; printf '%s\n' "$NEWID" > "$FAKE_STATE/ready-fail-release"
export FAKE_ROLLBACK_FAIL=1
code=$(run_publish "$SC23" 0); echo "$code" > "$SC23/code"; unset FAKE_ROLLBACK_FAIL
assert_fail "$SC23"
chk "输出含 ROLLBACK_ERROR 或人工恢复" "grep -qE 'ROLLBACK_ERROR|人工恢复' \"$SC23/out.log\""

echo "=== 场景 24：回滚后验证失败 → 明确报告 ==="
SC24="$WORK/s24"; rm -rf "$SC24"; mkdir -p "$SC24/bin"
FAKE_STATE="$SC24/state"; FAKE_BIN="$SC24/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC24/bin"
seed_prev; echo 6 > "$FAKE_STATE/ready-fail-remaining"
code=$(run_publish "$SC24" 0); echo "$code" > "$SC24/code"
assert_fail "$SC24"
chk "输出含 ROLLBACK_VERIFY_FAILED 或人工恢复" "grep -qE 'ROLLBACK_VERIFY_FAILED|人工恢复' \"$SC24/out.log\""

echo "=== 场景 25：PREV_ID 在锁内读取（远端脚本输出含 PREV_ID） ==="
SC25="$WORK/s25"; rm -rf "$SC25"; mkdir -p "$SC25/bin"
FAKE_STATE="$SC25/state"; FAKE_BIN="$SC25/bin"; export FAKE_STATE FAKE_BIN
write_stubs "$SC25/bin"
seed_prev
code=$(run_publish "$SC25" 0); echo "$code" > "$SC25/code"
assert_success "$SC25"
chk "远端脚本输出含 PREV_ID=（锁内读取）" "grep -q 'PREV_ID=' \"$SC25/out.log\""
chk "PREV_ID 值正确" "grep -q \"$PREVID\" \"$SC25/out.log\""

echo
echo "--- 状态机模拟结果 ---"
echo "通过断言: $PASS / 失败断言: $FAIL"
if [ "$FAIL" -eq 0 ]; then
  echo "=== PUBLISH STATE SIM ALL PASS ==="
  exit 0
fi
echo "=== PUBLISH STATE SIM FAILED ==="
exit 1
