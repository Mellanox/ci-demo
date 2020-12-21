#!/usr/bin/env groovy

@NonCPS
def resolveTemplate(varsMap, str) {
    GroovyShell shell = new GroovyShell(new Binding(varsMap))
    def res = shell.evaluate('"' + str +'"')
    return res
}


def call(oneStep) {

    def args = oneStep.args
    def actionName = oneStep.run

    println("==>DynamicAction(" + args + ")")

    def argList = []
    def vars = [:]
    vars['env'] = env

    if (args != null) {
        for (int i=0; i<args.size(); i++) {
            arg = args[i]
            arg = resolveTemplate(vars, arg)
            argList.add(arg)
        }
    }

    if (args.size() < 1) {
        println("fatal: DynamicAction() expects at least 1 parameter")
        sh(script: "false", label: "action failed", returnStatus: true)
    }
    def actionScript = libraryResource "actions/${actionName}"
    def toFile = env.WORKSPACE + "/cidemo_${actionName}"

    writeFile(file: toFile, text: actionScript)
    sh(script: "chmod +x " + toFile, label: "Set script permissions", returnStatus: true)

    def cmd = toFile
    if (args.size() > 1) {
        for (int i=0; i< args.size(); i++) {
            cmd += " '" + args[i] + "'"
        }
    }
    return sh(script: cmd, label: "Runing ${actionName}", returnStatus: true)
}
