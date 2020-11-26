# Run on ppc64le (aarch64) host - e.g. hpc-ppc64le-01
# docker build --no-cache --network=host --rm --force-rm -t harbor.mellanox.com/swx-storage/jenkins-ppc64le-agent-jnlp .
# docker login harbor.mellanox.com/swx-storage
# docker push harbor.mellanox.com/swx-storage/jenkins-ppc64le-agent-jnlp

FROM harbor.mellanox.com/swx-storage/jenkins-ppc64le-agent:latest
LABEL Description="This is a base image, which allows connecting Jenkins agents via JNLP protocols" Vendor="Jenkins project" Version="4.6"
COPY jenkins-agent /usr/local/bin/jenkins-agent
ENTRYPOINT ["jenkins-agent"]
