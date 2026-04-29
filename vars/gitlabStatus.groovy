#!/usr/bin/env groovy

// gitlabStatus: post commit/MR statuses to GitLab via the REST API (HTTP Request plugin).
//
// Two ways to use it:
//
// 1) Per-step flag (most common). Wrap any regular shell step:
//
//      - name: Generate failures
//        run: |
//          env > myenv.log
//          exit 1
//        gitlabStatus: true        # post running -> success/failed using step name
//
//    Or pass a map for overrides:
//
//        gitlabStatus:
//          name: 'jenkins/${arch}'
//          description: 'matrix axis ${arch}'
//
//    Connection settings come from top-level `gitlab:` config or env fallbacks:
//
//    Important: if you are using Jenkins plugin "Gitlab",
//    you need to use projectId as ${gitlabMergeRequestTargetProjectId} and
//    sha as ${gitlabMergeRequestLastCommit}
//
//      gitlab:
//        apiUrl: 'https://gitlab.com/api/v4'   # or env GITLAB_API_URL
//        projectId: 'group/sub/project'        # or env GITLAB_PROJECT_ID or ${gitlabMergeRequestTargetProjectId}
//        sha: '${GIT_COMMIT}'                  # or env GIT_COMMIT or ${gitlabMergeRequestLastCommit}
//        credentialsId: 'gitlab-token'         # or env GITLAB_TOKEN_CREDENTIALS_ID
//
// 2) Standalone action module — explicit one-shot status update:
//
//      - shell: action
//        module: gitlabStatus
//        run: updateCommitStatus
//        args:
//          state: success
//          name: 'jenkins/release'

int call(ctx, oneStep, config) {
    def args = oneStep.args ?: [:]
    if (!args || args.size() < 1) {
        ctx.reportFail(oneStep.name, 'fatal: gitlab module expects at least 1 arg')
        return 1
    }

    def stepRun = oneStep.run
    def allowedOps = ['updateCommitStatus'] as Set
    if (!allowedOps.contains(stepRun)) {
        ctx.reportFail(oneStep.name, "fatal: unsupported gitlab operation '${stepRun}'; allowed: ${allowedOps.sort().join(', ')}")
        return 1
    }

    def vars = []
    vars += ctx.toEnvVars(config, config.env)
    vars += ctx.toEnvVars(config, oneStep.env)

    int rc = 0
    withEnv(vars) {
        // Merge args with top-level `gitlab:` config and env fallbacks so
        // standalone usage doesn't have to repeat connection settings.
        def merged = buildMerged(args, config, oneStep?.name, args.state, args.description ?: '')
        resolveMerged(ctx, merged, config)
        rc = postStatus(ctx, oneStep.name, config, merged, true)
    }
    return rc
}

private Map buildMerged(stepOpts, config, oneStepName, state, String defaultDescription = '') {
    def gl = config?.gitlab ?: [:]
    def Map merged = [:]
    merged.apiUrl        = stepOpts.apiUrl        ?: gl.apiUrl        ?: env.GITLAB_API_URL
    merged.projectId     = stepOpts.projectId     ?: gl.projectId     ?: env.GITLAB_PROJECT_ID
    merged.sha           = stepOpts.sha           ?: gl.sha           ?: env.GIT_COMMIT
    merged.credentialsId = stepOpts.credentialsId ?: gl.credentialsId ?: env.GITLAB_TOKEN_CREDENTIALS_ID
    merged.name          = stepOpts.name          ?: oneStepName
    merged.targetUrl     = stepOpts.targetUrl     ?: gl.targetUrl     ?: env.BUILD_URL
    merged.description   = stepOpts.description   ?: defaultDescription
    merged.ref           = stepOpts.ref           ?: gl.ref
    merged.state         = state
    return merged
}

private void resolveMerged(ctx, Map merged, config) {
    ['apiUrl', 'projectId', 'sha', 'credentialsId', 'name', 'targetUrl', 'description', 'ref'].each { k ->
        def v = merged[k]
        if (v != null && v.toString()) {
            merged[k] = resolveValue(ctx, v.toString(), config)
        }
    }
}

// Helper for Matrix.groovy step-level `gitlabStatus` integration.
// `ctx` is the Matrix script (passed as `this` from the call site) and is
// used to access `resolveTemplate` for ${...} expansion. Returns 0 on
// success, non-zero on transport error. Failure to post is logged but
// never fails the build.
int notify(ctx, config, oneStep, String state, String defaultDescription = '') {
    def phase = 'enter'
    try {
        dbg(ctx, "phase=enter state=${state} oneStep.name=${oneStep?.name}")
        def stepOpts = (oneStep?.gitlabStatus instanceof Map) ? oneStep.gitlabStatus : [:]
        phase = 'merge';   def merged = buildMerged(stepOpts, config, oneStep?.name, state, defaultDescription)
        phase = 'resolve'; resolveMerged(ctx, merged, config)
        dbg(ctx, "post-resolve apiUrl=${merged.apiUrl} projectId=${merged.projectId} sha=${merged.sha} name=${merged.name}")
        phase = 'postStatus'
        return postStatus(ctx, oneStep?.name ?: 'gitlabStatus', config, merged, false)
    } catch (Throwable t) {
        def sw = new java.io.StringWriter()
        t.printStackTrace(new java.io.PrintWriter(sw))
        echo "gitlabStatus: notify(${state}) skipped at phase='${phase}' due to ${t.class.name}: ${t.message}\n${sw}"
        return 1
    }
}

