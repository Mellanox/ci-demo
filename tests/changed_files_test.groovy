// Unit tests for getChangedFilesList() and buildImage(config, image) in
// Matrix.groovy, which decide whether an image with a `file:` key is rebuilt
// (docs/RUNS_ON_DOCKERS.md). Each case runs the function's own git commands in
// a temporary clone, then the image decision with recording docker calls. A
// branch that is behind its target must not rebuild for the target's changes,
// a change it makes must still rebuild, and a PR whose changes cannot be known
// must not guess.
// Run with scripts/run_groovy_tests.sh, or with any Groovy 2.4+ and git from
// the repository root:
//   groovy tests/changed_files_test.groovy [path/to/Matrix.groovy]

import java.nio.file.Files
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

class BuildFailure extends RuntimeException {
    BuildFailure(String msg) { super(msg) }
}

List exec(File dir, List cmd) {
    def proc = new ProcessBuilder(cmd.collect { it.toString() }).directory(dir).start()
    def out = new StringBuilder()
    def err = new StringBuilder()
    proc.waitForProcessOutput(out, err)
    return [proc.exitValue(), out.toString(), err.toString()]
}

String git(File dir, String... argv) {
    List cmd = ['git', '-c', 'user.name=ci-demo-test', '-c', 'user.email=ci-demo-test@example.com',
                '-c', 'init.defaultBranch=master', '-c', 'advice.detachedHead=false']
    cmd.addAll(argv.toList())
    def r = exec(dir, cmd)
    if (r[0] != 0) {
        throw new IllegalStateException("git ${argv.join(' ')} failed in ${dir}: ${r[2]}")
    }
    return r[1].trim()
}

String commit(File repo, Map files, String msg) {
    files.each { path, text ->
        def f = new File(repo, path)
        f.parentFile.mkdirs()
        f.text = text
        git(repo, 'add', path)
    }
    git(repo, 'commit', '-q', '-m', msg)
    return git(repo, 'rev-parse', 'HEAD')
}

String matrixPath = args.length > 0 ? args[0] : 'src/com/mellanox/cicd/Matrix.groovy'

// Stubs for the Jenkins classes that Matrix.groovy needs at compile time.
def gcl = new GroovyClassLoader(this.class.classLoader)
[
    'com.cloudbees.groovy.cps.NonCPS': 'package com.cloudbees.groovy.cps\nimport java.lang.annotation.*\n@Retention(RetentionPolicy.RUNTIME) @interface NonCPS {}',
    'jenkins.model.Jenkins': 'package jenkins.model\nclass Jenkins {}',
].each { name, src ->
    try {
        gcl.loadClass(name)
    } catch (ClassNotFoundException e) {
        gcl.parseClass(src)
    }
}
def cc = new CompilerConfiguration()
def imports = new ImportCustomizer()
imports.addImports('com.cloudbees.groovy.cps.NonCPS')
cc.addCompilationCustomizers(imports)

// The `sh` step runs its script with bash in the current case's clone; the
// registry checks answer from `registryHasImage`. `docker` records its calls.
File cwd = null
List commands = []
Map env = [:]
boolean registryHasImage = true
List dockerCalls = []
def binding = new Binding()
binding.setVariable('env', env)
binding.setVariable('currentBuild', [result: null])
binding.setVariable('error', { msg -> throw new BuildFailure(msg.toString()) })
binding.setVariable('sh', { Map a ->
    String script = a.script.toString()
    commands << script
    if (script.startsWith('docker manifest inspect')) {
        return registryHasImage ? 0 : 1
    }
    if (script.startsWith('podman search')) {
        return 1
    }
    def r = exec(cwd, ['bash', '-c', script])
    if (a.returnStatus) {
        return r[0]
    }
    if (r[0] != 0) {
        throw new IllegalStateException("script returned exit code ${r[0]}")
    }
    return a.returnStdout ? r[1] : null
})
def docker = new Expando()
docker.build = { img, buildArgs ->
    dockerCalls << 'build ' + img
    def built = new Expando()
    built.push = { -> dockerCalls << 'push ' + img }
    return built
}
docker.image = { img ->
    def pulled = new Expando()
    pulled.pull = { ->
        if (!registryHasImage) {
            throw new IllegalStateException('no such image')
        }
        dockerCalls << 'pull ' + img
    }
    return pulled
}
binding.setVariable('docker', docker)
def matrix = new GroovyShell(gcl, binding, cc).parse(new File(matrixPath))

