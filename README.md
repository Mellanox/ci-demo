# Matrix workflow project for CI/CD

This is Jenkins CI/CD demo project. You can create custom workflows to automate your project software life cycle process. 

You need to configure workflows using YAML syntax, and save them as workflow files in your repository. Once you've successfully created a YAML workflow file and triggered the workflow - Jenkins parse flow and execute it.


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

kubernetes:
  cloud: swx-k8s
  nodeSelector: 'beta.kubernetes.io/os=linux'

runs_on_dockers:
  - {name: 'ubuntu16-4', tag: 'latest'}

matrix:
  axes:
    flags:
      - '--enable-debug'
      - '--prefix=/tmp/install'
    arch:
      - x86_64
steps:

  - name: Configure
    run: |
      ./autogen.sh
      ./configure $flags

  - name: Build
    run: make -j 2 all

  - name: Install
    run: make -j 2 install
     
    finalize:
      run: echo All done
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
* The actual [Jenkinsfile](.ci/Jenkinsfile) that gets executed is here at ```.ci/Jenkinsfile```


## Job matrix file

* Job file can contain array of steps, which are executed sequentially in the docker
* Job file can define own matrix and Jenkinsfile will generate axis combinations to execute in the docker
* Job file can define list of dockerfiles, in ```.ci/Dockerfiles.*``` that will be added to matrix combinations.
* Job file can define if need to build docker from dockerfiles and push resulting image to the registry or just fetch image from registry.
* Job can define different [environment](.ci/job_matrix.yaml#L13) variables that will be added to step run environment
* Job can define optional [include/exclude](.ci/job_matrix.yaml#L36) filters to select desired matrix dimensions

## Jenkins job builder

* Demo contains (Jenkins Job Builder)[https://docs.openstack.org/infra/jenkins-job-builder] (config file)[.ci/jjb_proj.yaml] which loads Jenkins project definition into Jenkins server.


### Running/Debugging Job Matrix pipeline locally

1. You can fetch docker image descibed in job_matrix.yaml and run steps in it to mimic Jenkins k8 approach

``` shell
% cd .ci
% make shell NAME=ubuntu16-4
docker%% cd /scratch
# the step below needed so workspace files (which belongs to $USER) will be copied as user jenkins so can be modified
docker%% cp -pr /scratch /tmp/ws
docker%% cd /tmp/ws
docker%% ./autogen.sh && ./configure && make
```







