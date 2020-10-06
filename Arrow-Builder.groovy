import java.io.*;
import java.sql.*; 
import groovy.sql.Sql
import groovy.transform.Field

def jsonParse(def json) { new groovy.json.JsonSlurperClassic().parseText(json) }
@Field slackThreadResp

public Boolean checkSlackPlugin() {
    def isAvailable = false
    def plugins = jenkins.model.Jenkins.instance.getPluginManager().getPlugins()
    plugins.each { plugin ->
        if(plugin.getShortName() == "slack") {
            isAvailable = true
            return isAvailable
        }
    }
    return isAvailable
}

public void sendSlackNotify(def msg, def consoleUrl = null, def downUrl = null) {
    if (!checkSlackPlugin()) {
        echo "Slack plugin not available/installed!"
        return
    }

    def msgBlock = [
                        [
                            "type": "section",
                            "text": [
                                "type": "mrkdwn",
                                "text": msg
                            ]
                        ],
                        [
                            "type": "actions",
                            "elements": [
                                [
                                    "type": "button",
                                    "text": [
                                        "type": "plain_text",
                                        "text": consoleUrl ? "Console" : "Download"
                                    ],
                                    "style": "primary",
                                    "url": consoleUrl ? consoleUrl.toString().trim() : downUrl.toString().trim()
                                ]
                            ]
                        ]
                    ]
                    
    if (!slackThreadResp) {
        slackThreadResp = slackSend(channel: "#arrowos-jenkins", blocks: msgBlock)
    } else {
        slackSend(
            channel: slackThreadResp.threadId,
            blocks: msgBlock
        )
    }
}

currentBuild.displayName = "${DEVICE} (${VERSION})"
currentBuild.description = "Waiting for Executor @ ${ASSIGNED_NODE}"

environment {
    def MAIN_DISK
    def SOURCE_DIR
    def CCACHE_DIR
    def STALE_PATHS_FILE
    def TG_VARS_FILE

    // device config holders
    def repo_paths
    def repo_clones
    def repo_clones_paths
    def repopick_topics
    def repopick_changes

    // device data (switch holders)
    def force_clean
    def test_build
    def is_official
    def buildtype
    def buildvariant
    def bootimage
    def changelog
    def common_changelog
    def xda_link
    def global_override
    def default_buildtype_state

    // tg notify args
    def is_tgnotify
    def TG_ARROW_ZIP
    def TG_DEVICE
    def TG_DEVICE_MAINTAINER
    def TG_DEVICE_MODEL
    def TG_DEVICE_OEM
    def TG_BUILD_TYPE
    def TG_BUILD_ZIP_TYPE
    def TG_ARROW_VERSION
    def TG_DATE
    def TG_HASHTAGS
    def BUILD_OUT_DIR
}

