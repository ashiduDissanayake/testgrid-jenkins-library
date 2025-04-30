def installTerraform() {
    if (!fileExists('/usr/local/bin/terraform')) {
        println "Terraform not found. Installing..."
        sh """
            curl -LO https://releases.hashicorp.com/terraform/1.11.3/terraform_1.11.3_linux_amd64.zip
            unzip terraform_1.11.3_linux_amd64.zip
            sudo mv terraform /usr/local/bin/
            terraform version
        """
    } else {
        println "Terraform is already installed."
    }
}

def installKubectl() {
    if (!fileExists('/usr/local/bin/kubectl')) {
        println "kubectl not found. Installing..."
        sh """
            curl -LO https://dl.k8s.io/release/v1.32.0/bin/linux/amd64/kubectl
            sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl
            kubectl version --client
        """
    } else {
        println "kubectl is already installed."
    }
}

def installHelm() {
    if (!fileExists('/usr/local/bin/helm')) {
        println "Helm not found. Installing..."
        sh """
            curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
            chmod 700 get_helm.sh
            ./get_helm.sh
            helm version
        """
    } else {
        println "Helm is already installed."
    }
}

def installDocker() {
if (!fileExists('/usr/bin/docker')) {
        println "Docker not found. Installing..."
        sh """
            sudo apt update
            sudo apt install apt-transport-https ca-certificates curl software-properties-common -y
            curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo apt-key add -
            sudo add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu focal stable"
            
            sudo apt install docker-ce -y
            
            sudo usermod -aG docker ${USER}
            su - ${USER}
        """
    } else {
        println "Docker is already installed."
    }
}

def installDBClients() {
    println "Checking and installing database client tools if not already installed..."
    if (!fileExists('/usr/bin/mysql')) {
        println "MySQL client not found. Installing..."
        sh """
            sudo apt-get update || echo "Failed to update package list, continuing..."
            sudo apt-get install -y mysql-client
        """
    } else {
        println "MySQL client is already installed."
    }

    if (!fileExists('/usr/bin/psql')) {
        println "PostgreSQL client not found. Installing..."
        sh """
            sudo apt-get update || echo "Failed to update package list, continuing..."
            sudo apt-get install -y postgresql-client
        """
    } else {
        println "PostgreSQL client is already installed."
    }
}

def installNewman() {
    def version = sh(script: 'newman --version || echo "NOT_INSTALLED"', returnStdout: true).trim()
    if (version == 'NOT_INSTALLED') {
        println "Newman not found. Installing..."
        sh """
            # Install newman globally using npm
            npm install -g newman
            
            # Verify newman installation
            newman --version
        """
    } else {
        println "Newman is already installed. Version: ${version}"
    }
}

@NonCPS
def parseJson(String jsonString) {
    return new groovy.json.JsonSlurper().parseText(jsonString)
}

@NonCPS
def stringifyJson(Map jsonMap) {
    return new groovy.json.JsonBuilder(jsonMap).toPrettyString()
}