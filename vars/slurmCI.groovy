#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    def args = oneStep.args ?: [:]

    // Load the shared Slurm helper library from the pipeline shared library.
    // refer to this doc for more details: https://www.jenkins.io/doc/book/pipeline/shared-libraries/#using-libraries
    // Default is 'swx-jenkins-lib' (from Mellanox-lab org). Optional step arg 'ref' can override the git ref.
    // e.g., args: [ref: 'my-branch'] => library(identifier: 'swx-jenkins-lib@my-branch')
    def libRef = args.remove('ref')

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
        args[entry.key] = ctx.resolveTemplate(['env': env], entry.value.toString(), config)
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
    if (rawResult == false) {
        return 1
    }
    if (rawResult instanceof Number) {
        return (rawResult as int)
    }
    return 0
}
