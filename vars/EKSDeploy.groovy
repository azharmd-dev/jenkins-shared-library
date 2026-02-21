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
            NOTIFY_EMAIL = credentials('notification-email')
            HELM_REVISION = "N/A"
            HELM_STATUS = "FAILED BEFORE REVISION FETCH"
            ROLLBACK_REVISION = "AUTO ROLLBACK BY HELM"


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
            stage('Deploy') {
                steps {
                    script {
                        withAWS(region:'us-east-1', credentials: 'aws-creds') {
                            sh """
                                // set -e
                                aws eks update-kubeconfig --region ${REGION} --name ${PROJECT}-${deploy_to}
                                kubectl get nodes

                                helm upgrade --install ${COMPONENT} \
                                -f values-${deploy_to}.yaml \
                                --set deployment.imageVersion=${appVersion} \
                                -n ${PROJECT} \
                                --atomic --wait --timeout=5m .
                            """

                            // ⭐ Get latest Helm revision
                            env.HELM_REVISION = sh(
                                script: "helm history ${COMPONENT} -n ${PROJECT} --max 1 -o json | jq -r '.[0].revision'",
                                returnStdout: true
                            ).trim()

                            // ⭐ Get status of latest revision
                            env.HELM_STATUS = sh(
                                script: "helm history ${COMPONENT} -n ${PROJECT} --max 1 -o json | jq -r '.[0].status'",
                                returnStdout: true
                            ).trim()

                            // ⭐ Get previous revision (rollback target)
                            env.ROLLBACK_REVISION = sh(
                                script: "helm history ${COMPONENT} -n ${PROJECT} -o json | jq -r '.[1].revision'",
                                returnStdout: true
                            ).trim()

                            echo "Helm Revision: ${HELM_REVISION}"
                            echo "Helm Status: ${HELM_STATUS}"
                            echo "Rollback Revision: ${ROLLBACK_REVISION}"
                        }
                    }
                }
            }

            // stage ( 'Deploy' ) {
            //     steps {
            //         script {
            //             withAWS(region:'us-east-1', credentials: 'aws-creds') {
            //                 sh """
            //                     set -e
            //                     aws eks update-kubeconfig --region ${REGION} --name ${PROJECT}-${deploy_to}
            //                     kubectl get nodes

            //                     helm upgrade --install ${COMPONENT} \
            //                     -f values-${deploy_to}.yaml \
            //                     --set deployment.imageVersion=${appVersion} \
            //                     -n ${PROJECT} \
            //                     --atomic --wait --timeout=5m .

            //                     """
            //             }
            //         }
            //     }

            // }      



        }

        post {
            success {
                emailext (
                    subject: "🚀 DEPLOY SUCCESS: ${JOB_NAME} #${BUILD_NUMBER}",
                    body: """\
                    🚀 DEPLOYMENT SUCCESSFUL 🚀

                    Job Name   : ${JOB_NAME}
                    Build No   : ${BUILD_NUMBER}
                    Project    : ${PROJECT}
                    Component  : ${COMPONENT}
                    Version    : ${appVersion}

                    Helm Revision : ${HELM_REVISION}
                    Helm Status   : ${HELM_STATUS}

                    Build URL  : ${BUILD_URL}
                    """,
                            to: "${NOTIFY_EMAIL}"
                )
            }

            failure {
                emailext (
                    subject: "🔥 DEPLOY FAILED & AUTO-ROLLED BACK",
                    body: """\
                    🔥 DEPLOYMENT FAILED 🔥

                    Job Name   : ${JOB_NAME}
                    Build No   : ${BUILD_NUMBER}
                    Project    : ${PROJECT}
                    Component  : ${COMPONENT}
                    Version    : ${appVersion}

                    Failed Revision     : ${HELM_REVISION}
                    Auto Rollback To    : ${ROLLBACK_REVISION}

                    Helm Status : ${HELM_STATUS}

                    Build URL  : ${BUILD_URL}
                    """,
                            to: "${NOTIFY_EMAIL}"
                )
            }
        }

    }
}