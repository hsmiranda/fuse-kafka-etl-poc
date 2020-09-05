pipeline {

    agent {
            node {
                label "maven"
            }
            
        }
        
    tools {
            jdk 'openjdk8'
    }  

    environment
    {
        def version = ""
        def artifactId = ""
        def rollout = true
    }



    stages {  

        stage("Initilize") {
            
            steps {
                script {
                    git branch: "master", credentialsId: "githubid", url: "https://github.com/iam-roger-io/fuse-etl.git" 

                    pom = readMavenPom(file: './person-transformer/pom.xml')
                    version = pom.getVersion();
                    artifactId = pom.getArtifactId();       

                    
                }
            }
        }     

        stage("Build Project") {
            steps {
                script {
                    withMaven( maven: "M361", mavenSettingsConfig: "maven-settings") {
                        sh  '''                             
                        cd person-transformer                          
                  
                        export JAVA_HOME=/usr/lib/jvm/java-1.8.0                      
                    
                        mvn install -DskipTests=true
                                 
                        '''                    
                    }
                }
            }
        }   

        stage('Create Image Builder') {
            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            echo "Using project: ${openshift.project()}"
                            if (!openshift.selector("bc", "${artifactId}").exists()) {
                                openshift.newBuild("--name=${artifactId}", "--docker-image=registry.access.redhat.com/redhat-openjdk-18/openjdk18-openshift:1.8", "--binary")                            
                            }
                        }
                    }
                }
            }
        }

        stage('Start Build Image') {
            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            echo "Using project: ${openshift.project()}"
                            openshift.selector("bc", "${artifactId}").startBuild("--from-file=person-transformer/target/${artifactId}-${version}.jar", "--wait=true")
                        }
                    }
                }
            }
        }


        stage('Promote to DEV') {
            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            openshift.tag("${artifactId}:latest", "${artifactId}:${version}")
                        }   
                    }
                }
            }
        }

        stage('Create ServiceAccount') {
            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            if (!openshift.selector('sa', "${artifactId}").exists()) {
                                openshift.create('sa', "${artifactId}")
                            }                            
                            openshift.raw('policy', 'add-role-to-user', 'view', "system:serviceaccount:${params.PROJECT_NAME}:${artifactId}")
                        }
                    }
                }
            }
        }

        stage('Create DEV') {
            when {
                expression {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            return !openshift.selector('dc', "${artifactId}").exists()
                            
                        }
                    }
                }
            }

            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                              if (!openshift.selector('dc', "${artifactId}").exists()) {
                                openshift.newApp("--template=fuse-person-transformer", 
                                " -p APP_TAG=${version}",
                                " -p NAMESPACE=${params.PROJECT_NAME}",
                                " -p NAME=${artifactId}",
                                " -p DEPLOYER_USER=jenkins",
                                " -p PIPELINES_NAMESPACE=cicd-tools"
                                )
                                
                                sh  '''

                                    oc rollout latest dc/person-transformer
                                                
                                '''  

                                rollout = false
                            }
                        }
                    }
                }
            }
        }


        stage('Rollout Version Container') {
            when {
                expression {
                    return rollout
                }
            }

            steps {
                script {
                    openshift.withCluster() {
                        openshift.withProject("${params.PROJECT_NAME}") {
                            echo "Using project ::: ${openshift.project()}"
                            // Getting the deploymentConfig
                            def deploymentConfig = openshift.selector("dc", "${artifactId}").object()

                            for(int a=0; a<deploymentConfig.spec.triggers.size(); a++ ) {
                                if(deploymentConfig.spec.triggers[a].toString().contains("imageChangeParams")) {
                                    if("${deploymentConfig.spec.triggers[a].imageChangeParams.from.name}" != "${artifactId}:${version}") {
                                        echo "ContainerImage changed to ::: ${deploymentConfig.spec.triggers[a].imageChangeParams.from.name}"
                                        deploymentConfig.spec.triggers[a].imageChangeParams.from.name="${artifactId}:${version}"
                                        openshift.apply(deploymentConfig)
                                    }
                                    else {
                                        echo "Wasn't possible change the Image, because is the same to the previous."
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
