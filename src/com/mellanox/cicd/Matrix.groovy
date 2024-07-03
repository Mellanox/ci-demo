#!/usr/bin/groovy
/* groovylint-disable BlockEndsWithBlankLine, BlockStartsWithBlankLine, ClassStartsWithBlankLine, CompileStatic, DuplicateListLiteral, DuplicateNumberLiteral, DuplicateStringLiteral, FactoryMethodName, FieldTypeRequired, GStringExpressionWithinString, ImplicitClosureParameter, Instanceof, LineLength, MethodCount, MethodParameterTypeRequired, MethodReturnTypeRequired, NestedForLoop, ParameterCount, ParameterName, PrintStackTrace, SpaceAroundOperator, TrailingWhitespace, UnnecessaryGString, UnnecessaryGetter, UnnecessaryNullCheck, UnusedMethodParameter, VariableTypeRequired */
/* groovylint-disable ConsecutiveBlankLines, ImplicitReturnStatement */
/* groovylint-disable LineLength, NoDef, UnnecessarySemicolon, VariableName */
package com.mellanox.cicd;

import jenkins.model.Jenkins

class Logger {
    def ctx
    def cat
    def traceLevel

    Logger(ctx) {
        this.ctx = ctx
        this.cat = "matrix_job"
        this.traceLevel = ctx.getDebugLevel()
    }
    def info(String message) {
        this.ctx.echo this.cat + " INFO: ${message}"
    }

    def error(String message) {
        this.ctx.echo this.cat + " ERROR: ${message}"
    }

    def warn(String message) {
        this.ctx.echo this.cat + " WARN: ${message}"
    }

    def debug(String message) {
        if (this.ctx.isDebugMode()) {
            this.ctx.echo this.cat + " DEBUG: ${message}"
        }
    }

    def trace(int level, String message) {
        if (level <= this.traceLevel) {
            this.ctx.echo this.cat + " TRACE[${level}]: ${message}"
        }
    }

}
 
@NonCPS
List getMatrixAxes(matrix_axes) {
    List axes = []
    matrix_axes.each { axis, values ->
        List axisList = []
        values.each { value ->
            axisList << [(axis): value]
        }
        axes << axisList
    }
    // calculate cartesian product
    axes.combinations()*.sum()
}

// hack to avoid Serializble errors as intermediate access to entrySet returns non-serializable objects

@NonCPS 
def entrySet(m) {
    m.collect { k, v -> [key: k, value: v] }
}


def run_shell(cmd, title, retOut=false) {
    def text = ""
    def rc
    def err = null
    try {
        if (retOut) {
            text = sh(script: cmd, label: title, returnStdout: true)
            rc = 0
        } else {
            rc = sh(script: cmd, label: title, returnStatus: true)
        }

    } catch (e) {
        err = e
        org.codehaus.groovy.runtime.StackTraceUtils.printSanitizedStackTrace(e)
    }
    return ['text': text, 'rc': rc, 'exception': err]
}

def run_step_shell(image, cmd, title, oneStep, config) {

    def vars = []
    vars += toEnvVars(config, config.env)
    vars += toEnvVars(config, oneStep.env)

    def names = ['registry_host', 'registry_path', 'job']
    for (int i=0; i<names.size(); i++) {
        vars.add(names[i] + "=" + config.get(names[i]) ?: '')
    }

    withEnv(vars) {
        def ret = run_shell(cmd, title)

        if (ret.rc != 0) {
            if (oneStep["onfail"] != null) {
                run_shell(oneStep.onfail, "onfail command for ${title}")
            }
        }

        if (oneStep["always"] != null) {
            run_shell(oneStep.always, "always command for ${title}")
        }

        attachResults(config, oneStep, ret)
        attachHTML(image, config, oneStep)

        if (ret.rc != 0) {
            def msg = "Step ${title} failed with exit code=${ret.rc}"
            if (ret.exception != null) {
                msg += " exception=${ret.exception}"
            }
            reportFail(title, msg)
        }
    }
}

def forceCleanup(prefix='', redirect='') {
    env.WORKSPACE = pwd()

    def cmd = """
    if [ -x /bin/bash ]; then
        $prefix bash -eE -c 'shopt -s dotglob; rm -rf ${env.WORKSPACE}/*' ${redirect}
    else
        $prefix find ${env.WORKSPACE} -depth ! -path . ! -path .. ! -path ${env.WORKSPACE} -exec rm -rf {} \\; ${redirect}
    fi
    """
    return run_shell(cmd, "Clean workspace $prefix")
}

def forceCleanupWS() {

    def res = forceCleanup('','&>/dev/null')
    if (res.rc != 0) {
        res = forceCleanup('sudo','')
        if (res.rc != 0) {
            reportFail('clean workspace', "Unable to cleanup workspace rc=" + res)
        }
    }
}


def getArchConf(config, arch) {

    def k8sArchConfTable = [:]

    config.logger.trace(4, "getArchConf: arch=" + arch)
    config.registry_jnlp_path = getConfigVal(config, ['registry_jnlp_path'], 'swx-infra')

    k8sArchConfTable['x86_64']  = [
        nodeSelector: 'kubernetes.io/arch=amd64',
        jnlpImage: 'jenkins/inbound-agent:latest',
        dockerImage: 'docker:19.03'
    ]

    k8sArchConfTable['aarch64'] = [
        nodeSelector: 'kubernetes.io/arch=arm64',
        jnlpImage: "jenkins/inbound-agent:latest",
        dockerImage: 'docker:19.03'
    ]

    k8sArchConfTable['ppc64le'] = [
        nodeSelector: 'kubernetes.io/arch=ppc64le',
        jnlpImage: "${config.registry_host}/${config.registry_jnlp_path}/jenkins-ppc64le-agent-jnlp:latest",
        dockerImage: 'ppc64le/docker'
    ]

    def aTable = getConfigVal(config, ['kubernetes', 'arch_table'], null)
    if (aTable != null && aTable.containsKey(arch)) {
        if (k8sArchConfTable[arch] != null) {
            k8sArchConfTable[arch] += aTable[arch]
        } else {
            k8sArchConfTable[arch] = aTable[arch]
        }
    }

    def vars = ['arch':arch]
    k8sArchConfTable[arch].each { key, val ->
        k8sArchConfTable[arch][key] = resolveTemplate(vars, val, config)
    }

    config.logger.trace(2, "getArchConf[${arch}] " + k8sArchConfTable[arch])
    return k8sArchConfTable[arch]
}

