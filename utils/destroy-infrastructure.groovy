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

def executeDBScripts(String dbEngine, String dbEndpoint, String dbUser, String dbPassword, String scriptPath) {
    println "Executing DB scripts for ${dbEngine} at ${dbEndpoint}..."

    try {
        timeout(time: 5, unit: 'MINUTES') {
            if (dbEngine == "mysql") {
                // Execute MySQL scripts
                println "Executing MySQL scripts..."
                sh """
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -e "DROP DATABASE IF EXISTS shared_db;"
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -e "DROP DATABASE IF EXISTS apim_db;"
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -e "CREATE DATABASE IF NOT EXISTS shared_db CHARACTER SET latin1;"
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -e "CREATE DATABASE IF NOT EXISTS apim_db CHARACTER SET latin1;"
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -Dshared_db < ${scriptPath}/dbscripts/mysql.sql
                    mysql -h ${dbEndpoint} -u ${dbUser} -p$dbPassword -Dapim_db < ${scriptPath}/dbscripts/apimgt/mysql.sql
                """
            } else if (dbEngine == "postgres") {
                // Execute PostgreSQL scripts
                println "Executing PostgreSQL scripts..."
                sh """
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d postgres -c "DROP DATABASE IF EXISTS shared_db;"
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d postgres -c "DROP DATABASE IF EXISTS apim_db;"
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d postgres -c "CREATE DATABASE shared_db;"
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d postgres -c "CREATE DATABASE apim_db;"
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d shared_db -f ${scriptPath}/dbscripts/postgresql.sql
                    PGPASSWORD=$dbPassword psql -h ${dbEndpoint} -U ${dbUser} -d apim_db -f ${scriptPath}/dbscripts/apimgt/postgresql.sql
                """
            } else {
                error "Unsupported DB engine: ${dbEngine}"
            }
        }
    } catch (Exception e) {
        error "Database operation timed out or failed: ${e.message}"
    }
}

pipeline {
    agent {label 'pipeline-kubernetes-agent'}

    stages {
        stage('Clone repos') {
            steps {
                script {
                    dir(helmDirectory) {
                        git branch: "${helmRepoBranch}",
                        credentialsId: githubCredentialId,
                        url: "${helmRepoUrl}"
                    }
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

                    // Install Terraform if not already installed
                    installTerraform()
                    // Install Docker if not already installed
                    installDocker()
                    // Install kubectl if not already installed
                    installKubectl()
                    // Install Helm if not already installed
                    installHelm()
                    // Install database client tools
                    installDBClients()
                    // Install Newman if not already installed
                    // installNewman()
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
