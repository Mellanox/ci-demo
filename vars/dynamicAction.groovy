#!/usr/bin/env groovy


def call(List args) {

    println("==>DynamicAction(" + args + ")")

    if (args.size() < 1) {
        println("fatal: DynamicAction() expects at least 1 parameter")
        sh("false")
    }
    def actionName = args[0]
    def actionScript = libraryResource "actions/${actionName}"
    def toFile = new File (env.WORKSPACE + "/cidemo_" + actionName)

    println("action file: " + toFile.getAbsolutePath())
    writeFile(file: toFile.getAbsolutePath(), text: actionScript)
    sh("chmod +x " + toFile.getAbsolutePath())

    def cmd = toFile.getAbsolutePath()
    if (args.size() > 1) {
        cmd += " " + args.subList(1,args.size()).collect{ "'" + it + "'"}.join(" ")
    }
    println("Running " + cmd)
    sh(cmd)
    return;
}