def gen_image_map(config) {

    def image_map = [:]

    def arch_list = getConfigVal(config, ['matrix', 'axes', 'arch'], null, false)

    if (!config.runs_on_dockers) {
        config.runs_on_dockers = []
    }

    if (arch_list) {
        for (int i=0; i<arch_list.size(); i++) {
            def arch = arch_list[i]
            image_map[arch] = []
        }
    } else {
        for (int i=0; i<config.runs_on_dockers.size(); i++) {
            def dfile = config.runs_on_dockers[i]
            if (dfile.arch) {
                image_map["${dfile.arch}"] = []
            } else {
                reportFail('config', "Please define tag 'arch' for image ${dfile.name} in 'runs_on_dockers' section of yaml file")
            }
        }
    }

    image_map.each { arch, images ->

        def k8sArchConf = getArchConf(config, arch)
        if (!k8sArchConf) {
            config.logger.trace(3, "gen_image_map | skipped unsupported arch (${arch})")
            return
        }

        config.runs_on_dockers.each { item ->

            def dfile = item.clone()
            config.logger.debug("run on dockers item: " + dfile)

            if (dfile.enable == null) {
                dfile.enable = "true"
                config.logger.debug("run on dockers item.enable: " + dfile.enable)
            }

            if (dfile.enable == "auto") {
                dfile.enable = '${' + dfile.name + '}'
                config.logger.debug("run on dockers item.enable: " + dfile.enable)
            }

            def enable = resolveTemplate(dfile, dfile.enable, config)

            config.logger.debug("run on dockers enable: " + enable)

            if (enable.toBoolean()) {
                dfile.arch = dfile.arch ?: arch

                if (dfile.arch && dfile.arch != arch) {
                    config.logger.trace(3, "skipped conf: " + arch + " name: " + dfile.name)
                    return
                }

                dfile.file = dfile.file ?: ''
                if (dfile.url) {
                    parts = dfile.url.tokenize('/').last().tokenize(':')
                    if (parts.size() == 2) {
                        dfile.tag = parts[1]
                        tag_size = dfile.tag.size() + 1
                        len = dfile.url.size() - tag_size
                        dfile.uri = dfile.url.substring(0,len)
                    }
                }

                dfile.tag = dfile.tag ?: 'latest'
                dfile.build_args = dfile.build_args ?: ''
                dfile.build_args = resolveTemplate(dfile, dfile.build_args, config)
                dfile.uri = dfile.uri ?: "${arch}/${dfile.name}"
                dfile.filename = dfile.file
                dfile.uri = resolveTemplate(dfile, dfile.uri, config)
                dfile.url = dfile.url ?: "${config.registry_host}${config.registry_path}/${dfile.uri}:${dfile.tag}"
                dfile.url = resolveTemplate(dfile, dfile.url, config)

                config.logger.debug("Adding docker to image_map for " + dfile.arch + ' name: ' + dfile.name)
                images.add(dfile)
            }

        }
    }
    return image_map
}

def matchMapEntry(filters, entry) {
    def match
    for (int i=0; i<filters.size(); i++) {
        match = true
        filters[i].each { k, v ->
            String ek = entry[k] + ''
            if (entry[k] == null || !ek.matches(v + "")) {
                match = false
            }
        }
        if (match) {
            break
        }
    }
    return match
}

def onUnstash() {

    def cmd = """#!/bin/sh
    export PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
    hash -r
    tar xf scm-repo.tar
    rm -f scm-repo.tar
    """
    run_shell(cmd, "Extracting project files into workspace")
}


def attachArtifacts(config, args) {
    if (args != null) {
        try {
            archiveArtifacts(artifacts: args, allowEmptyArchive: true )
        } catch (e) {
            config.logger.warn("Failed to add artifacts: " + args + " reason: " + e)
        }
    }
}

def attachJunit(config, args) {
    if (args != null) {
        try {
            junit(testResults: args, allowEmptyResults: true)
        } catch (e) {
            config.logger.warn("Failed to add junit results: " + args + " reason: " + e)
        }
    }
}

def attachTap(config, args) {
    if (args != null) {
        try {
            step([$class: "TapPublisher",
                    failedTestsMarkBuildAsFailure: true,
                    planRequired: false,
                    failIfNoResults: false,
                    testResults: args])
        } catch (e) {
            config.logger.warn("Failed to add tap results: " + args + " reason: " + e)
        }
    }
}


def attachHTML(image, config, oneStep) {

    def reportDir, reportFiles, reportName, allowMissing

    if (oneStep.publishHTML) {
        reportDir = resolveTemplate(image, oneStep.publishHTML.reportDir, config)
        reportFiles = resolveTemplate(image, oneStep.publishHTML.reportFiles, config)
        reportName = resolveTemplate(image, oneStep.publishHTML.reportName, config)
        allowMissing = oneStep.publishHTML.allowMissing
    } else if (oneStep.run == 'coverity.sh' || oneStep.resource == 'actions/coverity.sh') {
        reportDir = 'cov_build/output/errors/'
        reportFiles = 'index.html'
        reportName = 'Coverity Report'
        allowMissing = false
    } else {
        return
    }

    publishHTML (target : [allowMissing: allowMissing,
    alwaysLinkToLastBuild: true,
    keepAll: true,
    reportDir: reportDir,
    reportFiles: reportFiles,
    reportName: reportName,
    ])
}

