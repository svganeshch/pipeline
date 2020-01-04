import java.io.*;
import java.sql.*; 
import groovy.sql.Sql

def jsonParse(def json) { new groovy.json.JsonSlurperClassic().parseText(json) }

public Boolean checkTGplugin() {
    def plugins = jenkins.model.Jenkins.instance.getPluginManager().getPlugins()
    plugins.each { plugin ->
        if(plugin.getShortName() == "telegram-notifications") {
            return true
        } else {
            return false
        }
    }
}

currentBuild.displayName = "${DEVICE}"
currentBuild.description = "Waiting for Executor @ ${ASSIGNED_NODE}"

environment {
    def MAIN_DISK
    def SOURCE_DIR
    def CCACHE_DIR
    def STALE_PATHS_FILE

    // device config holders
    def lunch_override_name
    def repo_paths
    def repo_clones
    def repo_clones_paths
    def repopick_topics
    def repopick_changes

    // device data (switch holders)
    def lunch_override_state
    def force_clean
    def test_build
    def is_official
    def buildtype
    def bootimage
    def changelog
    def xda_link
    def global_override
    def default_buildtype_state

    // check holder
    def lunch_override
}

if(!ASSIGNED_NODE.isEmpty()) {
    node(ASSIGNED_NODE) {
        currentBuild.description = "Executing @ ${ASSIGNED_NODE}"

        if(checkTGplugin()) {
            telegramSend("[Build has started for ${DEVICE}](${BUILD_URL})\nExecuting @ ${ASSIGNED_NODE}")
        }

        env.MAIN_DISK = "/source".toString().trim()
        env.SOURCE_DIR = env.MAIN_DISK + "/arrow".toString().trim()
        env.CCACHE_DIR = env.MAIN_DISK + "/.ccache".toString().trim()
        env.STALE_PATHS_FILE = env.MAIN_DISK + "/stale_paths.txt".toString().trim()

        stage('Exporting vars') {
            sh  '''#!/bin/bash +x

                    # Linux exports
                    export PATH=~/bin:$PATH
                    export USE_CCACHE=1
                    export CCACHE_COMPRESS=1
                    export CCACHE_DIR='''+env.CCACHE_DIR+'''
                    export KBUILD_BUILD_USER=release
                    export KBUILD_BUILD_HOST=ArrowOS
                    export LOCALVERSION=-Arrow

                    # Rom exports
                    export SELINUX_IGNORE_NEVERALLOWS=true
                    export ALLOW_MISSING_DEPENDENCIES=true
                    export ARROW_OFFICIAL=true

                    cd '''+env.SOURCE_DIR+'''
                '''
        }

        stage('Fetching configs from DB') {
            echo "Establishing connection to configs DB...!"
            fetchConfigs(DEVICE)
        }

        stage("Hard reset") {
                sh '''#!/bin/bash
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
                        cd '''+env.SOURCE_DIR+'''
                        rm -rf '''+env.SOURCE_DIR+'''/.repo/local_manifests
                        repo init -u https://github.com/ArrowOS/android_manifest.git -b arrow-10.0 --depth=1 > /dev/null
                        repo sync --force-sync --no-tags --no-clone-bundle -c -j4
                        if [ $? -ne 0 ]; then
                            exit 1
                        fi
                    '''
        }

        stage('Clean plate') {
            sh  '''#!/bin/bash
                    cd '''+env.SOURCE_DIR+'''
                    source build/envsetup.sh

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
                    mka installclean
                '''
        }

        stage('Device lunch') {
            deviceLunch()
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
            setConfigsData(false, "common_config", false)
            delConfigRepos()
            cloneConfigRepos()
            repopickTopics()
            repopickChanges()
        }
    }
}

