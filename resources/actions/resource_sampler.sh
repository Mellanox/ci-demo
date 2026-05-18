#!/usr/bin/env bash
# Continuous cgroup resource sampler for ci-demo pipeline pods.
#
# Usage (inside a step's `run:` block):
#   bash ${WORKSPACE}/.ci/resource_sampler.sh start "${name}"
#   bash ${WORKSPACE}/.ci/resource_sampler.sh report "${name}"
#
# `start`  spawns a detached background loop that appends one CSV line every
#          SAMPLE_INTERVAL seconds (default: 2) to
#          ${WORKSPACE}/resource_samples_<container>.csv until killed.
# `report` stops the sampler, then summarises peak/p95/avg memory and CPU into
#          ${WORKSPACE}/resource_summary_<container>.txt and stdout.
#
# Auto-detects cgroup v1 vs v2 (Blossom nodes are still on v1 as of writing).
# Survives the parent shell exiting: stdio is redirected, SIGHUP is ignored,
# the process is disowned. Idempotent — calling `start` again while a sampler
# is already running is a no-op.

set -u

MODE="${1:-start}"
CONTAINER="${2:-${name:-unknown}}"
INTERVAL="${SAMPLE_INTERVAL:-2}"
WS="${WORKSPACE:-/tmp}"
OUT="${WS}/resource_samples_${CONTAINER}.csv"
SUMMARY="${WS}/resource_summary_${CONTAINER}.txt"
PIDFILE="/tmp/resource_sampler_${CONTAINER}.pid"

# Detect cgroup mode once (v2 has /sys/fs/cgroup/cgroup.controllers; v1 doesn't).
if [ -f /sys/fs/cgroup/cgroup.controllers ]; then
    CGROUP_MODE=v2
elif [ -d /sys/fs/cgroup/memory ] && [ -d /sys/fs/cgroup/cpu,cpuacct ]; then
    CGROUP_MODE=v1
else
    CGROUP_MODE=unknown
fi

sample_once() {
    local ts cpu_usec mem_curr mem_peak mem_max cpu_max top_line top_rss top_pcpu top_comm
    ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    case "$CGROUP_MODE" in
        v2)
            cpu_usec=$(awk '/^usage_usec/ {print $2; exit}' /sys/fs/cgroup/cpu.stat 2>/dev/null || echo 0)
            mem_curr=$(cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0)
            mem_peak=$(cat /sys/fs/cgroup/memory.peak 2>/dev/null || echo 0)
            mem_max=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)
            cpu_max=$(tr ' ' '/' < /sys/fs/cgroup/cpu.max 2>/dev/null || echo max)
            ;;
        v1)
            # v1 cpuacct.usage is in nanoseconds; convert to microseconds for schema parity.
            local cpu_ns
            cpu_ns=$(cat /sys/fs/cgroup/cpu,cpuacct/cpuacct.usage 2>/dev/null || echo 0)
            cpu_usec=$(( cpu_ns / 1000 ))
            mem_curr=$(cat /sys/fs/cgroup/memory/memory.usage_in_bytes 2>/dev/null || echo 0)
            mem_peak=$(cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null || echo 0)
            mem_max=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo max)
            local quota period
            quota=$(cat /sys/fs/cgroup/cpu,cpuacct/cpu.cfs_quota_us 2>/dev/null || echo -1)
            period=$(cat /sys/fs/cgroup/cpu,cpuacct/cpu.cfs_period_us 2>/dev/null || echo 100000)
            cpu_max="${quota}/${period}"
            ;;
        *)
            cpu_usec=0; mem_curr=0; mem_peak=0; mem_max=max; cpu_max=max
            ;;
    esac
    top_line=$(ps -eo rss,pcpu,comm --sort=-rss --no-headers 2>/dev/null | head -1 || true)
    top_rss=$(printf '%s' "${top_line:-}" | awk '{print $1+0}')
    top_pcpu=$(printf '%s' "${top_line:-}" | awk '{print $2+0}')
    top_comm=$(printf '%s' "${top_line:-}" | awk '{print $3}')
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$ts" "$CONTAINER" "$cpu_usec" "$mem_curr" "$mem_peak" \
        "$mem_max" "$cpu_max" "${top_rss:-0}" "${top_pcpu:-0}" "${top_comm:-NA}" \
        >> "$OUT"
}

