pipelineJob('Builds/P-build'){
  description('Java Update Builds CHECK NOTES.')

  triggers {
    cron('''
      TZ=America/Toronto
      # format: Minute Hour Day Month Day of the week (0-7)

      #Daily P-build
      #0 5 * * *
    ''')
  }

  logRotator {
    numToKeep(25)
  }

  definition {
    cpsScm {
      lightweight(true)
      scm {
        github('https://github.com/eclipse-platform/eclipse.platform.releng.aggregator/', 'R4_25_maintenance')
      }
    }

    cps {
      sandbox()
      script('''
pipeline {
	options {
		timeout(time: 360, unit: 'MINUTES')
		timestamps()
		buildDiscarder(logRotator(numToKeepStr:'25'))
	}
  agent {
    kubernetes {
      label 'aggrbuild-pod'
      defaultContainer 'container'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: "jnlp"
    image: "eclipsecbi/jiro-agent-centos-8-jdk11:latest"
    imagePullPolicy: "Always"
    resources:
      limits:
        memory: "8192Mi"
        cpu: "4000m"
      requests:
        memory: "6144Mi"
        cpu: "2000m"
    securityContext:
      privileged: false
    tty: true
    volumeMounts:
    - mountPath: "/home/jenkins/agent"
      name: "workspace-volume"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/toolchains.xml"
      name: "toolchains-xml"
      readOnly: true
      subPath: "toolchains.xml"
    - mountPath: "/opt/tools"
      name: "volume-0"
      readOnly: false
    - mountPath: "/home/jenkins"
      name: "volume-2"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/repository"
      name: "volume-3"
      readOnly: false
    - mountPath: "/home/jenkins/.m2/settings-security.xml"
      name: "settings-security-xml"
      readOnly: true
      subPath: "settings-security.xml"
    - mountPath: "/home/jenkins/.m2/settings.xml"
      name: "settings-xml"
      readOnly: true
      subPath: "settings.xml"
    - mountPath: "/home/jenkins/.ssh"
      name: "volume-1"
      readOnly: false
    workingDir: "/home/jenkins/agent"
  nodeSelector: {}
  restartPolicy: "Never"
  volumes:
  - name: "settings-security-xml"
    secret:
      items:
      - key: "settings-security.xml"
        path: "settings-security.xml"
      secretName: "m2-secret-dir"
  - name: "volume-0"
    persistentVolumeClaim:
      claimName: "tools-claim-jiro-releng"
      readOnly: true
  - configMap:
      items:
      - key: "toolchains.xml"
        path: "toolchains.xml"
      name: "m2-dir"
    name: "toolchains-xml"
  - emptyDir:
      medium: ""
    name: "volume-2"
  - configMap:
      name: "known-hosts"
    name: "volume-1"
  - name: "settings-xml"
    secret:
      items:
      - key: "settings.xml"
        path: "settings.xml"
      secretName: "m2-secret-dir"
  - emptyDir:
      medium: ""
    name: "workspace-volume"
  - emptyDir:
      medium: ""
    name: "volume-3"
"""
    }
  }
  environment {
      MAVEN_OPTS = "-Xmx6G"
      CJE_ROOT = "${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production"
      PATH = "$PATH:/opt/tools/apache-maven/latest/bin"
      logDir = "$CJE_ROOT/buildlogs"
    }
  
  stages {
      stage('Clean Workspace'){
          steps {
              container('jnlp') {
                sh \'\'\'
                    cd $WORKSPACE
                    rm -rf *
                \'\'\'
                }
            }
	    }
	  stage('Setup intial configuration'){
          steps {
              container('jnlp') {
                 sshagent(['github-bot-ssh']) {
                      dir ('eclipse.platform.releng.aggregator') {
                        sh \'\'\'
                            git clone -b R4_25_maintenance git@github.com:eclipse-platform/eclipse.platform.releng.aggregator.git
                        \'\'\'
                      }
                    }
                    sh \'\'\'
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production
                        chmod +x mbscripts/*
                        mkdir -p $logDir
                    \'\'\'
                }
            }
		}
	  stage('Genrerate environment variables'){
          steps {
              container('jnlp') {
                sh \'\'\'
                    cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                    cp ../P-build/buildproperties.txt ../buildproperties.txt
                    ./mb010_createEnvfiles.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb010_createEnvfiles.sh.log
                    if [[ ${PIPESTATUS[0]} -ne 0 ]]
                    then
                        echo "Failed in Genrerate environment variables stage"
                        exit 1
                    fi
                \'\'\'
                }
            }
		}
		stage('Load PGP keys'){
          environment {
                KEYRING = credentials('secret-subkeys-releng.asc')
                KEYRING_PASSPHRASE = credentials('secret-subkeys-releng.acs-passphrase')
          }
          steps {
              container('jnlp') {
                sh \'\'\'
                    cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                    ./mb011_loadPGPKeys.sh 2>&1 | tee $logDir/mb011_loadPGPKeys.sh.log
                    if [[ ${PIPESTATUS[0]} -ne 0 ]]
                    then
                        echo "Failed in Load PGP keys"
                        exit 1
                    fi
                \'\'\'
                }
            }
		}
	  stage('Export environment variables stage 1'){
          steps {
              container('jnlp') {
                script {
                    env.BUILD_IID = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $BUILD_TYPE$TIMESTAMP)', returnStdout: true)
                    env.RELEASE_VER = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                  }
                }
            }
        }
	  stage('Clone Repositories'){
          steps {
              container('jnlp') {
                  sshagent(['git.eclipse.org-bot-ssh', 'github-bot-ssh']) {
                    sh \'\'\'
                        git config --global user.email "releng-bot@eclipse.org"
                        git config --global user.name "Eclipse Releng Bot"
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb100_cloneRepos.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb100_cloneRepos.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Clone Repositories stage"
                            exit 1
                        fi
                    \'\'\'
                  }
                }
            }
		}
	  stage('Tag Build Inputs'){
          steps {
              container('jnlp') {
                  sshagent (['git.eclipse.org-bot-ssh', 'github-bot-ssh', 'projects-storage.eclipse.org-bot-ssh']) {
                    sh \'\'\'
                        git config --global user.email "releng-bot@eclipse.org"
                        git config --global user.name "Eclipse Releng Bot"
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        bash -x ./mb110_tagBuildInputs.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb110_tagBuildInputs.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Tag Build Inputs stage"
                            exit 1
                        fi
                    \'\'\'
                  }
                }
            }
		}
	  stage('Copy build scripts for P-build'){
          steps {
              container('jnlp') {
                    sh \'\'\'
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/P-build
                        cp mb220_buildSdkPatch.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                        cp mb220_buildSdkPatch.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                        cp mb300_gatherEclipseParts.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                        cp mb300_gatherEclipseParts.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                        cp mb620_promoteUpdateSite.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/gitCache/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                        cp mb620_promoteUpdateSite.sh ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts/.
                    \'\'\'
                }
            }
		}
		stage('Aggregator maven build'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                    sh \'\'\'
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        unset JAVA_TOOL_OPTIONS 
                        unset _JAVA_OPTIONS
                        ./mb220_buildSdkPatch.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb220_buildSdkPatch.sh.log
                        if [[ ${PIPESTATUS[0]} -ne 0 ]]
                        then
                            echo "Failed in Aggregator maven build stage"
                            exit 1
                        fi
                    \'\'\'
                  }
                }
            }
		}
	  stage('Gather Eclipse Parts'){
          steps {
              container('jnlp') {
                  withEnv(["JAVA_HOME=${ tool 'openjdk-jdk11-latest' }"]) {
                      withAnt(installation: 'apache-ant-latest', jdk: 'openjdk-jdk11-latest') {
                          sh \'\'\'
                            cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                            bash -x ./mb300_gatherEclipseParts.sh $CJE_ROOT/buildproperties.shsource 2>&1 | tee $logDir/mb300_gatherEclipseParts.sh.log
                            if [[ ${PIPESTATUS[0]} -ne 0 ]]
                            then
                                echo "Failed in Gather Eclipse Parts stage"
                                exit 1
                            fi
                          \'\'\'
                      }
                  }
                }
            }
		}
	  stage('Export environment variables stage 2'){
          steps {
              container('jnlp') {
                script {
                    env.BUILD_IID = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $BUILD_TYPE$TIMESTAMP)', returnStdout: true)
                    env.RELEASE_VER = sh(script:'echo $(source $CJE_ROOT/buildproperties.shsource;echo $RELEASE_VER)', returnStdout: true)
                  }
                }
            }
        }
	  stage('Archive artifacts'){
          steps {
              archiveArtifacts '**/siteDir/**'
            }
		}
	  stage('Promote Update Site'){
          steps {
              container('jnlp') {
                  sshagent(['projects-storage.eclipse.org-bot-ssh']) {
                      sh \'\'\'
                        cd ${WORKSPACE}/eclipse.platform.releng.aggregator/eclipse.platform.releng.aggregator/cje-production/mbscripts
                        ./mb620_promoteUpdateSite.sh $CJE_ROOT/buildproperties.shsource
                      \'\'\'
                  }
                }
            }
		}
	}
	post {
        failure {
            emailext body: "Please go to <a href='${BUILD_URL}console'>${BUILD_URL}console</a> and check the build failure.<br><br>",
            subject: "${env.RELEASE_VER.trim()} P-Build: ${env.BUILD_IID.trim()} - BUILD FAILED", 
            to: "jarthana@in.ibm.com sravankumarl@in.ibm.com kalyan_prasad@in.ibm.com lshanmug@in.ibm.com manoj.palat@in.ibm.com niraj.modi@in.ibm.com noopur_gupta@in.ibm.com sarika.sinha@in.ibm.com vikas.chandra@in.ibm.com",
            from:"genie.releng@eclipse.org"
        }
        success {
            emailext body: "Software site repository:<br>    <a href='https://download.eclipse.org/eclipse/updates/${env.RELEASE_VER.trim()}-P-builds'>https://download.eclipse.org/eclipse/updates/${env.RELEASE_VER.trim()}-P-builds</a><br><br>Specific (simple) site repository:<br>    <a href='https://download.eclipse.org/eclipse/updates/${env.RELEASE_VER.trim()}-P-builds/${env.BUILD_IID.trim()}'>https://download.eclipse.org/eclipse/updates/${env.RELEASE_VER.trim()}-P-builds/${env.BUILD_IID.trim()}</a><br><br>", 
            subject: "${env.RELEASE_VER.trim()} P-Build: ${env.BUILD_IID.trim()}", 
            to: "jarthana@in.ibm.com sravankumarl@in.ibm.com kalyan_prasad@in.ibm.com lshanmug@in.ibm.com manoj.palat@in.ibm.com niraj.modi@in.ibm.com noopur_gupta@in.ibm.com sarika.sinha@in.ibm.com vikas.chandra@in.ibm.com",
            from:"genie.releng@eclipse.org"
        }
	}
}

      ''')
    }
  }
}