#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(git rev-parse --show-toplevel)
cd "${ROOT_DIR}"

ENABLE_K8S=${ENABLE_K8S:-true}
K8S_CONTAINER_NAME=${K8S_CONTAINER_NAME:-k3s}
K8S_CONTAINER_IMAGE=${K8S_CONTAINER_IMAGE:-rancher/k3s:v1.30.6-k3s1}
KEEP_K8S=${KEEP_K8S:-true}
if [[ -n "${ENABLE_KIND:-}" ]]; then ENABLE_K8S="${ENABLE_KIND}"; fi
if [[ -n "${KEEP_KIND:-}" ]]; then KEEP_K8S="${KEEP_KIND}"; fi
K8S_RELABEL_FOR_CI=${K8S_RELABEL_FOR_CI:-true}

JENKINS_NAME=${JENKINS_NAME:-jenkins-local}
JENKINS_IMAGE=${JENKINS_IMAGE:-jenkins-ci-demo-local}
JENKINS_PORT=${JENKINS_PORT:-8080}
JENKINS_URL="http://localhost:${JENKINS_PORT}"
JENKINS_NETWORK_ALIAS=${JENKINS_NETWORK_ALIAS:-jenkins}
JENKINS_K8S_DNS_NAME=${JENKINS_K8S_DNS_NAME:-${JENKINS_NETWORK_ALIAS}.default.svc.cluster.local}
KEEP_JENKINS=${KEEP_JENKINS:-true}

CI_K8_FILE=${CI_K8_FILE:-.ci/job_matrix_gha_k8.yaml}
TARGET_ARCHES=${TARGET_ARCHES:-${TARGET_ARCH:-}}
SKIP_REGEX=${SKIP_REGEX:-}
AGENT_EXECUTORS=${AGENT_EXECUTORS:-8}

WORK_DIR=${WORK_DIR:-${ROOT_DIR}/.tmp/local-gha-ci}
LOG_DIR="${WORK_DIR}/logs"
CLI_JAR="${WORK_DIR}/jenkins-cli.jar"
JOB_CONFIG_XML="${WORK_DIR}/job-config.xml"

BRANCH=${BRANCH:-$(git rev-parse --abbrev-ref HEAD)}
REPO_MOUNT=${REPO_MOUNT:-/workspace/ci-demo}
REPO_URL=${REPO_URL:-}
DOCKER_NETWORK=${DOCKER_NETWORK:-ci-demo-net}
HOST_REPO_DIR=${HOST_REPO_DIR:-${ROOT_DIR}}
K8S_API_URL=${K8S_API_URL:-https://${K8S_CONTAINER_NAME}:6443}
K8S_TOKEN=""
USE_COLIMA=${USE_COLIMA:-false}
COLIMA_PROFILE=${COLIMA_PROFILE:-default}
COLIMA_START=${COLIMA_START:-true}
COLIMA_ARCH=${COLIMA_ARCH:-}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: Missing required command: $1" >&2
    exit 1
  }
}

cleanup() {
  if [[ "${KEEP_JENKINS}" != "true" ]]; then
    docker rm -f "${JENKINS_NAME}" >/dev/null 2>&1 || true
  fi

  if [[ "${ENABLE_K8S}" == "true" && "${KEEP_K8S}" != "true" ]]; then
    docker rm -f "${K8S_CONTAINER_NAME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_cmd git
require_cmd docker
require_cmd curl

branch_name="${BRANCH#refs/heads/}"
if [[ -z "${branch_name}" || "${branch_name}" == "HEAD" ]]; then
  branch_name="$(git symbolic-ref --short -q HEAD 2>/dev/null || true)"
fi
if [[ -z "${branch_name}" ]]; then
  branch_name="$(git rev-parse --abbrev-ref HEAD)"
fi
if [[ -z "${branch_name}" || "${branch_name}" == "HEAD" ]]; then
  echo "ERROR: Unable to determine branch name for Jenkins job configuration" >&2
  exit 1
fi

if [[ -z "${REPO_URL}" ]]; then
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    REPO_URL="$(git config --get remote.origin.url 2>/dev/null || true)"
  fi
  REPO_URL="${REPO_URL:-file://${REPO_MOUNT}}"
fi

# In GHA checkout can be detached and miss refs/heads/<branch>.
# Ensure local branch ref exists so Jenkins GitSCM can resolve it from file:// repo.
if ! git show-ref --verify --quiet "refs/heads/${branch_name}"; then
  head_commit=$(git rev-parse HEAD)
  git update-ref "refs/heads/${branch_name}" "${head_commit}"
  echo "Created local git ref refs/heads/${branch_name} -> ${head_commit}"
fi

host_arch=$(uname -m 2>/dev/null || echo unknown)
case "${host_arch}" in
  x86_64|amd64) detected_ci_arch="x86_64" ;;
  arm64|aarch64) detected_ci_arch="aarch64" ;;
  *)
    echo "WARN: Unsupported host arch '${host_arch}', defaulting to x86_64"
    detected_ci_arch="x86_64"
    ;;