List warnings = []
def logger = new Expando()
logger.debug = { String m -> }
logger.info = { String m -> }
logger.trace = { level, m -> }
logger.warn = { String m -> warnings << m }

int passed = 0
int failed = 0
def check = { String label, ok, detail = '' ->
    if (ok) {
        passed++
        println 'PASS ' + label
    } else {
        failed++
        println 'FAIL ' + label + (detail ? ' -- ' + detail : '')
    }
}

// Runs buildImage(config, image) for an image of `.ci/Dockerfile`: `exists`
// and `missing` say whether the registry has it, `deps` adds a dependency on
// `.ci/install.sh`, `forced` sets build_dockers=true. Returns the outcome:
// build, none or reject.
def build = { List cFiles, Map vars, String how ->
    env.clear()
    env.putAll(vars)
    if (how == 'forced') {
        env.build_dockers = 'true'
    }
    registryHasImage = how != 'missing'
    dockerCalls.clear()
    Map image = [name: 'test', filename: '.ci/Dockerfile', build_args: '', url: 'registry.example/test:1',
                 deps: how == 'deps' ? ['.ci/install.sh'] : null]
    String outcome
    String message = ''
    try {
        matrix.buildImage([logger: logger, cFiles: cFiles], image)
        if (dockerCalls.contains('build ' + image.url) && dockerCalls.contains('push ' + image.url)) {
            outcome = 'build'
        } else {
            outcome = dockerCalls.any { it.startsWith('build ') || it.startsWith('push ') } ? 'partial' : 'none'
        }
    } catch (BuildFailure e) {
        message = e.message
        boolean ours = message.contains('Unable to tell whether this PR changes .ci/Dockerfile')
        outcome = dockerCalls.any { it.startsWith('build ') } || !ours ? 'other failure' : 'reject'
    } catch (Throwable t) {
        outcome = 'error'
        message = t.getClass().name + ': ' + t.message
    }
    return [outcome: outcome, calls: new ArrayList(dockerCalls), message: message]
}

