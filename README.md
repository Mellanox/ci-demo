# ci-demo

`ci-demo` is a Jenkins pipeline framework driven by YAML job-matrix files.

It lets you define CI flows with matrix axes, container/agent selection, and optional Kubernetes execution, while keeping local runs and GitHub Actions runs on the same path.

## Goal and Role

Goal:

- Enable YAML-based configuration of Jenkins jobs.
- Keep CI behavior declarative through job-matrix YAML files instead of hardcoded pipeline logic.
- Make CI definitions portable across YAML-driven ecosystems such as GitHub Actions, GitLab CI, and similar systems.
- Provide a single, reproducible CI execution model for local development, Jenkins, and GitHub Actions.

Role in a project:

- `ci-demo` is the CI orchestration layer.
- Your matrix YAML defines what to run; `Matrix.groovy` and Jenkins execute it across containers/agents/k8s.
- `scripts/local_gha_ci.sh` is the bridge that makes local and GHA execute the same flow.

## What It Runs

Core runtime files:

- `src/com/mellanox/cicd/Matrix.groovy`: matrix engine and pipeline behavior
- `.ci/Jenkinsfile`: loads matrix YAML and executes it via `Matrix.groovy`
- `scripts/local_gha_ci.sh`: local/GHA runner that bootstraps Jenkins + k3s and triggers matrix job
- `.github/workflows/ci.yml`: CI workflow that calls `scripts/local_gha_ci.sh`

Common matrix files:

- `.ci/job_matrix_gha_k8.yaml`: static matrix used by local/GHA k8 flow
- `.ci/job_matrix_gha.yaml`: GHA-friendly docker-only example
- `.ci/job_matrix_debug.yaml`: broader feature/example matrix

Documentation:

- [`USERGUIDE.md`](USERGUIDE.md): schema-driven key reference and YAML examples

## Quick Start

Prerequisites:

- `docker`
- optionally `colima` (for native profile flows on macOS)

Run the same flow used by GitHub Actions:

```bash
make -C .ci local-gha-ci
```

Use Colima native profile:

```bash
make -C .ci local-gha-ci-colima
```

Clean local CI artifacts/containers:

```bash
make -C .ci local-gha-clean
```

Logs are written to:

- `.tmp/local-gha-ci/logs`

## CI and GHA Flow

GitHub Actions (`.github/workflows/ci.yml`) does not implement a separate CI path.
It calls:

```bash
bash ./scripts/local_gha_ci.sh
```

So local and GHA behavior is intentionally aligned.

High-level flow in `scripts/local_gha_ci.sh`:

1. Build Jenkins image (`.github/Dockerfile.jenkins`)
2. Start local k3s container
3. Start Jenkins container and configure cloud/labels
4. Validate matrix YAML schema
5. Create/update Jenkins job
6. Trigger Jenkins build with matrix config
7. Save logs/artifacts in `.tmp/local-gha-ci/logs`

## Pipeline View

![Matrix Pipeline View](.ci/pict/snapshot2.png?raw=true "Matrix Pipeline View")

## Schema Validation

Matrix schema validation is part of the default local/GHA flow and runs before job execution.

Files:

- `schema_validator/ci_demo_schema.yaml`: schema
- `schema_validator/ci_demo_yaml_validator.py`: validator entrypoint

Behavior:

- Validation failures stop the script and fail CI.
- Unknown keys are rejected in structured sections.
- Generic map sections (for example `matrix.axes`) allow custom axis names.

## Local Development

### Useful Environment Variables

`local_gha_ci.sh` supports overrides through env vars.

Most useful:

- `CI_K8_FILE`: matrix file to run (default: `.ci/job_matrix_gha_k8.yaml`)
- `TARGET_ARCHES`: comma-separated target matrix arch list (`x86_64`, `aarch64`)
- `KEEP_JENKINS`: keep Jenkins container after run (`true`/`false`)
- `KEEP_K8S`: keep k3s container after run (`true`/`false`)
- `USE_COLIMA`: enable colima mode (`true`/`false`)
- `COLIMA_PROFILE`: colima profile name (for example `native`)

Example:

```bash
TARGET_ARCHES=aarch64 KEEP_JENKINS=false KEEP_K8S=false make -C .ci local-gha-ci
```

### Run With a Different Matrix File

```bash
CI_K8_FILE=.ci/job_matrix_debug.yaml make -C .ci local-gha-ci
```

## Matrix YAML Essentials

A matrix config must include:

- `job`
- `steps`
- at least one execution backend, usually `runs_on_dockers`

Typical optional sections:

- `kubernetes`
- `matrix.axes/include/exclude`
- `pipeline_start` / `pipeline_stop`
- `env`
- `failFast`

### Minimal Docker Example

```yaml
---
job: ci-demo-mini

runs_on_dockers:
  - {name: ubuntu2204, url: ubuntu:22.04, arch: x86_64, nodeLabel: master}

matrix:
  axes:
    variant: [debug, release]

steps:
  - name: Print context
    run: echo "variant=$variant name=$name arch=$arch"

failFast: false
```

### Kubernetes Example (arch-aware)

```yaml
---
job: ci-k8-mini

kubernetes:
  cloud: swx-k8s
  namespace: default
  serviceAccount: jenkins

runs_on_dockers:
  - {name: ubuntu-x86_64, url: ubuntu:22.04, arch: x86_64}
  - {name: ubuntu-aarch64, url: ubuntu:22.04, arch: aarch64}

matrix:
  axes:
    arch: [x86_64, aarch64]
  include:
    - {arch: '${TARGET_ARCH}'}

steps:
  - name: Verify
    run: uname -m
```

## Troubleshooting

### Jenkins does not become ready

- Check container logs:
  - `docker logs jenkins-local | tail -200`
- Verify local port binding:
  - `docker exec jenkins-local curl -sf http://localhost:8080/login`

### Schema validation fails

- Read validator errors in stdout.
- Check against `schema_validator/ci_demo_schema.yaml`.
- Ensure matrix file path passed by `CI_K8_FILE` is correct.

### k8 node arch mismatch

- `local_gha_ci.sh` relabels k3s node arch based on `TARGET_ARCHES`.
- Keep `TARGET_ARCHES` aligned with matrix include/filter expectations.

### Jenkins checkout cannot find revision

- Ensure `BRANCH` and `REPO_URL` are correct.
- In GHA, `REPO_URL` should be the GitHub repository URL, not a stale local `file://` ref.

### Need all logs/artifacts

Use files under:

- `.tmp/local-gha-ci/logs/*.log`
- `.tmp/local-gha-ci/logs/*.job-output.log`
- `.tmp/local-gha-ci/logs/*.jenkins-console.log`
- `.tmp/local-gha-ci/logs/*.jenkins-build.json`
- `.tmp/local-gha-ci/logs/*.jenkins-container.log`

## Contributing

Before opening a PR for CI behavior changes:

1. Run `make -C .ci local-gha-clean`
2. Run `make -C .ci local-gha-ci`
3. Check `.tmp/local-gha-ci/logs`
4. Confirm `.github/workflows/ci.yml` and `scripts/local_gha_ci.sh` are still aligned
