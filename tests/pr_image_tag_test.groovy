// Unit tests for `pr_image_tag` in Matrix.groovy (docs/RUNS_ON_DOCKERS.md).
// With it, a PR build never pushes an image's declared tag: an image it
// rebuilds goes to <tag>-pr<ghprbPullId>, and its steps run on that image.
// Without it, and in branch builds, images are pushed to the declared tag.
// Each case runs gen_image_map(), getMatrixTasks() and buildDocker() as a
// pipeline run does, with recording Docker, registry and pod stand-ins.
// Run with scripts/run_groovy_tests.sh, or with any Groovy 2.4+ and bash
// from the repository root:
//   groovy tests/pr_image_tag_test.groovy [path/to/Matrix.groovy]

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

// The Jenkins `env` global: properties plus getEnvironment().
class FakeEnv extends LinkedHashMap {
    Map getEnvironment() {
        return new LinkedHashMap(this)
    }
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

// Docker calls, the env of each task and the pod image of each task are
// recorded; pushes also go to a log that outlives a run.
List events = []
List pushes = []
List taskEnvs = []
List podUrls = []
Set registry = [] as Set
Closure shell = null
def env = new FakeEnv()
def docker = new Expando()
docker.build = { name, args ->
    String img = name.toString()
    events << 'build ' + img
    def built = new Expando()
    built.push = { -> events << 'push ' + img; pushes << img; registry << img }
    return built
}
docker.withRegistry = { Object[] a -> a[-1].call() }
docker.image = { String name ->
    def pulled = new Expando()
    pulled.pull = { -> throw new IllegalStateException('no such image ' + name) }
    return pulled
}
def binding = new Binding()
binding.setVariable('env', env)
binding.setVariable('currentBuild', [result: null])
binding.setVariable('error', { msg -> throw new IllegalStateException(msg.toString()) })
binding.setVariable('docker', docker)
binding.setVariable('withCredentials', { List c, Closure body -> body() })
binding.setVariable('sh', { Map a -> shell(a.script.toString()) })
// withEnv sets its variables in `env` for the body, as Jenkins does.
binding.setVariable('withEnv', { List vars, Closure body ->
    List kv = vars.collect { it.toString() }
    if (kv.any { it.startsWith('job=') }) {
        taskEnvs << kv
    }
    Map saved = new LinkedHashMap(env)
    kv.each { e -> env[e.substring(0, e.indexOf('='))] = e.substring(e.indexOf('=') + 1) }
    try {
        body()
    } finally {
        env.clear()
        env.putAll(saved)
    }
})
def shellParser = new GroovyShell(gcl, binding, cc)
def matrix = shellParser.parse(new File(matrixPath))
matrix.metaClass.imageExistsInRegistry = { String img, config -> registry.contains(img) }
matrix.metaClass.runK8 = { image, branchName, config, axis -> podUrls << image.url }
// A second copy keeps the library's own imageExistsInRegistry().
def plain = shellParser.parse(new File(matrixPath))

def logger = new Expando()
logger.trace = { level, msg -> }
logger.debug = { String m -> }
logger.info = { String m -> }
logger.warn = { String m -> }
logger.error = { String m -> }

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

String SHARED = 'harbor.example.com/proj/x86_64/ub22:20261113'
String PR = SHARED + '-pr42'
Map dockerfile = [name: 'ub22', arch: 'x86_64', file: '.ci/Dockerfile', tag: '20261113']
Map prBuild = [ghprbPullId: '42', ghprbTargetBranch: 'master']

// One pipeline run: image setup, then the tasks, as startPipeline() orders them.
def run = { Map vars, List changed, Map top, List images, int setupAttempts = 1 ->
    env.clear()
    env.putAll(vars)
    events.clear()
    taskEnvs.clear()
    podUrls.clear()
    Map config = [job: 'test', registry_host: 'harbor.example.com', registry_path: '/proj',
                  kubernetes: [cloud: 'k8s'], steps: [[name: 'test', run: 'true']],
                  runs_on_dockers: images.collect { new LinkedHashMap(it) },
                  logger: logger, cFiles: changed] + top
    List built
    try {
        built = matrix.gen_image_map(config)['x86_64']
        Map tasks = [:]
        built.each { tasks += matrix.getMatrixTasks(it, config) }
        // Images are set up when any entry has a Dockerfile; the build pod is
        // retried when its agent is lost.
        if (built.any { it.file }) {
            setupAttempts.times { built.each { matrix.buildDocker(it, config) } }
        }
        tasks.values().each { it.call() }
    } catch (Throwable t) {
        return [error: t.getClass().name + ': ' + t.message]
    }
    def envOf = { String key -> taskEnvs.collect { e -> e.find { it.startsWith(key + '=') } } }
    return [events: events.clone(), pods: podUrls.clone(), url: envOf('url'), tag: envOf('tag'),
            uri: envOf('uri'), pr_url: built.collect { it.pr_url }]
}
def expect = { String label, Map got, Map want ->
    def bad = want.findAll { k, v -> got[k] != v }
    check(label, !got.error && !bad, got.error ?: "expected ${want}, got ${got.subMap(want.keySet())}")
}
Map on = [pr_image_tag: true]

registry.clear()
registry << SHARED
expect('without pr_image_tag, a PR build pushes its changed image to the declared tag',
       run(prBuild, ['.ci/Dockerfile'], [:], [dockerfile]),
       [events: ['build ' + SHARED, 'push ' + SHARED], pods: [SHARED], url: ['url=' + SHARED]])
expect('pr_image_tag: false keeps the declared tag',
       run(prBuild, ['.ci/Dockerfile'], [pr_image_tag: false], [dockerfile]),
       [events: ['build ' + SHARED, 'push ' + SHARED], pods: [SHARED]])

registry.clear()
registry << SHARED
pushes.clear()
expect('a PR that changes the Dockerfile pushes and runs on the PR tag',
       run(prBuild, ['.ci/Dockerfile'], on, [dockerfile]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR],
        tag: ['tag=20261113'], uri: ['uri=x86_64/ub22']])
