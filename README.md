# Matrix workflow project for CI/CD

This is Jenkins CI/CD demo project. You can create custom workflows to automate your project software life cycle process. 

You need to configure workflows using YAML syntax, and save them as workflow files in your repository. 
Once you've successfully created a YAML workflow file and triggered the workflow - Jenkins parse flow and execute it.


## Quick start

1. Copy ```.ci/Jenkinsfile.shlib``` to your new github project, under ```.ci/``` 

2. Copy ```.ci/Makefile``` to your new github project, under ```.ci/``` 

3. Create ```.ci/job_matrix.yaml``` basic workflow file with content:

``` yaml
---
job: ci-demo

registry_host: harbor.mellanox.com
registry_path: /swx-storage/ci-demo
registry_auth: swx-storage

volumes:
  - {mountPath: /hpc/local, hostPath: /hpc/local}
  - {mountPath: /auto/sw_tools, hostPath: /auto/sw_tools}
  - {mountPath: /.autodirect/mtrswgwork, hostPath: /.autodirect/mtrswgwork}
  - {mountPath: /.autodirect/sw/release, hostPath: /.autodirect/sw/release}

kubernetes:
  cloud: swx-k8s

runs_on_dockers:
  - {file: '.ci/Dockerfile.centos7.7.1908', name: 'centos7-7', tag: 'latest'}
  - {file: '.ci/Dockerfile.ubuntu16-4', name: 'ubuntu16-4', tag: 'latest'}

matrix:
  axes:
    flags:
      - '--enable-debug'
      - '--prefix=/tmp/install'
    arch:
      - x86_64

pipeline_start:
  run: echo Starting new job

pipeline_stop:
  run: echo All done

steps:
  - name: Install mofed
    run: |
      echo Installing driver: ${driver} ...
      mofed_installer_exe=/auto/sw/release/mlnx_ofed/MLNX_OFED/mlnx_ofed_install
      mofed_installer_opt='--user-space-only --without-fw-update --all -q --skip-unsupported-devices-check'
      sudo env build=$driver $mofed_installer_exe $mofed_installer_opt

  - name: Configure
    run: |
      ./autogen.sh
      ./configure $flags

  - name: Build
    run: make -j 2 all

  - name: Install
    run: make -j 2 install
     
```