if(!ASSIGNED_NODE.isEmpty()) {
    node(ASSIGNED_NODE) {
        currentBuild.description = "Executing @ ${ASSIGNED_NODE}"
        
        sendSlackNotify("*Build has started for ${DEVICE}*\n*Executing @ ${ASSIGNED_NODE}*", "${BUILD_URL}")

        env.MAIN_DISK = "/source".toString().trim()
        env.SOURCE_DIR = env.MAIN_DISK + "/arrow".toString().trim()
        env.CCACHE_DIR = env.MAIN_DISK + "/.ccache/" + DEVICE.toString().trim()
        env.STALE_PATHS_FILE = env.MAIN_DISK + "/stale_paths.txt".toString().trim()
        env.TG_VARS_FILE = env.MAIN_DISK + "/tgvars.txt".toString().trim()

        stage('Fetching configs from DB') {
            echo "Establishing connection to configs DB...!"
            fetchConfigs(DEVICE)
        }

        stage("Hard reset") {
                sh '''#!/bin/bash +x
                    cd '''+env.SOURCE_DIR+'''

                    if [ -f '''+env.STALE_PATHS_FILE+''' ]; then
                        txt_content=$(cat '''+env.STALE_PATHS_FILE+''')
                        if [ "$txt_content" != "" ]; then
                            echo "------------------------------------------"
                            echo "Deleting stale repos from previous build"
                            echo "------------------------------------------"
                            while IFS= read -r line
                            do
                                cd '''+env.SOURCE_DIR+'''
                                rm -rf "$line" > /dev/null
                                if [ $? -eq 0 ]; then
                                    echo "---------------------------------"
                                    echo "Deleted directory $line"
                                    echo "---------------------------------"
                                else
                                    echo "---------------------------------"
                                    echo "Failed to delete directory $line"
                                    echo "---------------------------------"
                                fi
                            done < '''+env.STALE_PATHS_FILE+'''
                            > '''+env.STALE_PATHS_FILE+'''
                        else
                            echo "---------------------------------"
                            echo "No stale repos present!"
                            echo "---------------------------------"
                        fi
                    else
                        echo "---------------------------------"
                        echo "No stale paths file found!"
                        echo "---------------------------------"
                    fi
                    '''

                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                echo " "
                echo "Performing hard reset and clean!"
                echo " "
                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                echo " "
                repoStatus = sh(returnStatus: true,
                                script: '''#!/bin/bash
                                            cd '''+env.SOURCE_DIR+'''
                                            repo forall -c "git clean -fdx && git reset --hard " -j4 > /dev/null
                                        '''
                                )

                if(repoStatus == 0)
                    echo "Hard rest and clean done!"
                else
                    echo "Hard reset and clean had issues!"
        }

        stage('Repo sync') {
                sh  '''#!/bin/bash
                        if [ '''+VERSION+''' = "arrow-community" ]; then
                            # Hardcode this to always the latest version for now
                            TO_SYNC="arrow-10.0"
                        else
                            TO_SYNC='''+VERSION+'''
                        fi
                        
                        cd '''+env.SOURCE_DIR+'''
                        rm -rf '''+env.SOURCE_DIR+'''/.repo/local_manifests
                        repo init -u https://github.com/ArrowOS/android_manifest.git -b $TO_SYNC --depth=1 > /dev/null
                        repo sync --force-sync --no-tags --no-clone-bundle -c -j4
                        if [ $? -ne 0 ]; then
                            exit 1
                        fi
                    '''
        }

        stage('Clean plate') {
            sh  '''#!/bin/bash
                    cd '''+env.SOURCE_DIR+'''
                    source build/envsetup.sh > /dev/null

                    avail_space=$(df | grep /source | df -BG --output=avail $(awk 'FNR == 1 {print $1}') | awk 'FNR == 2 {print $1}' | cut -d 'G' -f 1)
                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                    echo " "
                    echo "Current Available Space: $avail_space"
                    echo " "
                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                    if [ '''+env.force_clean+''' == "yes" ]; then
                        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                        echo " "
                        echo "Force clean enabled!"
                        echo "Performing a full clean"
                        echo " "
                        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                        mka clean
                        return
                    else
                        echo "Force clean not enabled"
                    fi

                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                    echo " "
                    echo "Nuking product out!"
                    echo " "
                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                    rm -rf '''+env.SOURCE_DIR+'''/out/target/product/*
                    if [ $? -eq 0 ]; then
                        echo "Cleaned up product out dirs!"
                    else
                        echo "Failed to nuke product out dirs"
                    fi

                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                    echo " "
                    echo "Doing installclean"
                    echo " "
                    echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                '''
        }
        
        stage('Parsing configs data') {
            delConfigRepos()
            cloneConfigRepos()
            repopickTopics()
            repopickChanges()

            // Fetch common configs and apply
            echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
            echo "Fetching common configuration"
            echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
            setConfigsData("common_config", false)
            delConfigRepos()
            cloneConfigRepos()
            repopickTopics()
            repopickChanges()
        }

        stage('Device lunch') {
            try {
                deviceLunch()
            } catch (Exception e) {
                slackThreadResp.addReaction("thumbsdown")
                slackThreadResp.addReaction("x")
                sendSlackNotify("Device lunch failed!", "${BUILD_URL}")
                currentBuild.description = "Device lunch issue"
                currentBuild.result = 'FAILURE'
                sh "exit 1"
            }
        }

        stage('Compiling') {
            try {
                deviceCompile()
            } catch (Exception e) {
                slackThreadResp.addReaction("thumbsdown")
                slackThreadResp.addReaction("x")
                sendSlackNotify("Device compile/bacon error!", "${BUILD_URL}")
                currentBuild.description = "Compile error"
                currentBuild.result = 'FAILURE'
                sh "exit 1"
            }
        }

        stage("Upload & Notify") {
            upload()

            if (env.buildvariant == "both") {
                sendSlackNotify("*|Stage(1/2) VANILLA| Build finished for ${DEVICE}*", "${BUILD_URL}")
            } else {
                sendSlackNotify ("*(${env.buildvariant.toUpperCase()}) Build finished for ${DEVICE}*", "${BUILD_URL}")
            }
            
            genOTA()
        }

        // Gapps build stage
        if(VERSION != "arrow-9.x" && env.bootimage != "yes" && env.buildvariant == "both") {
            stage("Gapps build") {

                stage("Device lunch") {
                    env.buildvariant = "gapps"
                    try {
                        deviceLunch()
                    } catch (Exception e) {
                        slackThreadResp.addReaction("thumbsdown")
                        slackThreadResp.addReaction("x")
                        sendSlackNotify("Device lunch failed!", "${BUILD_URL}")
                        currentBuild.description = "Device lunch issue"
                        currentBuild.result = 'FAILURE'
                        sh "exit 1"
                    }
                }

                stage("Compiling") {
                    try {
                        deviceCompile()
                    } catch (Exception e) {
                        slackThreadResp.addReaction("thumbsdown")
                        slackThreadResp.addReaction("x")
                        sendSlackNotify("Device compile/bacon error!", "${BUILD_URL}")
                        currentBuild.description = "Compile error"
                        currentBuild.result = 'FAILURE'
                        sh "exit 1"
                    }
                }

                stage("Upload & Notify") {
                    upload()

                    sendSlackNotify("*|Stage(2/2) GAPPS| Build finished for ${DEVICE}*", "${BUILD_URL}")
                    
                    genOTA()
                }
            }
        }
     buildNotify()
    }
}

