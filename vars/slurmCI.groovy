#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    def args = (oneStep.args ?: [:]).clone()

    // Load the shared Slurm helper library from the pipeline shared library.
    // refer to this doc for more details: https://www.jenkins.io/doc/book/pipeline/shared-libraries/#using-libraries
    // Default is 'swx-jenkins-lib' (from Mellanox-lab org). Optional step arg 'ref' can override the git ref.
    // e.g., args: [ref: 'my-branch'] => library(identifier: 'swx-jenkins-lib@my-branch')
    def libRef = args.remove('ref')
    def archiveImage = oneStep.archiveImage  // step-level dict, not forwarded to slurm library

    if (libRef) {
        library(identifier: "swx-jenkins-lib@${libRef}")
    } else {
        library('swx-jenkins-lib')
    }

    if (!args || args.size() < 1) {
        ctx.reportFail(oneStep.name, 'fatal: slurm module expects at least 1 arg')
        return 1
    }

    for (def entry in ctx.entrySet(args)) {
        def original = entry.value.toString()
        def resolved = ctx.resolveTemplate(['env': env], original, config)
        // resolveTemplate is @NonCPS and cannot see withEnv-set axis vars (e.g. ucx_version, arch)
        // via env.getEnvironment(). Do a second pass here in CPS context where env property
        // access DOES include withEnv vars.
        def pass2 = resolved
        def safety = 0
        while (pass2.contains('${') && safety++ < 20) {
            def start = pass2.indexOf('${')
            def end = pass2.indexOf('}', start)
            if (end == -1) break
            def varName = pass2.substring(start + 2, end)
            def val = env."${varName}"
            if (val != null) {
                pass2 = pass2.substring(0, start) + val + pass2.substring(end + 1)
            } else {
                break
            }
        }
        args[entry.key] = pass2
    }
    def resolvedOutputPath = null
    if (archiveImage?.outputPath) {
        def original = archiveImage.outputPath.toString()
        def resolved = ctx.resolveTemplate(['env': env], original, config)
        def pass2 = resolved
        def safety = 0
        while (pass2.contains('${') && safety++ < 20) {
            def start = pass2.indexOf('${')
            def end   = pass2.indexOf('}', start)
            if (end == -1) break
            def varName = pass2.substring(start + 2, end)
            def val = env."${varName}"
            if (val != null) {
                pass2 = pass2.substring(0, start) + val + pass2.substring(end + 1)
            } else {
                break
            }
        }
        resolvedOutputPath = pass2
    }
    def stepRun = oneStep.run
    def allowedOps = ['allocation', 'run', 'stop', 'stopAllForBuild'] as Set
    if (!allowedOps.contains(stepRun)) {
        ctx.reportFail(oneStep.name, "fatal: unsupported slurm operation '${stepRun}'; allowed: ${allowedOps.sort().join(', ')}")
        return 1
    }
    println("Calling slurm.${stepRun} with args=" + args)

    def vars = []
    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)

    def rawResult = null
    withEnv(vars) {
        rawResult = slurm."${stepRun}"(args)
    }

    def exitCode = 0
    if (rawResult == false) {
        exitCode = 1
    } else if (rawResult instanceof Number) {
        exitCode = (rawResult as int)
    }

    if (archiveImage && resolvedOutputPath) {
        def onFailOnly = archiveImage.onFailOnly != null ? archiveImage.onFailOnly.toBoolean() : true
        if (!onFailOnly || exitCode != 0) {
            def jobIdFile     = args.jobIdFile
            def containerName = args.containerName
            if (!jobIdFile || !containerName) {
                echo "archiveImage: skipping export — jobIdFile or containerName is null (step '${oneStep.name}')"
            } else {
                def jobId = ''
                withEnv(["ARCHIVE_JOB_ID_FILE=${jobIdFile}"]) {
                    jobId = sh(script: 'cat "$ARCHIVE_JOB_ID_FILE"', returnStdout: true).trim()
                }
                jobId = jobId.replaceAll(/[^0-9]/, '')
                if (resolvedOutputPath.contains('${')) {
                    echo "archiveImage: skipping export — outputPath contains unresolved variables: ${resolvedOutputPath}"
                } else {
                    try {
                        withEnv([
                            "ARCHIVE_CONTAINER=${containerName}",
                            "ARCHIVE_OUTPUT=${resolvedOutputPath}",
                            "ARCHIVE_JOB_ID=${jobId}",
                        ]) {
                            sh(label: "archiveImage: export '${containerName}' -> ${resolvedOutputPath}", script: '''
#!/bin/bash
set -euo pipefail
mkdir -p "$(dirname "$ARCHIVE_OUTPUT")"
# TODO: replace with actual enroot export command from cluster admin.
# Must run on the compute node without container flags.
scctl --raw-errors client connect -- srun --jobid="$ARCHIVE_JOB_ID" --ntasks=1 --oversubscribe enroot export --container "$ARCHIVE_CONTAINER" --output "$ARCHIVE_OUTPUT"
echo "Export complete: $ARCHIVE_OUTPUT"
echo "To debug: .ci/scripts/debug_run.sh $ARCHIVE_OUTPUT [core_file]"
''')
                        }
                    } catch (e) {
                        echo "archiveImage: export failed (non-fatal): ${e.message}"
                    }
                }
            }
        }
    }

    return exitCode
}
