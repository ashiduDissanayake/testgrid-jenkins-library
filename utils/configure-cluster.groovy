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
String tfS3Bucket = params.tfS3Bucket
String tfS3region = params.tfS3region
String awsCred = params.awsCred
String dbPassword = params.dbPassword
String project = params.project?: "wso2"

// Default values
def deploymentPatterns = []
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

// Create deployment patterns for all combinations of OS and database
@NonCPS
def createDeploymentPatterns(String project, String product, String productVersion, 
                                String[] osList, String[] databaseList, def dbEngineList, def deploymentPatterns) {
    println "Creating the deployment patterns by using infrastructure combination!"
    
    int count = 1
    for (String os : osList) {
        def dbEngines = []
        for (String db : databaseList) {
            def dbDetails = dbEngineList[db]
            if (dbDetails == null) {
                println "DB engine version not found for ${db}. Skipping..."
                continue
            }
            dbEngines.add([
                engine: db,
                version: dbDetails.version,
                port: dbDetails.port,
            ])
        }
        String deploymentDirName = "${project}-${product}-${productVersion}-${os}"
        
        def dbEnginesJson = new groovy.json.JsonBuilder(dbEngines).toString()
        def deploymentPattern = [
            id: count++,
            product: product,
            version: productVersion,
            os: os,
            dbEngines: dbEngines,
            dbEnginesJson: dbEnginesJson,
            directory: deploymentDirName,
            eksDesiredSize: 5*dbEngines.size(),
        ]
        deploymentPatterns.add(deploymentPattern)
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
                    
                    def common = load "utils/common.groovy"
                    // Install Terraform if not already installed
                    common.installTerraform()
                    // Install Docker if not already installed
                    common.installDocker()
                    // Install kubectl if not already installed
                    common.installKubectl()
                    // Install Helm if not already installed
                    common.installHelm()
                    // Install database client tools
                    common.installDBClients()
                }
            }
        }

        stage('Terraform Init') {
            steps {
                script {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        credentialsId: awsCred,
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                    ]]) { 
                        for (def pattern : deploymentPatterns) {
                            def deploymentDirName = pattern.directory
                            dir("${deploymentDirName}") {
                                println "Running Terraform init for ${deploymentDirName}..."
                                sh """
                                    terraform init -backend-config="bucket=${tfS3Bucket}" \
                                        -backend-config="region=${tfS3region}" \
                                        -backend-config="key=${deploymentDirName}.tfstate"
                                """
                            }
                        }
                    }
                }
            }
        }

        stage('Configure EKS cluster') {
            steps {
                script {
                    try {
                        withCredentials([[
                            $class: 'AmazonWebServicesCredentialsBinding',
                            credentialsId: awsCred,
                            accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                            secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                        ]]) { 
                            for (def pattern : deploymentPatterns) {
                                def deploymentDirName = pattern.directory
                                dir("${deploymentDirName}") {
                                    println "Configuring EKS for ${deploymentDirName}..."
                                    // EKS cluster name follows this pattern defined in the AWS Terraform modules:
                                    // https://github.com/wso2/aws-terraform-modules/blob/c9820b842ff2227c10bd22f4ff076461d972d520/modules/aws/EKS-Cluster/eks.tf#L21
                                    sh """
                                        aws eks --region ${productDeploymentRegion} \
                                        update-kubeconfig --name ${project}-${pattern.id}-${tfEnvironment}-${productDeploymentRegion}-eks \
                                        --alias ${pattern.directory}

                                        # Install nginx ingress controller
                                        kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.0.4/deploy/static/provider/aws/deploy.yaml || { echo "failed to install nginx ingress controller." ; exit 1 ; }

                                        # Delete Nginx admission if it exists.
                                        kubectl delete -A ValidatingWebhookConfiguration ingress-nginx-admission || echo "WARNING : Failed to delete nginx admission."

                                        # Wait for nginx to come alive.
                                        kubectl wait --namespace ingress-nginx --for=condition=ready pod --selector=app.kubernetes.io/component=controller --timeout=480s ||  { echo 'Nginx service is not ready within the expected time limit.';  exit 1; }
                                    """

                                    hostName = sh(script: "kubectl -n ingress-nginx get svc ingress-nginx-controller -o json | jq -r '.status.loadBalancer.ingress[0].hostname'", returnStdout: true).trim()
                                    println "Ingress Host Name: ${hostName}"
                                    pattern.hostName = hostName

                                    def ecrWso2AcpURL = sh(script: "terraform output -json | jq -r '.ecr_wso2am_acp_url.value'", returnStdout: true).trim()
                                    def ecrCommonURL = ecrWso2AcpURL.split('/')[0]
                                    println "ECR Common URL: ${ecrCommonURL}"

                                    def password = sh(
                                        script: "aws ecr get-login-password --region ${productDeploymentRegion}",
                                        returnStdout: true
                                    ).trim()
                                    pattern.dockerRegistry = [
                                        registry: ecrCommonURL,
                                        username: "AWS",
                                        password: password
                                    ]
                                }
                            }
                        }
                    } catch (Exception e) {
                        println "EKS configuration failed: ${e}"
                        error "EKS configuration failed. Please check the logs for more details."
                    }
                }
            }                        
        }
    }

    post {
        always {
            script {
                // Clean up the workspace
                cleanWs()
            }
        }
    }
}