/* 
-----------------------------------------------------
    Database connection stage   
-------------------------------------------------------
*/
@NonCPS
public def setConfigsData(String whichDevice, Boolean isGlobalOvr) {
    try {
        def which_db = VERSION
        def dbcon = Sql.newInstance('jdbc:mysql://localhost:3306/configs_' +which_db,
            'mirror1', 'get.mirror1.arrowos.net@69', 'com.mysql.jdbc.Driver')

        def md = dbcon.connection.metaData
        def deviceTable = md.getTables(null, null, whichDevice, null)

        try {
            while(deviceTable.next()) {
                if(deviceTable.getString('TABLE_NAME') == whichDevice) {
                    dbcon.eachRow("select * from "+whichDevice+"") {
                        configs->
                        if(whichDevice == "common_config" && isGlobalOvr) {
                            env.global_override = configs.global_override.toString().trim()
                            if(env.global_override == "yes") {
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                                echo "Fetching global overrides!"
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                                env.force_clean = configs.force_clean.toString().trim()
                                env.test_build = configs.test_build.toString().trim()
                                env.is_official = configs.is_official.toString().trim()
                                env.bootimage = configs.bootimage.toString().trim()
                                env.buildtype = configs.buildtype.toString().trim()
                                env.buildvariant = configs.buildvariant.toString().trim()
                                env.default_buildtype_state = configs.default_buildtype_state.toString().trim()

                                if(env.default_buildtype_state == "yes") {
                                    dbcon.eachRow("SELECT * FROM "+DEVICE+"") {
                                        env.buildtype = it.default_buildtype.toString().trim()
                                    }
                                }
                            }
                            return
                        }

                        if(whichDevice == "common_config") {
                            env.repo_paths = configs.repo_paths.toString().trim()
                            env.repo_clones = configs.repo_clones.toString().trim()
                            env.repo_clones_paths = configs.repo_clones_paths.toString().trim()
                            env.repopick_topics = configs.repopick_topics.toString().trim()
                            env.repopick_changes = configs.repopick_changes.toString().trim()
                            env.common_changelog = configs.changelog.toString().trim()

                            return
                        }

                        env.repo_paths = configs.repo_paths.toString().trim()
                        env.repo_clones = configs.repo_clones.toString().trim()
                        env.repo_clones_paths = configs.repo_clones_paths.toString().trim()
                        env.repopick_topics = configs.repopick_topics.toString().trim()
                        env.repopick_changes = configs.repopick_changes.toString().trim()
                        env.force_clean = configs.force_clean.toString().trim()
                        env.test_build = configs.test_build.toString().trim()
                        env.is_official = configs.is_official.toString().trim()
                        env.buildtype = configs.buildtype.toString().trim()
                        env.buildvariant = configs.buildvariant.toString().trim()
                        env.bootimage = configs.bootimage
                        env.changelog = configs.changelog.toString().trim()

                        if(whichDevice != "common_config") {
                            env.xda_link = configs.xda_link.toString().trim()
                        }
                    }
                } else {
                    echo "Failed to find table ${whichDevice}"
                }
            }
        } finally {
            dbcon.close()
        }
    } catch(e) {
        throw(e)
    }
}