esac

if [[ -z "${TARGET_ARCHES}" ]]; then
  TARGET_ARCHES="${detected_ci_arch}"
fi

if [[ "${USE_COLIMA}" == "true" ]]; then
  require_cmd colima
  colima_arch="${COLIMA_ARCH}"
  if [[ -z "${colima_arch}" ]]; then
    case "${host_arch}" in
      x86_64|amd64) colima_arch="x86_64" ;;
      arm64|aarch64) colima_arch="aarch64" ;;
      *) colima_arch="aarch64" ;;
    esac
  fi
  if [[ "${COLIMA_START}" == "true" ]]; then
    echo "Starting Colima profile '${COLIMA_PROFILE}' (arch=${colima_arch})..."
    colima start --profile "${COLIMA_PROFILE}" --arch "${colima_arch}"
  fi
  if [[ "${COLIMA_PROFILE}" == "default" ]]; then
    export DOCKER_CONTEXT=colima
  else
    export DOCKER_CONTEXT=colima-${COLIMA_PROFILE}
  fi
  unset DOCKER_HOST || true
  echo "Using Docker context: ${DOCKER_CONTEXT}"
fi

mkdir -p "${LOG_DIR}"

echo "[1/8] Using static workflow config ${CI_K8_FILE}"
if [[ ! -f "${CI_K8_FILE}" ]]; then
  echo "ERROR: Config file not found: ${CI_K8_FILE}" >&2
  exit 1
fi
conf_files=("${CI_K8_FILE}")

target_arch_list=()
while IFS= read -r arch; do
  [[ -n "${arch}" ]] && target_arch_list+=("${arch}")
done < <(echo "${TARGET_ARCHES}" | tr ', ' '\n' | awk 'NF' | sort -u)
if [[ "${#target_arch_list[@]}" -eq 0 ]]; then
  target_arch_list=("x86_64")
fi
primary_target_arch="${target_arch_list[0]}"
case "${primary_target_arch}" in
  x86_64|amd64) k8_target_arch="amd64" ;;
  aarch64|arm64) k8_target_arch="arm64" ;;
  *)
    echo "WARN: Unsupported TARGET_ARCH '${primary_target_arch}', defaulting k8 node relabel to amd64"
    k8_target_arch="amd64"
    ;;
esac

echo "Target matrix arch(es): ${target_arch_list[*]}"
clouds_csv=""
labels_csv=""

echo "[2/8] Building Jenkins image ${JENKINS_IMAGE}"
docker build -t "${JENKINS_IMAGE}" -f .github/Dockerfile.jenkins .

if ! docker network inspect "${DOCKER_NETWORK}" >/dev/null 2>&1; then
  docker network create "${DOCKER_NETWORK}" >/dev/null
fi