4. Copy ```.ci/proj_jjb.yaml``` to ```.ci``` folder in your project  and change github URL to point to your own github project as [here](.ci/proj_jjb.yaml#L67)

5. Register new Jenkins project via jenkins-job cli (or create new with UI)

``` bash
% cd .ci; make jjb
```

6. Trigger run via Jenkins UI


## Matrix job example

![Alt text](.ci/pict/snapshot2.png?raw=true "Matrix Job")


## Important files

The CI scripts are located in ```.ci``` folder
Jenkins behavior can be controlled by job_matrix.yaml file which has similar syntax/approach as Github actions.

* jenkinsfile parses .ci/job_matrix*.yaml files
* the job is bootstrapped and executing according to steps, as defined in yaml file
* The method works with vanilla jenkins [Jenkinsfile](.ci/Jenkinsfile)
* The method also works with pipeline as shared library [Jenkinsfile.shlib](.ci/Jenkinsfile.shlib) and uses this [shlib](src/com/mellanox/cicd/Matrix.groovy)

* [Basic job](.ci/job_matrix_basic.yaml) definition can be found at ```.ci/job_matrix_basic.yaml```
* [Advanced job](.ci/job_matrix.yaml) definition is at ```.ci/job_matrix.yaml```
* The actual [Jenkinsfile](.ci/Jenkinsfile.shlib) that gets executed is here at ```.ci/Jenkinsfile.shlib```


## Job matrix file

* Job file can contain array of steps, which are executed sequentially in the docker
* Job file can define own matrix and Jenkinsfile will generate axis combinations to execute in the docker
* Job file can define list of dockerfiles, in ```.ci/Dockerfiles.*``` that will be added to matrix combinations.
* Job file can define if need to build docker from dockerfiles and push resulting image to the registry or just fetch image from registry.
* Job can define different [environment](.ci/job_matrix.yaml#L13) variables that will be added to step run environment
* Job can define optional [include/exclude](.ci/job_matrix.yaml#L36) filters to select desired matrix dimensions

## Jenkins job builder

* Demo contains [Jenkins Job Builder](https://docs.openstack.org/infra/jenkins-job-builder) config [file](.ci/jjb_proj.yaml) 
which loads Jenkins project definition into Jenkins server.
* Jenkins project descibed by [file](.ci/jjb_proj.yaml) supports following UI actions/parameters:
 - boolean - rebuild docker files
 - string - Use named Dockerfile (defaul: .ci/Jenkinsfile.shlib)
 - string - Configuration file for Job Matrix (can be regex to load multiple), default: job_matrix.yaml


### Running/Debugging Job Matrix pipeline locally

1. You can fetch docker image descibed in job_matrix.yaml and run steps in it to mimic Jenkins k8 approach

``` shell
% cd .ci
% make shell NAME=ubuntu16-4
docker%% cd /scratch

# the step below needed so workspace files (which belongs to linux $USER) 
# will be copied (and owned) as Docker user 'jenkins' so it can be modified
# from docker shell

docker%% cp -pr /scratch /tmp/ws
docker%% cd /tmp/ws
docker%% ./autogen.sh && ./configure && make
```

2. You can build docker locally (if it does not exist in registry) as following

```
% cd .ci
% make build NAME=ubuntu16-4
```


### Job Matrix yaml - Advanced configuration

``` yaml
---
# Job name
job: ci-demo

# URI to docker registry
registry_host: harbor.mellanox.com

# Path to project`s dockers space under registry
registry_path: /swx-storage/ci-demo

# optional: Path to jnlp containers under $registry_host
# if not set - auto-calculated to $registry_path/..
registry_jnlp_path: /swx-storage

# Credentials (must be defined in Jenkins server configuration) to for access to registry
registry_auth: swx-storage

# k8 cloud name (must be defined in Jenkins server configuration)
kubernetes:
# cloud name to use for all containers
# cloud tag can be specified per specific container in runs_on_containers section
  cloud: swx-k8s
# Example how to use k8 node selector to request specific nodes for allocation
  nodeSelector: 'beta.kubernetes.io/os=linux'
# optional: enforce limits so that the running container is not allowed to use
# more of that resource than the limit you set. If a Container specifies its own limit,
# but does not specify a request, Kubernetes automatically assigns a request
# that matches the limit.
  limits: "{hugepages-1Gi: 1024Mi, hugepages-2Mi: 1024Mi, memory: 2048Mi}"
# optional: request additional k8s resources that isn't supported by
# kubernetes-plugin by default
  requests: "{hugepages-1Gi: 1024Mi, hugepages-2Mi: 1024Mi, memory: 2048Mi}"

# optional: can specify jenkins defined credentials and refer/request by credentialsId in step that
# requires it
credentials:
  - {credentialsId: '311997c9-cc1c-4d5d-8ba2-6eb43ba0a06d', usernameVariable: 'SWX_REPOS_USER', passwordVariable: 'SWX_REPOS_PASS'}
  - {credentialsId: 'jenkins-pulp', usernameVariable: 'pulp_usr', passwordVariable: 'pulp_pwd'}


# optional: for multi-arch k8 support, can define arch-specific nodeSelectors
# and jnlpImage locations

  arch_table:
    x86_64:
      nodeSelector: 'kubernetes.io/arch=amd64'
      jnlpImage: 'jenkins/inbound-agent:latest'
    aarch64:
      nodeSelector: 'kubernetes.io/arch=arm64'
      jnlpImage: '${registry_host}/${registry_jnlp_path}/jenkins-arm-agent-jnlp:latest'

# volumes to map into dockers
volumes:
  - {mountPath: /hpc/local, hostPath: /hpc/local}
  - {mountPath: /auto/sw_tools, hostPath: /auto/sw_tools}
  - {mountPath: /.autodirect/mtrswgwork, hostPath: /.autodirect/mtrswgwork}
  - {mountPath: /.autodirect/sw/release, hostPath: /.autodirect/sw/release}

# environment varibles to insert into Job shell environment, can be referenced from steps 
# or user-scripts or shell commands
env:
  mofed_installer_exe: /.autodirect/sw/release/mlnx_ofed/MLNX_OFED/mlnx_ofed_install
  mofed_installer_opt: --user-space-only --without-fw-update --all -q --skip-unsupported-devices-check


# default variables and values that can be used in yaml file
defaults:
  var1: value1
  var2: value2

# list of dockers to use for the job, `file` key is optional, if defined but docker image 
# does not exist in registry.
# image will be created during 1st invocation or if file was modified
# runs_on_dockers list can contain use-defined keys as well
# category:tools has special meaning - it will run for steps, that explicitly request it in the
# step`s containerSelector key.
# The use-case is as following: if some step requires special container with pre-installed toolchain (clang?)

runs_on_dockers:
  - {file: '.ci/Dockerfile.centos7.7.1908', name: 'centos7-7', tag: 'latest', category: 'tool'}
  - {file: '.ci/Dockerfile.ubuntu16-4', name: 'ubuntu16-4', tag: 'latest'}


# user-defined matrix to run tests, "steps" will be executed for every dimension of matrix.
# Can contain any use-defined dimensions
# Docker list will be added automatically to dimensions list
# foreach image in dockers_list
#   foreach driver in drivers_list
#       foreach cuda in cuda_list
#          foreach arch in arch_list
#              run steps
#          done
#       done
#   done
# done
#
matrix:
  axes:
    driver:
      - MLNX_OFED_LINUX-4.9-0.1.8.0
      - MLNX_OFED_LINUX-5.1-1.0.0.0
    cuda:
      - dev/cuda9.2
    arch:
      - x86_64

# include only dimensions as below. Exclude has same syntax. Only either include or exclude can be used.
# all keywords in include/exclude command are optional - if all provided keys 
# match - the dimension will be include/excluded

include:
  - {arch: x86_64, cuda: dev/cuda11.0, driver: MLNX_OFED_LINUX-4.9-0.1.8.0, name: ubuntu16-4}
  - {arch: x86_64, cuda: dev/cuda9.2, driver: MLNX_OFED_LINUX-4.9-0.1.8.0, name: ubuntu16-4}


# Steps can contain any number of name/run sections and all will be executed
# every matrix dimension will run in parallel with all other dimensions
# steps itself has sequential execution
# NOTE:
# shell environment variable representing dimension values will be inserted automatically and 
# can be used run section (see below)
# $name represents current value for docker name
# Also $driver $cuda,$arch env vars are available and can be used from shell
# $variant represents current axis serial number (relative to docker loop)

steps:

  - name: Coverity scan
# `shell` can be type of action, it will run script located in ci-demo/vars/actions/scriptname.sh
# with parameters
# defined by `args` key.
    shell: action
# dynamicAction is pre-defined action defined at https://github.com/Mellanox/ci-demo/blob/master/vars/dynamicAction.groovy 
# dynamicAction will execute ci-demo/vars/actions/$args[0] and will pass $args[1..] to it as command line arguments
    run: dynamicAction
# step can specify containerSelector filter to apply on `runs_on_dockers` section
# `variant` is built-in variable, available for every axis of the run and represents serial number for 
# execution of matrix dimension
    containerSelector: '{category:tool, variant:1}'
    args:
      - "--pre_script './autogen.sh;./configure;make -j 3 clean'"
      - "--build_script 'make -j 3'"
      - "--ignore_files 'devx googletest tests'"
    archiveArtifacts: 'cov.log'
# run this step in parallel with others
# each non-parallel step is a barrier for previous group of parallel steps
    parallel: true

  - name: Check package
# can set shell per step or globally
    shell: '!/bin/bash -xeEl'
    run: cuda=$cuda .ci/check_package.sh
    parallel: true

  - name: Run tests
    run: cuda=$cuda .ci/runtests.sh
# define shell command(s) to run if step fails
    onfail: |
      echo step execution step failed
      touch step_failed.log
# define shell command to run always, regardless if "run" step passed or failed
    always: env > always_env.txt
# define artifacts to collect for specific step
    archiveArtifacts: 'step_failed.log,always_env.txt'
# define raw xml results to collect for specific step
    archiveJunit: 'test-results.xml'

# executed once, before job starts its steps
pipeline_start:
  run: echo Starting new job

# executed once, after steps are done
pipeline_stop:
  run: echo All done

# executed before each container image build phase
# user can run any script to modify container image content
# also, can define 'on_image_build: script' key in runs_on_containers
# section , per specific containers
pipeline_on_image_build:
  run: echo Building image

# List of artifacts to attach to Jenkins results page for build
archiveArtifacts: config.log

# List of raw xml results to attach to Jenkins results page for build
archiveJunit: 'myproject/target/test-reports/*.xml'

# Fail job is one of the steps fails or continue
failFast: false

# Execute parallel job in batches (default 10 jobs in the air), to prevent overload k8 
# with large amount of parallel jobs
batchSize: 2

# Job timeout - fail job if it runs more than specified amount of minutes (default is 90 minutes)
timeout_minutes: 60

# Customize name of the parallel subtask as appears in Jenkins UI, according to the template below
# can use variable names from `axis` part of the `matrix` config section
# also can use variable names from `runs_on_dockers` config section.
# `${name}` comes from `runs_on_dockers` section
# `${axis_index}` is built-in variable representing axis serial number
taskName: '${name}/${axis_index}'

```







