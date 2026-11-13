// Unit tests for getChangedFilesList() in Matrix.groovy, the list that decides
// whether an image with a `file:` key is rebuilt (docs/RUNS_ON_DOCKERS.md).
// Each case runs the function's own git commands in a temporary clone. A
// branch that is behind its target must list only its own changes, never the
// target's newer ones, and a change the branch makes must still be listed.
// Run with scripts/run_groovy_tests.sh, or with any Groovy 2.4+ and git from
// the repository root:
//   groovy tests/changed_files_test.groovy [path/to/Matrix.groovy]

import java.nio.file.Files
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

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

// The `sh` step runs its script with bash in the current case's clone.
File cwd = null
List commands = []
Map env = [:]
def binding = new Binding()
binding.setVariable('env', env)
binding.setVariable('currentBuild', [result: null])
binding.setVariable('error', { msg -> throw new IllegalStateException(msg.toString()) })
binding.setVariable('sh', { Map a ->
    commands << a.script.toString()
    def r = exec(cwd, ['bash', '-c', a.script.toString()])
    if (a.returnStatus) {
        return r[0]
    }
    if (r[0] != 0) {
        throw new IllegalStateException("script returned exit code ${r[0]}")
    }
    return a.returnStdout ? r[1] : null
})
def matrix = new GroovyShell(gcl, binding, cc).parse(new File(matrixPath))

List warnings = []
def logger = new Expando()
logger.debug = { String m -> }
logger.info = { String m -> }
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

println 'Using ' + exec(new File('.'), ['git', '--version'])[1].trim()
File root = Files.createTempDirectory('ci-demo-changed-files').toFile()
try {
    // History: master has B then T (T changes the image files). Each PR branch
    // is named after what it models.
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
    git(up, 'checkout', '-q', '-b', 'pr-docker', B)
    String P2 = commit(up, ['.ci/Dockerfile': 'FROM ubuntu:22.04\nRUN true\n'], 'stale PR: image change')
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

    def scenario = { String label, File clone, String rev, Map vars, List expected, boolean expectWarning = false ->
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
        boolean ok = result instanceof List && result.collect { it.toString() }.sort() == expected.sort()
        if (expectWarning) {
            ok = ok && warnings.any { it.contains('Unable to find changed file list') }
        }
        check(label, ok, "expected ${expected}, got ${result.inspect()}; commands ${commands}; warnings ${warnings}")
    }
    Map pr = [ghprbTargetBranch: 'master']

    scenario('stale PR head lists only its own change', full, P1,
             pr + [ghprbActualCommit: P1], ['src/app.c'])
    scenario('stale PR built from its merge commit lists only its own change', full, M,
             pr + [ghprbActualCommit: P1], ['src/app.c'])
    scenario('stale PR that changes the Dockerfile lists the Dockerfile only', full, P2,
             pr + [ghprbActualCommit: P2], ['.ci/Dockerfile'])
    scenario('up-to-date PR lists its change', full, P3,
             pr + [ghprbActualCommit: P3], ['src/app.c'])
    scenario('branch build without PR variables lists only the branch change', full, P1,
             [:], ['src/app.c'])
    scenario('build of the target tip falls back to the previously built commit', full, T,
             [GIT_COMMIT: T, GIT_PREV_COMMIT: B], ['.ci/Dockerfile', '.ci/install.sh'])
    scenario('without the target ref, a commit added since the last build is listed', noTarget, P1b,
             pr + [ghprbActualCommit: P1b, GIT_COMMIT: P1b, GIT_PREV_COMMIT: P1], ['.ci/Dockerfile'])
    scenario('without the target ref, a PR moved back to an older base lists only its own change', noTarget, P1,
             pr + [ghprbActualCommit: P1, GIT_COMMIT: P1, GIT_PREV_COMMIT: P1r], ['src/app.c'])
    scenario('branch build after the branch was moved back lists the rolled-back files', rewound, B,
             [GIT_COMMIT: B, GIT_PREV_COMMIT: T], ['.ci/Dockerfile', '.ci/install.sh'])
    scenario('a clone too shallow for the merge base lists nothing and warns', shallow, P1,
             pr + [ghprbActualCommit: P1], [], true)
} finally {
    root.deleteDir()
}

println "\n${passed} passed, ${failed} failed"
if (failed || passed == 0) {
    System.exit(1)
}
