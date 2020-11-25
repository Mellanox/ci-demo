# Run on arm64 (aarch64) host - e.g. hpc-arm-01
# docker build --no-cache --network=host --rm --force-rm -t harbor.mellanox.com/swx-storage/jenkins-arm-agent .
# docker login harbor.mellanox.com/swx-storage
# docker push harbor.mellanox.com/swx-storage/jenkins-arm-agent

FROM arm64v8/openjdk:11.0.9-jre

ENV HOME /home/jenkins
RUN addgroup --system jenkins
RUN adduser --system --home $HOME --ingroup jenkins jenkins
LABEL Description="This is a base image, which provides the Jenkins agent executable (slave.jar)" Vendor="Jenkins project" Version="4.6"

ARG VERSION=4.6
ARG AGENT_WORKDIR=/home/jenkins/agent

RUN apt-get update \
  && apt-get install -y curl bash git openssh-client openssl \
  && curl --create-dirs -sSLo /usr/share/jenkins/slave.jar https://repo.jenkins-ci.org/public/org/jenkins-ci/main/remoting/${VERSION}/remoting-${VERSION}.jar \
  && chmod 755 /usr/share/jenkins \
  && chmod 644 /usr/share/jenkins/slave.jar 
USER jenkins
ENV AGENT_WORKDIR=${AGENT_WORKDIR}
RUN mkdir /home/jenkins/.jenkins && mkdir -p ${AGENT_WORKDIR}

VOLUME /home/jenkins/.jenkins
VOLUME ${AGENT_WORKDIR}
WORKDIR /home/jenkins
