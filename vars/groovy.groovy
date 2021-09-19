#!/usr/bin/env groovy

int call(ctx, oneStep, config) {

    println("Module groovy calling ${oneStep.run} ")

    def vars = []
    def ret = 1

    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)

    String actionScript = "" + oneStep.run

    println("Module groovy calling transformed: ${actionScript} ")
    
    withEnv(vars) {
        evaluate(actionScript)
    }
    if (ret) {
        return 0
    }
    return 1
}