println 'Using ' + exec(new File('.'), ['git', '--version'])[1].trim()
File root = Files.createTempDirectory('ci-demo-changed-files').toFile()
try {
    // History: master has B then T (T changes the image files). Each other
    // branch is named after what it models.
    File up = new File(root, 'upstream')
    up.mkdirs()
    git(up, 'init', '-q')
    String B = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:22.04\n', '.ci/install.sh': 'echo v1\n',
                           'src/app.c': 'int main(void) { return 0; }\n'], 'base')
    String T = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:24.04\n', '.ci/install.sh': 'echo v2\n'],
                      'target: newer image')
    git(up, 'checkout', '-q', '-b', 'pr-code', B)
    String P1 = commit(up, ['src/app.c': 'int main(void) { return 1; }\n'], 'stale PR: code change')
    git(up, 'checkout', '-q', '-b', 'pr-update', P1)
    String P1b = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:22.04\nRUN true\n'], 'stale PR: later image change')
    git(up, 'checkout', '-q', '-b', 'pr-revert', P1b)
    String P1c = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:22.04\n'], 'branch: revert the image change')
    git(up, 'checkout', '-q', '-b', 'pr-docker', B)
    String P2 = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:22.04\nRUN true\n'], 'stale PR: image change')
    git(up, 'checkout', '-q', '-b', 'pr-docker-more', P2)
    String P2b = commit(up, ['src/app.c': 'int main(void) { return 2; }\n'], 'stale PR: code change after its image change')
    git(up, 'checkout', '-q', '-b', 'pr-fresh', T)
    String P3 = commit(up, ['src/app.c': 'int main(void) { return 3; }\n'], 'current PR: code change')
    git(up, 'checkout', '-q', '-b', 'pr-rebased', T)
    git(up, 'cherry-pick', P1)
    String P1r = git(up, 'rev-parse', 'HEAD')
    git(up, 'checkout', '-q', '-b', 'pr-merge', T)
    git(up, 'merge', '-q', '--no-ff', '-m', 'merge stale PR into the target', 'pr-code')
    String M = git(up, 'rev-parse', 'HEAD')
    git(up, 'checkout', '-q', 'master')

    File full = new File(root, 'full')
    git(root, 'clone', '-q', up.absolutePath, full.absolutePath)
    // A clone without the target's remote-tracking ref, as a refspec that
    // fetches only PR refs leaves it.
    File noTarget = new File(root, 'no-target')
    git(root, 'clone', '-q', up.absolutePath, noTarget.absolutePath)
    git(noTarget, 'update-ref', '-d', 'refs/remotes/origin/master')
    // A clone after the target branch was moved back from T to B.
    File rewound = new File(root, 'rewound')
    git(root, 'clone', '-q', up.absolutePath, rewound.absolutePath)
    git(rewound, 'update-ref', 'refs/remotes/origin/master', B)
    // A clone too shallow to reach the merge base of the target and the PR.
    File shallow = new File(root, 'shallow')
    git(root, 'clone', '-q', '--depth', '1', '--no-single-branch', 'file://' + up.absolutePath, shallow.absolutePath)
    // Two commits deep on one PR branch and on the target: the PR's previous
    // commit is in the clone, its merge base with the target is not.
    File shallow2 = new File(root, 'shallow2')
    git(root, 'clone', '-q', '--depth', '2', '--single-branch', '--branch', 'pr-docker-more',
        'file://' + up.absolutePath, shallow2.absolutePath)
    git(shallow2, 'fetch', '-q', '--depth', '2', 'origin', '+refs/heads/master:refs/remotes/origin/master')

    // Checks the list (null: unknown, with a warning), then each named image
    // decision against its expected outcome.
    def scenario = { String label, File clone, String rev, Map vars, List expected, Map builds ->
        git(clone, 'checkout', '-q', '--detach', rev)
        cwd = clone
        env.clear()
        env.putAll(vars)
        commands.clear()
        warnings.clear()
        def result
        try {
            result = matrix.getChangedFilesList([logger: logger])
        } catch (Throwable t) {
            result = t.getClass().name + ': ' + t.message
        }
        boolean ok
        if (expected == null) {
            ok = result == null && !warnings.isEmpty()
        } else {
            ok = result instanceof List && result.collect { it.toString() }.sort() == expected.sort()
        }
        check(label + (expected == null ? ': unknown' : ': ' + expected), ok,
              "got ${result.inspect()}; commands ${commands}; warnings ${warnings}")
        builds.each { how, want ->
            def r = build(result instanceof List ? result : null, vars, how)
            check("${label}: ${how} image -> ${want}", r.outcome == want, r.toString())
        }
    }
    Map pr = [ghprbTargetBranch: 'master']
    String gone = '0123456789abcdef0123456789abcdef01234567'

    // PRs with full history: only the PR's own changes count.
    scenario('stale PR head', full, P1,
             pr + [ghprbActualCommit: P1], ['src/app.c'], [exists: 'none', deps: 'none'])
    scenario('stale PR built from its merge commit', full, M,
             pr + [ghprbActualCommit: P1], ['src/app.c'], [deps: 'none'])
    scenario('stale PR that changes the Dockerfile', full, P2,
             pr + [ghprbActualCommit: P2], ['.ci/Dockerfile'], [exists: 'build', deps: 'build'])
    scenario('up-to-date PR', full, P3,
             pr + [ghprbActualCommit: P3], ['src/app.c'], [exists: 'none'])

    // PRs without the target ref fall back to their previous build.
    scenario('no target ref, PR gained an image change since its last build', noTarget, P1b,
             pr + [ghprbActualCommit: P1b, GIT_COMMIT: P1b, GIT_PREV_COMMIT: P1], ['.ci/Dockerfile'],
             [exists: 'build'])
    scenario('no target ref, PR moved back to an older base', noTarget, P1,
             pr + [ghprbActualCommit: P1, GIT_COMMIT: P1, GIT_PREV_COMMIT: P1r], ['src/app.c'],
             [exists: 'none'])
    scenario('no target ref and no previous build, as before', noTarget, P1,
             pr + [ghprbActualCommit: P1], [], [exists: 'none', deps: 'none'])
    scenario('no target ref, previous commit not in the clone, as before', noTarget, P1,
             pr + [ghprbActualCommit: P1, GIT_COMMIT: P1, GIT_PREV_COMMIT: gone], [], [exists: 'none', deps: 'none'])

    // PRs whose clone does not reach a merge base: unknown, so no guess.
    scenario('shallow clone, stale PR', shallow, P1,
             pr + [ghprbActualCommit: P1], null,
             [exists: 'reject', deps: 'reject', missing: 'build', forced: 'build'])
    scenario('shallow clone, PR that changes the Dockerfile', shallow, P2,
             pr + [ghprbActualCommit: P2], null, [exists: 'reject', deps: 'reject', forced: 'build'])
    scenario('shallow clone, PR that changes the Dockerfile, previous build on the target', shallow, P2,
             pr + [ghprbActualCommit: P2, GIT_COMMIT: P2, GIT_PREV_COMMIT: T], null, [deps: 'reject'])
    scenario('shallow clone, repeated build of a PR that changes the Dockerfile', shallow, P2,
             pr + [ghprbActualCommit: P2, GIT_COMMIT: P2, GIT_PREV_COMMIT: P2], null, [exists: 'reject'])
    scenario('shallow clone, PR built one commit after its image change', shallow2, P2b,
             pr + [ghprbActualCommit: P2b, GIT_COMMIT: P2b, GIT_PREV_COMMIT: P2], null, [exists: 'reject'])

    // Branch builds: changes against the target plus those since the previous build.
    scenario('branch build behind the target, no previous build', full, P1,
             [:], ['src/app.c'], [exists: 'none'])
    scenario('build of the target tip', full, T,
             [GIT_COMMIT: T, GIT_PREV_COMMIT: B], ['.ci/Dockerfile', '.ci/install.sh'], [exists: 'build'])
    scenario('build after the target branch was moved back', rewound, B,
             [GIT_COMMIT: B, GIT_PREV_COMMIT: T], ['.ci/Dockerfile', '.ci/install.sh'], [exists: 'build'])
    scenario('build after another branch was moved back', full, P1,
             [GIT_COMMIT: P1, GIT_PREV_COMMIT: P1b], ['.ci/Dockerfile', 'src/app.c'], [exists: 'build'])
    scenario('build after a commit reverted the image change', full, P1c,
             [GIT_COMMIT: P1c, GIT_PREV_COMMIT: P1b], ['.ci/Dockerfile', 'src/app.c'], [deps: 'build'])
    scenario('build whose previous commit is not in the clone', full, P1,
             [GIT_COMMIT: P1, GIT_PREV_COMMIT: gone], null, [exists: 'build'])
    scenario('shallow clone, branch build without a previous build', shallow, P1,
             [:], null, [exists: 'build'])
} finally {
    root.deleteDir()
}

println "\n${passed} passed, ${failed} failed"
if (failed || passed == 0) {
    System.exit(1)
}
