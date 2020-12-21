#!/usr/bin/env groovy

@NonCPS 
def entrySet(m) {
    m.collect {k, v -> [key: k, value: v]}
}

@NonCPS
def resolveTemplate(varsMap, str) {
    GroovyShell shell = new GroovyShell(new Binding(varsMap))
    def res = shell.evaluate('"' + str +'"')
    return res
}

def call(oneStep) {

    def args = oneStep.args
    def actionName = oneStep.run

    library(identifier: 'ngci@ci_version-3.1',
            retriever: modernSCM([$class: 'GitSCMSource', 
            remote: 'http://l-gerrit.mtl.labs.mlnx:8080/DevOps/Jenkins/ci_framework']))

    if (args.size() < 1) {
        println("fatal: DynamicAction() expects at least 1 parameter")
        sh(script: "false", label: "action failed", returnStatus: true)
    }

    def vars = [env: env]
    for (def entry in entrySet(args)) {
        args[entry.key] = resolveTemplate(vars, entry.value)
    }
    
    println("Calling ${actionName} with args=" + args)
    "$actionName"(args)

    return;
}