if [[ "${ENABLE_K8S}" == "true" ]]; then
  echo "[3/8] Starting Kubernetes container ${K8S_CONTAINER_NAME}"
  docker rm -f "${K8S_CONTAINER_NAME}" >/dev/null 2>&1 || true
  docker run -d --name "${K8S_CONTAINER_NAME}" \
    --hostname "${K8S_CONTAINER_NAME}" \
    --network "${DOCKER_NETWORK}" \
    --privileged \
    -e K3S_KUBECONFIG_MODE=644 \
    "${K8S_CONTAINER_IMAGE}" \
    server --disable=traefik >/dev/null

  ready=false
  node_name=""
  for i in $(seq 1 60); do
    node_name=$(docker exec "${K8S_CONTAINER_NAME}" kubectl get nodes -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
    if [[ -n "${node_name}" ]]; then
      ready=true
      break
    fi
    sleep 2
  done
  if [[ "${ready}" != "true" ]]; then
    echo "ERROR: Kubernetes container did not become ready" >&2
    docker logs "${K8S_CONTAINER_NAME}" | tail -120 >&2 || true
    exit 1
  fi

  if [[ "${K8S_RELABEL_FOR_CI}" == "true" ]]; then
    docker exec "${K8S_CONTAINER_NAME}" kubectl label node "${node_name}" kubernetes.io/arch="${k8_target_arch}" --overwrite >/dev/null 2>&1 || true
    docker exec "${K8S_CONTAINER_NAME}" kubectl label node "${node_name}" beta.kubernetes.io/arch="${k8_target_arch}" --overwrite >/dev/null 2>&1 || true
    docker exec "${K8S_CONTAINER_NAME}" kubectl label node "${node_name}" kubernetes.io/os=linux --overwrite >/dev/null 2>&1 || true
    docker exec "${K8S_CONTAINER_NAME}" kubectl label node "${node_name}" beta.kubernetes.io/os=linux --overwrite >/dev/null 2>&1 || true
    echo "Relabeled k3s node '${node_name}' for CI selectors (arch=${k8_target_arch})"
  fi

  docker exec "${K8S_CONTAINER_NAME}" kubectl create serviceaccount jenkins -n default >/dev/null 2>&1 || true
  docker exec "${K8S_CONTAINER_NAME}" kubectl create clusterrolebinding jenkins-admin-binding \
    --clusterrole=cluster-admin --serviceaccount=default:jenkins >/dev/null 2>&1 || true
  K8S_TOKEN=$(docker exec "${K8S_CONTAINER_NAME}" kubectl -n default create token jenkins --duration=24h)
fi

clouds_csv=$(
  grep -hE "cloud:[[:space:]]*['\"]?[^'\" ,}]+" "${conf_files[@]}" 2>/dev/null \
    | sed -E "s/.*cloud:[[:space:]]*['\"]?([^'\" ,}]+).*/\\1/" \
    | sort -u \
    | paste -sd, - \
    || true
)

labels_csv=$(
  grep -hE "nodeLabel:[[:space:]]*" "${conf_files[@]}" 2>/dev/null \
    | sed -E "s/.*nodeLabel:[[:space:]]*['\"]?([^'\",}#]+).*/\\1/" \
    | tr -cs 'A-Za-z0-9_.-' '\n' \
    | awk 'NF && $0 !~ /^(and|or|not|true|false)$/ {print}' \
    | sort -u \
    | paste -sd, - \
    || true
)

arch_labels=()
for ci_arch in "${target_arch_list[@]}"; do
  arch_labels+=("${ci_arch}")
  case "${ci_arch}" in
    x86_64) arch_labels+=("amd64") ;;
    aarch64) arch_labels+=("arm64") ;;
  esac
done
arch_labels_csv=$(printf '%s\n' "${arch_labels[@]}" | awk 'NF' | sort -u | paste -sd, -)
if [[ -n "${labels_csv}" && -n "${arch_labels_csv}" ]]; then
  labels_csv="${labels_csv},${arch_labels_csv}"
elif [[ -n "${arch_labels_csv}" ]]; then
  labels_csv="${arch_labels_csv}"
fi

echo "[4/8] Starting Jenkins container ${JENKINS_NAME}"
docker rm -f "${JENKINS_NAME}" >/dev/null 2>&1 || true