def attachResults(config, oneStep=null, res=null) {

    def obj = oneStep? oneStep : config

    if (res && res.rc != 0) {
        attachArtifacts(config, obj["archiveArtifacts-onfail"])
        attachJunit(config, obj["archiveJunit-onfail"])
        attachTap(config, obj["archiveTap-onfail"])
    }
    attachArtifacts(config, obj["archiveArtifacts"])
    attachJunit(config, obj["archiveJunit"])
    attachTap(config, obj["archiveTap"])

}

@NonCPS
int getDebugLevel() {
    def val = env.DEBUG
    def intValue = 0
    if (val != null) {
        if (val == "true") {
            intValue = 1
        } else {
            intValue = val.isInteger()? val.toInteger() : 0
        }
    }

    return intValue
}

def isDebugMode() {
    def mode = (getDebugLevel())? true : false
    return mode
}

def getDefaultShell(config=null, step=null, shell=null) {

    def cmd = """#!/bin/sh
    if [ -x /bin/bash ]; then
        echo '#!/bin/bash -elE'
    else
        echo '#!/bin/sh -el'
    fi
    """
    def res = run_shell(cmd, "Detect shell", true)
    shell = res.text.trim()
    
    if (isDebugMode()) {
        shell += 'x'
    }

    def ret = shell
    if ((step != null) && (step.shell != null)) {
        ret = step.shell
    } else if ((config != null) && (config.shell != null)) {
        ret = config.shell
    }


    if (ret != "action") {
        if (ret.substring(0,1) == '/') {
            ret = '#!' + ret
        } else if (ret.substring(0,2) != '#!') {
            reportFail("config", "Unsupported value for shell parameter: " + ret + " should be '#!/path/to/shell'")
        }
    }
    return ret
}


Map toStringMap(String param) {
    Map ret = [:]
    String strMap = param
    if (strMap != null) {
        strMap = '[' + strMap.replaceAll('[\\{\\}]', ' ') + ']'
        ret = evaluate(strMap)
    }
    return ret
}

def stringToList(selector) {

    def customSel = []

    if (selector && selector.size() > 0) {

        if (selector.getClass() == String) {
            customSel.add(toStringMap(selector))
        } else {
            // groovy casts yaml Map definition to LinkedHashMap type
            // which is not serializable and causes Jenkins pipeline to fail
            // on non-serializable error, this is a reason for ugle hack to
            // convert LinkedHashMap to Map which is serializable
            for (int i=0; i<selector.size(); i++) {
                customSel.add(toStringMap(selector[i].toString()))
            }
        }
    }
    return customSel
}

def check_skip_stage(image, config, title, oneStep, axis, runtime=null) {

    def stepEnabled = getConfigVal(config, ['enable'], true, true, oneStep, true).toString()


    if (!stepEnabled.toBoolean()) {
        config.logger.trace(2, "Step '${oneStep.name}' is disabled in project yaml file, skipping")
        return true
    }


    def selectors = [oneStep.containerSelector, oneStep.agentSelector]

    // check if two selectors configured and only one allowed
    def singleSelector = getConfigVal(config, ['step_allow_single_selector'], false)

    if ((singleSelector == true) && (oneStep.containerSelector != null) && (oneStep.agentSelector != null)) {
        reportFail('config', "Step='${oneStep.name}' has both containerSelector and agentSelector configured, step_allow_single_selector=${singleSelector}, set `step_allow_single_selector: false` to disable")
    }

    def skip = false

    if (runtime) {
        if(runtime == 'k8') {
            if (singleSelector && oneStep.agentSelector) { // skip if wrong selector
                return true
            }
            selectors = [oneStep.containerSelector]
        } else {
            if (singleSelector && oneStep.containerSelector) { // skip if wrong selector
                return true
            }
            selectors = [oneStep.agentSelector]
        }
    }

    config.logger.trace(2, "check_skip_stage step='${oneStep.name}' runtime=${runtime} selectors=${selectors}")


    // tools by default should be skipped, unless explicitly requested by selectors below
    if (image['category'] == 'tool') {
        skip = true
    }

    for (int i=0; i<selectors.size(); i++) {
        selector = selectors[i]
        if (selector && selector.size() > 0) {
            def customSel = stringToList(selector)
            config.logger.trace(2, "Selector=" + selector + " custom=" + customSel + " name=" + image.name)
            if (matchMapEntry(customSel, axis)) {
                config.logger.trace(2, "Step '" + oneStep.name + " matched with axis=" + axis + " selector=" + selector)
                skip = false
                break
            } else {
                skip = true
            }
        }
    }

    config.logger.trace(2, "${oneStep.name} - Step '" + oneStep.name + "' skip=" + skip)
    return skip
}

void reportFail(String stage, String msg) {
    currentBuild.result = 'FAILURE'
    error(stage + " failed with msg: " + msg)
}

def toEnvVars(config, vars) {
    def map = []
    if (vars) {
        for (def entry in entrySet(vars)) {
            map.add(entry.key + "=" + resolveTemplate(vars, '' + entry.value, config))
        }
    }
    return map
}

