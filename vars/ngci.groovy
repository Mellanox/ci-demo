#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    def args = oneStep.args

    // Use SSH credentials for library loading if ngci_credentials_id is specified
    def ngciCredentialsId = config.get('ngci_credentials_id') ?: 'swx-jenkins_ssh_key'
    
    if (ngciCredentialsId) {
        ctx.println("Loading NGCI library with SSH credentials: ${ngciCredentialsId}")
        sshagent(credentials: [ngciCredentialsId]) {
            library(identifier: 'ngci@5.0.4-26',
                    retriever: modernSCM([$class: 'GitSCMSource',
                    remote: 'ssh://git-nbu.nvidia.com:12023/DevOps/Jenkins/ci_framework',
                    credentialsId: ngciCredentialsId ]))
        }
    } else {
        ctx.println("Loading NGCI library without SSH agent")
        library(identifier: 'ngci@5.0.4-26',
                retriever: modernSCM([$class: 'GitSCMSource',
                remote: 'ssh://git-nbu.nvidia.com:12023/DevOps/Jenkins/ci_framework',
                credentialsId: 'swx-jenkins_ssh_key' ]))
    }

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