docker run -d --name "${JENKINS_NAME}" \
  --network "${DOCKER_NETWORK}" \
  --network-alias "${JENKINS_NETWORK_ALIAS}" \
  -u root \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${ROOT_DIR}/.github/scripts/jenkins-disable-security.groovy:/usr/share/jenkins/ref/init.groovy.d/disable-security.groovy:ro" \
  -v "${ROOT_DIR}/.github/scripts/jenkins-add-master-label.groovy:/usr/share/jenkins/ref/init.groovy.d/add-master-label.groovy:ro" \
  -v "${ROOT_DIR}/.github/scripts/jenkins-configure-k8s-and-labels.groovy:/usr/share/jenkins/ref/init.groovy.d/configure-k8s-and-labels.groovy:ro" \
  -v "${HOST_REPO_DIR}:${REPO_MOUNT}" \
  -p "${JENKINS_PORT}:8080" \
  -e CI_REPO_DIR="${REPO_MOUNT}" \
  -e JENKINS_AGENT_LABELS="${labels_csv}" \
  -e JENKINS_AGENT_EXECUTORS="${AGENT_EXECUTORS}" \
  -e JENKINS_K8S_CLOUDS="${clouds_csv}" \
  -e JENKINS_K8S_API_URL="${K8S_API_URL}" \
  -e JENKINS_K8S_TOKEN="${K8S_TOKEN}" \
  -e JENKINS_K8S_JENKINS_URL="http://${JENKINS_K8S_DNS_NAME}:8080" \
  -e JENKINS_K8S_JENKINS_TUNNEL="${JENKINS_K8S_DNS_NAME}:50000" \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.security.csrf.GlobalCrumbIssuerConfiguration.DISABLE_CSRF_PROTECTION=true -Dpermissive-script-security.enabled=no_security -Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true" \
  "${JENKINS_IMAGE}" \
  /usr/local/bin/jenkins.sh >/dev/null

echo "[5/8] Waiting for Jenkins at ${JENKINS_URL}"
for i in $(seq 1 90); do
  if [[ "$(docker inspect -f '{{.State.Running}}' "${JENKINS_NAME}" 2>/dev/null || echo false)" != "true" ]]; then
    echo "ERROR: Jenkins container '${JENKINS_NAME}' stopped before becoming ready" >&2
    docker logs "${JENKINS_NAME}" | tail -120 >&2 || true
    exit 1
  fi
  if curl -sf "${JENKINS_URL}/login" >/dev/null; then
    break
  fi
  sleep 5
  if [[ "${i}" == "90" ]]; then
    echo "ERROR: Jenkins did not become ready" >&2
    docker logs "${JENKINS_NAME}" | tail -120 >&2 || true
    exit 1
  fi
done