def run_step(image, config, title, oneStep, axis, runtime=null) {

    if ((image != null) && 
        (axis != null) && 
        check_skip_stage(image, config, title, oneStep, axis, runtime)) {
        return
    }



    stage("${title}") {
        def shell = getDefaultShell(config, oneStep)
        env.WORKSPACE = pwd()

        if (oneStep.resource) {
            def actionScript = libraryResource "${oneStep.resource}"
            def idx = oneStep.resource.lastIndexOf('/')
            def dirname = '.ci/' + oneStep.resource.substring(0, idx)
            def filename = oneStep.resource.substring(idx+1)
            def toFile = "${dirname}/${filename}"
            sh(script: "mkdir -p $dirname", label: "Create action dir $dirname", returnStatus: true)
            writeFile(file: toFile, text: actionScript)
            sh(script: "chmod +x " + toFile, label: "Set script $toFile permissions", returnStatus: true)
        }

        if (shell == "action") {
            if (oneStep.module == null) {
                reportFail(title, "Step is type of action but has no 'module' defined")
            }

            config.logger.trace(4, "Running step action module=" + oneStep.module + " args=" + oneStep.args + " run=" + oneStep.run)
            int rc = this."${oneStep.module}"(this, oneStep, config)
            if (rc != 0) {
                reportFail(oneStep.name, "exit with error code=${rc}")
            }
        } else {
            def String cmd = shell + "\n" + oneStep.run
            config.logger.trace(4, "Running step script=" + cmd)
            if (oneStep.credentialsId) {
                Map found = null
                for (int i=0; i<config.credentials.size(); i++) {
                    Map entry = config.credentials[i]
                    if (entry.credentialsId == oneStep.credentialsId) {
                        found = entry
                        break
                    }
                }
                if (!found || !found.usernameVariable || !found.passwordVariable) {
                    reportFail(title, "Credentials requested but undefined in yaml file")
                }
                withCredentials([usernamePassword(credentialsId: oneStep.credentialsId,
                                passwordVariable: found.passwordVariable,
                                usernameVariable: found.usernameVariable)]) {
                        run_step_shell(image, cmd, title, oneStep, config)
                    }
            } else {
                run_step_shell(image, cmd, title, oneStep, config)
            }
        }
    }
}

def runSteps(image, config, branchName, axis, steps=config.steps, runtime) {
    forceCleanupWS()
    // fetch .git from server and unpack
    unstash getStashName()
    onUnstash()

    def parallelNestedSteps = [:]
    for (int i = 0; i < steps.size(); i++) {
        def one = steps[i]
        def par = one["parallel"]
        def oneStep = one
        // collect parallel steps (if any) and run it when non-parallel step discovered or last element.
        // Skip parallel stages if not used. Fix for Blueocean UI.
        if ( par != null && par == true && !check_skip_stage(image, config, branchName, oneStep, axis)) {
            def stepName = branchName + "->" + one.name
            parallelNestedSteps[stepName] = { run_step(image, config, stepName, oneStep, axis, runtime) }
            // last element - run and flush
            if (i == steps.size() - 1) {
                parallel(parallelNestedSteps)
                parallelNestedSteps = [:]
            }
            continue
        }
        // non-parallel step discovered, need to flush all parallel 
        // steps collected previously to keep ordering.
        // run non-parallel step right after
        if (parallelNestedSteps.size() > 0) {
            parallel(parallelNestedSteps)
            parallelNestedSteps = [:]
        }
        run_step(image, config, one.name, oneStep, axis, runtime)
    }
    attachResults(config)
}

def getConfigVal(config, list, defaultVal=null, toString=true, oneStep=null, useTemplate=false) {
    def val = oneStep ?: config
    for (int i=0; i<list.size(); i++) {
        item = list[i]
        config.logger.trace(5, "getConfigVal: Checking $item in config file")
        val = val[item]
        if (val == null) {
            config.logger.trace(5, "getConfigVal: Defaulting " + list + " = " + defaultVal)
            return defaultVal
        }
    }

    def ret
    if (toString && (val instanceof ArrayList) && (val.size() == 1)) {
        config.logger.trace(5, "getConfigVal: arraylist hack "+ val[0])
        ret = val[0]
    } else {
        ret = val
    }

    if (useTemplate) {
        ret = resolveTemplate([:], ret, config)
    }

    config.logger.trace(5, "getConfigVal: Found " + list + " = " + ret)
    return ret
}

def parseListV(volumes) {
    def listV = []
    volumes.each { vol ->
        hostPath = vol.get("hostPath")
        mountPath = vol.get("mountPath")
        hpv = hostPathVolume(hostPath: hostPath, mountPath: mountPath)
        listV.add(hpv)
    }
    return listV
}

def parseListNfsV(volumes) {
    def listV = []
    volumes.each { vol ->
        serverAddress = vol.get("serverAddress")
        serverPath = vol.get("serverPath")
        mountPath = vol.get("mountPath")
        readOnly = vol.get("readOnly", false)
        nfsv = nfsVolume(serverAddress: serverAddress,
                         serverPath: serverPath,
                         mountPath: mountPath,
                         readOnly: readOnly)
        listV.add(nfsv)
    }
    return listV
}
        

def parseListA(annotations) {
    def listA = []
    annotations.each { an ->
        key = an.get("key")
        value = an.get("value")
        pan = podAnnotation(key: key, value: value)
        listA.add(pan)
    }
    return listA
}