public def fetchConfigs(def DEVICE) {
    try {
        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
        echo "Fetching configuration for ${DEVICE}"
        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

        //set device configs
        setConfigsData(DEVICE, false)
        //set global overrides
        setConfigsData("common_config", true)
    } catch(e) {
        throw(e)
    }
}

/*
---------------------------------------------------
    Device lunch stage
---------------------------------------------------
*/
public def deviceLunch() {
    sh  '''#!/bin/bash

            cd '''+env.SOURCE_DIR+'''
            source build/envsetup.sh > /dev/null

            export ARROW_OFFICIAL=true

            if [ '''+env.is_official+''' = "no" ]; then
                unset ARROW_OFFICIAL
            fi
            
            if [ '''+VERSION+''' = "arrow-community" ]; then
                unset ARROW_OFFICIAL
                export ARROW_COMMUNITY=true
            fi

            if [ ! -z '''+env.buildvariant+''' ]; then
                if [ '''+env.buildvariant+''' = "vanilla" ]; then
                    export ARROW_GAPPS=false
                elif [ '''+env.buildvariant+''' = "gapps" ]; then
                    export ARROW_GAPPS=true
                else
                    export ARROW_GAPPS=false
                fi    
            fi

            # Perform lunch
            if [ ! -z '''+env.buildtype+''' ]; then
                lunch arrow_'''+DEVICE+'''-'''+env.buildtype+'''
                if [ $? -ne 0 ]; then
                    echo "Device lunch FAILED!"
                    exit 1
                fi
            else
                echo "No buildtype specified!"
                exit 0
            fi

            >'''+env.TG_VARS_FILE+'''
            echo TG_ARROW_ZIP $(get_build_var ARROW_VERSION).zip >> '''+env.TG_VARS_FILE+'''
            echo TG_DEVICE $(get_build_var TARGET_DEVICE) >> '''+env.TG_VARS_FILE+'''
            echo TG_DEVICE_MAINTAINER $(get_build_var DEVICE_MAINTAINER) >> '''+env.TG_VARS_FILE+'''
            echo TG_DEVICE_MODEL $(get_build_var PRODUCT_MODEL) >> '''+env.TG_VARS_FILE+'''
            echo TG_DEVICE_OEM $(get_build_var PRODUCT_BRAND) >> '''+env.TG_VARS_FILE+'''
            echo TG_BUILD_TYPE $(get_build_var ARROW_BUILD_TYPE) >> '''+env.TG_VARS_FILE+'''
            echo TG_BUILD_ZIP_TYPE $(get_build_var ARROW_BUILD_ZIP_TYPE) >> '''+env.TG_VARS_FILE+'''
            echo TG_ARROW_VERSION $(get_build_var ARROW_MOD_VERSION) >> '''+env.TG_VARS_FILE+'''
            echo TG_DATE `date +'%d/%m/%Y'` >> '''+env.TG_VARS_FILE+'''
            echo TG_HASHTAGS "#ArrowOS #Arrow" >> '''+env.TG_VARS_FILE+'''
            echo BUILD_OUT_DIR $OUT >> '''+env.TG_VARS_FILE+'''

        '''

        // Set holder vars
        env.TG_ARROW_ZIP = getTgVars("TG_ARROW_ZIP").toString().trim()
        env.TG_DEVICE = getTgVars("TG_DEVICE").toString().trim()
        env.TG_DEVICE_MAINTAINER = getTgVars("TG_DEVICE_MAINTAINER").toString().trim()
        env.TG_DEVICE_MODEL = getTgVars("TG_DEVICE_MODEL").toString().trim()
        env.TG_DEVICE_OEM = getTgVars("TG_DEVICE_OEM").toString().trim()
        env.TG_BUILD_TYPE = getTgVars("TG_BUILD_TYPE").toString().trim()
        env.TG_BUILD_ZIP_TYPE = getTgVars("TG_BUILD_ZIP_TYPE").toString().trim()
        env.TG_ARROW_VERSION = getTgVars("TG_ARROW_VERSION").toString().trim()
        env.TG_DATE = getTgVars("TG_DATE").toString().trim()
        env.TG_HASHTAGS = getTgVars("TG_HASHTAGS").toString().trim()
        env.BUILD_OUT_DIR = getTgVars("BUILD_OUT_DIR").toString().trim()
}

