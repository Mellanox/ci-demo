# `runs_on_dockers` reference

`runs_on_dockers` is a top-level YAML list of **container descriptors**. Each entry tells the pipeline:

1. **Which image to use** (pull an existing one, or build one from a `Dockerfile` in the repo).
2. **Where to run it** (a Jenkins agent via the Docker plugin, or a Kubernetes pod).
3. **When to rebuild it** (forced, on Dockerfile change, on dependency change, or when missing from the registry).
4. **Which steps target it** (via `containerSelector` and the `category` flag).

For every entry the pipeline produces one (or more, with a matrix) parallel branch. Steps in `steps:` run inside that container unless excluded by a selector.

The implementation lives in [src/com/mellanox/cicd/Matrix.groovy](../src/com/mellanox/cicd/Matrix.groovy) — most of it in `gen_image_map()` ([Matrix.groovy:206-294](../src/com/mellanox/cicd/Matrix.groovy#L206-L294)) and `buildImage()` ([Matrix.groovy:1313-1351](../src/com/mellanox/cicd/Matrix.groovy#L1313-L1351)).

---

## Minimal examples

**Pull a pre-built image and run on a Jenkins agent**

```yaml
runs_on_dockers:
  - {name: 'centos7',
     url: 'harbor.mellanox.com/toolbox/ngci-centos:7.9.2009',
     arch: 'x86_64',
     nodeLabel: '(dockerserver || docker) && x86_64'}
```

**Build an image from a Dockerfile and run on Kubernetes**

```yaml
kubernetes:
  cloud: 'swx-k8s-spray'

runs_on_dockers:
  - {name: 'ubuntu24-04',
     file: '.ci/Dockerfile.ubuntu24-04',
     arch: 'x86_64'}
```

The build runs in a `podman` container on the same K8s cloud, then the resulting image is pushed and used for steps.

---

## Field reference

### Required

| Field | Type | Description |
|---|---|---|
| `name` | string | Logical name. Used in `containerSelector`, branch names, pod names. Must be unique within the file. |
| `arch` | string | CPU architecture (`x86_64`, `aarch64`, ...). Required unless `matrix.axes.arch` is set; in that case the entry is replicated per arch in the axis. Mapped to a `kubernetes.arch_table` entry for K8s placement ([Matrix.groovy:226-227](../src/com/mellanox/cicd/Matrix.groovy#L226-L227)). |

### Image source — pick one

| Field | Description |
|---|---|
| `url` | Full registry URL, e.g. `harbor.mellanox.com/toolbox/ngci-centos:7.9.2009`. If `:tag` is present in the URL it is parsed out into `tag` and `uri` ([Matrix.groovy:268-275](../src/com/mellanox/cicd/Matrix.groovy#L268-L275)). |
| `file` | Path to a `Dockerfile` in the repo. Setting this enables the build phase. The pushed image URL is `${registry_host}${registry_path}/${uri}:${tag}` unless `url` is also given. |
| `uri` | Registry path component (without host or tag). Defaults to `${arch}/${name}`. Templated. |
| `tag` | Image tag. Defaults to `latest`. Used to compose `url` when `url` is not provided. |

If only `file:` is set, `url` is composed from `registry_host` + `registry_path` + `uri` + `tag`. So with `registry_host: harbor.mellanox.com`, `registry_path: /swx-storage/ci-demo`, `name: ubuntu24-04`, `arch: x86_64`, the resulting image is pushed to `harbor.mellanox.com/swx-storage/ci-demo/x86_64/ubuntu24-04:latest`.

### Build inputs (only relevant when `file:` is set)

| Field | Type | Description |
|---|---|---|
| `build_args` | string | Extra arguments passed to `docker build`, e.g. `'FOO=bar BAZ=$var2'`. Templated against the image entry, `env:`, and Jenkins env ([Matrix.groovy:281](../src/com/mellanox/cicd/Matrix.groovy#L281)). |
| `deps` | list of strings | Repo-relative file paths. If **any** is in the PR / commit's changed-files list, force a rebuild. See *Rebuild logic* below. |
| `on_image_build` | string | Shell snippet run **before** `docker build` (per-image hook). Overrides the file-wide `pipeline_on_image_build.run` ([Matrix.groovy:1182-1186](../src/com/mellanox/cicd/Matrix.groovy#L1182-L1186)). |

### Run-time placement — pick one

The container needs somewhere to run. For each entry exactly one of these must resolve:

| Field | Effect |
|---|---|
| `nodeLabel` | Jenkins agent label expression (e.g. `'(dockerserver \|\| docker) && x86_64'`). The Jenkins Docker plugin runs the container on a matching agent. |
| `cloud` | Kubernetes cloud name (overrides the file-level `kubernetes.cloud`). The container runs as a pod, scheduled by `kubernetes.arch_table[arch].nodeSelector`. |

If neither is on the entry **and** no `kubernetes.cloud` is set at the top level, the build fails with `"Please define cloud or nodeLabel..."` ([Matrix.groovy:1107-1108](../src/com/mellanox/cicd/Matrix.groovy#L1107-L1108)).

When `file:` is set, the **build** uses the same placement (baremetal docker via `nodeLabel`, or podman-in-pod via `cloud`).

### Selector / scheduling controls

| Field | Description |
|---|---|
| `category` | If set to `'tool'`, **steps are skipped by default** on this container. Only steps that explicitly target it via `containerSelector` will run. Use this for utility containers (blackduck, coverity, lint...) that should not run the full step list ([Matrix.groovy:549-551](../src/com/mellanox/cicd/Matrix.groovy#L549-L551)). |
| `enable` | Controls whether this entry is included at all. Values: `"true"` (default), `"false"`, `"auto"`, or any template string evaluating to a boolean. `"auto"` is rewritten to `${<name>}` so it picks up a same-named env var or job parameter ([Matrix.groovy:245-259](../src/com/mellanox/cicd/Matrix.groovy#L245-L259)). |
| `nodeSelector` | Extra K8s nodeSelector appended to the arch's selector (string in `key=value,key=value` form). |

### Kubernetes pod overrides

These mirror the `kubernetes:` block and apply only to K8s placement. If unset, the value falls back to the file-level `kubernetes.<field>` (see [Matrix.groovy:891-901](../src/com/mellanox/cicd/Matrix.groovy#L891-L901) and [Matrix.groovy:1418-1425](../src/com/mellanox/cicd/Matrix.groovy#L1418-L1425)).

| Field | Default fallback | Notes |
|---|---|---|
| `cloud` | `kubernetes.cloud` | K8s cloud name. |
| `namespace` | `default` | |
| `hostNetwork` | `false` | |
| `runAsUser` | `"0"` | |
| `runAsGroup` | `"0"` | |
| `privileged` | `false` (run); `true` (build pod for podman) | |
| `limits` | `{memory: 8Gi, cpu: 4000m}` | YAML inline map. |
| `requests` | `{memory: 8Gi, cpu: 4000m}` | YAML inline map. |
| `annotations` | `[]` | List of annotations. |
| `caps_add` | `[]` | List of Linux capabilities. |
| `tolerations` | `[]` | List of toleration entries. |

---

## Rebuild logic (when `file:` is set)

`buildImage()` ([Matrix.groovy:1313-1351](../src/com/mellanox/cicd/Matrix.groovy#L1313-L1351)) decides whether to rebuild on each pipeline run. It rebuilds if **any** of these is true:

1. **Force flag:** the Jenkins parameter / env var `build_dockers` is `true` (defined as a job parameter in [.ci/proj_jjb.yaml:22-25](../.ci/proj_jjb.yaml#L22-L25)).
2. **Dockerfile changed:** the path in `file:` appears in the changed-files list (computed by `getChangedFilesList()` from the PR or commit diff, [Matrix.groovy:1225-1265](../src/com/mellanox/cicd/Matrix.groovy#L1225-L1265)).
3. **Dependency changed:** any path listed in `deps:` appears in the changed-files list.
4. **Image missing in registry:** *only when `deps:` is unset* — if the registry doesn't have `url`, build it. With `deps:` set, the registry-existence check is skipped: dependencies are the sole signal.

The changed-files list is what `git diff` produces against the merge base of the PR (or the previous commit on push builds). Paths must match exactly — repo-relative, no leading `./`.

### Putting it together — file change triggers

```yaml
runs_on_dockers:
  - {name: 'ubuntu24-04',
     file: '.ci/Dockerfile.ubuntu24-04',
     arch: 'x86_64',
     deps:
       - '.ci/install_deps.sh'
       - '.ci/requirements.txt'
       - 'scripts/setup-build-env.sh',
     build_args: 'CUDA_VERSION=12.4'}
```

Rebuild triggers for this entry:

- A change to `.ci/Dockerfile.ubuntu24-04` (the Dockerfile itself).
- A change to any of the three `deps:` files.
- A user-forced run with `build_dockers=true`.

A change to anything else — even other files in `.ci/` — does **not** trigger a rebuild. The previously pushed image is reused.

> **Caveat.** Once you add `deps:`, the "image missing → build" fallback is **disabled** for that entry. If the image was never built (e.g. first run on a fresh registry) and none of the deps changed, the run will try to pull a non-existent image. Either bootstrap once with `build_dockers=true` or omit `deps:` for the first run.

---

## Templating

The following fields are passed through `resolveTemplate()` and can reference `${var}` placeholders:

- `url`, `uri`, `build_args`, `enable`

Lookup order for `${var}`:

1. Keys on the same image entry (`${name}`, `${arch}`, `${tag}`, ...).
2. Keys in the file-level `env:` section.
3. Jenkins build environment (job parameters, `BUILD_NUMBER`, ...).

Example from [.ci/job_matrix_debug.yaml](../.ci/job_matrix_debug.yaml):

```yaml
env:
  blackduck_url: 'harbor.mellanox.com/toolbox/ngci-centos:7.9.2009'
  var1: 12
  var2: '${var1}-1'

runs_on_dockers:
  - {name: 'blackduck',
     url: '$blackduck_url',
     category: 'tool',
     arch: 'x86_64',
     build_args: 'bobo=$var2'}
```

After resolution: `url` is the literal harbor URL, `build_args` becomes `bobo=12-1`.

---

## Interaction with `matrix:`

When `matrix.axes` is defined, each container entry is **replicated per axis combination**. The `arch` axis filters the entries: only entries whose `arch` matches an axis value are kept ([Matrix.groovy:215-228](../src/com/mellanox/cicd/Matrix.groovy#L215-L228)).

A branch's `axis` map is the merge of (axis combination + image entry) — see [docs/VARIANT_AND_MATRIX.md](VARIANT_AND_MATRIX.md) for the full mechanics. Image fields therefore become available as keys in `containerSelector` filters.

---

## Cheat sheet — common patterns

**Pull-only utility container (no Dockerfile, runs only when explicitly selected)**

```yaml
- {name: 'blackduck', url: 'harbor.mellanox.com/toolbox/ngci-centos:7.9.2009',
   category: 'tool', arch: 'x86_64'}
```

**Build on K8s, run on a baremetal agent (mixed mode)**

```yaml
env:
  build_dockers: true       # forces rebuild every run

runs_on_dockers:
  - {file: '.ci/Dockerfile.ubuntu18-4',
     name: 'ubuntu18-4',
     cloud: 'swx-k8s',                       # build pod on K8s
     nodeLabel: 'swx-clx01 || swx-clx02',    # run on these BM agents
     arch: 'x86_64'}
```

**Conditionally enabled container (toggled by job parameter `run_arm`)**

```yaml
- {name: 'ubuntu24-arm', file: '.ci/Dockerfile.ubuntu24-04',
   arch: 'aarch64', enable: '${run_arm}'}
```

**Image rebuilt only when its dependency files change**

```yaml
- {name: 'builder', file: '.ci/Dockerfile.builder', arch: 'x86_64',
   deps: ['.ci/install_deps.sh', 'requirements.txt']}
```

---

## See also

- [.ci/examples/](../.ci/examples/) — complete working YAMLs for each pattern.
- [.ci/proj_jjb.yaml](../.ci/proj_jjb.yaml) — Jenkins job definition with the `build_dockers` parameter.