/* 
-----------------------------------------------------
    Database connection stage   
-------------------------------------------------------
*/
@NonCPS
public def setConfigsData(Boolean isDevOvr, String whichDevice, Boolean isGlobalOvr) {
    try {
        def dbcon = Sql.newInstance('jdbc:mysql://localhost:3306/configs',
            'root', 'abansode', 'com.mysql.jdbc.Driver')

        def md = dbcon.connection.metaData
        def deviceTable = md.getTables(null, null, whichDevice, null)

        try {
            while(deviceTable.next()) {
                if(deviceTable.getString('TABLE_NAME') == whichDevice) {
                    dbcon.eachRow("select * from "+whichDevice+"") {
                        configs->
                        if(whichDevice == "common_config" && isGlobalOvr) {
                            global_override = configs.global_override.toString().trim()
                            if(global_override == "yes") {
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                                echo "Fetching global overrides!"
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                                echo "Global overrides set to ${global_override}"
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                                env.force_clean = configs.force_clean.toString().trim()
                                env.test_build = configs.test_build.toString().trim()
                                env.is_official = configs.is_official.toString().trim()
                                env.bootimage = configs.bootimage.toString().trim()
                                env.buildtype = configs.buildtype.toString().trim()
                                env.default_buildtype_state = configs.default_buildtype_state.toString().trim()

                                if(env.default_buildtype_state == "yes") {
                                    dbcon.eachRow("SELECT * FROM "+DEVICE+"") {
                                        env.buildtype = configs.default_buildtype.toString().trim()
                                    }
                                }
                            }
                            return
                        }

                        env.repo_paths = isDevOvr ? configs.ovr_repo_paths.toString().trim() : configs.repo_paths.toString().trim()
                        env.repo_clones = isDevOvr ? configs.ovr_repo_clones.toString().trim() : configs.repo_clones.toString().trim()
                        env.repo_clones_paths = isDevOvr ? configs.ovr_repo_clones_paths.toString().trim() : configs.repo_clones_paths.toString().trim()
                        env.repopick_topics = isDevOvr ? configs.ovr_repopick_topics.toString().trim() : configs.repopick_topics.toString().trim()
                        env.repopick_changes = isDevOvr ? configs.ovr_repopick_changes.toString().trim() : configs.repopick_changes.toString().trim()
                        env.force_clean = isDevOvr ? configs.ovr_force_clean.toString().trim() : configs.force_clean.toString().trim()
                        env.test_build = isDevOvr ? configs.ovr_test_build.toString().trim() : configs.test_build.toString().trim()
                        env.is_official = isDevOvr ? configs.ovr_is_official : configs.is_official.toString().trim()
                        env.buildtype = isDevOvr ? configs.ovr_buildtype.toString().trim() : configs.buildtype.toString().trim()
                        env.bootimage = isDevOvr ? configs.ovr_bootimage.toString().trim() : configs.bootimage
                        env.changelog = isDevOvr ? configs.ovr_changelog.toString().trim() : configs.changelog.toString().trim()

                        if(whichDevice != "common_config") {
                            env.lunch_override_name = configs.lunch_override_name.toString().trim()
                            env.lunch_override_state = configs.lunch_override_state.toString().trim()
                            env.xda_link = isDevOvr ? configs.ovr_xda_link.toString().trim() : configs.xda_link.toString().trim()
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

        setConfigsData(true, DEVICE, false)
        if(env.lunch_override_name != null && !env.lunch_override_name.isEmpty() && env.lunch_override_state != "no") {
            echo "Lunch override set to ${env.lunch_override_name}"
            env.lunch_override = 0
            //set global overrides
            setConfigsData(false, "common_config", true)
            return
        }

        //set normal device
        env.lunch_override = 1
        setConfigsData(false, DEVICE, false)
        //set global overrides
        setConfigsData(false, "common_config", true)
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
            source build/envsetup.sh

            if [ '''+env.is_official+''' = "no" ]; then
                unset ARROW_OFFICIAL
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

            if [ '''+env.lunch_override.toInteger()+''' -eq 0 ]; then

                if [ ! -z '''+env.buildtype+''' ]; then
                    lunch arrow_'''+env.lunch_override_name+'''-'''+env.buildtype+'''
                    lunch_ovr_ok=$?
                else 
                    echo "No buildtype specified!"
                    exit 0
                fi

                if [ $lunch_ovr_ok -eq 0 ]; then
                    echo "lunch override successfull!"
                else
                    echo "Failed to override lunch"
                    echo "Terminating build!"
                    exit 0
                fi
            fi

        '''
}

/*
-----------------------------------------------
    Delete config repos stage
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

public def repopickTopics() {
    def pickTopics = jsonParse(env.repopick_topics)

    pickTopics.repopick_topics.each { changeTopic ->
        sh  '''#!/bin/bash

                cd '''+env.SOURCE_DIR+'''
                source build/envsetup.sh

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

public def repopickChanges() {
    def pickChanges = jsonParse(env.repopick_changes)

    pickChanges.repopick_changes.each { changeNum ->
        sh  '''#!/bin/bash

                cd '''+env.SOURCE_DIR+'''
                source build/envsetup.sh

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

// Set build description as executed at end
currentBuild.description = "Executed @ ${ASSIGNED_NODE}"

if(checkTGplugin()) {
    telegramSend("[Build finished for ${DEVICE}](${BUILD_URL})")
}