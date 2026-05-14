#!/usr/bin/env groovy

// Matrix-side bridge to the `antivirus` var in Mellanox-lab/swx-jenkins-lib.
// Modeled on slurmCI.

int call(ctx, oneStep, config) {
    def args = (oneStep.args ?: [:]).clone()

    def libRef = args.remove('ref')
    if (libRef) {
        library(identifier: "swx-jenkins-lib@${libRef}")
    } else {
        // TEMP: load swx-jenkins-lib from the fork branch carrying the
        // antivirus var (https://github.com/Mellanox-lab/swx-jenkins-lib/pull/4).
        // Revert to library('swx-jenkins-lib') before merging.
        library('github.com/orbalayla-nvidia/swx-jenkins-lib@feat/antivirus')
    }

    if (!args || args.size() < 1) {
        ctx.reportFail(oneStep.name, 'antivirusCI: at least one arg required (path or paths)')
        return 1
    }

    // Resolve ${VAR} on String values only; lists pass through.
    for (def entry in ctx.entrySet(args)) {
        if (!(entry.value instanceof String)) continue
        def resolved = ctx.resolveTemplate(['env': env], entry.value, config)
        def pass2 = resolved
        def safety = 0
        while (pass2.contains('${') && safety++ < 20) {
            def start = pass2.indexOf('${')
            def end = pass2.indexOf('}', start)
            if (end == -1) break
            def name = pass2.substring(start + 2, end)
            def val = env."${name}"
            if (val == null) break
            pass2 = pass2.substring(0, start) + val + pass2.substring(end + 1)
        }
        args[entry.key] = pass2
    }

    def vars = []
    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)

    int rc = 0
    withEnv(vars) {
        try {
            antivirus.scan(args)
        } catch (Throwable t) {
            ctx.reportFail(oneStep.name, "antivirus.scan failed: ${t.message}")
            rc = 1
        }
    }
    return rc
}