def runK8(image, branchName, config, axis, steps=config.steps) {

    def cloudName = image.cloud ?: getConfigVal(config, ['kubernetes', 'cloud'], null)
    if (!cloudName) {
        reportFail('config', "kubernetes run requested but kubernetes.cloud name is not defined in yaml file")
    }

    config.logger.trace(2, "Using kubernetes ${cloudName}, axis=" + axis)

    def listV = parseListV(config.volumes)
    listV.addAll(parseListNfsV(config.nfs_volumes))
    def cname = image.get("name").replaceAll("[\\.:/_]", "")
    def pod_name = config.job + "-" + cname + "-" + env.BUILD_NUMBER

    def k8sArchConf = getArchConf(config, axis.arch)
    def nodeSelector = ''

    if (!k8sArchConf) {
        config.logger.error("runK8 | arch conf is not defined for ${axis.arch}")
        return
    }

    nodeSelector = k8sArchConf.nodeSelector
    config.logger.trace(2, "runK8 ${branchName} | nodeSelector: ${nodeSelector}")

    if (axis.nodeSelector) {
        if (nodeSelector) {
            nodeSelector = nodeSelector + ',' + axis.nodeSelector
        } else {
            nodeSelector = axis.nodeSelector
        }
    }
    def hostNetwork = image.hostNetwork ?: getConfigVal(config, ['kubernetes', 'hostNetwork'], false)
    def runAsUser = image.runAsUser ?: getConfigVal(config, ['kubernetes', 'runAsUser'], "0")
    def runAsGroup = image.runAsGroup ?: getConfigVal(config, ['kubernetes', 'runAsGroup'], "0")
    def privileged = image.privileged ?: getConfigVal(config, ['kubernetes', 'privileged'], false)
    def limits = image.limits ?: getConfigVal(config, ['kubernetes', 'limits'], "{memory: 8Gi, cpu: 4000m}")
    def requests = image.requests ?: getConfigVal(config, ['kubernetes', 'requests'], "{memory: 8Gi, cpu: 4000m}")
    def annotations = image.annotations ?: getConfigVal(config, ['kubernetes', 'annotations'], [], false)
    def caps_add = image.caps_add ?: getConfigVal(config, ['kubernetes', 'caps_add'], "[]")
    def service_account = getConfigVal(config, ['kubernetes', 'serviceAccount'], "default")
    def namespace = getConfigVal(config, ['kubernetes', 'namespace'], "default")
    def tolerations = image.tolerations ?: getConfigVal(config, ['kubernetes', 'tolerations'], "[]")
    def yaml = """
spec:
  containers:
    - name: ${cname}
      resources:
        limits: ${limits}
        requests: ${requests}
      securityContext:
        capabilities:
          add: ${caps_add}
  tolerations: ${tolerations}
"""
    podTemplate(
        cloud: cloudName,
        runAsUser: runAsUser,
        runAsGroup: runAsGroup,
        nodeSelector: nodeSelector,
        hostNetwork: hostNetwork,
        annotations: parseListA(annotations),
        yamlMergeStrategy: merge(),
        serviceAccount: service_account,
        namespace: namespace,
        name: pod_name,
        yaml: yaml,
        containers: [
            containerTemplate(name: 'jnlp', image: k8sArchConf.jnlpImage, args: '${computer.jnlpmac} ${computer.name}'),
            containerTemplate(privileged: privileged, name: cname, image: image.url, ttyEnabled: true, alwaysPullImage: true, command: 'cat')
        ],
        volumes: listV
    )
    {
        node(POD_LABEL) {
            stage (branchName) {
                container(cname) {
                    runSteps(image, config, branchName, axis, steps, 'k8')
                }
            }
        }
    }
    config.logger.trace(2, "runK8 ${branchName} done")
}

@NonCPS
def replaceVars(vars, str) {
    def res = str.toString()

    for (def entry in entrySet(vars)) {

        if (entry.key == "" ||  entry.key == null || entry.value == "" || entry.value == null) {
            continue;
        }
        if (!res.contains('$')) {
            return res
        }
        def opts = ['$' + entry.key, '${' + entry.key + '}']
        for (int i=0; i<opts.size(); i++) {
            if (res.contains(opts[i])) {
                res = res.replace(opts[i], entry.value + '')
                break
            }
        }
    }
    return res
}

@NonCPS
def resolveTemplate(vars, str, config) {
    def res = str
    def varsMap = vars

    if (config.env) {
        res = replaceVars(config.env, res)
        varsMap += config.env
    }

    varsMap += config
    varsMap += env.getEnvironment()

    res = replaceVars(varsMap, res)
    return res
}

def getDockerOpt(config) {
    def opts = getConfigVal(config, ['docker_opt'], "")
    if (config.get("volumes")) {
        for (int i=0; i<config.volumes.size(); i++) {
            def vol = config.volumes[i]
            hostPath = vol.get("hostPath")? vol.hostPath : vol.mountPath
            opts += " -v ${vol.mountPath}:${hostPath}"
        }
    }
    return opts
}

def runAgent(image, config, branchName=null, axis=null, Closure func, runInDocker=true) {
    def nodeName = image.nodeLabel

    config.logger.debug("Running on agent with label: ${nodeName} branch: ${branchName} - docker: " + runInDocker)

    node(nodeName) {
        forceCleanupWS()
        unstash getStashName()
        onUnstash()
        stage(branchName) {
            env.WORKSPACE = pwd()
            if (runInDocker) {
                def opts = getDockerOpt(config)
                if (image.privileged && image.privileged == 'true') {
                    opts += " --privileged "
                }
                docker.image(image.url).inside(opts) {
                    func(image, config, branchName, axis, "docker")
                }
            } else {
                func(image, config, branchName, axis, "baremetal")
            }
        }
    }

}