curl -sf -o "${CLI_JAR}" "${JENKINS_URL}/jnlpJars/jenkins-cli.jar"
CLI_JAR_IN_CONTAINER="${REPO_MOUNT}/.tmp/local-gha-ci/jenkins-cli.jar"
jenkins_cli() {
  docker exec "${JENKINS_NAME}" java -jar "${CLI_JAR_IN_CONTAINER}" -s http://localhost:8080 "$@"
}
jenkins_script() {
  curl -sf -X POST --data-urlencode "script=$1" "${JENKINS_URL}/scriptText"
}
save_jenkins_artifacts() {
  local prefix="$1"
  local build_number
  build_number=$(docker exec "${JENKINS_NAME}" sh -lc "curl -sf http://localhost:8080/job/ci-demo/lastBuild/api/json" 2>/dev/null \
    | sed -n 's/.*"number":[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -n1 || true)

  if [[ -n "${build_number}" ]]; then
    docker exec "${JENKINS_NAME}" sh -lc "curl -sf http://localhost:8080/job/ci-demo/${build_number}/consoleText" > "${LOG_DIR}/${prefix}.jenkins-console.log" 2>/dev/null || true
    docker exec "${JENKINS_NAME}" sh -lc "curl -sf http://localhost:8080/job/ci-demo/${build_number}/api/json" > "${LOG_DIR}/${prefix}.jenkins-build.json" 2>/dev/null || true
  else
    docker exec "${JENKINS_NAME}" sh -lc "curl -sf http://localhost:8080/job/ci-demo/lastBuild/consoleText" > "${LOG_DIR}/${prefix}.jenkins-console.log" 2>/dev/null || true
  fi

  docker logs "${JENKINS_NAME}" > "${LOG_DIR}/${prefix}.jenkins-container.log" 2>&1 || true
}

echo "Waiting for Jenkins CLI readiness"
cli_ready=false
for i in $(seq 1 90); do
  if [[ "$(docker inspect -f '{{.State.Running}}' "${JENKINS_NAME}" 2>/dev/null || echo false)" != "true" ]]; then
    echo "ERROR: Jenkins container '${JENKINS_NAME}' stopped before CLI became ready" >&2
    docker logs "${JENKINS_NAME}" | tail -120 >&2 || true
    exit 1
  fi
  if jenkins_cli version >/dev/null 2>&1; then
    cli_ready=true
    break
  fi
  sleep 2
done
if [[ "${cli_ready}" != "true" ]]; then
  echo "ERROR: Jenkins CLI did not become ready" >&2
  docker logs "${JENKINS_NAME}" | tail -120 >&2 || true
  jenkins_cli version || true
  exit 1
fi

echo "Waiting for Jenkins full initialization"
jenkins_fully_up=false
for i in $(seq 1 90); do
  if docker logs "${JENKINS_NAME}" 2>&1 | grep -q "Jenkins is fully up and running"; then
    jenkins_fully_up=true
    break
  fi
  sleep 2
done
if [[ "${jenkins_fully_up}" != "true" ]]; then
  echo "ERROR: Jenkins did not reach full initialization state" >&2
  docker logs "${JENKINS_NAME}" | tail -200 >&2 || true
  exit 1
fi

docker exec "${JENKINS_NAME}" git config --global --add safe.directory "${REPO_MOUNT}" >/dev/null 2>&1 || true
docker exec "${JENKINS_NAME}" git config --global --add safe.directory "${REPO_MOUNT}/.git" >/dev/null 2>&1 || true

echo "[6/8] Creating/updating Jenkins job ci-demo"
sed -e "s|REPO_URL_PLACEHOLDER|${REPO_URL}|g" \
    -e "s|BRANCH_PLACEHOLDER|${branch_name}|g" \
    -e "s|TARGET_ARCH_PLACEHOLDER|${detected_ci_arch}|g" \
    .github/jenkins-job-config.xml > "${JOB_CONFIG_XML}"

# Jenkins GitSCMFileSystem (lightweight checkout) can fail for file:// repos in
# detached/shallow GHA checkouts. Force full SCM checkout in generated job config.
sed -i.bak "s|<lightweight>true</lightweight>|<lightweight>false</lightweight>|g" "${JOB_CONFIG_XML}"
rm -f "${JOB_CONFIG_XML}.bak"

# Keep repository XML unchanged, but ensure runtime job config supports TARGET_ARCH.
# Matrix include uses ${TARGET_ARCH}, so the parameter must exist on the Jenkins job.
if ! grep -q "<name>TARGET_ARCH</name>" "${JOB_CONFIG_XML}"; then
  awk '
    /<\/parameterDefinitions>/ {
      print "        <hudson.model.StringParameterDefinition>"
      print "          <name>TARGET_ARCH</name>"
      print "          <description>Target matrix arch for CI matrix include filter (e.g. x86_64 or aarch64)</description>"
      print "          <defaultValue>x86_64</defaultValue>"
      print "          <trim>false</trim>"
      print "        </hudson.model.StringParameterDefinition>"
    }
    { print }
  ' "${JOB_CONFIG_XML}" > "${JOB_CONFIG_XML}.tmp"
  mv "${JOB_CONFIG_XML}.tmp" "${JOB_CONFIG_XML}"
fi

docker exec -i "${JENKINS_NAME}" java -jar "${CLI_JAR_IN_CONTAINER}" -s http://localhost:8080 create-job ci-demo < "${JOB_CONFIG_XML}" >/dev/null 2>&1 || true
docker exec -i "${JENKINS_NAME}" java -jar "${CLI_JAR_IN_CONTAINER}" -s http://localhost:8080 update-job ci-demo < "${JOB_CONFIG_XML}" >/dev/null

script='import jenkins.model.Jenkins; def sa = Jenkins.instance.getExtensionList("org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval")[0].get(); sa.preapproveAll(); sa.save(); return "OK";'
curl -sf -X POST --data-urlencode "script=${script}" "${JENKINS_URL}/scriptText" >/dev/null

echo "[7/8] Verifying Jenkins cloud/label startup configuration"

echo "Configured clouds: ${clouds_csv:-<none>}"
echo "Configured labels: ${labels_csv:-<none>}"
echo "Configured Jenkins alias: ${JENKINS_NETWORK_ALIAS}"
echo "Configured Jenkins in-cluster DNS: ${JENKINS_K8S_DNS_NAME}"

if [[ -n "${clouds_csv}" ]]; then
  echo "Verifying required Jenkins clouds: ${clouds_csv}"
  clouds_ready=false
  for i in $(seq 1 30); do
    missing=""
    for c in ${clouds_csv//,/ }; do
      have=$(jenkins_script "import jenkins.model.Jenkins; return Jenkins.instance.clouds.getByName('${c}') != null ? 'yes' : 'no'" 2>/dev/null \
        | sed -n 's/^Result: //p' | tr -d '\r\n')
      if [[ "${have}" != "yes" ]]; then
        missing="${missing}${c} "
      fi
    done
    if [[ -z "${missing}" ]]; then
      clouds_ready=true
      break
    fi
    echo "Cloud(s) not ready yet: ${missing}"
    sleep 2
  done
  if [[ "${clouds_ready}" != "true" ]]; then
    echo "ERROR: Jenkins clouds did not become ready: ${clouds_csv}" >&2
    jenkins_script "import jenkins.model.Jenkins; return Jenkins.instance.clouds.collect{ it.name }.join(',')" >&2 || true
    docker logs "${JENKINS_NAME}" | tail -120 >&2 || true
    exit 1
  fi
fi

if [[ "${ENABLE_K8S}" == "true" ]]; then
  jenkins_container_ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${JENKINS_NAME}" 2>/dev/null || true)
  if [[ -z "${jenkins_container_ip}" ]]; then
    echo "ERROR: Could not resolve Jenkins container IP for Kubernetes service wiring" >&2
    docker inspect "${JENKINS_NAME}" >&2 || true
    exit 1
  fi

  docker exec -i "${K8S_CONTAINER_NAME}" kubectl -n default apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${JENKINS_NETWORK_ALIAS}
spec:
  ports:
  - name: http
    port: 8080
    targetPort: 8080
  - name: agent
    port: 50000
    targetPort: 50000
EOF

  docker exec -i "${K8S_CONTAINER_NAME}" kubectl -n default apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Endpoints
metadata:
  name: ${JENKINS_NETWORK_ALIAS}
subsets:
- addresses:
  - ip: ${jenkins_container_ip}
  ports:
  - name: http
    port: 8080
  - name: agent
    port: 50000
EOF
  echo "Configured in-cluster Service '${JENKINS_NETWORK_ALIAS}' -> ${jenkins_container_ip}"

  actual_arch=$(docker exec "${K8S_CONTAINER_NAME}" kubectl get node "${node_name}" -o jsonpath='{.metadata.labels.kubernetes\.io/arch}' 2>/dev/null || true)
  if [[ -z "${actual_arch}" ]]; then
    echo "ERROR: Could not read kubernetes.io/arch label from node '${node_name}'" >&2
    docker exec "${K8S_CONTAINER_NAME}" kubectl get nodes --show-labels >&2 || true
    exit 1
  fi
  if [[ "${actual_arch}" != "${k8_target_arch}" ]]; then
    echo "ERROR: k8 node arch label mismatch: expected '${k8_target_arch}', got '${actual_arch}'" >&2
    docker exec "${K8S_CONTAINER_NAME}" kubectl get nodes --show-labels >&2 || true
    exit 1
  fi
  echo "Verified k8 node arch label: ${actual_arch}"

  if ! docker exec "${K8S_CONTAINER_NAME}" sh -lc "wget -q -O /dev/null 'http://${JENKINS_NETWORK_ALIAS}:8080/login'"; then
    echo "ERROR: k3s container cannot reach Jenkins at http://${JENKINS_NETWORK_ALIAS}:8080/login" >&2
    exit 1
  fi
  echo "Verified k3s container can reach Jenkins alias '${JENKINS_NETWORK_ALIAS}'"

  # Wait for cluster DNS readiness to avoid transient early lookup failures.
  dns_ready=false
  for i in $(seq 1 30); do
    coredns_ready=$(docker exec "${K8S_CONTAINER_NAME}" kubectl -n kube-system get pods -l k8s-app=kube-dns \
      -o jsonpath='{range .items[*]}{.status.containerStatuses[0].ready}{"\n"}{end}' 2>/dev/null || true)
    if echo "${coredns_ready}" | grep -q "^true$"; then
      dns_ready=true
      break
    fi
    sleep 2
  done
  if [[ "${dns_ready}" != "true" ]]; then
    echo "WARN: CoreDNS did not report ready before netcheck; proceeding with checks"
  fi

  docker exec "${K8S_CONTAINER_NAME}" kubectl -n default delete pod jenkins-netcheck --ignore-not-found >/dev/null 2>&1 || true
  docker exec "${K8S_CONTAINER_NAME}" kubectl -n default run jenkins-netcheck \
    --image=curlimages/curl:8.12.1 --restart=Never --command -- \
    sh -c "curl -fsS 'http://${JENKINS_K8S_DNS_NAME}:8080/login' >/dev/null || curl -fsS 'http://${JENKINS_NETWORK_ALIAS}:8080/login' >/dev/null" >/dev/null

  pod_ok=false
  for i in $(seq 1 90); do
    phase=$(docker exec "${K8S_CONTAINER_NAME}" kubectl -n default get pod jenkins-netcheck -o jsonpath='{.status.phase}' 2>/dev/null || true)
    if [[ "${phase}" == "Succeeded" ]]; then
      pod_ok=true
      break
    fi
    if [[ "${phase}" == "Failed" ]]; then
      echo "ERROR: In-cluster Jenkins reachability check pod failed" >&2
      docker exec "${K8S_CONTAINER_NAME}" kubectl -n default logs jenkins-netcheck >&2 || true
      docker exec "${K8S_CONTAINER_NAME}" kubectl -n default describe pod jenkins-netcheck >&2 || true
      break
    fi
    sleep 2
  done
  if [[ "${pod_ok}" != "true" ]]; then
    echo "ERROR: In-cluster Jenkins reachability preflight did not succeed" >&2
    docker exec "${K8S_CONTAINER_NAME}" kubectl -n default get pod jenkins-netcheck -o wide >&2 || true
    docker exec "${K8S_CONTAINER_NAME}" kubectl -n default describe pod jenkins-netcheck >&2 || true
    exit 1
  fi
  docker exec "${K8S_CONTAINER_NAME}" kubectl -n default delete pod jenkins-netcheck --ignore-not-found >/dev/null 2>&1 || true
  echo "Verified in-cluster pod can reach Jenkins alias '${JENKINS_NETWORK_ALIAS}'"
fi

fail_count=0
for conf in "${conf_files[@]}"; do
  conf_rel="${conf}"
  if [[ "${conf_rel}" == ./* ]]; then
    conf_rel="${REPO_MOUNT}/${conf_rel#./}"
  elif [[ "${conf_rel}" != /* ]]; then
    conf_rel="${REPO_MOUNT}/${conf_rel}"
  fi
  conf_base=$(basename "${conf_rel}")

  if [[ -n "${SKIP_REGEX}" && "${conf_rel}" =~ ${SKIP_REGEX} ]]; then
    echo "SKIP ${conf_rel} (matched SKIP_REGEX)"
    continue
  fi

  for target_arch in "${target_arch_list[@]}"; do
    echo "[8/8] Running ${conf_rel} with TARGET_ARCH=${target_arch}"
    output_prefix="${conf_base}.${target_arch}"
    job_output_log="${LOG_DIR}/${output_prefix}.job-output.log"
    set +e
    jenkins_cli build ci-demo -p "conf_file=${conf_rel}" -p "TARGET_ARCH=${target_arch}" -s -v \
      | tee "${job_output_log}" | tee "${LOG_DIR}/${conf_base}.${target_arch}.log" >/dev/null
    rc=${PIPESTATUS[0]}
    set -e
    save_jenkins_artifacts "${output_prefix}"

    if [[ "${rc}" -ne 0 ]]; then
      echo "FAIL ${conf_rel} TARGET_ARCH=${target_arch}"
      if [[ -f "${LOG_DIR}/${output_prefix}.jenkins-build.json" ]]; then
        build_result=$(sed -n 's/.*"result":"\([^"]*\)".*/\1/p' "${LOG_DIR}/${output_prefix}.jenkins-build.json" | head -n1 || true)
        [[ -n "${build_result}" ]] && echo "Jenkins build result: ${build_result}"
      fi
      echo "---- tail: ${job_output_log} ----"
      tail -n 120 "${job_output_log}" || true
      if [[ -f "${LOG_DIR}/${output_prefix}.jenkins-console.log" ]]; then
        echo "---- tail: ${LOG_DIR}/${output_prefix}.jenkins-console.log ----"
        tail -n 120 "${LOG_DIR}/${output_prefix}.jenkins-console.log" || true
      fi
      fail_count=$((fail_count + 1))
    else
      echo "PASS ${conf_rel} TARGET_ARCH=${target_arch}"
    fi
  done
done

echo "Logs: ${LOG_DIR}"
if [[ "${fail_count}" -ne 0 ]]; then
  echo "Completed with ${fail_count} failed configs" >&2
  exit 1
fi

echo "Completed successfully"