public def getTgVars(def tg_key) {
    def tg_value = sh(returnStdout: true,
                        script: '''#!/bin/bash
                                    cat '''+env.TG_VARS_FILE+''' | grep -w '''+tg_key+''' | cut -d' ' -f 2-
                                '''
                    )
    return tg_value.trim()
}

/*
-----------------------------------------------
    Delete config repos
-----------------------------------------------
*/
public def delConfigRepos() {
    def repoPaths = jsonParse(env.repo_paths)

    repoPaths.repo_paths.each { rpath->
        sh  '''#!/bin/bash

                if [ -d '''+env.SOURCE_DIR+"/"+rpath+''' ]; then
                    rm -rf '''+env.SOURCE_DIR+"/"+rpath+'''
                    if [ $? -eq 0 ]; then
                        echo "Deleted '''+rpath+''' "
                    else
                        echo "Failed to delete '''+rpath+''' "
                        exit 1
                    fi
                else
                    echo "No such directory '''+rpath+''' "
                fi

            '''
    }
}

/*
-----------------------------------------------
    Clone config repos
-----------------------------------------------
*/
public def cloneConfigRepos() {
    def repoClonesUrl = jsonParse(env.repo_clones)
    def repoClonesPaths = jsonParse(env.repo_clones_paths)

    repoClonesUrl.repo_clones.eachWithIndex { url,i ->
        def rurl = repoClonesUrl["repo_clones"][i]
        def rpath = repoClonesPaths["repo_clones_paths"][i]
        sh  '''#!/bin/bash

                cd '''+env.SOURCE_DIR+'''
                git clone '''+rurl+''' '''+rpath+''' --depth=1
                if [ $? -eq 0 ]; then
                    echo "---------------------------------"
                    echo "Cloned repo '''+rurl+''' into '''+rpath+''' "
                    echo "---------------------------------"

                    # store the paths into a temp file so that we can clean those repos on next
                    # run in case the build gets interrupted and doesn't reach end of the script
                    echo '''+rpath+''' >> '''+env.STALE_PATHS_FILE+'''
                else
                    echo "---------------------------------"
                    echo "Failed to clone repo '''+rurl+''' into '''+rpath+''' "
                    echo "---------------------------------"
                    exit 1
                fi

            '''
    }
}

