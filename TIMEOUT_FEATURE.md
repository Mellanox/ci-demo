# Timeout Feature for Shell Run Actions

## Overview

The Matrix.groovy pipeline now supports timeout configuration for shell run actions. This allows you to set timeouts for individual steps or globally for all steps.

## Configuration Options

### 1. Global Timeout

Set a default timeout for all steps in your YAML configuration:

```yaml
job: my_job
timeout_minutes: 60  # 60 minutes default timeout for all steps

steps:
  - name: step1
    run: "echo hello"
    # Uses global timeout of 60 minutes
```

### 2. Step-Specific Timeout

Override the global timeout for individual steps:

```yaml
steps:
  - name: quick_step
    run: "echo hello"
    timeout: 5  # 5 minutes timeout

  - name: long_step
    run: "sleep 3600"
    timeout: 10  # 10 minutes timeout - will timeout before completion
```

### 3. Template-Based Timeout

Use environment variables or template variables for timeout values:

```yaml
env:
  DEFAULT_TIMEOUT: 30
  LONG_TIMEOUT: 120

steps:
  - name: template_step
    run: "echo using template timeout"
    timeout: "${DEFAULT_TIMEOUT}"  # Uses environment variable
```

## Error Handling

When a step times out:

1. **Timeout Exception**: The step will throw a timeout exception
2. **onfail Handler**: If configured, the `onfail` command will execute
3. **always Handler**: If configured, the `always` command will execute
4. **Pipeline Failure**: The step will be marked as failed

## Example with Error Handling

```yaml
steps:
  - name: timeout_with_handlers
    run: "sleep 3600"  # Long running command
    timeout: 5  # 5 minutes timeout
    onfail: "echo 'Step timed out - cleaning up'"
    always: "echo 'Always executed regardless of timeout'"
```

## Implementation Details

### Modified Functions

1. **`run_shell(cmd, title, retOut=false, timeout_minutes=null)`**
   - Added `timeout_minutes` parameter
   - Wraps shell execution in `timeout()` block when timeout is specified

2. **`run_step_shell(image, cmd, title, oneStep, config)`**
   - Retrieves timeout value from step configuration using `getConfigVal()`
   - Passes timeout to `run_shell()` function
   - Applies timeout to `onfail` and `always` handlers

### Configuration Resolution

The timeout value is resolved using the existing `getConfigVal()` function:
- Checks step-specific timeout first
- Falls back to global timeout if step timeout is not specified
- Supports template variable resolution (e.g., `${VARIABLE}`)
- Returns `null` if no timeout is configured (no timeout applied)

## Backward Compatibility

- Existing YAML configurations without timeout settings continue to work unchanged
- Steps without timeout configuration run without time limits (original behavior)
- All existing step properties (`run`, `onfail`, `always`, etc.) continue to work

## Usage Examples

### Basic Timeout
```yaml
steps:
  - name: basic_timeout
    run: "long_running_command"
    timeout: 30
```

### Conditional Timeout
```yaml
env:
  BUILD_TYPE: "release"

steps:
  - name: conditional_timeout
    run: "build_command"
    timeout: "${BUILD_TYPE == 'release' ? '120' : '30'}"
```

### Global Default with Override
```yaml
timeout: 60  # Global default

steps:
  - name: quick_step
    run: "echo quick"
    timeout: 5  # Override for this step

  - name: normal_step
    run: "echo normal"
    # Uses global timeout of 60 minutes
```

## Testing

The `test_timeout_example.yaml` file demonstrates various timeout scenarios:
- Global timeout configuration
- Step-specific timeouts
- Template variable usage
- Error handling with `onfail` and `always` handlers