Map getTasks(axes, image, config, include, exclude) {


    config.logger.trace(3, "getTasks() --> image=" + image)

    int serialNum = 1
    Map tasks = [:]
    for (int i = 0; i < axes.size(); i++) {
        Map axis = axes[i]

        if (axis.arch != image.arch) {
            config.logger.debug("getTasks: skipping axis=" + axis + " as its arch does not match image=" + image)
            continue
        }

        // todo: some keys from matrix can be same as in image map and it will cause confusion
        // maybe need to prefix image keys with special prefix to distinguish or copy only non-existing keys
        axis += image
        axis.put("job", config.job)


        if (exclude.size() && matchMapEntry(exclude, axis)) {
            config.logger.debug("Skipping by 'exclude' rule, axis " + axis.toMapString())
            continue
        } else if (include.size() && ! matchMapEntry(include, axis)) {
            config.logger.debug("Skipping by 'include' rule, axis " + axis.toMapString())
            continue
        }

        if (!config.steps) {
            continue
        }

        axis.put("variant", serialNum)
        axis.put("axis_index", serialNum)
        serialNum++

        config.logger.debug("Working on axis " + axis.toMapString())

        def tmpl = getConfigVal(config, ['taskName'], "${axis.arch}/${image.name} v${axis.axis_index}")
        def branchName = resolveTemplate(axis, tmpl, config)

        def canSkip = true
        for (int j = 0; j < config.steps.size(); j++) {
            def oneStep = config.steps[j]
            if (!check_skip_stage(image, config, branchName, oneStep, axis)) {
                canSkip = false
                break
            }
        }

        // can skip, image defined but unused by steps
        // optimization for blueocean UI to not show unused green nodes
        if (canSkip) {
            continue
        }

        // convert the Axis into valid values for withEnv step
        if (config.get("env")) {
            axis += config.env
        }

        def axisEnv = []
        axis.each { k, v ->
            axisEnv.add("${k}=${v}")
        }

        config.logger.trace(5, "task name " + branchName)
        tasks[branchName] = { ->
            withEnv(axisEnv) {
                if ((config.get("kubernetes") == null) &&
                    (image.nodeLabel == null) &&
                    (image.cloud == null) 
                    ) {
                    reportFail('config', "Please define cloud or nodeLabel in yaml config file or define nodeLabel for docker")
                }
                if (image.nodeLabel) {
                    runBareMetal = true
                    if (image.url == null) {
                        runBareMetal = false
                    }
                    def callback = {pimage, pconfig, pname, paxis, pruntime ->
                        runSteps(pimage, pconfig, pname, paxis, pruntime)
                    }
                    runAgent(image, config, branchName, axis, callback, runBareMetal)
                } else {
                    runK8(image, branchName, config, axis)
                }
            }
        }
    }

    config.logger.debug("getTasks() done image=" + image)


    return tasks
}

def getMatrixTasks(image, config) {

    def include = [], exclude = [], axes = []
    config.logger.debug("getMatrixTasks() --> image=" + image)

    if (config.get("matrix")) {
        axes = getMatrixAxes(config.matrix.axes).findAll()
        exclude = getConfigVal(config, ['matrix', 'exclude'], [], false)
        include = getConfigVal(config, ['matrix', 'include'], [], false)
    } else {
        axes.add(image)
    }

    config.logger.trace(2, "Filters: include[" +
                            include.size() + "] = " +
                            include + " exclude[" +
                            exclude.size() +
                            "] = " + exclude
                            )
    return getTasks(axes, image, config, include, exclude)
}

def buildImage(img, filename, extra_args, config, image) {
    if (filename == "") {
        config.logger.warn("No docker filename specified, skipping build docker")
        return
    }

    def preBuild = null
    preBuild = preBuild ?: image.on_image_build
    preBuild = preBuild ?: getConfigVal(config, ['pipeline_on_image_build', 'run'], null)

    if (preBuild) {
        run_shell(preBuild, "Image preparation script")
    }
    customImage = docker.build("${img}", "-f ${filename} ${extra_args} . ")
    customImage.push()
}

Boolean isEnvVarSet(var) {
    if (var != null) {
        if (var.contains('null')) {
            return false
        }

        if (var == "") {
            return false
        }
        return true
    }
    return false
}

int tryFindChangedList(config, dcmd) {
    def cFiles = null
    def logger = config.get("logger")
    def ret = run_shell(dcmd, 'Checking changed files list')

    if (ret.rc == 0)  {
        cFiles = run_shell(dcmd, 'Calculating changed files list', true).text.trim().tokenize()

        cFiles.each { oneFile ->
            logger.debug("Tracking Changed File: " + oneFile)
        }
    } else {
        logger.warn("Unable to find changed file list by " + dcmd)
        cFiles = null
    }

    return cFiles
}

String getChangedFilesList(config) {

    def cFiles = []
    def logger = config.get("logger")
    def dcmd

    try {

        def br
        if (isEnvVarSet(env.ghprbTargetBranch)) {
            br = env.ghprbTargetBranch
        } else {
            def ret = run_shell('git ls-remote -q | grep -q refs/heads/master', 'detecting branch name')
            // master or main?
            if (ret.rc == 0) {
                br = 'master'
            } else {
                br = 'main'
            }
        }
        def sha = "HEAD"
        if (isEnvVarSet(env.ghprbActualCommit)) {
            sha = env.ghprbActualCommit
        }

        dcmd = "git diff --name-only origin/${br}..${sha}"
        cFiles = tryFindChangedList(config, dcmd);
        if (cFiles != null && cFiles.size() > 0) {
            return cFiles
        }

       if (isEnvVarSet(env.GIT_COMMIT) && isEnvVarSet(env.GIT_PREV_COMMIT)) {
            logger.debug("Checking changes for git commit: ${env.GIT_COMMIT} prev commit: ${env.GIT_PREV_COMMIT}")
            dcmd = "git diff --name-only ${env.GIT_PREV_COMMIT} ${env.GIT_COMMIT}"
            cFiles = tryFindChangedList(config, dcmd);
            if (cFiles != null && cFiles.size() > 0) {
                return cFiles
            }
        }


    } catch (e) {
        logger.warn("Unable to calc changed file list - make sure shallow clone depth is configured in Jenkins, reason: " + e)
    }

    return []
}

def buildImage(config, image) {

    // Vasily Ryabov: we need .toString() to make changed_files.contains(filename) work correctly
    // See https://stackoverflow.com/q/56829842/3648361
    def filename = image.filename.toString().trim()
    def extra_args = image.build_args
    def changed_files = config.get("cFiles")
    def need_build = 0
    def img = image.url

    try {
        config.logger.info("Pulling image - ${img}")
        docker.image(img).pull()
    } catch (exception) {
        config.logger.info("Image NOT found - ${img} - will build ${filename} ...")
        need_build++
    }

    if ("${env.build_dockers}" == "true") {
        config.logger.info("Forcing building file per user request: ${filename} ... ")
        need_build++
    }
    config.logger.debug("Changed files: ${changed_files}")
    if (changed_files.contains(filename)) {
        config.logger.info("Forcing building, by modified file: ${filename} ... ")
        need_build++
    }

    if (image.deps) {
        for (int i=0; i<image.deps.size(); i++) {
            def oneDep = image.deps[i]
            config.logger.debug("Checking " + oneDep)
            if (changed_files.contains(oneDep)) {
                config.logger.info("Forcing building by dependency on changed file: ${oneDep} ... ")
                need_build++
            }
        }
    }

    if (need_build) {
        config.logger.info("Building - ${img} - ${filename}")
        buildImage(img, filename, extra_args, config, image)
    }
    return need_build
}

