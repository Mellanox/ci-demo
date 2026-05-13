#!/usr/bin/env groovy

int call(ctx, oneStep, config) {
    def args = (oneStep.args ?: [:]).clone()

    for (def entry in ctx.entrySet(args)) {
        args[entry.key] = ctx.resolveTemplate(['env': env], entry.value.toString(), config)
    }

    def credentialsId = args.credentials_id
    if (!credentialsId) {
        ctx.reportFail(oneStep.name, "fatal: nvbugs module requires 'credentials_id' arg (Jenkins string credential holding the NVBugs Bearer token)")
        return 1
    }

    def moduleId = args.module_id
    def moduleName = args.module_name
    if (!moduleId && !moduleName) {
        ctx.reportFail(oneStep.name, "fatal: nvbugs module requires 'module_id' or 'module_name' arg")
        return 1
    }
    if (moduleId && moduleName) {
        ctx.reportFail(oneStep.name, "fatal: nvbugs module: pass either 'module_id' OR 'module_name', not both")
        return 1
    }

    def onEmailMismatch = args.on_email_mismatch ?: 'warn'
    def onBugNotInModule = args.on_bug_not_in_module ?: 'fail'
    def apiUrl = args.api_url ?: ''

    // Commit info comes from Jenkins changeSets - no git binary or ghprb required.
    def commitMsg = ''
    def authorEmail = ''
    def commitSha = env.GIT_COMMIT ?: ''
    def changeSets = currentBuild.changeSets ?: []
    if (changeSets.size() > 0) {
        def lastSet = changeSets[changeSets.size() - 1]
        def items = lastSet.items
        if (items && items.length > 0) {
            def latest = items[items.length - 1]
            commitMsg = latest.msg ?: ''
            authorEmail = latest.authorEmail ?: ''
            if (!commitSha) commitSha = latest.commitId ?: ''
        }
    }
    if (!commitMsg) {
        ctx.reportFail(oneStep.name, "fatal: nvbugs module could not read commit message from currentBuild.changeSets (empty - retriggered build with no SCM changes?)")
        return 1
    }

    def matcher = (commitMsg =~ /(?i)nvbug:\s*(\d+)/)
    if (!matcher.find()) {
        ctx.reportFail(oneStep.name, "fatal: no 'nvbug: <id>' tag found in latest commit message")
        return 1
    }
    def bugId = matcher.group(1)

    def scriptContent = libraryResource 'actions/check_nvbugs.py'
    def scriptPath = '.ci/actions/check_nvbugs.py'
    sh(script: "mkdir -p .ci/actions", label: "create .ci/actions dir")
    writeFile file: scriptPath, text: scriptContent
    sh(script: "chmod +x ${scriptPath}", label: "chmod +x ${scriptPath}")

    // Pass user-controlled values through env vars to avoid shell injection
    // via crafted values (e.g. a malicious git author email with shell
    // metacharacters). Double-quoted "$VAR" shell expansion does NOT re-parse
    // the value for command substitution, so this is safe regardless of
    // contents.
    def stepEnv = [
        "NVBUG_BUG_ID=${bugId}",
        "NVBUG_AUTHOR_EMAIL=${authorEmail}",
        "NVBUG_COMMIT_SHA=${commitSha}",
        "NVBUG_MODULE_ID=${moduleId ?: ''}",
        "NVBUG_MODULE_NAME=${moduleName ?: ''}",
        "NVBUG_ON_EMAIL_MISMATCH=${onEmailMismatch}",
        "NVBUG_ON_BUG_NOT_IN_MODULE=${onBugNotInModule}",
        "NVBUG_API_URL=${apiUrl}",
    ]

    def cli = "python3 ${scriptPath}"
    cli += ' --bug-id="$NVBUG_BUG_ID"'
    cli += ' --author-email="$NVBUG_AUTHOR_EMAIL"'
    cli += ' --on-email-mismatch="$NVBUG_ON_EMAIL_MISMATCH"'
    cli += ' --on-bug-not-in-module="$NVBUG_ON_BUG_NOT_IN_MODULE"'
    if (commitSha)  cli += ' --commit-sha="$NVBUG_COMMIT_SHA"'
    if (moduleId)   cli += ' --module-id="$NVBUG_MODULE_ID"'
    if (moduleName) cli += ' --module-name="$NVBUG_MODULE_NAME"'
    if (apiUrl)     cli += ' --api-url="$NVBUG_API_URL"'

    def vars = []
    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)
    vars += stepEnv

    int rc = 0
    withEnv(vars) {
        withCredentials([string(credentialsId: credentialsId, variable: 'NVBUGS_API_TOKEN')]) {
            rc = sh(script: cli, returnStatus: true)
        }
    }
    return rc
}
