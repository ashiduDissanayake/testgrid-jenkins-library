#!groovy
/*
* Copyright (c) 2025 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* WSO2 Inc. licenses this file to you under the Apache License,
* Version 2.0 (the "License"); you may not use this file except
* in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*
*/

import groovy.json.JsonSlurperClassic 

// Input parameters
String product = params.product
String productVersion = params.productVersion
String productDeploymentRegion = params.productDeploymentRegion
String[] osList = params.osList?.split(',')?.collect { it.trim() } ?: []
String[] databaseList = params.databaseList?.split(',')?.collect { it.trim() } ?: []
String albCertArn = params.albCertArn
String acpUpdateLevel = params.acpUpdateLevel?: "-1"
String tmUpdateLevel = params.tmUpdateLevel?: "-1"
String gwUpdateLevel = params.gwUpdateLevel?: "-1"
Boolean useStaging = params.useStaging
String tfS3Bucket = params.tfS3Bucket
String tfS3region = params.tfS3region
String awsCred = params.awsCred
String dbPassword = params.dbPassword
String project = params.project?: "wso2"
Boolean onlyDestroyResources = params.onlyDestroyResources
Boolean destroyResources = params.destroyResources
Boolean skipTfApply = params.skipTfApply
Boolean skipDockerBuild = params.skipDockerBuild
Boolean skipTests = params.skipTests

// Default values
def deploymentPatterns = []
String updateType = "u2"
String hostName = ""
String dbUser = "wso2carbon"
// Helm repository details
String helmRepoUrl = "https://github.com/wso2/helm-apim.git"
String helmRepoBranch = "main"
String helmDirectory = "helm-apim"
// APIM Test Integration repository details
String apimIntgRepoUrl = "https://github.com/kavindasr/apim-test-integration.git"
String apimIntgRepoBranch = "4.5.0-profile-automation"
String apimIntgDirectory = "apim-test-integration"
String tfDirectory = "terraform"
String tfEnvironment = "dev"
String logsDirectory = "logs"

String githubCredentialId = "WSO2_GITHUB_TOKEN"
def dbEngineList = [
    "mysql": [
        version: "5.7",
        dbDriver: "com.mysql.cj.jdbc.Driver",
        driverUrl: "https://repo1.maven.org/maven2/mysql/mysql-connector-java/8.0.29/mysql-connector-java-8.0.29.jar",
        dbType: "mysql",
        port: 3306
        ],
    "postgres": [
        version: "16.6",
        dbDriver: "org.postgresql.Driver",
        driverUrl: "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.3.6/postgresql-42.3.6.jar",
        dbType: "postgresql",
        port: 5432
        ],
]

def buildDockerImage(String project, String product, String productVersion, String os, String updateLevel, String tag, String dbDriverUrl, 
    String dockerRegistry, String dockerRegistryUsername, String dockerRegistryPassword, Boolean useStaging) {
    
    println "Building Docker image for ${product} ${productVersion} on ${os} with update level ${updateLevel} and tag ${tag}..."
    try {
        // Define parameters for the downstream job
        def dockerBuildParameters = [
            [$class: 'StringParameterValue', name: 'project', value: project],
            [$class: 'StringParameterValue', name: 'wso2_product', value: product],
            [$class: 'StringParameterValue', name: 'wso2_product_version', value: productVersion],
            [$class: 'StringParameterValue', name: 'os', value: os],
            [$class: 'StringParameterValue', name: 'update_level', value: updateLevel],
            [$class: 'StringParameterValue', name: 'tag', value: tag],
            [$class: 'StringParameterValue', name: 'docker_registry', value: dockerRegistry],
            [$class: 'StringParameterValue', name: 'docker_registry_username', value: dockerRegistryUsername],
            [$class: 'PasswordParameterValue', name: 'docker_registry_password', value: hudson.util.Secret.fromString(dockerRegistryPassword)],
            [$class: 'StringParameterValue', name: 'db_driver_url', value: dbDriverUrl],
            [$class: 'BooleanParameterValue', name: 'use_staging', value: useStaging],
        ]
        
        // Invoke the downstream build job
        def buildJob = build job: 'U2/Integration-Tests/product-apim/utils/docker-builder', 
            parameters: dockerBuildParameters,
            propagate: true,
            wait: true
            
        println "Docker image build job completed with status: ${buildJob.result}"
        
        if (buildJob.result != 'SUCCESS') {
            error "Docker image build job failed with status: ${buildJob.result}"
        }
        
        return true
    } catch (Exception e) {
        println "Docker image build job failed for OS ${os}: ${e}"
        error "Failed to build Docker image for OS ${os}. Please check the logs for more details."
        return false
    }
}

