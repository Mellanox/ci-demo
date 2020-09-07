#!/usr/bin/groovy
package com.mellanox.cicd;

class Logger {
    def ctx
    def cat

    Logger(ctx) {
        this.ctx = ctx
        this.cat = "matrix_job"
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
        if (this.ctx.env.DEBUG)
            this.ctx.echo this.cat + " DEBUG: ${message}"
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

def run_shell(cmd, title) {
    sh(script: cmd, label: title)
}

def forceCleanupWS() {
    env.WORKSPACE = pwd()
    def cmd = """
    rm -rf ${env.WORKSPACE}/*
    find ${env.WORKSPACE}/ -maxdepth 1 -name '.*' | xargs rm -rf 
    """
    run_shell(cmd, "Clean workspace")
}

def gen_image_map(config) {
    def image_map = [:]

    if (config.get("matrix") && config.matrix.axes.arch) {
        for (arch in config.matrix.axes.arch) {
            image_map[arch] = []
        }
    } else {
        for (dfile in config.runs_on_dockers) {
            if (dfile.arch) {
                image_map["${dfile.arch}"] = []
            }
        }
    }


    image_map.each { arch, images ->
        config.runs_on_dockers.each { dfile ->
            if (!dfile.file) {
                dfile.file = ""
            }
            def item = [\
                arch: "${arch}", \
                tag:  "${dfile.tag}", \
                filename: "${dfile.file}", \
                url: "${config.registry_host}${config.registry_path}/${arch}/${dfile.name}:${dfile.tag}", \
                name: "${dfile.name}" \
            ]
            config.logger.debug("Adding docker to image_map for " + arch + " name: " + item.name)
            images.add(item)
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

    def cmd = """#!/bin/bash -l
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
            config.logger.error("Failed to add artifacts: " + args + " reason: " + e)
        }
    }
}

def runSteps(config) {
    forceCleanupWS()
    // fetch .git from server and unpack
    unstash "${env.JOB_NAME}"
    onUnstash()

    config.steps.each { one->
        def shell = one.shell? one.shell : '#!/bin/bash -leE'
        echo "Step: ${one.name}"
        def cmd = """${shell}
        ${one.run}
        """
        try {
            run_shell(cmd, one.name)
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

def getConfigVal(config, list, defaultVal) {
    def val = config
    for (item in list) {
        config.logger.debug("Checking $item")
        val = val.get(item)
        if (val == null) {
            config.logger.debug("Defaulting " + list.toString() + " = " + defaultVal)
            return defaultVal
        }
    }

    def ret =  (val instanceof ArrayList)? val[0] : val
    config.logger.debug("Found " + list.toString() + " = " + ret)
    return ret
}

def parseListV(config) {
    def listV = []
    for (vol in config.volumes) {
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

    def listV = parseListV(config)
    def cname = image.get("name").replaceAll("[\\.:/_]","")
    def nodeSelector = getConfigVal(config, ['kubernetes', 'nodeSelector'], "")

    podTemplate(cloud: cloudName, runAsUser: "0", runAsGroup: "0",
                nodeSelector: nodeSelector,
                containers: [
                    containerTemplate(name: cname, image: image.url, ttyEnabled: true, alwaysPullImage: true, command: 'cat')
                ],
                volumes: listV
                )
    {
        node(POD_LABEL) {
            stage (branchName) {
                container(cname) {
                    runSteps(config)
                }
            }
        }
    }
}

def resolveTemplate(varsMap, str) {
    GroovyShell shell = new GroovyShell(new Binding(varsMap))
    return shell.evaluate('"' + str +'"')
}

def runDocker(image, branchName, config, axis) {
    def label = getConfigVal(config, ['docker_node'], '${arch} && docker')
    def nodeName = resolveTemplate(axis, label)

    config.logger.info("Running docker on ${nodeName}")

    node(nodeName) {
        stage(branchName) {
            def opts = getDockerOpt(config)
            docker.image(image.url).inside(opts) {
                runSteps(config)
            }
        }
    }

}

Map getTasks(axes, image, config, include=null, exclude=null) {

    def val = getConfigVal(config, ['failFast'], true)
    config.logger.debug("failFast = " + val)

    Map tasks = [failFast: val]
    for(int i = 0; i < axes.size(); i++) {
        Map axis = axes[i]
        axis.put("name", image.name)
        axis.put("job", config.job)
        axis.put("variant", i + 1)

        if (exclude && matchMapEntry(exclude, axis)) {
            config.logger.info("Applying exclude filter on  " + axis.toMapString())
            continue
        } else if (include && ! matchMapEntry(include, axis)) {
            config.logger.info("Applying include filter on  " + axis.toMapString())
            continue
        }

        def branchName = axis.values().join(', ')
        //def branchName = "mypod-${UUID.randomUUID().toString()}"

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
                if(config.get("kubernetes")) {
                    runK8(image, branchName, config, axis)
                } else {
                    runDocker(image, branchName, config, axis)
                }
            }
        }
    }
    return tasks
}
Map getMatrixTasks(image, config) {
    List axes = []
    List include = null, exclude = null

    if (config.get("matrix")) {
        axes = getMatrixAxes(config.matrix.axes).findAll()
        if (config.get("matrix").get("exclude")) {
            exclude = config.matrix.exclude
        }
        if (config.get("matrix").get("include")) {
            include = config.matrix.include
        }
    } else {
        axes.add(image)
    }

    return getTasks(axes, image, config, include, exclude)
}

def buildImage(img, filename, config) {
    if(filename == "") {
        config.logger.error("No docker filename specified, skipping build docker")
        sh 'false'
    }
    customImage = docker.build("${img}", "-f ${filename} . ")
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
        cFiles = sh(returnStdout: true, script: dcmd, label: 'Calculating changed files list').trim().tokenize()

        cFiles.each { oneFile ->
            logger.debug("Tracking Changed File: " + oneFile)
        }
    } catch(e) {
        logger.error("Unable to calc changed file list - make sure shallow clone depth is configured in Jenkins, reason: " + e)
    }

    return cFiles
}

def build_docker_on_k8(image, config) {

    def listV = parseListV(config)
    def v1 = hostPathVolume(hostPath: '/var/run/docker.sock', mountPath: '/var/run/docker.sock')
    listV.add(v1)

    def img      = image.url
    def arch     = image.arch
    def filename = image.filename
    def distro   = image.name
    def cloudName = getConfigVal(config, ['kubernetes','cloud'], "")

    config.logger.debug("Checking docker image $distro availability")

    podTemplate(cloud: cloudName, runAsUser: "0", runAsGroup: "0",
                containers: [
                    containerTemplate(name: 'docker', image: 'docker:19.03', ttyEnabled: true, alwaysPullImage: true, command: 'cat')
                ],
                volumes: listV
                )
    {
        node(POD_LABEL) {
            unstash "${env.JOB_NAME}"
            onUnstash()

            container('docker') {
                stage("Prepare docker image for ${config.job}/$arch/$distro") {
                    config.logger.info("Going to fetch docker image: ${img} from ${config.registry_host}")
                    def need_build = 0

                    docker.withRegistry("https://${config.registry_host}", config.registry_auth) {
                        try {
                            config.logger.info("Pulling image - ${img}")
                            customImage = docker.image(img).pull()
                        } catch (exception) {
                            config.logger.info("Image NOT found - ${img} - will build ${filename} ...")
                            need_build++
                        }

                        if ("${env.build_dockers}" == "true") {
                            config.logger.info("Forcing building file per user request: ${filename} ... ")
                            need_build++
                        }
                        if (config.get("cFiles").contains(filename)) {
                            config.logger.info("Forcing building, file modified by commit: ${filename} ... ")
                            need_build++
                        }
                        if (need_build) {
                            config.logger.info("Building - ${img} - ${filename}")
                            buildImage(img, filename, config)
                        }
                    }
                }
            }
        }
    }
}


def main() {
    node("master") {

        logger = new Logger(this)
        def scmVars

        stage("Prepare checkout") {
            forceCleanupWS()
            scmVars = checkout scm
            
            env.GIT_COMMIT      = scmVars.GIT_COMMIT
            env.GIT_PREV_COMMIT = scmVars.GIT_PREVIOUS_COMMIT

            logger.info("Git commit: ${env.GIT_COMMIT} prev commit: ${env.GIT_PREV_COMMIT}")
            // create git tarball on server, agents will copy it and unpack
            sh "tar cf scm-repo.tar .git"
            stash includes: "scm-repo.tar", name: "${env.JOB_NAME}"
        }

        files = findFiles(glob: "${env.conf_file}")
        if (0 == files.size()) {
            logger.error("No conf_file found by ${env.conf_file}")
            sh 'false'
        }


        files.each { file ->
            def branches = [:]
            def config = readYaml(file: file.path)
            def cmd
            logger.info("New Job: " + config.job + " file: " + file.path)

            config.put("logger", logger)
            config.put("cFiles", getChangedFilesList(config))

            if (config.pipeline_start) {
                cmd = config.pipeline_start.run
                if (cmd) {
                    logger.debug("Starting pipeline")
                    stage("Start ${config.job}") {
                        run_shell("${cmd}", "start")
                    }
                }
            }

            def arch_distro_map = gen_image_map(config)
            arch_distro_map.each { arch, images ->
                images.each { image ->
                    build_docker_on_k8(image, config)
                    branches += getMatrixTasks(image, config)
                }
            }
        
            try {
                timestamps {
                    parallel branches
                }
            } finally {
                if (config.pipeline_stop) {
                    cmd = config.pipeline_stop.run
                    if (cmd) {
                        logger.debug("Stopping pipline")
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
