# ci-demo User Guide

This guide describes the CI matrix YAML format used by `ci-demo`.
Source of truth: `schema_validator/ci_demo_schema.yaml`.

## Purpose

`ci-demo` lets you define Jenkins pipeline behavior in YAML so job configuration is:

- declarative
- reusable
- portable across YAML-driven CI workflows

## File Basics

- YAML document root must contain `job`.
- `steps` must exist and contain at least one step.
- Most other keys are optional.
- Validation is strict for structured sections.

## Top-Level Keys

| Key | Type | Required | Notes |
|---|---|---|---|
| `job` | `str` | yes | Job name used by matrix runtime |
| `registry_host` | `str` | no | Registry host for image resolution |
| `registry_path` | `str` | no | Registry path prefix |
| `registry_auth` | `str` | no | Jenkins credentials for registry auth |
| `registry_jnlp_path` | `str` | no | Path used for JNLP image templates |
| `docker_opt` | `str` | no | Extra docker run options |
| `kubernetes` | `kubernetes_conf` | no | Global Kubernetes defaults |
| `arch_table` | `map` | no | Optional arch mapping table |
| `env` | `map(str -> str/int/num/bool)` | no | Global env vars |
| `step_allow_single_selector` | `bool` | no | Selector behavior toggle |
| `volumes` | `list(host_volume)` | no | HostPath-style volumes |
| `nfs_volumes` | `list(nfs_volume)` | no | NFS mounts |
| `pvc_volumes` | `list(pvc_volume)` | no | Kubernetes PVC mounts |
| `secret_volumes` | `list(secret_volume)` | no | Kubernetes Secret mounts |
| `empty_volumes` | `list(empty_volume)` | no | Kubernetes emptyDir mounts |
| `credentials` | `list(credential)` | no | Credential mappings for steps |
| `runs_on_dockers` | `list(docker_image)` | no | Container execution targets |
| `runs_on_agents` | `list(agent_image)` | no | Jenkins agent execution targets |
| `matrix` | `matrix_conf` | no | Axes/include/exclude |
| `steps` | `list(step_conf)` | yes | Pipeline actions |
| `disable` | `list(step_conf)` | no | Disabled step definitions |
| `pipeline_on_image_build` | `step_conf` | no | Hook during image build |
| `pipeline_start` | `step_conf` | no | Hook before matrix execution |
| `pipeline_stop` | `step_conf` | no | Hook after matrix execution |
| `archiveArtifacts` | `str` | no | Global artifacts pattern |
| `taskName` | `str` | no | Task name template |
| `taskNameSetupImage` | `str` | no | Image setup task name template |
| `batchSize` | `int` | no | Parallel batch size |
| `timeout_minutes` | `int/str` | no | Pipeline timeout |
| `timeout` | `int/str` | no | Global step timeout |
| `failFast` | `bool` | no | Stop parallel execution on first failure |

## Section Schemas

### `kubernetes_conf`

When using Kubernetes, the Jenkins server must have the Kubernetes cloud(s) referenced by `cloud` pre-configured (e.g. via init scripts at startup or by administrators). The pipeline does not create or update Jenkins cloud configuration.

| Key | Type |
|---|---|
| `cloud` | `str` |
| `nodeSelector` | `str` |
| `namespace` | `str` |
| `serviceAccount` | `str` |
| `hostNetwork` | `bool` |
| `runAsUser` | `str/int` |
| `runAsGroup` | `str/int` |
| `privileged` | `bool` |
| `limits` | `str/map` |
| `requests` | `str/map` |
| `annotations` | `list/map/str` |
| `caps_add` | `str/list` |
| `tolerations` | `str/list` |
| `imagePullSecrets` | `str/list` |
| `arch_table` | `map` |

### Volume Objects

`host_volume`
- `mountPath: str` (required)
- `hostPath: str` (optional)

`nfs_volume`
- `mountPath: str` (required)
- `serverAddress: str` (optional)
- `serverPath: str` (optional)

`pvc_volume`
- `mountPath: str` (required)
- `claimName: str` (optional)
- `readOnly: bool` (optional)

`secret_volume`
- `mountPath: str` (required)
- `secretName: str` (optional)

`empty_volume`
- `mountPath: str` (required)
- `memory: bool` (optional)

### `credential`

- `credentialsId: str` (required)
- `usernameVariable: str` (optional)
- `passwordVariable: str` (optional)
- `variable: str` (optional)

### `docker_image`

Common keys:

- identity/image: `name`, `url`, `tag`, `uri`, `file`
- execution routing: `arch`, `nodeLabel`, `cloud`, `category`, `enable`
- runtime/security: `hostNetwork`, `privileged`, `runAsUser`, `runAsGroup`
- resources/scheduling: `limits`, `requests`, `namespace`, `tolerations`, `annotations`
- pull/secrets/caps: `imagePullSecrets`, `caps_add`
- build helpers: `on_image_build`, `build_args`, `deps`

All are optional in schema.

### `agent_image`

- `nodeLabel: str` (optional)
- `name: str` (optional)
- `arch: str` (optional)

### `matrix_conf`

- `axes: map` (optional)
- `include: list(map)` (optional)
- `exclude: list(map)` (optional)

### `step_conf`

| Key | Type |
|---|---|
| `name` | `str` |
| `run` | `str` |
| `onfail` | `str` |
| `always` | `str` |
| `shell` | `str` |
| `module` | `str` |
| `timeout` | `int/str` |
| `enable` | `bool/str` |
| `parallel` | `bool` |
| `archiveArtifacts` | `str` |
| `containerSelector` | `str/list(str)` |
| `agentSelector` | `str/list(str)` |
| `credentialsId` | `str/list(str)` |
| `args` | `list/map/str` |
| `env` | `map` |
| `stage` | `str` |

## Minimal Valid Example

```yaml
---
job: ci-demo-mini

runs_on_dockers:
  - {name: ubuntu2204, url: ubuntu:22.04, arch: x86_64, nodeLabel: master}

steps:
  - name: Print
    run: echo "hello from ${job}"
```

## Kubernetes Example

```yaml
---
job: ci-k8

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

## Stages

Steps can be grouped into named stages with the optional `stage` key in `step_conf`.
This lets you define sequential phases (for example build, then test, then deploy)
while running the work inside each phase in parallel.

Behavior:

- Stage names are collected from the `stage` field of each step, in the order they
  first appear. Steps without a `stage` fall into a stage named `default`.
- Stages execute sequentially in that first-seen order; every task in a stage
  finishes before the next stage starts (each is rendered as a Jenkins stage block).
- Within a stage, tasks for all matching images/agents run in parallel (subject to
  `batchSize` and `failFast`). Multiple steps of the same stage on a single image run
  in their defined order unless the step sets `parallel: true`.
- Stage grouping only takes effect when more than one distinct stage is present. If
  every step resolves to the same stage (for example when none define `stage`), all
  tasks run together in parallel, as before.

```yaml
steps:
  - name: Build lib
    run: echo build lib
    stage: build
  - name: Build tools
    run: echo build tools
    stage: build
  - name: Unit tests
    run: echo unit
    stage: test
  - name: Publish
    run: echo publish
    stage: publish
```

Here `build` runs first (both build steps in parallel across images), then `test`,
then `publish`. See `.ci/examples/job_matrix_stages.yaml` for a complete example.

## Validation

Validate a file with:

```bash
python3 schema_validator/ci_demo_yaml_validator.py <path-to-matrix-yaml>
```

In local/GHA flow, validation is already run by `scripts/local_gha_ci.sh` before Jenkins build trigger.