/*
-----------------------------------------------
    Repopick config topics
-----------------------------------------------
*/
public def repopickTopics() {
    def pickTopics = jsonParse(env.repopick_topics)

    pickTopics.repopick_topics.each { changeTopic ->
        sh  '''#!/bin/bash

                cd '''+env.SOURCE_DIR+'''
                source build/envsetup.sh > /dev/null

                repopick -t '''+changeTopic+'''
                if [ $? -eq 0 ]; then
                    echo "---------------------------------"
                    echo "Repopicked topic '''+changeTopic+''' "
                    echo "---------------------------------"
                else
                    echo "---------------------------------"
                    echo "Failed to repopick topic '''+changeTopic+''' "
                    echo "---------------------------------"
                fi

            '''
    }
}

/*
-----------------------------------------------
    Repopick config change numbers
-----------------------------------------------
*/
public def repopickChanges() {
    def pickChanges = jsonParse(env.repopick_changes)

    pickChanges.repopick_changes.each { changeNum ->
        sh  '''#!/bin/bash

                cd '''+env.SOURCE_DIR+'''
                source build/envsetup.sh > /dev/null

                repopick '''+changeNum+'''
                if [ $? -eq 0 ]; then
                    echo "---------------------------------"
                    echo "Repopicked change number '''+changeNum+''' "
                    echo "---------------------------------"
                else
                    echo "---------------------------------"
                    echo "Failed to repopick change number '''+changeNum+''' "
                    echo "---------------------------------"
                fi

            '''
    }
}

/*
-----------------------------------------------
    Compiling stage
-----------------------------------------------
*/
public def deviceCompile() {
    sh  '''#!/bin/bash

            # Linux exports
            export PATH=~/bin:$PATH
            export USE_CCACHE=1
            export CCACHE_COMPRESS=1
            export CCACHE_DIR='''+env.CCACHE_DIR+'''
            export KBUILD_BUILD_USER=release
            export KBUILD_BUILD_HOST=ArrowOS
            export LOCALVERSION=-Arrow

            # Rom exports
            if [ '''+env.buildtype+''' = "user" ]; then
                export SELINUX_IGNORE_NEVERALLOWS_ON_USER=true
            else
                export SELINUX_IGNORE_NEVERALLOWS=true
            fi
            export ALLOW_MISSING_DEPENDENCIES=true
            export ARROW_OFFICIAL=true
            
            if [ '''+env.TG_BUILD_ZIP_TYPE+''' = "GAPPS" ]; then
                export ARROW_GAPPS=true
            else
                export ARROW_GAPPS=false
            fi

            cd '''+env.SOURCE_DIR+'''
            source build/envsetup.sh > /dev/null

            if [ '''+env.is_official+''' = "no" ]; then
                unset ARROW_OFFICIAL
            fi
            
            if [ '''+VERSION+''' = "arrow-community" ]; then
                unset ARROW_OFFICIAL
                export ARROW_COMMUNITY=true
            fi

            # Perform lunch for the main device as we might be needing them
            # by the overriding device
            if [ ! -z '''+env.buildtype+''' ]; then
                lunch arrow_'''+DEVICE+'''-'''+env.buildtype+'''
                if [ $? -ne 0 ]; then
                    echo "Device lunch FAILED!"
                    exit 1
                fi
            else
                echo "No buildtype specified!"
                exit 0
            fi

            if [ '''+env.bootimage+''' = "yes" ]; then
                mka bootimage
            else
                mka installclean
                mka bacon
            fi
        '''
}


