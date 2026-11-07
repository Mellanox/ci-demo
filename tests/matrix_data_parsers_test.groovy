// Unit tests for the data parsers in Matrix.groovy: step selectors
// (containerSelector, agentSelector) and kubernetes.imagePullSecrets. Both are
// read as data; Groovy code in a matrix file must fail the build as a config
// error and must never run.
// Run with scripts/run_groovy_tests.sh, or with any Groovy 2.4+ from the
// repository root:
//   groovy tests/matrix_data_parsers_test.groovy [path/to/Matrix.groovy]

import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer

class ConfigError extends RuntimeException {
    ConfigError(String msg) { super(msg) }
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

// reportFail() ends in the `error` step; here it throws ConfigError.
def binding = new Binding()
binding.setVariable('currentBuild', [result: null])
binding.setVariable('error', { msg -> throw new ConfigError(msg.toString()) })
def matrix = new GroovyShell(gcl, binding, cc).parse(new File(matrixPath))

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
// [value: result], [config: message] for a config error, or [unexpected: ...]
// for any other exception.
def attempt = { Closure c ->
    try {
        return [value: c()]
    } catch (ConfigError e) {
        return [config: e.message]
    } catch (Throwable t) {
        return [unexpected: t.getClass().name + ': ' + t.message]
    }
}
// Value plus exact type, so that 1 (Integer) never equals '1' or 1L.
def typed
typed = { v ->
    if (v instanceof Map) {
        return v.collectEntries { k, x -> [(k): typed(x)] }
    }
    if (v instanceof List) {
        return v.collect { typed(it) }
    }
    return v == null ? 'null' : v.toString() + ':' + v.getClass().simpleName
}
def show = { v -> v instanceof String && v.length() > 60 ? v.take(20) + "... (${v.length()} chars)" : v.inspect() }

// Every payload sets this system property if it runs.
String MARK = 'ci_demo_162'
def ranCode = { -> System.getProperty(MARK) != null }

def logger = new Expando()
logger.trace = { level, msg -> }
def configWith = { kube -> [logger: logger, kubernetes: kube] }

// ---------------------------------------------------------------- selectors
// The selector forms used in this repository's docs and .ci/ files, and other
// data forms. The expected values are what the former Groovy evaluation returned.
def selectorCases = [
    "{name: 'ubuntu24.04'}"                      : [name: 'ubuntu24.04'],
    "{name:'x'}"                                 : [name: 'x'],
    '{name:"x"}'                                 : [name: 'x'],
    '{category:"x"}'                             : [category: 'x'],
    "{arch: 'x'}"                                : [arch: 'x'],
    "{category:'os',variant:1}"                  : [category: 'os', variant: 1],
    "{name: 'blackduck'}"                        : [name: 'blackduck'],
    "{name: 'builder'}"                          : [name: 'builder'],
    "{name: 'ubuntu20.10.buildah'}"              : [name: 'ubuntu20.10.buildah'],
    "{nodeLabel: 'hpc-test-node-inbox'}"         : [nodeLabel: 'hpc-test-node-inbox'],
    "{name:'centos7-7', variant:1}"              : [name: 'centos7-7', variant: 1],
    "{name:'ubuntu2204', variant:1}"             : [name: 'ubuntu2204', variant: 1],
    "{name:'ubuntu16-4',variant:1}"              : [name: 'ubuntu16-4', variant: 1],
    "{category:'tool', name: 'blackduck', variant:1}": [category: 'tool', name: 'blackduck', variant: 1],
    "{name: 'ubuntu.*', arch: 'x86_64|aarch64'}" : [name: 'ubuntu.*', arch: 'x86_64|aarch64'],
    "{enabled: true, debug: false}"              : [enabled: true, debug: false],
    "{n: -2, z: 0}"                              : [n: -2, z: 0],
    "{n: 3000000000}"                            : [n: 3000000000L],
    "{n: 99999999999999999999}"                  : [n: 99999999999999999999G],
    "name: 'x'"                                  : [name: 'x'],
    "  { name : 'x' ,\n  variant : 2 }  "        : [name: 'x', variant: 2],
    "{name: 'x',}"                               : [name: 'x'],
    "{name: 'x', name: 'y'}"                     : [name: 'y'],
    "{name: 'a\\\\.b'}"                          : [name: 'a\\.b'],
    "{name: 'it\\'s'}"                           : [name: "it's"],
    '{name: "say \\"hi\\""}'                     : [name: 'say "hi"'],
    '{name: "x\\$"}'                             : [name: 'x$'],
    "{name: 'x\$'}"                              : [name: 'x$'],
    "{name: ''}"                                 : [name: ''],
]
selectorCases.each { s, expected ->
    def r = attempt { matrix.toStringMap(s) }
    check("selector ${s.inspect()} -> ${expected}", r.containsKey('value') && typed(r.value) == typed(expected), r)
}
// Intentional difference: the former evaluation removed every brace, also inside values.
def braces = attempt { matrix.toStringMap("{name: 'a{2}'}") }
check("selector value keeps its braces", braces.value == [name: 'a{2}'], braces)

// List selectors and matching keep their meaning.
def axis = [name: 'centos7-7', variant: 1, arch: 'x86_64']
[
    ["{name:'centos7-7', variant:1}", true],
    ["{name:'centos7-7', variant:2}", false],
    ["{name: 'centos.*', arch: 'x86_64'}", true],
    ["{variant: '1'}", true],
    ["{debug: true}", false],
    [["{name:'ubuntu'}", "{name:'centos7-7', variant:1}"], true],
    [["{name:'ubuntu'}", "{variant: 3}"], false],
].each { sel, expected ->
    def r = attempt { matrix.matchMapEntry(matrix.stringToList(sel), axis) }
    check("match ${sel.inspect()} on ${axis} -> ${expected}", r.containsKey('value') && r.value == expected, r)
}

// Code, and anything else that is not plain data, is a config error and runs nothing,
// including a payload that would run code before failing.
def selectorRejects = [
    "{name: 'x'.toUpperCase()}",
    "{name: System.setProperty('ci_demo_162', 'ran')}",
    "{name: System.setProperty('ci_demo_162', 'ran'), arch: noSuchVariable}",
    '{name: "${System.setProperty(\'ci_demo_162\', \'ran\')}"}',
    "name: 'x'] ; System.setProperty('ci_demo_162', 'ran') ; [",
    "{name: 'x', arch: [System.setProperty('ci_demo_162', 'ran')]}",
    "{}",
    "   ",
    "{name}",
    "{name: x}",
    "{name: 1.5}",
    "{name: null}",
    "{name: [1, 2]}",
    "{name: 010}",
    "{'name': 'x'}",
    "{name: 'a' arch: 'b'}",
    "{name: 'a'}, {name: 'b'}",
    "{name: 'a'",
    "[name: 'a']",
    "{name: 'a\\d'}",
    '{name: "a$b"}',
    "{name: 'a',,}",
]
selectorRejects.each { s ->
    System.clearProperty(MARK)
    def r = attempt { matrix.toStringMap(s) }
    check("selector ${s.inspect()} is a config error", r.config?.contains('Invalid selector'), r)
    check("selector ${s.inspect()} ran no code", !ranCode(), 'the payload ran')
}
System.clearProperty(MARK)
def listResult = attempt { matrix.stringToList(["{name: 'ok'}", "{name: System.setProperty('ci_demo_162', 'ran')}"]) }
check('list selector with a code element is a config error', listResult.config?.contains('Invalid selector'), listResult)
check('list selector with a code element ran no code', !ranCode(), 'the payload ran')

// --------------------------------------------------------- imagePullSecrets
def name253 = 'a' * 253
def secretCases = [
    [['a', 'b.c-d'], ['a', 'b.c-d']],
    [['my-secret'], ['my-secret']],
    [[], []],
    ['[]', []],
    ['[ ]', []],
    ['', []],
    [null, []],
    ["['my-registry-secret']", ['my-registry-secret']],
    ["['secret-1', 'secret-2', 'secret-3']", ['secret-1', 'secret-2', 'secret-3']],
    ['[a, b]', ['a', 'b']],
    ['["a", \'b\', c]', ['a', 'b', 'c']],
    ["['a','b',]", ['a', 'b']],
    ['my-secret', ['my-secret']],
    ['  my-secret  ', ['my-secret']],
    [name253, [name253]],
]
secretCases.each { input, expected ->
    def r = attempt { matrix.parseImagePullSecrets(input) }
    check("imagePullSecrets ${show(input)} -> ${show(expected)}", r.containsKey('value') && r.value == expected, r)
}

def secretRejects = [
    "['a'].each{ println 'x' }",
    "[System.setProperty('ci_demo_162', 'ran')]",
    "System.setProperty('ci_demo_162', 'ran')",
    "['a', System.setProperty('ci_demo_162', 'ran')]",
    "[System.setProperty('ci_demo_162', 'ran'), noSuchVariable]",
    "['A']",
    "['a_b']",
    "['-a']",
    "['a-']",
    '[a,,b]',
    '[,]',
    "'a'",
    'a,b',
    'a' * 254,
    5,
    [name: 'a'],
    [1],
    ['A'],
    [null],
    [''],
    [['a', 'b']],
    ["['a', 'b']"],
    [[name: 'a']],
]
secretRejects.each { input ->
    System.clearProperty(MARK)
    def r = attempt { matrix.parseImagePullSecrets(input) }
    check("imagePullSecrets ${show(input)} is a config error", r.config?.contains('imagePullSecrets'), r)
    check("imagePullSecrets ${show(input)} ran no code", !ranCode(), 'the payload ran')
}

// The pod builders read the value with readImagePullSecrets(config), through
// getConfigVal(): a one-item YAML list must reach the parser as a list.
[
    [[imagePullSecrets: ['my-secret']], ['my-secret']],
    [[imagePullSecrets: ['a', 'b']], ['a', 'b']],
    [[imagePullSecrets: "['a', 'b']"], ['a', 'b']],
    [[imagePullSecrets: 'my-secret'], ['my-secret']],
    [[imagePullSecrets: []], []],
    [[imagePullSecrets: null], []],
    [[:], []],
].each { kube, expected ->
    def r = attempt { matrix.readImagePullSecrets(configWith(kube)) }
    check("kubernetes ${kube.inspect()} -> imagePullSecrets ${expected}", r.containsKey('value') && r.value == expected, r)
}
[
    [imagePullSecrets: [null]],
    [imagePullSecrets: ['']],
    [imagePullSecrets: [['a', 'b']]],
    [imagePullSecrets: ["['a', 'b']"]],
    [imagePullSecrets: [123]],
    [imagePullSecrets: ["System.setProperty('ci_demo_162', 'ran')"]],
].each { kube ->
    System.clearProperty(MARK)
    def r = attempt { matrix.readImagePullSecrets(configWith(kube)) }
    check("kubernetes ${kube.inspect()} is a config error", r.config?.contains('imagePullSecrets'), r)
    check("kubernetes ${kube.inspect()} ran no code", !ranCode(), 'the payload ran')
}
System.clearProperty(MARK)

// ------------------------------------------------------- helper return shape
def shape = attempt { matrix.parseSelector('{name: x}') }
check('parseSelector returns an error message, not an exception', shape.value instanceof String, shape)
shape = attempt { matrix.parseImagePullSecretNames('[A]') }
check('parseImagePullSecretNames returns an error message, not an exception', shape.value instanceof String, shape)
check('isK8sSecretName accepts a 253-char name', attempt { matrix.isK8sSecretName(name253) }.value == true)
check('isK8sSecretName rejects a 254-char name', attempt { matrix.isK8sSecretName('a' * 254) }.value == false)
check('isK8sSecretName rejects a non-string', attempt { matrix.isK8sSecretName(5) }.value == false)

println "\n${passed} passed, ${failed} failed"
if (failed) {
    System.exit(1)
}
