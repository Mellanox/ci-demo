#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    def args = oneStep.args

    library(identifier: 'ngci@5.0',
            retriever: modernSCM([$class: 'GitSCMSource',
            remote: 'ssh://git-nbu.nvidia.com:12023/DevOps/Jenkins/ci_framework',
            credentialsId: 'b7d08ca7-378c-45d6-ac4b-3f30bdf49168' ]))

    if (args.size() < 1) {
        ctx.reportFail(oneStep.name, 'fatal: DynamicAction() expects at least 1 parameter')
    }

    for (def entry in ctx.entrySet(args)) {
        args[entry.key] = ctx.resolveTemplate(['env':env], entry.value.toString(), config)
    }
    println("Calling ${oneStep.run} with args=" + args)

    def vars = []
    def ret

    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)
    withEnv(vars) {
        ret =  "${oneStep.run}"(args)
    }
    if (ret) {
        return 0
    }
    return 1
}