String resolveValue(ctx, value, config) {
    if (value == null) return ''
    def s = value.toString()
    def resolved = ctx.resolveTemplate(['env': env], s, config)
    if (resolved == null) resolved = s
    def safety = 0
    while (resolved.contains('${') && safety++ < 20) {
        def start = resolved.indexOf('${')
        def end = resolved.indexOf('}', start)
        if (end == -1) break
        def varName = resolved.substring(start + 2, end)
        // accept ${env.X}, ${params.X}, or bare ${X}
        def lookup = varName.startsWith('env.') ? varName.substring(4) : varName
        def val = env."${lookup}"
        if (val == null && varName.startsWith('params.') && binding.hasVariable('params')) {
            val = params."${varName.substring(7)}"
        }
        if (val != null) {
            resolved = resolved.substring(0, start) + val + resolved.substring(end + 1)
        } else {
            break
        }
    }
    return resolved
}

// failHard=true: missing/invalid args call ctx.reportFail (used by standalone module).
// failHard=false: log and return non-zero (used by per-step notify).
private int postStatus(ctx, String stepName, config, Map args, boolean failHard) {
    def phase = 'enter'
    try {
        phase = 'unpack'
        def apiUrl        = args.apiUrl
        def projectId     = args.projectId
        def sha           = args.sha
        def state         = args.state
        def name          = args.name          ?: 'jenkins'
        def targetUrl     = args.targetUrl
        def description   = args.description   ?: ''
        def ref           = args.ref           ?: ''
        def credentialsId = args.credentialsId

        phase = 'validate'
        def allowedStates = ['pending', 'running', 'success', 'failed', 'canceled'] as Set
        def fail = { String msg ->
            if (failHard) { ctx.reportFail(stepName, msg) } else { echo "gitlabStatus: ${msg}" }
            return 1
        }
        if (!apiUrl)    return fail("missing 'apiUrl' (or env GITLAB_API_URL)")
        if (apiUrl.toString().contains('${')) return fail("'apiUrl' has unresolved template: ${apiUrl}")
        if (!apiUrl.toString().matches('^https?://.*')) return fail("'apiUrl' must start with http:// or https://, got: ${apiUrl}")
        if (!projectId) return fail("missing 'projectId' (or env GITLAB_PROJECT_ID)")
        if (projectId.toString().contains('${')) return fail("'projectId' has unresolved template: ${projectId}")
        if (!sha)       return fail("missing 'sha' (or env GIT_COMMIT)")
        if (sha.toString().contains('${')) return fail("'sha' has unresolved template: ${sha}")
        if (!state)     return fail("missing 'state'")
        if (!allowedStates.contains(state.toString())) {
            return fail("invalid state '${state}'; allowed: ${allowedStates.sort().join(', ')}")
        }
        if (!credentialsId) return fail("missing 'credentialsId' (or env GITLAB_TOKEN_CREDENTIALS_ID)")
        if (credentialsId.toString().contains('${')) return fail("'credentialsId' has unresolved template: ${credentialsId}")

        phase = 'build-url'
        def encodedProject = java.net.URLEncoder.encode(projectId.toString(), 'UTF-8')
        def baseUrl = apiUrl.toString().replaceAll('/+$', '')
        def url = "${baseUrl}/projects/${encodedProject}/statuses/${sha}"

        phase = 'build-body'
        def fields = [['state', state.toString()], ['name', name.toString()]]
        if (targetUrl)   fields << ['target_url', targetUrl.toString()]
        if (description) fields << ['description', description.toString()]
        if (ref)         fields << ['ref', ref.toString()]
        def body = fields.collect { entry ->
            "${entry[0]}=${java.net.URLEncoder.encode(entry[1], 'UTF-8')}"
        }.join('&')

        dbg(ctx, "postStatus url=${url} bodyLen=${body.length()} credId=${credentialsId}")

        phase = 'withCredentials'
        int rc = 0
        withCredentials([string(credentialsId: credentialsId, variable: 'GITLAB_TOKEN')]) {
            def token = env.GITLAB_TOKEN
            if (!token) {
                def msg = "credential '${credentialsId}' resolved to empty token"
                if (failHard) { ctx.reportFail(stepName, msg) } else { echo "gitlabStatus: ${msg}" }
                rc = 1
                return
            }
            phase = 'httpRequest'
            def quietMode = !(ctx?.isDebugMode())
            def response = timeout(time: 30, unit: 'SECONDS') {
                httpRequest(
                    httpMode: 'POST',
                    url: url,
                    customHeaders: [[name: 'PRIVATE-TOKEN', value: token, maskValue: true]],
                    contentType: 'APPLICATION_FORM',
                    requestBody: body,
                    validResponseCodes: '100:599',
                    consoleLogResponseBody: false,
                    quiet: quietMode
                )
            }
            phase = 'response'
            if (response.status >= 400) {
                def msg = "HTTP ${response.status} posting status: ${response.content}"
                if (failHard) { ctx.reportFail(stepName, msg) } else { echo "gitlabStatus: ${msg}" }
                rc = 1
            }
        }
        return rc
    } catch (Throwable t) {
        def sw = new java.io.StringWriter()
        t.printStackTrace(new java.io.PrintWriter(sw))
        def msg = "postStatus failed at phase='${phase}': ${t.class.name}: ${t.message}\n${sw}"
        if (failHard) { ctx.reportFail(stepName, msg) } else { echo "gitlabStatus: ${msg}" }
        return 1
    }
}

private void dbg(ctx, String msg) {
    try {
        if (ctx?.isDebugMode()) { echo "gitlabStatus[dbg]: ${msg}" }
    } catch (ignored) { /* ctx without isDebugMode — silently skip */ }
}