/*
-----------------------------------------------
    Update & Notify stage
-----------------------------------------------
*/
public def upload() {
    sh  '''#!/bin/bash
            
            cd '''+env.SOURCE_DIR+'''
            if [ '''+env.bootimage+''' = "yes" ]; then
                cd '''+env.BUILD_OUT_DIR+'''
                BUILD_ARTIFACT=$(ls -t boot.img | head -1)
                TO_UPLOAD='''+env.BUILD_OUT_DIR+'''/$BUILD_ARTIFACT
            else
                BUILD_ARTIFACT='''+env.TG_ARROW_ZIP+'''
                TO_UPLOAD='''+env.BUILD_OUT_DIR+'''/$BUILD_ARTIFACT
            fi

            if [ -f $TO_UPLOAD ]; then
                if [ '''+env.test_build+''' = "yes" ]; then
                    if [ '''+VERSION+''' = "arrow-community" ]; then
                        #arrow mirror
                        script -q -c "scp $TO_UPLOAD root@10.0.0.200:/mnt/HDD1/builds/arrow-$(echo '''+env.TG_ARROW_VERSION+''' | cut -d "v" -f 2)/community_experiments" | stdbuf -oL tr '\r' '\n'
                    else
                        #arrow mirror
                        script -q -c "scp $TO_UPLOAD root@get.mirror1.arrowos.net:/mnt/HDD1/builds/'''+VERSION+'''/experiments" | stdbuf -oL tr '\r' '\n'
                        script -q -c "scp $TO_UPLOAD root@get.mirror2.arrowos.net:/mnt/HDD1/builds/'''+VERSION+'''/experiments" | stdbuf -oL tr '\r' '\n'
                    fi
                    if [ $? -eq 0 ]; then
                        echo "SUCCESSFULLY UPLOADED TEST BUILD TO ARROW SERVER"
                        notify=0
                    else
                        echo "FAILED TO UPLOAD TO TEST BUILD ARROW SERVER"
                        notify=1
                    fi
                    TG_DOWN_URL="https://downloads.arrowos.net/'''+DEVICE+'''"
                    echo TG_TITLE "**New ['''+DEVICE+''']($TG_DOWN_URL) build [(`date +'%d-%m-%Y'`)](https://changelog.arrowos.net) is out! ('''+VERSION+''')**" >> '''+env.TG_VARS_FILE+'''
                else
                    if [ '''+VERSION+''' = "arrow-community" ]; then
                        #arrow mirror
                        script -q -c "scp $TO_UPLOAD root@10.0.0.200:/mnt/HDD1/builds/arrow-$(echo '''+env.TG_ARROW_VERSION+''' | cut -d "v" -f 2)/community" | stdbuf -oL tr '\r' '\n'
                    else
                        #arrow mirror
                        script -q -c "scp $TO_UPLOAD root@get.mirror1.arrowos.net:/mnt/HDD1/builds/'''+VERSION+'''/official" | stdbuf -oL tr '\r' '\n'
                        script -q -c "scp $TO_UPLOAD root@get.mirror2.arrowos.net:/mnt/HDD1/builds/'''+VERSION+'''/official" | stdbuf -oL tr '\r' '\n'
                    fi
                    if [ $? -eq 0 ]; then
                        echo "SUCCESSFULLY UPLOADED TO ARROW SERVERS"
                        notify=0
                    else
                        echo "FAILED TO UPLOAD TO ARROW SERVERS"
                        notify=1
                    fi
                    TG_DOWN_URL="https://downloads.arrowos.net/'''+DEVICE+'''"
                    echo TG_TITLE "**New ['''+DEVICE+''']($TG_DOWN_URL) build [(`date +'%d-%m-%Y'`)](https://changelog.arrowos.net) is out! ('''+VERSION+''')**" >> '''+env.TG_VARS_FILE+'''
                fi

                # Generate OTA
                buildsha256=$(sha256sum $TO_UPLOAD | awk '{print $1}')
                buildsize=$(ls -l $TO_UPLOAD | awk '{print $5}')
                echo BUILD_ARTIFACT $BUILD_ARTIFACT >> '''+env.TG_VARS_FILE+'''
                echo BUILD_ARTIFACT_SHA256 $buildsha256 >> '''+env.TG_VARS_FILE+'''
                echo BUILD_ARTIFACT_SIZE $buildsize >> '''+env.TG_VARS_FILE+'''
            else
                echo "NOTHING TO UPLOAD! NO FILE FOUND!"
                echo "NOT PROCEEDING WITH GAPPS BUILD...!!"
                exit 1
            fi

            if [ $notify -eq 0 ]; then
                echo TG_NOTIFY yes >> '''+env.TG_VARS_FILE+'''
                echo TG_DOWN_URL $TG_DOWN_URL >> '''+env.TG_VARS_FILE+'''
            fi

        '''
}