def buildDocker(image, config) {
    def vars = []
    vars += toEnvVars(config, config.env)

    withEnv(vars) {
        if (config.registry_host && image.url.contains(config.registry_host)) {
            docker.withRegistry("https://${config.registry_host}", config.registry_auth) {
                buildImage(config, image)
            }
        } else {
            buildImage(config, image)
        }
    }
}

def build_docker_on_k8(image, config) {

    if (config.get("volumes") == null) {
        config.put("volumes", [])
    }
    if (config.get("nfs_volumes") == null) {
        config.put("nfs_volumes", [])
    }
    config.volumes.add([mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'])

    def listV = parseListV(config.volumes)
    listV.addAll(parseListNfsV(config.nfs_volumes))
    def cname = image.get("name").replaceAll("[\\.:/_]", "")
    def pod_name = config.job + "-build-" + cname + "-" + env.BUILD_NUMBER

    def cloudName = image.cloud ?: getConfigVal(config, ['kubernetes', 'cloud'], null)
    if (!cloudName) {
        reportFail('config', "kubernetes run requested but kubernetes.cloud name is not defined in yaml file")
    }

    config.logger.trace(7, "Checking docker image availability for " + image)

    def k8sArchConf = getArchConf(config, image.arch)
    def nodeSelector = ''

    if (!k8sArchConf) {
        config.logger.error("build_docker_on_k8 | arch conf is not defined for ${image.arch}")
        return
    }

    nodeSelector = k8sArchConf.nodeSelector

    if (image.nodeSelector) {
        if (nodeSelector) {
            nodeSelector = nodeSelector + ',' + image.nodeSelector
        } else {
            nodeSelector = image.nodeSelector
        }
    }

    config.logger.trace(2, "build_docker_on_k8 for image ${image.name} | nodeSelector: ${nodeSelector}")

    def hostNetwork = image.hostNetwork ?: getConfigVal(config, ['kubernetes', 'hostNetwork'], false)
    def privileged = image.privileged ?: getConfigVal(config, ['kubernetes', 'privileged'], false)
    def limits = image.limits ?: getConfigVal(config, ['kubernetes', 'limits'], "{memory: 8Gi, cpu: 4000m}")
    def requests = image.requests ?: getConfigVal(config, ['kubernetes', 'requests'], "{memory: 8Gi, cpu: 4000m}")
    def service_account = getConfigVal(config, ['kubernetes', 'serviceAccount'], "default")
    def namespace = getConfigVal(config, ['kubernetes', 'namespace'], "default")
    def tolerations = image.tolerations ?: getConfigVal(config, ['kubernetes', 'tolerations'], "[]")
    def yaml = """
spec:
  containers:
    - name: docker
      resources:
        limits: ${limits}
        requests: ${requests}
  tolerations: ${tolerations}
"""
    podTemplate(
        cloud: cloudName,
        nodeSelector: nodeSelector,
        hostNetwork: hostNetwork,
        name: pod_name,
        serviceAccount: service_account,
        namespace: namespace,
        yamlMergeStrategy: merge(),
        yaml: yaml,
        containers: [
            containerTemplate(name: 'jnlp', image: k8sArchConf.jnlpImage, args: '${computer.jnlpmac} ${computer.name}'),
            containerTemplate(privileged: privileged, name: 'docker', image: k8sArchConf.dockerImage, ttyEnabled: true, alwaysPullImage: true, command: 'cat')
        ],
        volumes: listV
    )
    {
        node(POD_LABEL) {
            unstash getStashName()
            onUnstash()

            container('docker') {
 //               stage ('Build Docker') {
                    buildDocker(image, config)
 //               }
            }
        }
    }
}

def run_parallel_in_chunks(config, myTasks, depth) {

    if (myTasks.size() == 0) {
        return
    }

    int bSize = depth

    if (bSize <= 0) {
        bSize = myTasks.size()
    }

    def val = getConfigVal(config, ['failFast'], false)

    config.logger.trace(3, "run_parallel_in_chunks: batch size is ${bSize}")
    (myTasks.keySet() as List).collate(bSize).each {
        def batchMap = myTasks.subMap(it)
        batchMap['failFast'] = val
        parallel batchMap
    }
}


def loadConfigFile(filepath, logger) {

    logger.debug("loadConfigFile: path=" + filepath)
    def rawFile = readFile(filepath)
    rawFile = rawFile.trim()

    def config = readYaml(text: rawFile, charset: 'UTF-8')

    logger.info("loadConfigFile:\n" + rawFile)

    if (config.get("matrix")) {
        if (config.matrix.include != null && config.matrix.exclude != null) {
            reportFail('config', "matrix.include and matrix.exclude sections in config file=${filepath} are mutually exclusive. Please keep only one.")
        }
    }
    return config
}

def String getStashName() {
	return "${env.BUILD_NUMBER}"
}



def startPipeline(String label) {
    node(label) {

        logger = new Logger(this)

        stage("Checkout source code") {
            forceCleanupWS()
            def scmVars = checkout scm

            env.GIT_COMMIT      = scmVars.GIT_COMMIT
            env.GIT_PREV_COMMIT = scmVars.GIT_PREVIOUS_COMMIT

            logger.debug("Git commit: ${env.GIT_COMMIT} prev commit: ${env.GIT_PREV_COMMIT}")
            // create git tarball on server, agents will copy it and unpack
            run_shell("tar -c --exclude scm-repo.tar -f scm-repo.tar .", 'Creating workspace copy')
            stash includes: "scm-repo.tar", name: getStashName()
            run_shell("[ -x .ci/cidemo-init.sh ] && .ci/cidemo-init.sh", 'Run cidemo init hook')
        }

        if (fileExists("${env.conf_file}")) {
            files = [ "${env.conf_file}".toString() ]
        } else {
            files = findFiles(glob: "${env.conf_file}")
            if (!files.size()) {
                reportFail('config', "No conf_file found by ${env.conf_file}")
            }
        }

        for (int i=0; i < files.size(); i++) {
            def file = files[i]
            def file_path = ""
            if (file in String) {
                file_path = file
            } else {
                file_path = file.path
            }
            def branches = [:]
            def config = loadConfigFile(file_path, logger)
            logger.info("New Job: '" + config.job + "'' file: " + file_path)

            config.put("logger", logger)
            config.put("cFiles", getChangedFilesList(config))
            if (!config.env) {
                config.put("env", [:])
            }


// prepare MAP in format:
// $arch -> List[$docker, $docker, $docker]
// this is to avoid that multiple axis from matrix will create own same copy for $docker but creating it upfront.


            def parallelBuildDockers = [:]

            def arch_distro_map = gen_image_map(config)
            for (def entry in entrySet(arch_distro_map)) {
                def images = entry.value
                for (int j=0; j<images.size(); j++) {
                    def image = images[j]
                    def imgName = "${image.arch}/${image.name}/${j}"
                    def tmpl = getConfigVal(config, ['taskNameSetupImage'], "Setup Image ${imgName}")
                    def branchName = resolveTemplate(image, tmpl, config)

                    if (config.pipeline_start && !config.pipeline_start.image) {
                        if (config.pipeline_start.containerSelector) {
                            if (matchMapEntry(stringToList(config.pipeline_start.containerSelector), image)) {
                                config.pipeline_start.image = image
                            }
                        }
                    }

                    if (config.pipeline_stop && !config.pipeline_stop.image) {
                        if (config.pipeline_stop.containerSelector) {
                            if (matchMapEntry(stringToList(config.pipeline_stop.containerSelector), image)) {
                                config.pipeline_stop.image = image
                            }
                        }
                    }

                    def needSetupContainers = false
                    // prepare containers only if thery have assotiated dockerfile
                    for (int ii=0; ii<config.runs_on_dockers.size(); ii++) {
                        if (config.runs_on_dockers[ii].file) {
                            needSetupContainers = true
                        }
                    }

                    if (needSetupContainers) {
                        parallelBuildDockers[branchName] = {

                            def cloudName = image.cloud ?: getConfigVal(config, ['kubernetes', 'cloud'], null)
                            if (cloudName) {
                                build_docker_on_k8(image, config)
                            } else if (image.nodeLabel) {
                                def callback = { pimage, pconfig, pname=null, paxis=null ->
                                    buildDocker(pimage, pconfig)
                                }
                                runAgent(image, config, "Preparing image ${imgName}", null, callback, false)
                            } else {
                                reportFail('init', 'No cloud or Agent defined in project file')
                            }
                        }
                    }
                    branches += getMatrixTasks(image, config)
                }
            }
        
            if (config.runs_on_agents) {
                for (int a=0; a<config.runs_on_agents.size();a++) {
                    image = config.runs_on_agents[a]
                    image.name = image.nodeLabel
                    image.arch = 'x86_64'
                    branches += getMatrixTasks(image, config)
                }
            }

            try {
                def bSize = getConfigVal(config, ['batchSize'], 0)
                def timeout_min = getConfigVal(config, ['timeout_minutes'], "90")
                timeout(time: timeout_min, unit: 'MINUTES') {
                    timestamps {
                        run_parallel_in_chunks(config, parallelBuildDockers, bSize)
                        if (config.pipeline_start) {
                            if (config.pipeline_start.image) {
                                image = config.pipeline_start.image
                                config.pipeline_start.name = "pipeline_start"
                                runK8(image, "pipline start on ${image.name}", config, image, [config.pipeline_start])
                            } else {
                                run_step(null, config, "pipeline start", config.pipeline_start, null)
                            }
                        }
                        run_parallel_in_chunks(config, branches, bSize)
                    }
                }
                config.env.pipeline_status = 'SUCCESS'

            } catch (e) {
                config.env.pipeline_status = 'FAILURE'
                reportFail('parallel task', e.toString())

            } finally {
                if (config.pipeline_stop) {
                    if (config.pipeline_stop.image) {
                        image = config.pipeline_stop.image
                        config.pipeline_stop.name = "pipeline_stop"
                        runK8(image, "pipline stop on ${image.name}", config, image, [config.pipeline_stop])
                    } else {
                        run_step(null, config, "pipeline stop", config.pipeline_stop, null)
                    }
                }
            }
        }
    }
}

@NonCPS
def launchMethod(label=null) {

    if (label) {
        def labelObj = Jenkins.instance.getLabel(label)
        if (labelObj && (labelObj.nodes.size() + labelObj.clouds.size() > 0) && (labelObj.nodes[0].numExecutors > 0)) {
            return true
        }
        return false
    }

    def cloudList = Jenkins.instance.clouds


    if (cloudList.size() > 0) {
        return true
    }
    return false

}

def main() {

    def label = 'master'

    // legacy launch via agent with label 'master'
    if (launchMethod(label)) {
        println("Legacy launch on ${label}")
        startPipeline(label)
        return
    }

    // try launch via Jenkins cloud definition
    if (launchMethod()) {
        label = "worker-${UUID.randomUUID().toString()}"
        println("Cloud launch on ${label}")

        podTemplate(
            label: label,
        ) {
            startPipeline(label)
        }
        return
    }

    reportFail("init", "No launch method detected - define clouds or agents in Jenkins")
  }

return this
