# Demo project for CI/CD

This is Jenkins CI/CD demo project. The CI scripts are located in ```.ci``` folder
Jenkins behavior can be controlled by job_matrix.yaml file which has almost similar syntax/approach as Github actions.

* jenkinsfile parses .ci/job_matrix*.yaml files
* the job is bootstrapped and executing according to steps, as defined in yaml file
* The method works with vanilla jenkins [Jenkinsfile](.ci/Jenkinsfile)

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

* Demo contains [Jenkins Job Builder](.ci/jjb_proj.yaml) config file to load Jenkins project definition into Jenkins server


## Matrix job example

![Alt text](.ci/pict/snapshot2.png?raw=true "Matrix Job")