expect('the same PR without the Dockerfile change runs on the declared tag again',
       run(prBuild, ['src/app.c'], on, [dockerfile]),
       [events: [], pods: [SHARED], url: ['url=' + SHARED], pr_url: [PR]])
check('neither build of that PR pushed the declared tag', pushes == [PR], "pushes ${pushes}")

registry.clear()
registry << SHARED
expect('a changed dependency selects the PR tag',
       run(prBuild, ['.ci/install.sh'], on, [dockerfile + [deps: ['.ci/install.sh']]]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR]])
expect('build_dockers=true in a PR build selects the PR tag',
       run(prBuild + [build_dockers: 'true'], [], on, [dockerfile]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR]])
expect('build_dockers=true from the matrix env selects the PR tag',
       run(prBuild, [], on + [env: [build_dockers: 'true']], [dockerfile]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR]])
expect('a matrix env build_dockers=false over the job parameter leaves the image alone',
       run(prBuild + [build_dockers: 'true'], [], on + [env: [build_dockers: 'false']],
           [dockerfile + [deps: ['.ci/install.sh']]]),
       [events: [], pods: [SHARED], url: ['url=' + SHARED]])
expect('pr_image_tag given as a string is read as a boolean',
       run(prBuild, ['.ci/Dockerfile'], [pr_image_tag: 'true'], [dockerfile]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR]])

registry.clear()
expect('an image missing from the registry is pushed under the PR tag',
       run(prBuild, [], on, [dockerfile]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR], tag: ['tag=20261113']])
check('a PR build left the declared tag missing', !registry.contains(SHARED), "registry ${registry}")

registry.clear()
expect('a retried image setup builds the PR tag once and never doubles the suffix',
       run(prBuild, [], on, [dockerfile], 2),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR], url: ['url=' + PR]])
registry.clear()
registry << SHARED
expect('a retried setup of a changed image rebuilds the same PR tag',
       run(prBuild, ['.ci/Dockerfile'], on, [dockerfile], 2),
       [events: ['build ' + PR, 'push ' + PR, 'build ' + PR, 'push ' + PR], pods: [PR]])

registry.clear()
registry << SHARED
expect('a branch build pushes the declared tag',
       run([:], ['.ci/Dockerfile'], on, [dockerfile]),
       [events: ['build ' + SHARED, 'push ' + SHARED], pods: [SHARED], url: ['url=' + SHARED], pr_url: [null]])
expect('a branch build seeds a missing declared tag',
       run([:], [], on, [dockerfile + [tag: 'new']]),
       [events: ['build harbor.example.com/proj/x86_64/ub22:new', 'push harbor.example.com/proj/x86_64/ub22:new'],
        pods: ['harbor.example.com/proj/x86_64/ub22:new']])
expect('a pull-only image beside a built one keeps its URL in a PR build',
       run(prBuild + [build_dockers: 'true'], [], on,
           [dockerfile, [name: 'tool', arch: 'x86_64', url: 'harbor.example.com/tools/lint:1.0']]),
       [events: ['build ' + PR, 'push ' + PR], pods: [PR, 'harbor.example.com/tools/lint:1.0'], pr_url: [PR, null]])

registry.clear()
expect('an explicit url with a tag and a registry port gets the suffix on its tag',
       run(prBuild, ['.ci/Dockerfile'], on, [dockerfile + [url: 'reg.example.com:5000/a/b:7.1']]),
       [events: ['build reg.example.com:5000/a/b:7.1-pr42', 'push reg.example.com:5000/a/b:7.1-pr42'],
        pods: ['reg.example.com:5000/a/b:7.1-pr42']])
expect('an explicit url without a tag is pushed as latest-pr<id>',
       run(prBuild, ['.ci/Dockerfile'], on, [dockerfile + [url: 'reg.example.com:5000/a/b']]),
       [events: ['build reg.example.com:5000/a/b:latest-pr42', 'push reg.example.com:5000/a/b:latest-pr42'],
        pods: ['reg.example.com:5000/a/b:latest-pr42']])

// imageExistsInRegistry() with the manifest check failing: its podman search
// pipeline runs with the search replaced by `tags`, and docker pull fails.
def fallbackFinds = { String img, List tags ->
    shell = { String script ->
        if (script.startsWith('docker manifest inspect ')) {
            return 1
        }
        def m = script =~ /^podman search .* 2> \/dev\/null (\|.*)$/
        if (!m.find()) {
            throw new IllegalStateException('unexpected command: ' + script)
        }
        def listing = "printf '%s\\n' " + tags.collect { "'" + it + "'" }.join(' ')
        def proc = new ProcessBuilder(['bash', '-c', listing + ' ' + m.group(1)]).start()
        proc.waitForProcessOutput(new StringBuilder(), new StringBuilder())
        return proc.exitValue()
    }
    try {
        return plain.imageExistsInRegistry(img, [logger: logger])
    } catch (Throwable t) {
        return t.getClass().name + ': ' + t.message
    }
}
def found = fallbackFinds(SHARED, ['20261113-pr42', 'latest'])
check('the registry fallback does not take a PR tag for the declared tag', found == false, "got ${found}")
found = fallbackFinds(SHARED, ['20261113-pr42', '20261113'])
check('the registry fallback finds the declared tag among PR tags', found == true, "got ${found}")

println "\n${passed} passed, ${failed} failed"
if (failed || passed == 0) {
    System.exit(1)
}
