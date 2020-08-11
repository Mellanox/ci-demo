#!/bin/bash

git add Jenkinsfile job_matrix*.yaml
git commit -m "update matrix support"
git push
make trigger BUILD_DOCKERS=true
