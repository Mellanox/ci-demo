# Volume mounts reference

The pipeline supports five volume types declared as top-level lists in the job-matrix YAML. They are passed directly to the Jenkins Kubernetes plugin (K8s pods) or translated to `-v` flags for Docker-agent runs.

The implementation lives in [src/com/mellanox/cicd/Matrix.groovy](../src/com/mellanox/cicd/Matrix.groovy): parsing functions `parseListV`, `parseListNfsV`, `parseListPVC`, `parseSecretV`, `parseEmptyDirV` ([Matrix.groovy:761–830](../src/com/mellanox/cicd/Matrix.groovy#L761-L830)), wired into `runK8` ([Matrix.groovy:873–877](../src/com/mellanox/cicd/Matrix.groovy#L873-L877)) and `getDockerOpt` ([Matrix.groovy:1001–1013](../src/com/mellanox/cicd/Matrix.groovy#L1001-L1013)).

---

## Compatibility

| Volume type    | Docker agent | K8s pod |
|----------------|:------------:|:-------:|
| `volumes`      | ✓            | ✓       |
| `nfs_volumes`  | ✗            | ✓       |
| `pvc_volumes`  | ✗            | ✓       |
| `secret_volumes` | ✗          | ✓       |
| `empty_volumes` | ✗           | ✓       |

Only `volumes` (host-path) works on Docker agents. All five work on K8s pods.

---

## `volumes` — host path

Maps a host directory into the container. On Docker agents this becomes a `-v` flag; on K8s pods it becomes a `hostPathVolume`.

```yaml
volumes:
  - {mountPath: /hpc/local, hostPath: /hpc/local}
  - {mountPath: /auto/sw_tools}          # hostPath defaults to mountPath
```

| Field | Required | Description |
|---|---|---|
| `mountPath` | yes | Path inside the container. |
| `hostPath` | no | Path on the host. **Defaults to `mountPath`** when omitted — same path is used on both sides. |

**Docker agent behavior:** `getDockerOpt` appends `-v <mountPath>:<hostPath>` to the `docker run` command ([Matrix.groovy:1003–1012](../src/com/mellanox/cicd/Matrix.groovy#L1003-L1012)).

**K8s behavior:** passed as a `hostPathVolume` to the pod template. The node must have the path present.

---

## `nfs_volumes` — NFS mount

Mounts an NFS share into the pod. K8s only.

```yaml
nfs_volumes:
  - mountPath: /mnt/shared
    serverAddress: nfs-server.example.com
    serverPath: /exports/shared
    readOnly: false
```

| Field | Required | Description |
|---|---|---|
| `mountPath` | yes | Path inside the container. |
| `serverAddress` | no | NFS server hostname or IP. |
| `serverPath` | no | Exported path on the NFS server. |
| `readOnly` | no | Mount read-only. Defaults to `false`. |

---

## `pvc_volumes` — PersistentVolumeClaim

Attaches an existing PVC to the pod. K8s only.

```yaml
pvc_volumes:
  - {claimName: my-pvc, mountPath: /mnt/pvc, readOnly: false}
```

| Field | Required | Description |
|---|---|---|
| `mountPath` | yes | Path inside the container. |
| `claimName` | no | Name of the PersistentVolumeClaim in the pod's namespace. |
| `readOnly` | no | Mount read-only. Defaults to `false`. |

The PVC must already exist in the namespace the pod runs in (controlled by `kubernetes.namespace`, default `default`).

---

## `secret_volumes` — Kubernetes Secret

Projects a Kubernetes Secret as files into the pod. K8s only.

```yaml
secret_volumes:
  - {secretName: my-tls-cert, mountPath: /etc/certs}
```

| Field | Required | Description |
|---|---|---|
| `mountPath` | yes | Path inside the container. Each key in the Secret becomes a file under this directory. |
| `secretName` | no | Name of the Kubernetes Secret in the pod's namespace. |

For secrets used as environment variables (not files), use `credentials:` instead. See the job-matrix reference.

---

## `empty_volumes` — EmptyDir

Creates a temporary scratch volume. Useful for sharing data between steps or for fast ephemeral storage. K8s only.

```yaml
empty_volumes:
  - {mountPath: /tmp/workspace, memory: false}
  - {mountPath: /dev/shm/cache, memory: true}   # RAM-backed
```

| Field | Required | Description |
|---|---|---|
| `mountPath` | yes | Path inside the container. |
| `memory` | no | `true` to back the volume with RAM (tmpfs). Defaults to `false` (disk-backed). |

**Gotcha:** RAM-backed volumes (`memory: true`) count against the pod's memory limit. If your step writes large files to a `memory: true` volume, the pod may be OOM-killed.

---

## Cheat sheet

**Mount shared toolchain from host (Docker agent)**

```yaml
volumes:
  - {mountPath: /hpc/local, hostPath: /hpc/local}
  - {mountPath: /auto/sw_tools, hostPath: /auto/sw_tools}
```

**Scratch space for build artifacts (K8s)**

```yaml
empty_volumes:
  - {mountPath: /tmp/build}
```

**Shared NFS read-only dataset (K8s)**

```yaml
nfs_volumes:
  - mountPath: /mnt/datasets
    serverAddress: nfs.example.com
    serverPath: /exports/datasets
    readOnly: true
```

**Inject a TLS cert from a Kubernetes Secret (K8s)**

```yaml
secret_volumes:
  - {secretName: my-tls-cert, mountPath: /etc/certs}
```

**Reuse a pre-provisioned PVC for caching (K8s)**

```yaml
pvc_volumes:
  - {claimName: build-cache, mountPath: /cache, readOnly: false}
```
