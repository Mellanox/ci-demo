
ARCH   := $(shell uname -m)
NAME   := ubuntu16-4
JUSER  := $(shell whoami)

TOPDIR := $(shell git rev-parse --show-toplevel)

REPO_URL := harbor.mellanox.com/swx-storage/ci-demo
REGISTRY := ${REPO_URL}/${ARCH}

TAG    := $(shell git log -1 --pretty=%h)
IMG    := ${NAME}:${TAG}
LATEST := ${NAME}:latest
DOCKERFILE := Dockerfile.${NAME}
FIG := ${REGISTRY}/${LATEST}
BUILD_DOCKERS := false
CONF_FILE := job_matrix.yaml


build:
	docker build -t $(NAME) \
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


login:
	cat ~/.docker/.repo_passwd | docker login -u swx-storage --password-stdin ${REGISTRY}

run:
	docker exec -it ${NAME} bash

jjb:
	docker cp proj_jjb.yaml myjenkins:/tmp
	docker exec -it myjenkins sudo jenkins-jobs update /tmp/proj_jjb.yaml

trigger:
	docker exec -it myjenkins /usr/share/jenkins/ref/jcli.sh build ci-demo \
			-p sha1=master \
			-p build_dockers=${BUILD_DOCKERS} \
			-p conf_file=${CONF_FILE}

shell:
	docker run  \
		--rm -it \
		--shm-size=256m \
		-v /dev/hugepages:/dev/hugepages \
		-v /hpc/local:/hpc/local \
		-v /auto/sw_tools:/auto/sw_tools \
		-v /.autodirect/sw/release/mlnx_ofed/MLNX_OFED:/.autodirect/sw/release/mlnx_ofed/MLNX_OFED \
		-v ${TOPDIR}:/scrap         \
		${NAME} bash

