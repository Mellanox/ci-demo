#!/usr/bin/env groovy

int call(ctx, oneStep) {

    def args = oneStep.args

    library(identifier: 'ngci@ci_version-3.1',
            retriever: modernSCM([$class: 'GitSCMSource', 
            remote: 'http://l-gerrit.mtl.labs.mlnx:8080/DevOps/Jenkins/ci_framework']))

    if (args.size() < 1) {
        ctx.reportFail(oneStep.name, 'fatal: DynamicAction() expects at least 1 parameter')
    }

    Map vars = [env: env]
    for (def entry in ctx.entrySet(args)) {
        args[entry.key] = ctx.resolveTemplate(vars, entry.value)
    }
    println("Calling ${oneStep.run} with args=" + args)

    return "${oneStep.run}"(args)
}