start_daemon() {
    if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
        echo "[sampler] already running pid=$(cat "$PIDFILE")"
        return 0
    fi
    if [ ! -f "$OUT" ]; then
        printf 'timestamp,container,cpu_usage_usec,mem_current,mem_peak,mem_max,cpu_max,top_rss_kb,top_pcpu,top_comm\n' > "$OUT"
    fi
    (
        trap '' HUP
        exec </dev/null >/dev/null 2>&1
        while :; do
            sample_once
            sleep "$INTERVAL"
        done
    ) &
    local pid=$!
    echo "$pid" > "$PIDFILE"
    disown 2>/dev/null || true
    echo "[sampler] started container=$CONTAINER pid=$pid interval=${INTERVAL}s out=$OUT"
}

stop_daemon() {
    if [ -f "$PIDFILE" ]; then
        local pid
        pid=$(cat "$PIDFILE" 2>/dev/null || true)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null || true
            sleep 1
        fi
        rm -f "$PIDFILE"
    fi
}

summarise() {
    if [ ! -s "$OUT" ]; then
        echo "[sampler] no samples for $CONTAINER" | tee "$SUMMARY"
        return 0
    fi
    python3 - "$OUT" "$CONTAINER" "$INTERVAL" > "$SUMMARY" <<'PY'
import csv, sys

path, container, interval = sys.argv[1], sys.argv[2], float(sys.argv[3])

rows = []
with open(path) as f:
    for r in csv.DictReader(f):
        rows.append(r)

def to_int(s, default=0):
    try:
        return int(s)
    except Exception:
        return default

def fmt_b(n):
    if n >= 1024 ** 3:
        return f"{n / 1024 ** 3:.2f}Gi"
    return f"{n / 1024 ** 2:.1f}Mi"

def pct(xs, p):
    if not xs:
        return 0
    xs = sorted(xs)
    idx = max(0, min(len(xs) - 1, int(round(len(xs) * p)) - 1))
    return xs[idx]

mem_curr = [to_int(r["mem_current"]) for r in rows if to_int(r["mem_current"]) > 0]
mem_peak = [to_int(r["mem_peak"]) for r in rows if to_int(r["mem_peak"]) > 0]
cpu_usec = [to_int(r["cpu_usage_usec"]) for r in rows]

print(f"=== container={container} samples={len(rows)} ===")
if rows:
    print(f"span: {rows[0]['timestamp']} -> {rows[-1]['timestamp']}")
    print(f"cpu_max (cgroup): {rows[-1]['cpu_max']}    mem_max (cgroup): {rows[-1]['mem_max']}")

if mem_curr:
    print(f"memory.current  max={fmt_b(max(mem_curr))}  p95={fmt_b(pct(mem_curr, 0.95))}  "
          f"p50={fmt_b(pct(mem_curr, 0.50))}  avg={fmt_b(sum(mem_curr) // len(mem_curr))}")
if mem_peak:
    print(f"memory.peak (cgroup high-water): {fmt_b(max(mem_peak))}")

cpu_usec = [c for c in cpu_usec if c > 0]
if len(cpu_usec) >= 2:
    total_cpu_sec = (cpu_usec[-1] - cpu_usec[0]) / 1_000_000
    span_s = (len(cpu_usec) - 1) * interval
    avg_cores = total_cpu_sec / span_s if span_s else 0
    deltas = [(cpu_usec[i + 1] - cpu_usec[i]) / 1_000_000 / interval
              for i in range(len(cpu_usec) - 1)]
    peak_cores = max(deltas) if deltas else 0
    p95_cores = pct(deltas, 0.95) if deltas else 0
    print(f"cpu  avg={avg_cores:.2f} cores  p95={p95_cores:.2f} cores  "
          f"peak={peak_cores:.2f} cores  total={total_cpu_sec:.1f} CPU-sec / {span_s:.0f}s wall")

# Suggested requests/limits from observed data.
if mem_curr:
    req_mem = max(512 * 1024 * 1024, int(pct(mem_curr, 0.95) * 1.30))
    lim_mem = max(req_mem, int((max(mem_peak) if mem_peak else max(mem_curr)) * 1.50))
    print(f"suggested  requests.memory={fmt_b(req_mem)}  limits.memory={fmt_b(lim_mem)}")
if len(cpu_usec) >= 2:
    p95 = pct(deltas, 0.95) if deltas else 0
    peak = max(deltas) if deltas else 0
    req_cpu = max(0.5, p95 * 1.30)
    lim_cpu = max(req_cpu, peak * 1.50)
    print(f"suggested  requests.cpu={req_cpu:.2f}  limits.cpu={lim_cpu:.2f}")
PY
    cat "$SUMMARY"
}

case "$MODE" in
    start)  start_daemon ;;
    stop)   stop_daemon ;;
    report) stop_daemon; summarise ;;
    *)
        echo "usage: $0 {start|stop|report} <container_name>" >&2
        exit 2
        ;;
esac
