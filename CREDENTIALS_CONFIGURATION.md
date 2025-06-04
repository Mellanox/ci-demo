# SSH Credentials Configuration for CI-Demo Framework

This document explains how to configure SSH credentials for Git repository access in the ci-demo framework, eliminating the need to rely on Jenkins master's filesystem for SSH keys.

## Overview

The ci-demo framework now supports configurable SSH credentials through Jenkins credential management, allowing pipelines to run on any agent or Kubernetes pod while securely accessing Git repositories.

## Configuration Parameters

### `git_credentials_id`

- **Purpose**: Specifies the Jenkins credential ID for Git repository checkout operations
- **Type**: String
- **Default**: None (uses default checkout without SSH agent)
- **Example**: `'swx-jenkins_ssh_key'`

### `ngci_credentials_id`

- **Purpose**: Specifies the Jenkins credential ID for NGCI shared library access
- **Type**: String  
- **Default**: `'swx-jenkins_ssh_key'`
- **Example**: `'my-service-account-ssh-key'`

## YAML Configuration Example

```yaml
job: my-project
registry_host: harbor.example.com
registry_path: /my-org/my-project

# SSH Credentials configuration
git_credentials_id: 'swx-jenkins_ssh_key'      # For Git checkout
ngci_credentials_id: 'swx-jenkins_ssh_key'     # For NGCI library

env:
  DOCKER_BUILDKIT: "1"

matrix:
  axes:
    arch: [x86_64]
    distro: [ubuntu20.04]

runs_on_dockers:
  - {name: 'ubuntu20.04', file: '.ci/Dockerfile.ubuntu20.04', arch: 'x86_64'}

kubernetes:
  cloud: 'my-k8s-cloud'

steps:
  - name: 'test'
    run: |
      echo "Running with SSH credentials"
      git status
```

## Jenkins Credential Setup

### 1. Create SSH Private Key Credential

1. Go to **Manage Jenkins** → **Manage Credentials**
2. Select appropriate domain (usually "Global")
3. Click **Add Credentials**
4. Choose **SSH Username with private key**
5. Configure:
   - **ID**: `swx-jenkins_ssh_key` (or your custom ID)
   - **Username**: Your service account username
   - **Private Key**: Upload or paste your SSH private key
   - **Passphrase**: Enter if your key has a passphrase

### 2. Verify Credential Access

Ensure the credential is accessible from:
- Jenkins master (for pipeline initialization)
- Kubernetes pods (if using cloud execution)
- Jenkins agents (if using agent-based execution)

## How It Works

### Git Checkout Process

1. **Configuration Loading**: Framework reads `git_credentials_id` from matrix.yaml
2. **Environment Setup**: Credential ID is passed to environment variables
3. **SSH Agent**: `sshagent` wrapper provides SSH credentials during checkout
4. **Fallback**: If no credential ID specified, uses default checkout

```groovy
// In startPipeline() function
def gitCredentialsId = env.git_credentials_id ?: null

if (gitCredentialsId) {
    logger.debug("Using SSH credentials for checkout: ${gitCredentialsId}")
    sshagent(credentials: [gitCredentialsId]) {
        scmVars = checkout scm
    }
} else {
    logger.debug("No git_credentials_id specified, using default checkout")
    scmVars = checkout scm
}
```

### NGCI Library Loading

1. **Credential Resolution**: Framework reads `ngci_credentials_id` from config
2. **SSH Agent Wrapper**: Library loading wrapped with SSH credentials
3. **Default Fallback**: Uses `swx-jenkins_ssh_key` if not specified

```groovy
// In ngci.groovy
def ngciCredentialsId = config.get('ngci_credentials_id') ?: 'swx-jenkins_ssh_key'

sshagent(credentials: [ngciCredentialsId]) {
    library(identifier: 'ngci@5.0.4-26', ...)
}
```

## Migration from Master-based SSH Keys

### Before (Master-dependent)
- SSH keys stored in `/home/jenkins/.ssh/` on master
- Pipeline required running on master for repository access
- Single point of failure and resource contention

### After (Credential-based)  
- SSH keys managed through Jenkins credentials
- Pipeline can run on any agent or Kubernetes pod
- Improved security and scalability

## Troubleshooting

### Common Issues

1. **Credential Not Found**
   ```
   Error: Could not find credentials entry with ID 'swx-jenkins_ssh_key'
   ```
   - Verify credential exists in Jenkins
   - Check credential ID spelling
   - Ensure credential scope allows access

2. **Permission Denied During Checkout**
   ```
   Permission denied (publickey)
   ```
   - Verify SSH key has access to repository
   - Check key format and passphrase
   - Test SSH connection manually

3. **Library Loading Fails**
   ```
   Could not resolve dependencies for :ngci@5.0.4-26
   ```
   - Check `ngci_credentials_id` configuration
   - Verify network connectivity to git server
   - Ensure credential has access to ci_framework repository

### Debug Steps

1. **Enable Debug Logging**
   ```yaml
   env:
     DEBUG: "3"
   ```

2. **Test Credential Access**
   ```yaml
   steps:
     - name: 'test-ssh'
       credentialsId: ['swx-jenkins_ssh_key']
       run: |
         ssh -T git@github.com
   ```

3. **Verify Environment Variables**
   ```yaml
   steps:
     - name: 'debug-env'
       run: |
         echo "Git credentials: ${git_credentials_id}"
         echo "NGCI credentials: ${ngci_credentials_id}"
   ```

## Best Practices

1. **Use Dedicated Service Accounts**: Create dedicated SSH keys for CI/CD operations
2. **Rotate Keys Regularly**: Update SSH keys periodically for security
3. **Limit Key Scope**: Grant minimum necessary repository access
4. **Monitor Usage**: Track credential usage through Jenkins logs
5. **Test Configuration**: Verify credentials work before production deployment

## Example Files

- [job_matrix_with_credentials.yaml](/.ci/examples/job_matrix_with_credentials.yaml): Complete example configuration
- [job_matrix.yaml](/.ci/job_matrix.yaml): Default configuration without credentials 
