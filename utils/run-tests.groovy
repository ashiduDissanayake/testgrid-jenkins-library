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

        stage('Prepare Deployment and Test') {
            steps {
                script {
                    
                    // Create a map of parallel deployment tasks
                    def parallelDeployments = [:]
                    
                    // Add each deployment as a parallel task
                    for (def pattern : deploymentPatterns) {
                        def patternDir = pattern.directory
                        for (def dbEngine : pattern.dbEngines) {
                            def dbEngineName = dbEngine.engine
                            
                            // We need to use variables that are safe for the closure
                            def patternDirSafe = patternDir
                            def dbEngineNameSafe = dbEngineName
                            def patternSafe = pattern
                            def dockerRegistrySafe = pattern.dockerRegistry.registry
                            def dockerRegistryUsernameSafe = pattern.dockerRegistry.username
                            def dockerRegistryPasswordSafe = pattern.dockerRegistry.password
                            def stageId = "${patternDirSafe}-${dbEngineNameSafe}"
                            
                            // Add deployment task to parallel map
                            parallelDeployments["Deploy ${stageId}"] = {
                                stage("Deploy ${stageId}") {
                                    try {
                                        withCredentials([
                                            [
                                                $class: 'AmazonWebServicesCredentialsBinding',
                                                credentialsId: awsCred,
                                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                            ]
                                        ]) {
                                            String pwd = sh(script: "pwd", returnStdout: true).trim()
                                            // Login to Docker registry
                                            sh """
                                                echo ${dockerRegistryPasswordSafe} | sudo docker login ${dockerRegistrySafe} --username ${dockerRegistryUsernameSafe} --password-stdin
                                            """

                                            dir("${patternDirSafe}") {
                                                def dbWriterEndpointsJson = sh(script: "terraform output -json | jq -r '.database_writer_endpoints.value'", returnStdout: true).trim()
                                                def dbWriterEndpoints = new groovy.json.JsonSlurperClassic().parseText(dbWriterEndpointsJson)
                                                if (!dbWriterEndpoints) {
                                                    error "DB Writer Endpoints are null or empty for ${patternDirSafe}. Please check the Terraform output."
                                                }
                                                println "DB Writer Endpoints: ${dbWriterEndpoints}"
                                                // Convert LazyMap to HashMap
                                                patternSafe.dbEndpoints = new HashMap<>(dbWriterEndpoints)

                                                def (endpoint, dbPort) = patternSafe.dbEndpoints["${dbEngineNameSafe}-${dbEngineList[dbEngineNameSafe].version}"]?.tokenize(':')
                                                def namespace = "${patternSafe.id}-${dbEngineNameSafe}"
                                                sh """
                                                    # Change context
                                                    kubectl config use-context ${patternDirSafe}

                                                    # Create a namespace for the deployment
                                                    kubectl create namespace ${namespace} || echo "Namespace ${namespace} already exists."

                                                    aws s3 cp --quiet s3://${tfS3Bucket}/tools/client-truststore.jks .
                                                    aws s3 cp --quiet s3://${tfS3Bucket}/tools/wso2carbon.jks .

                                                    # Create apim-keystore-secret
                                                    kubectl create secret generic apim-keystore-secret --from-file=wso2carbon.jks --from-file=client-truststore.jks -n ${namespace} || echo "Failed to create apim-keystore-secret."
                                                """
                                                println "Namespace created: ${namespace}"

                                                sh """
                                                # Delete existing release if it exists
                                                helm list -n ${namespace} -q | xargs -n1 -I{} helm uninstall {} -n ${namespace} || echo "Failed to delete existing release."
                                                """

                                                String wso2amAcpImageDigest = sh(script: "aws ecr describe-images --repository-name ${project}-wso2am-acp --query 'imageDetails[?contains(imageTags, `${dbEngineNameSafe}-latest`)].imageDigest' --region ${productDeploymentRegion} --output text", returnStdout: true).trim()
                                                String wso2amTmImageDigest = sh(script: "aws ecr describe-images --repository-name ${project}-wso2am-tm --query 'imageDetails[?contains(imageTags, `${dbEngineNameSafe}-latest`)].imageDigest' --region ${productDeploymentRegion} --output text", returnStdout: true).trim()
                                                String wso2amGwImageDigest = sh(script: "aws ecr describe-images --repository-name ${project}-wso2am-universal-gw --query 'imageDetails[?contains(imageTags, `${dbEngineNameSafe}-latest`)].imageDigest' --region ${productDeploymentRegion} --output text", returnStdout: true).trim()

                                                sleep 60

                                                // Execute DB scripts
                                                executeDBScripts(dbEngineNameSafe, endpoint, dbUser, dbPassword, "${pwd}/${apimIntgDirectory}")

                                                String helmChartPath = "${pwd}/${helmDirectory}"
                                                // Install the product using Helm
                                                sh """
                                                    # Deploy wso2am-acp
                                                    echo "Deploying WSO2 API Manager - API Control Plane in ${namespace} namespace..."
                                                    helm install apim-acp ${helmChartPath}/distributed/control-plane \
                                                        --namespace ${namespace} \
                                                        --set aws.enabled=false \
                                                        --set wso2.apim.configurations.adminUsername="admin" \
                                                        --set wso2.apim.configurations.adminPassword="admin" \
                                                        --set wso2.apim.configurations.security.keystores.primary.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.primary.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.truststore.password="wso2carbon" \
                                                        --set wso2.deployment.resources.requests.cpu="1000m" \
                                                        --set wso2.apim.configurations.userStore.properties.ReadGroups=true \
                                                        --set kubernetes.ingress.controlPlane.hostname="am-${dbEngineNameSafe}.wso2.com" \
                                                        --set wso2.apim.configurations.gateway.environments[0].name="Default" \
                                                        --set wso2.apim.configurations.gateway.environments[0].type="hybrid" \
                                                        --set wso2.apim.configurations.gateway.environments[0].gatewayType="Regular" \
                                                        --set wso2.apim.configurations.gateway.environments[0].provider="wso2" \
                                                        --set wso2.apim.configurations.gateway.environments[0].displayInApiConsole=true \
                                                        --set wso2.apim.configurations.gateway.environments[0].description="This is a hybrid gateway that handles both production and sandbox token traffic." \
                                                        --set wso2.apim.configurations.gateway.environments[0].showAsTokenEndpointUrl=true \
                                                        --set wso2.apim.configurations.gateway.environments[0].serviceName="apim-universal-gw-wso2am-universal-gw-service" \
                                                        --set wso2.apim.configurations.gateway.environments[0].servicePort=9443 \
                                                        --set wso2.apim.configurations.gateway.environments[0].wsHostname="websocket-${dbEngineNameSafe}.wso2.com" \
                                                        --set wso2.apim.configurations.gateway.environments[0].httpHostname="gw-${dbEngineNameSafe}.wso2.com" \
                                                        --set wso2.apim.configurations.gateway.environments[0].websubHostname="websub-${dbEngineNameSafe}.wso2.com" \
                                                        --set wso2.apim.configurations.oauth_config.oauth2JWKSUrl="https://apim-acp-wso2am-acp-service:9443/oauth2/jwks" \
                                                        --set wso2.deployment.image.registry="${dockerRegistrySafe}" \
                                                        --set wso2.deployment.image.repository="${project}-wso2am-acp:${dbEngineNameSafe}-latest" \
                                                        --set wso2.deployment.image.digest="${wso2amAcpImageDigest}" \
                                                        --set wso2.deployment.image.imagePullSecrets.enabled=true \
                                                        --set wso2.deployment.image.imagePullSecrets.username="${dockerRegistryUsernameSafe}" \
                                                        --set wso2.deployment.image.imagePullSecrets.password="${dockerRegistryPasswordSafe}" \
                                                        --set wso2.apim.configurations.databases.type="${dbEngineList[dbEngineNameSafe].dbType}" \
                                                        --set wso2.apim.configurations.databases.jdbc.driver="${dbEngineList[dbEngineNameSafe].dbDriver}" \
                                                        --set wso2.apim.configurations.databases.apim_db.url="jdbc:${dbEngineList[dbEngineNameSafe].dbType}://${endpoint}:${dbPort}/apim_db?useSSL=false" \
                                                        --set wso2.apim.configurations.databases.apim_db.username="${dbUser}" \
                                                        --set wso2.apim.configurations.databases.apim_db.password="${dbPassword}" \
                                                        --set wso2.apim.configurations.databases.shared_db.url="jdbc:${dbEngineList[dbEngineNameSafe].dbType}://${endpoint}:${dbPort}/shared_db?useSSL=false" \
                                                        --set wso2.apim.configurations.databases.shared_db.username="${dbUser}" \
                                                        --set wso2.apim.configurations.databases.shared_db.password="${dbPassword}"
                                                    
                                                    # Wait for the deployment to be ready
                                                    kubectl wait --for=condition=available --timeout=400s deployment/apim-acp-wso2am-acp-deployment-1 -n ${namespace}

                                                    # Deploy wso2am-tm
                                                    echo "Deploying WSO2 API Manager - Traffic Manager in ${namespace} namespace..."
                                                    helm install apim-tm ${helmChartPath}/distributed/traffic-manager \
                                                        --namespace ${namespace} \
                                                        --set aws.enabled=false \
                                                        --set wso2.apim.configurations.adminUsername="admin" \
                                                        --set wso2.apim.configurations.adminPassword="admin" \
                                                        --set wso2.apim.configurations.security.keystores.primary.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.primary.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.truststore.password="wso2carbon" \
                                                        --set wso2.deployment.resources.requests.cpu="1000m" \
                                                        --set wso2.apim.configurations.km.serviceUrl="apim-acp-wso2am-acp-service" \
                                                        --set wso2.apim.configurations.throttling.serviceUrl="apim-tm-wso2am-tm-service" \
                                                        --set wso2.apim.configurations.throttling.urls="{apim-tm-wso2am-tm-1-service,apim-tm-wso2am-tm-2-service}" \
                                                        --set wso2.apim.configurations.eventhub.enabled=true \
                                                        --set wso2.apim.configurations.eventhub.serviceUrl="apim-acp-wso2am-acp-service" \
                                                        --set wso2.apim.configurations.eventhub.urls="{apim-acp-wso2am-acp-1-service,apim-acp-wso2am-acp-2-service}" \
                                                        --set wso2.deployment.replicas=1 \
                                                        --set wso2.deployment.minReplicas=1 \
                                                        --set wso2.deployment.image.registry="${dockerRegistrySafe}" \
                                                        --set wso2.deployment.image.repository="${project}-wso2am-tm:${dbEngineNameSafe}-latest" \
                                                        --set wso2.deployment.image.digest=${wso2amTmImageDigest} \
                                                        --set wso2.deployment.image.imagePullSecrets.enabled=true \
                                                        --set wso2.deployment.image.imagePullSecrets.username="${dockerRegistryUsernameSafe}" \
                                                        --set wso2.deployment.image.imagePullSecrets.password="${dockerRegistryPasswordSafe}" \
                                                        --set wso2.apim.configurations.databases.type="${dbEngineList[dbEngineNameSafe].dbType}" \
                                                        --set wso2.apim.configurations.databases.jdbc.driver="${dbEngineList[dbEngineNameSafe].dbDriver}" \
                                                        --set wso2.apim.configurations.databases.apim_db.url="jdbc:${dbEngineList[dbEngineNameSafe].dbType}://${endpoint}:${dbPort}/apim_db?useSSL=false" \
                                                        --set wso2.apim.configurations.databases.apim_db.username="${dbUser}" \
                                                        --set wso2.apim.configurations.databases.apim_db.password="${dbPassword}" \
                                                        --set wso2.apim.configurations.databases.shared_db.url="jdbc:${dbEngineList[dbEngineNameSafe].dbType}://${endpoint}:${dbPort}/shared_db?useSSL=false" \
                                                        --set wso2.apim.configurations.databases.shared_db.username="${dbUser}" \
                                                        --set wso2.apim.configurations.databases.shared_db.password="${dbPassword}"

                                                    # Wait for the deployment to be ready
                                                    kubectl wait --for=condition=available --timeout=400s deployment/apim-tm-wso2am-tm-deployment-1 -n ${namespace}

                                                    # Deploy wso2am-gw
                                                    echo "Deploying WSO2 API Manager - Gateway in ${namespace} namespace..."
                                                    helm install apim-universal-gw ${helmChartPath}/distributed/gateway \
                                                        --namespace ${namespace} \
                                                        --set aws.enabled=false \
                                                        --set wso2.apim.configurations.adminUsername="admin" \
                                                        --set wso2.apim.configurations.adminPassword="admin" \
                                                        --set wso2.apim.configurations.security.keystores.primary.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.primary.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.tls.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.password="wso2carbon" \
                                                        --set wso2.apim.configurations.security.keystores.internal.keyPassword="wso2carbon" \
                                                        --set wso2.apim.configurations.security.truststore.password="wso2carbon" \
                                                        --set wso2.deployment.resources.requests.cpu="1000m" \
                                                        --set kubernetes.ingress.gateway.hostname="gw-${dbEngineNameSafe}.wso2.com" \
                                                        --set kubernetes.ingress.websocket.hostname="websocket-${dbEngineNameSafe}.wso2.com" \
                                                        --set kubernetes.ingress.websub.hostname="websub-${dbEngineNameSafe}.wso2.com" \
                                                        --set wso2.apim.configurations.km.serviceUrl="apim-acp-wso2am-acp-service" \
                                                        --set wso2.apim.configurations.throttling.serviceUrl="apim-tm-wso2am-tm-service" \
                                                        --set wso2.apim.configurations.throttling.urls="{apim-tm-wso2am-tm-1-service,apim-tm-wso2am-tm-2-service}" \
                                                        --set wso2.apim.configurations.eventhub.enabled=true \
                                                        --set wso2.apim.configurations.eventhub.serviceUrl="apim-acp-wso2am-acp-service" \
                                                        --set wso2.apim.configurations.eventhub.urls="{apim-acp-wso2am-acp-1-service,apim-acp-wso2am-acp-2-service}" \
                                                        --set wso2.deployment.image.registry="${dockerRegistrySafe}" \
                                                        --set wso2.deployment.image.repository="${project}-wso2am-universal-gw:${dbEngineNameSafe}-latest" \
                                                        --set wso2.deployment.image.digest=${wso2amGwImageDigest} \
                                                        --set wso2.deployment.image.imagePullSecrets.enabled=true \
                                                        --set wso2.deployment.image.imagePullSecrets.username="${dockerRegistryUsernameSafe}" \
                                                        --set wso2.deployment.image.imagePullSecrets.password="${dockerRegistryPasswordSafe}" \
                                                        --set wso2.apim.configurations.databases.type="${dbEngineList[dbEngineNameSafe].dbType}" \
                                                        --set wso2.apim.configurations.databases.jdbc.driver="${dbEngineList[dbEngineNameSafe].dbDriver}" \
                                                        --set wso2.apim.configurations.databases.shared_db.url="jdbc:${dbEngineList[dbEngineNameSafe].dbType}://${endpoint}:${dbPort}/shared_db?useSSL=false" \
                                                        --set wso2.apim.configurations.databases.shared_db.username="${dbUser}" \
                                                        --set wso2.apim.configurations.databases.shared_db.password="${dbPassword}" \
                                                        --set wso2.deployment.replicas=1 \
                                                        --set wso2.deployment.minReplicas=1
                                                    
                                                    # Wait for the deployment to be ready
                                                    kubectl wait --for=condition=ready --timeout=300s pod -l deployment=apim-universal-gw-wso2am-universal-gw -n ${namespace}
                                                """
                                            }
                                        }
                                    } catch (Exception e) {
                                        println "Deployment failed for ${patternDirSafe}-${dbEngineNameSafe}: ${e}"
                                        error "Deployment failed for ${patternDirSafe}-${dbEngineNameSafe}. Please check the logs for more details."
                                    }
                                }

                                stage("Test ${stageId}") {
                                    if (skipTests) {
                                        echo "Skipping tests for ${stageId} as skipTests is set to true."
                                        return
                                    }
                                    try {
                                        withCredentials([
                                            [
                                                $class: 'AmazonWebServicesCredentialsBinding',
                                                credentialsId: awsCred,
                                                accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                                secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                                            ]
                                        ]) {
                                            String namespace = "${patternSafe.id}-${dbEngineNameSafe}"
                                            dir("${apimIntgDirectory}") {
                                                sh """
                                                    # Change context
                                                    kubectl config use-context ${patternDirSafe}
                                                """

                                                echo "Waiting 60 seconds before proceeding with tests for ${patternDirSafe} with ${dbEngineNameSafe}..."
                                                sleep 60

                                                sh """
                                                    ./main.sh --HOSTNAME="${patternSafe.hostName}" \
                                                        --PORTAL_HOST="am-${dbEngineNameSafe}.wso2.com" \
                                                        --GATEWAY_HOST="gw-${dbEngineNameSafe}.wso2.com" \
                                                        --kubernetes_namespace="${namespace}"
                                                """
                                            }

                                            dir("${logsDirectory}") {
                                                def podNames = sh(
                                                    script: "kubectl get pods -l product=apim -n=${namespace} -o custom-columns=:metadata.name",
                                                    returnStdout: true
                                                ).trim().split('\n')
                                                println "APIM pods in namespace ${namespace}: ${podNames}"
                                                for (def podName : podNames) {
                                                    if (podName?.trim()) {
                                                        def logFile = "${dbEngineNameSafe}-${podName}.log"
                                                        sh """
                                                            kubectl logs ${podName} -n=${namespace} > ${logFile} || echo "Failed to get logs for pod ${podName}"
                                                        """
                                                    }
                                                }
                                                sh "ls -la"
                                            }
                                        }
                                    } catch (Exception e) {
                                        println "Test execution failed for ${patternDirSafe}-${dbEngineNameSafe}: ${e}"
                                        error "Test execution failed for ${patternDirSafe}-${dbEngineNameSafe}. Please check the logs for more details."
                                    }
                                }
                            }
                        }
                    }
                    
                    // Run all stages in parallel
                    parallel parallelDeployments
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
