#!/usr/bin/env bash
# =============================================================================
# wait-mesh-ready.sh —— 等待 Luban-RDS mesh 集群就绪后再启动应用
#
# 背景: 2026-08-13 生产事故中, 11 节点重启后应用随即启动, 但 Redisson
#       初始化恰好撞上"节点不知 Leader"的窗口(12/19 对 11 的总线重连仍在
#       旧构建退避中, 11 收不到心跳), mesh 返回的 CLUSTER SLOTS 为空,
#       Redisson 报 "Can't connect to servers!" 导致应用启动失败。
#       本脚本在拉起应用前先确认集群已就绪(存在 Leader 且广播了 0-16383
#       槽位), 从时序上消除这类竞态。
#
# 用法:
#   ./wait-mesh-ready.sh                                        # 仅等待就绪(默认 300s)
#   ./wait-mesh-ready.sh -- nohup java -jar app.jar &           # 就绪后执行启动命令
#   ./wait-mesh-ready.sh --force -- nohup java -jar app.jar &   # 超时后仍强制执行
#
# 环境变量(可覆盖):
#   MESH_NODES     节点列表 "host:port ..."  默认 "172.16.83.11:9736 172.16.83.12:9736 172.16.83.19:9736"
#   MESH_TIMEOUT   总等待秒数, 0=无限等待    默认 300
#   MESH_INTERVAL  轮询间隔秒数              默认 2
#   MESH_SOCK_TIMEOUT 单次探测超时秒数       默认 3
#
# 依赖: GNU bash(支持 /dev/tcp) + timeout 命令, 生产 Linux 可直接使用。
# =============================================================================

set -u

# ---------------------------------------------------------------- 默认配置
MESH_NODES="${MESH_NODES:-172.16.83.11:9736 172.16.83.12:9736 172.16.83.19:9736}"
MESH_TIMEOUT="${MESH_TIMEOUT:-300}"
MESH_INTERVAL="${MESH_INTERVAL:-2}"
MESH_SOCK_TIMEOUT="${MESH_SOCK_TIMEOUT:-3}"
FORCE=0

# ---------------------------------------------------------------- 工具函数
log() {
    echo "[$(date '+%F %T')] $*"
}

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
}

# 探测单个节点: 发送 CLUSTER SLOTS, 响应中出现槽范围终点 16383 即视为就绪
# (mesh 单 Leader 全槽 0-16383; 无 Leader 时返回空数组 *0, 无槽位广播)
check_node() {
    local host="$1" port="$2"
    timeout "$MESH_SOCK_TIMEOUT" bash -c '
        exec 3<>/dev/tcp/'"$host"'/'"$port"' || exit 1
        printf "*2\r\n\$7\r\nCLUSTER\r\n\$5\r\nSLOTS\r\n" >&3
        while IFS= read -r line <&3; do
            case "$line" in
                "*0"*|"-"*) exit 1 ;;        # 空数组或错误响应: 未就绪
                *"16383"*) exit 0 ;;         # 槽范围终点已广播: 就绪
            esac
        done
        exit 1                               # 响应异常, 判未就绪
    ' 2>/dev/null
}

# ---------------------------------------------------------------- 参数解析
CMD=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force) FORCE=1; shift ;;
        --)      shift; CMD=("$@"); break ;;
        -h|--help) usage; exit 0 ;;
        *)       log "未知参数: $1"; usage; exit 2 ;;
    esac
done

# ---------------------------------------------------------------- 主循环
deadline=$(( SECONDS + MESH_TIMEOUT ))
while true; do
    for node in $MESH_NODES; do
        host="${node%:*}"; port="${node#*:}"
        if check_node "$host" "$port"; then
            log "mesh 集群就绪: $node (CLUSTER SLOTS 已广播 0-16383)"
            if (( ${#CMD[@]} > 0 )); then
                log "执行启动命令: ${CMD[*]}"
                exec "${CMD[@]}"
            fi
            exit 0
        fi
    done

    if (( MESH_TIMEOUT == 0 )); then
        log "集群未就绪, 继续等待(无限)..."
    elif (( SECONDS >= deadline )); then
        if (( FORCE )); then
            if (( ${#CMD[@]} > 0 )); then
                log "警告: ${MESH_TIMEOUT}s 内集群未就绪, --force 强制执行启动命令" >&2
                exec "${CMD[@]}"
            fi
            log "警告: ${MESH_TIMEOUT}s 内集群未就绪, --force 但无启动命令, 退出" >&2
            exit 1
        fi
        log "错误: ${MESH_TIMEOUT}s 内集群未就绪, 放弃启动(避免 Redisson Can't connect to servers!)" >&2
        exit 1
    else
        log "集群未就绪, 剩余 $(( deadline - SECONDS ))s 后超时"
    fi
    sleep "$MESH_INTERVAL"
done
