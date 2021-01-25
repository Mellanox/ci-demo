#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    args = oneStep.args

    println("==>DynamicAction(" + args + ")")

    def argList = []
    def vars = [:]
    vars['env'] = env

    if (args != null) {
        for (int i=0; i<args.size(); i++) {
            arg = args[i]
            arg = ctx.resolveTemplate(vars, arg, config)
            argList.add(arg)
        }
    }

    if (args.size() < 1) {
        ctx.reportFail(oneStep.name, "fatal: DynamicAction() expects at least 1 parameter")
    }

    def actionScript = libraryResource "actions/${oneStep.run}"
    def toFile = env.WORKSPACE + "/cidemo_${oneStep.run}"

    writeFile(file: toFile, text: actionScript)
    sh(script: "chmod +x " + toFile, label: "Set script permissions", returnStatus: true)

    String cmd = toFile
    if (args.size() > 1) {
        for (int i=0; i< args.size(); i++) {
            cmd += " " + args[i]
        }
    }
    println("Running cmd: ${oneStep.run} " + cmd)
    return sh(script: cmd, label: "Runing ${oneStep.run}", returnStatus: true)
}
