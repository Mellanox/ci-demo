#!/usr/bin/env groovy

def call(projectName, projectVersion, projectSrcPath, attachArtifact, reportName, scanMode) {

    library(identifier: 'ngci@ci_version-3.1',
            retriever: modernSCM([$class: 'GitSCMSource', 
            remote: 'http://l-gerrit.mtl.labs.mlnx:8080/DevOps/Jenkins/ci_framework']))

    println("==>BlackDuck($projectName, $projectVersion, $projectSrcPath, $attachArtifact, $reportName, $scanMode)")

    NGCIBlackDuckScan (
        projectName: $projectName,
        projectVersion: $projectVersion,
        projectSrcPath: $projectSrcPath,
        attachArtifact: $attachArtifact,
        reportName: $reportName,
        scanMode: $scanMode
    )
    return;
}
