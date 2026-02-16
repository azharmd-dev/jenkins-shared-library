def call (Map configMap){
    pipeline {
        agent {
            node {
                label 'robomart'
            }
        }
        environment {
            ACCOUNT_ID = "522534289017"
            appVersion = configMap.get("appVersion")
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")
            deploy_to = configMap.get("deploy_to")
            REGION = "us-east-1"

        }
        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
            ansiColor('xterm')
        }
        // parameters {
        //     string(name: 'appVersion', description: 'Version of the application')
        //     choice(name: 'deploy_to', choices: ['dev', 'qa', 'prod'], description: 'Select environment to deploy')
        // }
        stages  {
            stage ( 'Deploy' ) {
                steps {
                    script {
                        withAWS(region:'us-east-1', credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${REGION} --name ${PROJECT}-${deploy_to}
                                kubectl get nodes
                                echo ${deploy_to}, ${appVersion}
                            """
                        }
                    }
                }

            }      



        }

    }
}