pipeline {
    agent {label 'pipeline-kubernetes-agent'}

    stages {
        stage('Clone repos') {
            steps {
                script {
                    dir(apimIntgDirectory) {
                        git branch: "${apimIntgRepoBranch}",
                        credentialsId: githubCredentialId,
                        url: "${apimIntgRepoUrl}"
                    }
                }
            }
        }

        stage('Preparation') {
            steps {
                script {
                    println "OS List: ${osList}"
                    println "Database List: ${databaseList}"
                    createDeploymentPatterns(project, product, productVersion, osList, databaseList, dbEngineList, deploymentPatterns)

                    println "Deployment patterns created: ${deploymentPatterns}"

                    // Create directories for each deployment pattern
                    for (def pattern : deploymentPatterns) {
                        def deploymentDirName = pattern.directory
                        println "Creating directory: ${deploymentDirName}"
                        sh "mkdir -p ${deploymentDirName}"
                        
                        // Copy the Terraform files to the respective directories
                        dir("${deploymentDirName}") {
                            sh "cp -r ../${apimIntgDirectory}/${tfDirectory}/* ."
                        }
                    }
                }
            }
        }

        stage('Create Infrastructure') {
            when {
                expression { !onlyDestroyResources }
            }
            steps {
                script {
                    // First, invoke the create-infrastructure pipeline
                    println "Creating infrastructure using create-infrastructure.groovy..."
                    
                    // Define parameters for the create-infrastructure pipeline
                    def infraParams = [
                        [$class: 'StringParameterValue', name: 'product', value: product],
                        [$class: 'StringParameterValue', name: 'productVersion', value: productVersion],
                        [$class: 'StringParameterValue', name: 'productDeploymentRegion', value: productDeploymentRegion],
                        [$class: 'StringParameterValue', name: 'osList', value: osList.join(',')],
                        [$class: 'StringParameterValue', name: 'databaseList', value: databaseList.join(',')],
                        [$class: 'StringParameterValue', name: 'tfS3Bucket', value: tfS3Bucket],
                        [$class: 'StringParameterValue', name: 'tfS3region', value: tfS3region],
                        [$class: 'StringParameterValue', name: 'awsCred', value: awsCred],
                        [$class: 'StringParameterValue', name: 'dbPassword', value: dbPassword],
                        [$class: 'StringParameterValue', name: 'project', value: project ?: "wso2"]
                    ]
                    
                    // Call the create-infrastructure pipeline
                    def infraBuild = build job: 'U2/Integration-Tests/product-apim/utils/', 
                          parameters: infraParams,
                          propagate: true,
                          wait: true
                          
                    println "Infrastructure creation completed with status: ${infraBuild.result}"
                    
                    if (infraBuild.result != 'SUCCESS') {
                        error "Infrastructure creation failed with status: ${infraBuild.result}"
                    }
                    
                }
            }                        
        }

        stage('Configure cluster') {
            when {
                expression { !onlyDestroyResources }
            }
            steps {
                script {
                    // Invoke the configure-cluster pipeline
                    println "Configuring cluster using configure-cluster.groovy..."
                    
                    // Define parameters for the configure-cluster pipeline
                    def configParams = [
                        [$class: 'StringParameterValue', name: 'product', value: product],
                        [$class: 'StringParameterValue', name: 'productVersion', value: productVersion],
                        [$class: 'StringParameterValue', name: 'productDeploymentRegion', value: productDeploymentRegion],
                        [$class: 'StringParameterValue', name: 'osList', value: osList.join(',')],
                        [$class: 'StringParameterValue', name: 'databaseList', value: databaseList.join(',')],
                        [$class: 'StringParameterValue', name: 'tfS3Bucket', value: tfS3Bucket],
                        [$class: 'StringParameterValue', name: 'tfS3region', value: tfS3region],
                        [$class: 'StringParameterValue', name: 'awsCred', value: awsCred],
                        [$class: 'StringParameterValue', name: 'dbPassword', value: dbPassword],
                        [$class: 'StringParameterValue', name: 'project', value: project ?: "wso2"]
                    ]
                    
                    // Call the configure-cluster pipeline
                    def configBuild = build job: 'U2/Integration-Tests/product-apim/utils/configure-cluster', 
                          parameters: configParams,
                          propagate: true,
                          wait: true
                          
                    println "Cluster configuration completed with status: ${configBuild.result}"
                    
                    if (configBuild.result != 'SUCCESS') {
                        error "Cluster configuration failed with status: ${configBuild.result}"
                    }
                }
            }
        }

        stage('Build docker images') {
            when {
                expression { !onlyDestroyResources && !skipDockerBuild }
            }
            steps {
                script {
                    // Create a map of parallel builds - one for each OS
                    def parallelBuilds = [:]
                    
                    for (def pattern : deploymentPatterns) {
                        for (def dbEngine : pattern.dbEngines) {
                            // Need to bind the os variable within the closure
                            def currentOs = pattern.os
                            def db = dbEngine.engine
                            def dbDriverUrl = dbEngineList[db].driverUrl
                            def dockerRegistry = pattern.dockerRegistry.registry
                            def dockerRegistryUsername = pattern.dockerRegistry.username
                            def dockerRegistryPassword = pattern.dockerRegistry.password
                            
                            parallelBuilds["Build ${currentOs}-${db} wso2am-acp image"] = {
                                buildDockerImage(project, "wso2am-acp", '4.5.0', currentOs, acpUpdateLevel, "${db}-latest", dbDriverUrl, dockerRegistry, dockerRegistryUsername, dockerRegistryPassword, useStaging)
                            }
                            parallelBuilds["Build ${currentOs}-${db} wso2am-tm image"] = {
                                buildDockerImage(project, "wso2am-tm", '4.5.0', currentOs, tmUpdateLevel, "${db}-latest", dbDriverUrl, dockerRegistry, dockerRegistryUsername, dockerRegistryPassword, useStaging)
                            }
                            parallelBuilds["Build ${currentOs}-${db} wso2am-universal-gw image"] = {
                                buildDockerImage(project, "wso2am-universal-gw", '4.5.0', currentOs, gwUpdateLevel, "${db}-latest", dbDriverUrl, dockerRegistry, dockerRegistryUsername, dockerRegistryPassword, useStaging)
                            }
                        }
                    }
                    
                    // Execute all builds in parallel
                    parallel parallelBuilds
                }
            }
        }

        stage('Prepare Deployment and Test') {
            steps {
                script {
                    if (onlyDestroyResources) {
                        echo "Skipping deployment because onlyDestroyResources is set to true"
                        return
                    }
                    
                    
                }
            }
        }

        stage('Run Tests') {
            when {
                expression { !onlyDestroyResources && !skipTests }
            }
            steps {
                script {
                    // Invoke the run-tests pipeline
                    println "Running tests using run-tests.groovy..."
                    
                    // Define parameters for the run-tests pipeline
                    def testParams = [
                        [$class: 'StringParameterValue', name: 'product', value: product],
                        [$class: 'StringParameterValue', name: 'productVersion', value: productVersion],
                        [$class: 'StringParameterValue', name: 'productDeploymentRegion', value: productDeploymentRegion],
                        [$class: 'StringParameterValue', name: 'osList', value: osList.join(',')],
                        [$class: 'StringParameterValue', name: 'databaseList', value: databaseList.join(',')],
                        [$class: 'StringParameterValue', name: 'tfS3Bucket', value: tfS3Bucket],
                        [$class: 'StringParameterValue', name: 'tfS3region', value: tfS3region],
                        [$class: 'StringParameterValue', name: 'awsCred', value: awsCred],
                        [$class: 'StringParameterValue', name: 'dbPassword', value: dbPassword],
                        [$class: 'StringParameterValue', name: 'project', value: project ?: "wso2"]
                    ]
                    
                    // Call the run-tests pipeline
                    def testBuild = build job: 'U2/Integration-Tests/product-apim/utils/run-tests', 
                          parameters: testParams,
                          propagate: true,
                          wait: true
                          
                    println "Test execution completed with status: ${testBuild.result}"
                    
                    if (testBuild.result != 'SUCCESS') {
                        error "Test execution failed with status: ${testBuild.result}"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                try {
                    println "Cleaning up the workspace..."
                    if (destroyResources || onlyDestroyResources) {
                        withCredentials([[
                            $class: 'AmazonWebServicesCredentialsBinding',
                            credentialsId: awsCred,
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                        ]]) { 
                            println "Destroying cloud resources!"
                            // Destroy the created resources
                            for (def pattern : deploymentPatterns) {
                                def deploymentDirName = pattern.directory
                                dir("${deploymentDirName}") {
                                    println "Destroying resources for ${deploymentDirName}..."
                                    sh """
                                        # Configure EKS cluster
                                        aws eks --region ${productDeploymentRegion} \
                                        update-kubeconfig --name ${project}-${pattern.id}-${tfEnvironment}-${productDeploymentRegion}-eks \
                                        --alias ${pattern.directory} || echo "Failed to update kubeconfig."

                                        kubectl delete -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.0.4/deploy/static/provider/aws/deploy.yaml || echo "Failed to delete ingress controller."

                                        kubectl wait --namespace ingress-nginx --for=delete pod --selector=app.kubernetes.io/component=controller --timeout=480s || echo "Ingress controller pods were not deleted within the expected time limit."

                                        terraform destroy -auto-approve \
                                            -var="project=${project}" \
                                            -var="client_name=${pattern.id}" \
                                            -var="region=${productDeploymentRegion}" \
                                            -var='db_engine_options=${pattern.dbEnginesJson}' \
                                            -var="db_password=$dbPassword" \
                                            -var="eks_default_nodepool_desired_size=${pattern.eksDesiredSize}" \
                                            -no-color
                                    """
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    echo "Workspace cleanup failed: ${e.message}"
                    currentBuild.result = 'FAILURE'
                } finally {
                    if (!onlyDestroyResources && !skipTests) {
                        archiveArtifacts artifacts: "${logsDirectory}/**/*.*", fingerprint: true
                    }
                    // Clean up the workspace
                    cleanWs()
                }
            }
        }
    }
}
