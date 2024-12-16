
ARCH   := $(shell uname -m)
JUSER  := swx-jenkins
JUSER  := $(shell whoami)
NAME   := ubuntu20.04
DOCKERFILE := Dockerfile.${NAME}
DOCKER_OPT := --network=host --uts=host --ipc=host --ulimit stack=67108864 --ulimit memlock=-1 --security-opt seccomp=unconfined --cap-add=SYS_ADMIN --device=/dev/infiniband/ --user root
DOCKER_CMD := bash

TOPDIR := $(shell git rev-parse --show-toplevel)

PROJ     := $(shell basename `cd .. && pwd | tr [A-Z] [a-z]`)

REPO_URL := nbu-harbor.gtm.nvidia.com/swx-storage/${PROJ}
REGISTRY := ${REPO_URL}/${ARCH}

TAG    := $(shell git log -1 --pretty=%h)
IMG    := ${NAME}:${TAG}
LATEST := ${NAME}:latest
FIG := ${REGISTRY}/${LATEST}
BUILD_DOCKERS := false
CONF_FILE := job_matrix.yaml

JENKINS_HOST := hpc-master
JENKINS_URL  := http://$(JENKINS_HOST):8080
JENKINS_TOKEN := $(shell cat ${HOME}/.jenkins_${USER}_token)
JENKINS_AUTH  := ${USER}:${JENKINS_TOKEN}
CONF := .ci/job_matrix.yaml
JPROJ := cidemo-debug
DEBUG := 3


BRANCH := $(shell git rev-parse --abbrev-ref HEAD)


test:
	@echo ${FIG}

build:
	docker build -t $(NAME) \
	 --network=host \
	 --build-arg _UID=$(shell id -u ${JUSER}) \
	 --build-arg _GID=$(shell id -g ${JUSER}) \
	 --build-arg _LOGIN=${JUSER} \
	 -f $(DOCKERFILE) .

tag:
	docker tag ${NAME} ${LATEST}
	docker tag ${NAME} ${REGISTRY}/${LATEST}

push:
	docker push ${REGISTRY}/$(LATEST)

pull:
	docker pull ${REGISTRY}/$(LATEST)

rmi:
	docker rmi --force ${REGISTRY}/$(LATEST)

shell:
	docker run $(ARGS) $(DOCKER_OPT) \
		-v /hpc/local:/hpc/local \
		-v /var/run/docker.sock:/var/run/docker.sock \
		-v /auto/sw_tools:/auto/sw_tools \
		-v /auto/sw/release/mlnx_ofed/MLNX_OFED:/auto/sw/release/mlnx_ofed/MLNX_OFED \
		-v /.autodirect/sw/release/mlnx_ofed/MLNX_OFED:/.autodirect/sw/release/mlnx_ofed/MLNX_OFED \
		-v /auto/sw_tools/Commercial:/auto/sw_tools/Commercial \
		-v $(shell pwd)/..:/scratch \
		--rm -it --pid=host ${NAME} $(DOCKER_CMD)

shell2:
	docker run $(ARGS) --name ${NAME} \
		-v /hpc/local:/hpc/local \
		-v /auto/sw_tools:/auto/sw_tools \
		-v /.autodirect/sw/release/mlnx_ofed/MLNX_OFED:/.autodirect/sw/release/mlnx_ofed/MLNX_OFED \
		-v $(shell pwd)/..:/scratch \
		--rm -it ${FIG} $(DOCKER_CMD)

build_ufmt:
	rm -rf docker_build ||:
	mkdir -p docker_build
	git clone --recurse-submodules .. docker_build/Collectx
	cp -f /project/sw/ufm/install/UFM6.6.0/license/UFM_License-1.0-py3-none-any.whl docker_build/
	cd docker_build && docker build --build-arg KEEP_SOURCES=1 --rm -t ufmt -f Collectx/deploy/ufm/Dockerfile . |& tee docker_build.log

ufmt:
	make shell NAME=ufmt

jenkins-token: ${HOME}/.jenkins_${USER}_token
	@if [ ! -f ${HOME}/.jenkins_${USER}_token ]; then \
		echo Error: No jenkins access token configured ;\
		echo Connect with web browser to $(JENKINS_URL)/user/${USER}/configure ;\
		echo create token and save it in the file ${HOME}/.jenkins_${USER}_token ;\
		false ;\
	fi

jenkins-cli.jar: jenkins-cli.jar
	wget $(JENKINS_URL)/jnlpJars/jenkins-cli.jar -O jenkins-cli.jar

trigger: jenkins-token jenkins-cli.jar
	java -jar jenkins-cli.jar -s $(JENKINS_URL) -webSocket -auth $(JENKINS_AUTH) build $(JPROJ) -p conf_file=$(CONF) -p sha1=$(BRANCH) -p DEBUG=$(DEBUG)

login:
	cat ~/.docker/.repo_passwd | docker login -u swx-storage --password-stdin ${REGISTRY}

jjb:
	ssh ${JENKINS_HOST} sudo jenkins-jobs update $(shell pwd)/proj_jjb.yaml

clean:
	docker image prune -a -f