public def genOTA() {
    is_tgnotify = getTgVars("TG_NOTIFY").toString().trim()
    build_artifact = getTgVars("BUILD_ARTIFACT").toString().trim()
    build_artifact_sha256 = getTgVars("BUILD_ARTIFACT_SHA256").toString().trim()
    build_artifact_size = getTgVars("BUILD_ARTIFACT_SIZE").toString().trim()

    def build_version = sh(returnStdout: true,
                        script: '''#!/bin/bash
                                    echo arrow-$(echo '''+env.TG_ARROW_VERSION+''' | cut -d 'v' -f 2-)
                                '''
                        )

    // GenOTA
    if(is_tgnotify == "yes" && env.bootimage == "no") {
        echo "-----------------------------------------------"
        echo "Generating ota json"
        echo "-----------------------------------------------"
        build job: 'genOTA', parameters: [
            string(name: 'IS_TEST', value: env.test_build),
            string(name: 'TG_BUILD_TYPE', value: env.TG_BUILD_TYPE),
            string(name: 'TG_BUILD_VERSION', value: build_version),
            string(name: 'TG_DEVICE', value: env.TG_DEVICE),
            string(name: 'TG_DEVICE_CHANGELOG', value: env.changelog),
            string(name: 'TG_DEVICE_MAINTAINER', value: env.TG_DEVICE_MAINTAINER),
            string(name: 'TG_DEVICE_MODEL', value: env.TG_DEVICE_MODEL),
            string(name: 'TG_DEVICE_OEM', value: env.TG_DEVICE_OEM),
            string(name: 'BUILD_ARTIFACT', value: build_artifact),
            string(name: 'BUILD_ARTIFACT_SHA256', value: build_artifact_sha256),
            string(name: 'BUILD_ARTIFACT_SIZE', value: build_artifact_size),
            string(name: 'TG_BUILD_ZIP_TYPE', value: env.TG_BUILD_ZIP_TYPE)
        ], propagate: false, wait: false
    }
}

public def buildNotify() {
    TG_TITLE = getTgVars("TG_TITLE").toString().trim()
    env.is_tgnotify = getTgVars("TG_NOTIFY").toString().trim()
    tg_down_url = getTgVars("TG_DOWN_URL").toString().trim()
    
    if(env.is_tgnotify == "yes" && env.bootimage != "yes") {
        echo "-----------------------------------------------"
        echo "Notifying on tg"
        echo "-----------------------------------------------"
        build job: 'tg_notify', parameters: [
            string(name: 'is_test', value: env.test_build),
            string(name: 'TG_TITLE', value: TG_TITLE)
        ], propagate: false, wait: false
    } else if(env.is_tgnotify == "yes" && env.bootimage == "yes") {
        echo "-----------------------------------------------"
        echo "Notifying on tg"
        echo "-----------------------------------------------"
        build job: 'tg_notify', parameters: [
            string(name: 'is_test', value: env.test_build),
            string(name: 'TG_TITLE', value: TG_TITLE + " (bootimage)")
        ], propagate: false, wait: false
    }

    sendSlackNotify("*BUILD:*", null, "${tg_down_url}")
}

// Set build description as executed at end
slackThreadResp.addReaction("thumbsup")
currentBuild.description = "Executed @ ${ASSIGNED_NODE}"
currentBuild.result = "SUCCESS"
