def call (Map configMap) {
    pipeline {
        agent {
            node {
                label 'robomart'
            }
        }
        environment {
            ACCOUNT_ID = "522534289017"
            appVersion = ""
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")

        }
        options{
            timeout(time: 40, unit: 'MINUTES')
            disableConcurrentBuilds()
            ansiColor('xterm')
        }
        stages {
            stage ('Read Version') {
                steps {
                    script {
                        def pkg = readJSON file: 'package.json'
                        appVersion = pkg.version
                        echo "app version is : ${appVersion}"
                        echo "App Name    : ${pkg.name}"

                    }
                }
            }

            stage ('Install dependencies') {
                steps {
                    script {
                        sh  """
                        npm install
                    """
                    }
                }
            }
            stage ('Unit Testing') {
                steps {
                    script {
                        sh  """
                        echo test
                    """
                    }
                }
            }
            // stage ('Sonar Scan'){
            //     environment {
            //         def scannerHome = tool 'sonar-8.0'
            //     }
            //     steps {
            //         script {
            //             withSonarQubeEnv('sonar-server') {
            //                 sh "${scannerHome}/bin/sonar-scanner"       
            //             }
            //         }
            //     }
            // }
            // stage("Quality Gate Check") {
            //     steps {
            //         script {
            //             // Wait for SonarQube quality gate result
            //             def qualityGateStatus = waitForQualityGate abortPipeline: false

            //             echo "=================================="
            //             echo "SonarQube Quality Gate Status: ${qualityGateStatus.status}"
            //             echo "=================================="

            //             if (qualityGateStatus.status == 'OK') {
            //                 echo "✅ Sonar Scan PASSED"
            //             } else {
            //                 echo "❌ Sonar Scan FAILED"
            //                 error "Pipeline aborted due to quality gate failure: ${qualityGateStatus.status}"
            //             }
            //         }
            //     }
            // }

            stage('Dependabot Security Gate') {
                when {
                    expression { false }
                }

                steps {
                    script {
                        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {

                            def response = sh(
                                script: '''
                                    curl -s -L \
                                    -H "Accept: application/vnd.github+json" \
                                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                                    https://api.github.com/repos/azharmd-dev/catalogue/dependabot/alerts?state=open
                                ''',
                                returnStdout: true
                            ).trim()

                            def alerts = readJSON text: response

                            if (alerts.size() == 0) {
                                echo "No open Dependabot vulnerabilities found"
                                return
                            }

                            echo "Found ${alerts.size()} open Dependabot alert(s)"

                            def highRisk = alerts.findAll {
                                it.security_advisory.severity in ['high', 'critical']
                            }

                            if (highRisk.size() > 0) {
                                echo "High/Critical vulnerabilities detected!"

                                highRisk.each {
                                    echo "Package: ${it.dependency.package.name} | CVE: ${it.security_advisory.cve_id}"
                                }

                                error("Pipeline failed due to security vulnerabilities")
                            }

                            echo "Only low/medium vulnerabilities found — pipeline continues"
                        }
                    }
                }
            }

            stage ('Build Docker image') {
                steps {
                    script {
                        withAWS(region:'us-east-1', credentials: 'aws-creds') {
                            sh """
                                aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                                docker images
                                docker push ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}

                            """    
                        }
                    }
                }
            }

            // stage('Trivy Scan'){
            //     steps {
            //         script{
            //             sh """
            //                 trivy image \
            //                 --scanners vuln \
            //                 --severity HIGH,CRITICAL,MEDIUM \
            //                 --pkg-types os \
            //                 --exit-code 1 \
            //                 --format table \
            //                 ${ACCOUNT_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
            //             """
            //         }
            //     }
            // }

            stage('Trigger Dev Deploy') {
                steps {
                    script {
                        build job: "../${COMPONENT}-deploy", 
                            wait: false,
                            propagate: false,
                            parameters: [
                                string(name: 'appVersion', value: "${appVersion}"),
                                string(name: 'deploy_to', value: "dev")
                        ]
                    }
                }
            }

        }
        post {
            always {
                echo "Post Message after stages"
                cleanWs()
            }

            success {
                
                echo "Build is success"

                emailext (
                    subject: "BUILD SUCCESS: ${JOB_NAME} #${BUILD_NUMBER}",
                    body: """
                    🎉 GOOD NEWS 🎉

                    Job Name: ${JOB_NAME}
                    Build Number: ${BUILD_NUMBER}
                    Project: ${PROJECT}
                    Component: ${COMPONENT}
                    Version: ${appVersion}

                    Status: SUCCESS ✅

                    Build URL: ${BUILD_URL}
                    """,
                    to: "${NOTIFY_EMAIL}"
                )
            }

            failure {
                echo "Build is failure"

                emailext (
                    subject: "BUILD FAILED: ${JOB_NAME} #${BUILD_NUMBER}",
                    body: """
                    ❌ BUILD FAILED ❌

                    Job Name: ${JOB_NAME}
                    Build Number: ${BUILD_NUMBER}
                    Project: ${PROJECT}
                    Component: ${COMPONENT}
                    Version: ${appVersion}

                    Please check logs immediately.
                    Build URL: ${BUILD_URL}
                    """,
                    to: "${NOTIFY_EMAIL}"
                )
            }
        }
        
    }
}