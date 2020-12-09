#!/usr/bin/groovy
package com.mellanox.cicd;

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

    def fatal(String message) {
        this.ctx.echo this.cat + " FATAL: ${message}"
        this.ctx.run_shell("false", "Fatal error")
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

def run_shell(cmd, title, retOut=false) {
    sh(script: cmd, label: title, returnStdout: retOut)
}

def forceCleanupWS() {
    env.WORKSPACE = pwd()
    def cmd = """
    rm -rf ${env.WORKSPACE}/*
    find ${env.WORKSPACE}/ -maxdepth 1 -name '.*' | xargs rm -rf 
    """
    run_shell(cmd, "Clean workspace")
}


def getArchConf(config, arch) {

    def k8sArchConfTable = [:]

    config.logger.debug("getArchConf: arch=" + arch)
    
    k8sArchConfTable['x86_64']  = [
        nodeSelector: 'kubernetes.io/arch=amd64',
        jnlpImage: 'jenkins/inbound-agent:latest',
        dockerImage: 'docker:19.03'
    ]

    if (!config.registry_jnlp_path) {
        def array = config.registry_path.split("/")
        config.registry_jnlp_path = array[array.length - 2]
    }

    k8sArchConfTable['aarch64'] = [
        nodeSelector: 'kubernetes.io/arch=arm64',
        jnlpImage: "${config.registry_host}/${config.registry_jnlp_path}/jenkins-arm-agent-jnlp:latest",
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
    
    def varsMap = [
        registry_path:  config.registry_path,
        registry_jnlp_path: config.registry_jnlp_path,
        registry_host: config.registry_host
    ]

    k8sArchConfTable[arch].each { key, val ->
        key = resolveTemplate(varsMap, val)
    }

    config.logger.debug("getArchConf[${arch}] " + k8sArchConfTable[arch])
    return k8sArchConfTable[arch]
}

def gen_image_map(config) {
    def image_map = [:]

    def arch_list = getConfigVal(config, ['matrix', 'axes', 'arch'], null, false)

    if (arch_list) {
        for (arch in arch_list) {
            image_map[arch] = []
        }
    } else {
        for (dfile in config.runs_on_dockers) {
            if (dfile.arch) {
                image_map["${dfile.arch}"] = []
            } else {
                config.logger.fatal("Please define tag 'arch' for image ${dfile.name} in 'runs_on_dockers' section of yaml file")
            }
        }
    }


    image_map.each { arch, images ->

        def k8sArchConf = getArchConf(config, arch)
        if (!k8sArchConf) {
            config.logger.warn("gen_image_map | skipped unsupported arch (${arch})")
            return
        }

        config.runs_on_dockers.each { item ->

            def dfile = item.clone()

            if (!dfile.arch) {
                dfile.arch = arch
            }

            if (!dfile.file) {
                dfile.file = ""
            }
    
            if (dfile.arch && dfile.arch != arch) {
                config.logger.warn("skipped conf: " + arch + " name: " + dfile.name)
                return
            }

            if (!dfile.build_args) {
                dfile.build_args = ""
            }

            if (!dfile.uri) {
                // default URI subpath for Docker image on a harbor
                dfile.uri = "${arch}/${dfile.name}"
            } else {
                def env_map = env.getEnvironment()
                dfile.each { key, value ->
                    env_map[key] = value
                }
                dfile.uri = resolveTemplate(env_map, dfile.uri)
            }

            dfile.url = "${config.registry_host}${config.registry_path}/${dfile.uri}:${dfile.tag}"
            dfile.filename = "${dfile.file}"

            config.logger.debug("Adding docker to image_map for " + dfile.arch + " name: " + dfile.name)
            images.add(dfile)
        }
    }
    return image_map
}

def matchMapEntry(filters, entry) {
    def match
    for (filter in filters) {
        match = 1
        filter.each { k,v ->
            if (v != entry[k]) {
                match = 0
                return
            }
        }
        if (match) {
            break
        }
    }
    return match
}

def onUnstash() {

    env.PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    env.WORKSPACE = pwd()

    def cmd = """#!/bin/bash
    hash -r
    tar xf scm-repo.tar
    git reset --hard
    rm -f scm-repo.tar
    """
    run_shell(cmd, "Extracting project files into workspace")
}

def attachArtifacts(args) {
    if(args) {
        try {
            archiveArtifacts(artifacts: args, allowEmptyArchive: true )
        } catch (e) {
            config.logger.warn("Failed to add artifacts: " + args + " reason: " + e)
        }
    }
}

@NonCPS
def int getDebugLevel() {
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

def getDefaultShell(config=null, step=null, shell='#!/bin/bash -l') {

    def ret = shell
    if ((step != null) && (step.shell != null)) {
        ret = step.shell
    } else if ((config != null) && (config.shell != null)) {
        ret = config.shell
    } else if (isDebugMode()) {
        ret += 'x'
    }

    new Logger(this).debug("shell: " + ret)
    return ret
}

def run_step(image, config, title, oneStep) {

    if (oneStep.get("enable") != null && !oneStep.enable) {
        config.logger.debug("Step '${oneStep.name}' is disabled in project yaml file, skipping")
        return
    }

    def skip = 0
    if (image.get("category") != null && image.category == "tool") {
        config.logger.debug("Detected image category=tool")
        skip++
    }

    def customSel = oneStep.get("containerSelector")
    if (customSel != null && matchMapEntry(customSel, image)) {
        config.logger.debug("step name='" + oneStep.name + "' requests container with attr=" + customSel)
        skip--
    }

    if (skip > 0) {
        config.logger.debug("Skipping step=" + oneStep.name + " for image category=tool")
        return
    }

    def shell = getDefaultShell(config, oneStep)
    def script = oneStep.run

    config.logger.debug("Running step with shell=" + shell)
    run_shell("echo Starting step: ${title}", title)

    if (oneStep.env) {
        oneStep.env.each {k,v ->
            env[k] = v
        }
    }

    if (shell == "action") {

        def argList = []
        def vars = [:]
        vars['env'] = env
        for (arg in oneStep.args) {
            arg = resolveTemplate(vars, arg)
            argList.add(arg)
        }

        config.logger.debug("Running step action=" + script + " args=" + argList)
        this."${script}"(argList)
        return
    }

    def cmd = """${shell}
    ${script}
    """
    run_shell(cmd, title)
}

def runSteps(image, config, branchName) {
    forceCleanupWS()
    // fetch .git from server and unpack
    unstash "${env.JOB_NAME}"
    onUnstash()

    def parallelNestedSteps = [:]
    config.steps.eachWithIndex { one, i ->

        def par = one.get("parallel")
        def oneStep = one
        // collect parallel steps (if any) and run it when non-parallel step discovered or last element.
        if ( par != null && par == true) {
            def stepName = branchName + "->" + one.name
            parallelNestedSteps[stepName] = {run_step(image, config, stepName, oneStep)}
            // last element - run and flush
            if (i == config.steps.size() -1) {
                parallel(parallelNestedSteps)
                parallelNestedSteps = [:]
            }
            return
        }
        // non-parallel step discovered, need to flush all parallel 
        // steps collected previously to keep ordering.
        // run non-parallel step right after
        if (parallelNestedSteps.size() > 0) {
            parallel(parallelNestedSteps)
            parallelNestedSteps = [:]
        }
        try {
            run_step(image, config, one.name, oneStep)
        } catch (e) {
            if (one.get("onfail") != null) {
                run_shell(one.onfail, "onfail command for ${one.name}")
            }
            attachArtifacts(config.archiveArtifacts)
            throw(e)
        } finally {
            if (one.get("always") != null) {
                run_shell(one.always, "always command for ${one.name}")
            }
            attachArtifacts(one.archiveArtifacts)
        }
    }
    attachArtifacts(config.archiveArtifacts)
}

def getConfigVal(config, list, defaultVal=null, toString=true) {
    def val = config
    for (item in list) {
        config.logger.trace(5, "getConfigVal: Checking $item in config file")
        val = val.get(item)
        if (val == null) {
            config.logger.trace(5, "getConfigVal: Defaulting " + list.toString() + " = " + defaultVal)
            return defaultVal
        }
    }

    def ret
    if (toString && (val instanceof ArrayList) && (val.size() == 1)) {
        ret = val[0]
    } else {
        ret = val
    }
    config.logger.trace(5, "getConfigVal: Found " + list.toString() + " = " + ret)
    return ret
}

def parseListV(volumes) {
    def listV = []
    for (vol in volumes) {
        hostPath = vol.get("hostPath")
        mountPath = vol.get("mountPath")
        hpv = hostPathVolume(hostPath: hostPath, mountPath: mountPath)
        listV.add(hpv)
    }
    return listV
}

def runK8(image, branchName, config, axis) {

    def cloudName = getConfigVal(config, ['kubernetes','cloud'], "")

    config.logger.info("Running kubernetes ${cloudName}")

    def str = ""
    axis.collect { key, val ->
        str += "$key = $val\n"
    }

    config.logger.debug("runK8 | str: ${str}")

    def listV = parseListV(config.volumes)
    def cname = image.get("name").replaceAll("[\\.:/_]","")

    config.logger.debug("runK8 | arch: ${axis.arch}")

    def k8sArchConf = getArchConf(config, axis.arch)
    def nodeSelector = ''

    if (!k8sArchConf) {
        config.logger.error("runK8 | arch conf is not defined for ${axis.arch}")
        return
    }

    nodeSelector = k8sArchConf.nodeSelector
    config.logger.info("runK8 | nodeSelector: ${nodeSelector}")

    if (axis.nodeSelector) {
        if (nodeSelector) {
            nodeSelector = nodeSelector + ',' + axis.nodeSelector
        } else {
            nodeSelector = axis.nodeSelector
        }
    }

    podTemplate(
        cloud: cloudName,
        runAsUser: "0",
        runAsGroup: "0",
        nodeSelector: nodeSelector,
        containers: [
            containerTemplate(name: 'jnlp', image: k8sArchConf.jnlpImage, args: '${computer.jnlpmac} ${computer.name}'),
            containerTemplate(name: cname, image: image.url, ttyEnabled: true, alwaysPullImage: true, command: 'cat')
        ],
        volumes: listV
    )
    {
        node(POD_LABEL) {
            stage (branchName) {
                container(cname) {
                    runSteps(image, config, branchName)
                }
            }
        }
    }
}

def resolveTemplate(varsMap, str) {
    GroovyShell shell = new GroovyShell(new Binding(varsMap))
    def res = shell.evaluate('"' + str +'"')
    new Logger(this).trace(3, "resolveTemplate: Evaluating varsMap: " + varsMap.toString() + " str: " + str + " res: " + res)
    return res
}

def getDockerOpt(config) {
    def opts = getConfigVal(config, ['docker_opt'], "")
    if (config.get("volumes")) {
        for (vol in config.volumes) {
            hostPath = vol.get("hostPath")? vol.hostPath : vol.mountPath
            opts += " -v ${vol.mountPath}:${hostPath}"
        }
    }
    return opts
}

def runDocker(image, config, branchName=null, axis=null, Closure func, runInDocker=true) {
    def nodeName = image.nodeLabel

    config.logger.debug("Running docker on node: ${nodeName} branch: ${branchName}")

    node(nodeName) {
        unstash "${env.JOB_NAME}"
        onUnstash()
        stage(branchName) {
            if (runInDocker) {
                def opts = getDockerOpt(config)
                docker.image(image.url).inside(opts) {
                    func(image, config)
                }
            } else {
                func(image, config)
            }
        }
    }

}


Map getTasks(axes, image, config, include, exclude) {

    def val = getConfigVal(config, ['failFast'], true)

    config.logger.debug("getTasks() -->")

    Map tasks = [failFast: val]
    for(int i = 0; i < axes.size(); i++) {
        Map axis = axes[i]

        if(axis.arch != image.arch) {
            config.logger.debug("getTasks: skipping axis=" + axis + " as its arch does not match image=" + image)
            continue
        }

        // todo: some keys from matrix can be same as in image map and it will cause confusion
        // maybe need to prefix image keys with special prefix to distinguish or copy only non-existing keys
        axis += image
        axis.put("job", config.job)
        axis.put("variant", i + 1)
        axis.put("axis_index", i + 1)


        if (exclude.size() && matchMapEntry(exclude, axis)) {
            config.logger.debug("Excluding by 'exclude' rule, axis " + axis.toMapString())
            continue
        } else if (include.size() && ! matchMapEntry(include, axis)) {
            config.logger.debug("Excluding by 'include' rule, axis " + axis.toMapString())
            continue
        }

        config.logger.info("Working on axis " + axis.toMapString())

        def tmpl = getConfigVal(config, ['taskName'], "${axis.arch}/${image.name} v${axis.axis_index}")
        def branchName = resolveTemplate(axis, tmpl)

        // convert the Axis into valid values for withEnv step
        if (config.get("env")) {
            axis += config.env
        }
        List axisEnv = axis.collect { k, v ->
            "${k}=${v}"
        }

        config.logger.debug("task name " + branchName)
        def arch = axis.arch
        tasks[branchName] = { ->
            withEnv(axisEnv) {
                if((config.get("kubernetes") == null) && (image.nodeLabel == null)) {
                    config.logger.fatal("Please define kubernetes cloud name in yaml config file or define nodeLabel for docker")
                }
                if (image.nodeLabel) {
                    runDocker(image, config, branchName, axis, { pimage, pconfig -> runSteps(pimage, pconfig, branchName) })
                } else {
                    runK8(image, branchName, config, axis)
                }
            }
        }
    }
    return tasks
}

def getMatrixTasks(image, config) {

    def include = [], exclude = [], axes = []
    config.logger.debug("getMatrixTasks() --> image=" + image)

    if (config.get("matrix")) {
        axes = getMatrixAxes(config.matrix.axes).findAll()
        exclude = getConfigVal(config, ['matrix', 'exclude'], [])
        include = getConfigVal(config, ['matrix', 'include'], [])
    } else {
        axes.add(image)
    }

    config.logger.debug("Filters include size: " + include.size() + " exclude size: " + exclude.size())
    return getTasks(axes, image, config, include, exclude)
}

def buildImage(img, filename, extra_args, config) {
    if(filename == "") {
        config.logger.fatal("No docker filename specified, skipping build docker")
    }
    customImage = docker.build("${img}", "-f ${filename} ${extra_args} . ")
    customImage.push()
}


String getChangedFilesList(config) {

    def cFiles = []

    def logger = config.get("logger")
    logger.debug("Calculating changes for git commit: ${env.GIT_COMMIT} prev commit: ${env.GIT_PREV_COMMIT}")

    try {
        def dcmd
        if (env.GIT_COMMIT && env.GIT_PREV_COMMIT) {
            dcmd = "git diff --name-only ${env.GIT_PREV_COMMIT} ${env.GIT_COMMIT}"
        } else {
            def br  = env.ghprbTargetBranch? env.ghprbTargetBranch : "master"
            def sha = env.ghprbActualCommit? env.ghprbActualCommit : "HEAD"
            dcmd = "git diff --name-only origin/${br}..${sha}"
        }
        cFiles = run_shell(dcmd, 'Calculating changed files list', true).trim().tokenize()

        cFiles.each { oneFile ->
            logger.debug("Tracking Changed File: " + oneFile)
        }
    } catch(e) {
        logger.warn("Unable to calc changed file list - make sure shallow clone depth is configured in Jenkins, reason: " + e)
    }

    return cFiles
}

def buildDocker(image, config) {

    def img = image.url
    def arch = image.arch
    // Vasily Ryabov: we need .toString() to make changed_files.contains(filename) work correctly
    // See https://stackoverflow.com/q/56829842/3648361
    def filename = image.filename.toString().trim()
    def distro = image.name
    def extra_args = image.build_args
    def changed_files = config.get("cFiles")

    stage("Prepare docker image for ${config.job}/$arch/$distro") {
        config.logger.info("Going to fetch docker image: ${img} from ${config.registry_host}")
        def need_build = 0

        docker.withRegistry("https://${config.registry_host}", config.registry_auth) {
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
            config.logger.debug("Dockerfile name: ${filename}")
            config.logger.debug("Changed files: ${changed_files}")
            if (changed_files.contains(filename)) {
                config.logger.info("Forcing building, file modified by commit: ${filename} ... ")
                need_build++
            }
            if (need_build) {
                config.logger.info("Building - ${img} - ${filename}")
                buildImage(img, filename, extra_args, config)
            }
        }
    }
}


def build_docker_on_k8(image, config) {

    def myVols = config.volumes.collect()
    myVols.add([mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'])

    def listV = parseListV(myVols)

    def cloudName = getConfigVal(config, ['kubernetes','cloud'], "")

    config.logger.debug("Checking docker image availability")

    def k8sArchConf = getArchConf(config, image.arch)
    def nodeSelector = ''

    if (!k8sArchConf) {
        config.logger.error("build_docker_on_k8 | arch conf is not defined for ${image.arch}")
        return
    }

    nodeSelector = k8sArchConf.nodeSelector
    config.logger.info("build_docker_on_k8 | nodeSelector: ${nodeSelector}")

    if (image.nodeSelector) {
        if (nodeSelector) {
            nodeSelector = nodeSelector + ',' + image.nodeSelector
        } else {
            nodeSelector = image.nodeSelector
        }
    }

    podTemplate(
        cloud: cloudName,
        runAsUser: "0",
        runAsGroup: "0",
        nodeSelector: nodeSelector,
        containers: [
            containerTemplate(name: 'jnlp', image: k8sArchConf.jnlpImage, args: '${computer.jnlpmac} ${computer.name}'),
            containerTemplate(name: 'docker', image: k8sArchConf.dockerImage, ttyEnabled: true, alwaysPullImage: true, command: 'cat')
        ],
        volumes: listV
    )
    {
        node(POD_LABEL) {
            unstash "${env.JOB_NAME}"
            onUnstash()

            container('docker') {
                buildDocker(image, config)
            }
        }
    }
}

def run_parallel_in_chunks(config, myTasks, bSize) {
    if (bSize <= 0) {
        bSize = myTasks.size()
    }

    config.logger.trace(3, "run_parallel_in_chunks: batch size is ${bSize}")

    (myTasks.keySet() as List).collate(bSize).each {
        parallel myTasks.subMap(it)
    }
}


def loadConfigFile(filepath, logger) {
    def config = readYaml(file: filepath)
    def rawFile = readFile(filepath)

    logger.debug("loadConfigFile:\n" + rawFile)

    if (config.get("matrix")) {
        if (config.matrix.include != null && config.matrix.exclude != null) {
            logger.fatal("matrix.include and matrix.exclude sections in config file=${filepath} are mutually exclusive. Please keep only one.")
        }
    }
    return config
}

def main() {
    node("master") {

        logger = new Logger(this)

        stage("Prepare checkout") {
            forceCleanupWS()
            def scmVars = checkout scm
            
            env.GIT_COMMIT      = scmVars.GIT_COMMIT
            env.GIT_PREV_COMMIT = scmVars.GIT_PREVIOUS_COMMIT

            logger.debug("Git commit: ${env.GIT_COMMIT} prev commit: ${env.GIT_PREV_COMMIT}")
            // create git tarball on server, agents will copy it and unpack
            run_shell("tar cf scm-repo.tar .git", 'Extracting scm repository files')
            stash includes: "scm-repo.tar", name: "${env.JOB_NAME}"
        }

        files = findFiles(glob: "${env.conf_file}")
        if (0 == files.size()) {
            logger.fatal("No conf_file found by ${env.conf_file}")
        }


        files.each { file ->
            def branches = [:]
            def config = loadConfigFile(file.path, logger)
            logger.info("New Job: " + config.job + " file: " + file.path)

            config.put("logger", logger)
            config.put("cFiles", getChangedFilesList(config))

            if (config.pipeline_start) {
                def cmd = config.pipeline_start.run
                if (cmd) {
                    logger.debug("Running pipeline_start")
                    stage("Start ${config.job}") {
                        run_shell("${cmd}", "start")
                    }
                }
            }

// prepare MAP in format:
// $arch -> List[$docker, $docker, $docker]
// this is to avoid that multiple axis from matrix will create own same copy for $docker but creating it upfront.


            def val = getConfigVal(config, ['failFast'], true)
            def parallelBuildDockers = [failFast: val]

            def arch_distro_map = gen_image_map(config)
            arch_distro_map.each { arch, images ->
                images.each { image ->
                    parallelBuildDockers[image.name] = {
                        if (image.nodeLabel) {
                            runDocker(image, config, "Preparing docker image", null, { pimage, pconfig -> buildDocker(pimage, pconfig) }, false)
                        } else {
                            build_docker_on_k8(image, config)
                        }
                    }
                    branches += getMatrixTasks(image, config)
                }
            }
        
            try {
                def bSize = getConfigVal(config, ['batchSize'], 0)
                def timeout_min = getConfigVal(config, ['timeout_minutes'], "90")
                timeout(time: timeout_min, unit: 'MINUTES') {
                    timestamps {
                        run_parallel_in_chunks(config, parallelBuildDockers, bSize)
                        run_parallel_in_chunks(config, branches, bSize)
                    }
                }
            } catch (e) {
                logger.warn("Pipeline was terminated by exception: " + e)

            } finally {
                if (config.pipeline_stop) {
                    def cmd = config.pipeline_stop.run
                    if (cmd) {
                        logger.debug("running pipeline_stop")
                        stage("Stop ${config.job}") {
                            run_shell("${cmd}", "stop")
                        }
                    }
                }
            }
        }
    }
}

return this
