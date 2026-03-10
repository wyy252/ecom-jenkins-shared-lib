def call(Map config = [:]) {
    pipeline {
        agent {
            label 'docker-agent'
        }

        options {
            timestamps()
        }

        environment {
            SERVICE_NAME = "${config.serviceName}"
            IMAGE_NAME = "${config.imageName}"
            DOCKER_CREDENTIALS_ID = "${config.dockerCredentialsId}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        env.SHORT_COMMIT = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                        env.BUILD_TAG_NAME = "${env.BUILD_NUMBER}"
                        env.GIT_TAG_NAME = "git-${env.SHORT_COMMIT}"
                        env.RELEASE_TAG_NAME = env.BRANCH_NAME.startsWith('release/') ? env.BRANCH_NAME.replace('release/', '') : ''
                    }
                }
            }

            stage('Build Stage') {
                steps {
                    sh config.buildCommand
                }
            }

            stage('Test Stage') {
                steps {
                    sh config.testCommand
                }
            }

            stage('Filesystem Security Scan') {
                when {
                    not {
                        changeRequest()
                    }
                }
                steps {
                    sh "docker run --rm -v ${env.WORKSPACE}:/project aquasec/trivy:0.57.1 fs --severity HIGH,CRITICAL --exit-code 0 --format table /project > trivy-fs-${SERVICE_NAME}.txt"
                    archiveArtifacts artifacts: "trivy-fs-${SERVICE_NAME}.txt", allowEmptyArchive: true
                }
            }

            stage('Container Build') {
                when {
                    anyOf {
                        branch 'develop'
                        branch 'main'
                        expression { env.BRANCH_NAME.startsWith('release/') }
                    }
                }
                steps {
                    sh """
                        docker build -t ${IMAGE_NAME}:${BUILD_TAG_NAME} -t ${IMAGE_NAME}:${GIT_TAG_NAME} .
                    """
                    script {
                        if (env.BRANCH_NAME == 'main') {
                            sh "docker tag ${IMAGE_NAME}:${BUILD_TAG_NAME} ${IMAGE_NAME}:latest"
                        }
                        if (env.BRANCH_NAME.startsWith('release/')) {
                            sh "docker tag ${IMAGE_NAME}:${BUILD_TAG_NAME} ${IMAGE_NAME}:${RELEASE_TAG_NAME}"
                        }
                    }
                }
            }

            stage('Container Security Gate') {
                when {
                    not {
                        changeRequest()
                    }
                }
                steps {
                    script {
                        if (env.BRANCH_NAME == 'develop') {
                            sh """
                                docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.57.1 image --severity HIGH,CRITICAL --exit-code 0 --format table ${IMAGE_NAME}:${BUILD_TAG_NAME} > trivy-image-${SERVICE_NAME}.txt
                            """
                        } else if (env.BRANCH_NAME.startsWith('release/') || env.BRANCH_NAME == 'main') {
                            sh """
                                docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.57.1 image --severity HIGH,CRITICAL --exit-code 1 --format table ${IMAGE_NAME}:${BUILD_TAG_NAME} > trivy-image-${SERVICE_NAME}.txt
                            """
                        }
                    }
                    archiveArtifacts artifacts: "trivy-image-${SERVICE_NAME}.txt", allowEmptyArchive: true
                }
            }

            stage('Container Push') {
                when {
                    anyOf {
                        branch 'develop'
                        branch 'main'
                        expression { env.BRANCH_NAME.startsWith('release/') }
                    }
                }
                steps {
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_PASSWORD')]) {
                        sh """
                            echo "${DOCKERHUB_PASSWORD}" | docker login -u "${DOCKERHUB_USERNAME}" --password-stdin
                            docker push ${IMAGE_NAME}:${BUILD_TAG_NAME}
                            docker push ${IMAGE_NAME}:${GIT_TAG_NAME}
                        """
                        script {
                            if (env.BRANCH_NAME == 'main') {
                                sh "docker push ${IMAGE_NAME}:latest"
                            }
                            if (env.BRANCH_NAME.startsWith('release/')) {
                                sh "docker push ${IMAGE_NAME}:${RELEASE_TAG_NAME}"
                            }
                        }
                    }
                }
            }

            stage('Deploy') {
                when {
                    anyOf {
                        branch 'develop'
                        branch 'main'
                        expression { env.BRANCH_NAME.startsWith('release/') }
                    }
                }
                steps {
                    script {
                        if (env.BRANCH_NAME == 'develop') {
                            sh config.devDeployCommand
                        } else if (env.BRANCH_NAME.startsWith('release/')) {
                            sh config.stagingDeployCommand
                        } else if (env.BRANCH_NAME == 'main') {
                            input message: 'Approve production deployment?', ok: 'Deploy'
                            sh config.prodDeployCommand
                        }
                    }
                }
            }
        }
    }
}