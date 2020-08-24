#!/bin/bash

git add Jenkinsfile job_matrix*.yaml
git commit -m "k8 prepare"
git push
#make trigger BUILD_DOCKERS=true
