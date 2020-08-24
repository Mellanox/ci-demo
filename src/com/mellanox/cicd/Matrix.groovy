#!/usr/bin/groovy
package com.mellanox.cicd;

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
            echo "[INFO] Adding docker to image_map for " + arch + " name: " + item.name
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


def runSteps(config) {
    forceCleanupWS()
    // fetch .git from server and unpack
    unstash "${env.JOB_NAME}"
    onUnstash()

    def shell = getConfigVal(config, ['shell'], '#!/bin/bash -leE')
    config.steps.each { one->
        echo "Step: ${one.name}"
        def cmd = """${shell}
        ${one.run}
        """
        run_shell(cmd, one.name)
    }
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
        val = val.get(item)
        if (!val) {
            return defaultVal
        }
    }

    def ret =  (val instanceof ArrayList)? val[0] : val
    return ret
}

def runK8(image, branchName, config, axis) {

    def cloudName = getConfigVal(config, ['kubernetes','cloud'], "")

    println("[INFO] Running kubernetes ${cloudName}")

    def listV = []
    for (vol in config.volumes) {
        hostPath = vol.get("hostPath")
        mountPath = vol.get("mountPath")
        hpv = hostPathVolume(hostPath: hostPath, mountPath: mountPath)
        listV.add(hpv)
    }

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

    println("[INFO] Running docker on ${nodeName}")

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

    Map tasks = [failFast: true]
    for(int i = 0; i < axes.size(); i++) {
        Map axis = axes[i]
        axis.put("name", image.name)
        axis.put("job", config.job)

        if (exclude && matchMapEntry(exclude, axis)) {
            echo "[INFO] Applying exclude filter on  " + axis.toMapString()
            continue
        } else if (include && ! matchMapEntry(include, axis)) {
            echo "[INFO] Applying include filter on  " + axis.toMapString()
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

        echo "[INFO] task name " + branchName
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

def buildImage(img, filename) {
    unstash "${env.JOB_NAME}"
    onUnstash()

    if(filename == "") {
        println("[ERROR] No docker filename specified, skipping build docker")
        sh 'false'
    }
    juser = env.USER
    juid  = sh(script: "id -u", returnStdout: true).trim()
    jgid  = sh(script: "id -g", returnStdout: true).trim()

    customImage = docker.build("${img}",
        "--build-arg _UID=${juid} " +
        "--build-arg _GID=${jgid} " +
        "--build-arg _LOGIN=${juser} " +
        "-f ${filename} . "
        )
    customImage.push()
}

// returns a list of changed files
@NonCPS
String getChangedFilesList() {

    changedFiles = []
    println("XXXXXXX in")
    for (changeLogSet in currentBuild.changeSets) { 
        for (entry in changeLogSet.getItems()) { // for each commit in the detected changes
            for (file in entry.getAffectedFiles()) {
                println("XXXXXX " + file.getPath())
                changedFiles.add(file.getPath()) // add changed file to list
            }
        }
    }

    return changedFiles

}

def main() {
    node("master") {

        stage("Prepare checkout") {
            forceCleanupWS()
            checkout scm
            
            // create git tarball on server, agents will copy it and unpack
            sh "tar cf scm-repo.tar .git"
            stash includes: "scm-repo.tar", name: "${env.JOB_NAME}"
        }

        files = findFiles(glob: ".ci/${env.conf_file}")
        if (0 == files.size()) {
            println("[ERROR] No conf_file found by .ci/${env.conf_file}")
            sh 'false'
        }


        def changedFiles = [:]

        getChangedFilesList()
        //try {
        //    sh("git --version;")
        //    def cmd_tree = "git diff-tree --no-commit-id --name-only -r ${env.sha1}"
        //    def changedFiles = sh(returnStdout: true, 
        //                        script: cmd_tree).trim().tokenize().collectEntries {[it, it.toUpperCase()]}
//
  //      } catch (e) {
    //        println("XXXXX " + e)
     //   }


        files.each { file ->
            def branches = [:]
            def config = readYaml(file: file.path)
            println ("New Job: " + config.job + " file: " + file.path)

            def arch_distro_map = gen_image_map(config)
            arch_distro_map.each { arch, images ->
                images.each { image ->
                    def img      = image.url
                    def filename = image.filename
                    def distro   = image.name


                    def customImage
                    node ("${arch} && docker") {
                        stage("Prepare docker image for ${config.job}/$arch/$distro") {
                            echo "Going to fetch docker image: ${img} from ${config.registry_host}"
                            def need_build = 0
                            docker.withRegistry("https://${config.registry_host}", config.registry_auth) {
                                try {
                                    echo("[INFO] Pulling image - ${img}")
                                    customImage = docker.image(img).pull()
                                } catch (exception) {
                                    echo("[INFO] Image NOT found - ${img} - will build ${filename} ...")
                                    need_build++
                                }

                                if ("${env.build_dockers}" == "true") {
                                    echo("[INFO] Forcing building file per user request: ${filename} ... ")
                                    need_build++
                                }
                                if (changedFiles.containsKey(filename)) {
                                    echo("[INFO] Forcing building, file modified by commit: ${filename} ... ")
                                    need_build++
                                }
                                if (need_build) {
                                    echo("[INFO] Building - ${img} - ${filename}")
                                    buildImage(img, filename)
                                }
                            }
                        }
                    }
                    branches += getMatrixTasks(image, config)
                }
            }
        
            try {
                timestamps {
                    parallel branches
                }
            } finally {
                node("master") {
                    stage("Finalize ${config.job}") {
                        def cmd = config.steps?.finalize?.run[0]
                        sh "${cmd}"
                    }
                }
            }
        }

    }

}
