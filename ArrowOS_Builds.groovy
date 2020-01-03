import java.io.*;
import java.sql.*; 
import groovy.sql.Sql

def MAIN_DISK = "/source"
def SOURCE_DIR = MAIN_DISK + "/arrow"
def CCACHE_DIR = MAIN_DISK + "/.ccache"
def STALE_PATHS_FILE = MAIN_DISK + "/temp_paths.txt"

currentBuild.displayName = "${DEVICE}"
currentBuild.description = "Waiting for Executor @ ${ASSIGNED_NODE}"

if(!ASSIGNED_NODE.isEmpty()) {
    node(ASSIGNED_NODE) {
        currentBuild.description = "Executing @ ${ASSIGNED_NODE}"

        stage('Exporting vars') {
            sh  '''#!/bin/bash

                    # Linux exports
                    export PATH=~/bin:$PATH
                    export USE_CCACHE=1
                    export CCACHE_COMPRESS=1
                    export CCACHE_DIR='''+CCACHE_DIR+'''
                    export KBUILD_BUILD_USER=release
                    export KBUILD_BUILD_HOST=ArrowOS
                    export LOCALVERSION=-Arrow

                    # Rom exports
                    export SELINUX_IGNORE_NEVERALLOWS=true
                    export ALLOW_MISSING_DEPENDENCIES=true
                    export ARROW_OFFICIAL=true

                    cd '''+SOURCE_DIR+'''
                '''
        }

        stage('Fetching configs from DB') {
            echo "Establishing connection to configs DB...!"
            device_config_fetch(DEVICE)
        }

        stage("Hard reset") {
            try {
                sh 'cd '+SOURCE_DIR+''
                def stale_file = new File(STALE_PATHS_FILE)
                if(stale_file.exists()) {
                    BufferedReader br = new BufferedReader(new FileReader(stale_file))

                    String stale_content;
                    while((stale_content = br.readLine()) != null) {
                        echo "---------------------------------"
                        echo "Deleting stale repos from previous build"
		                echo "---------------------------------"

                        def dir = new File(stale_content)
                        if(dir.exists()) {
                            if(dir.deleteDir()) {
                                echo "Deleted path ${stale_content}"
                            }
                        }
                    }
                } else {
                    echo "---------------------------------"
	                echo "No temp paths file found!"
	                echo "---------------------------------"
                }

                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                echo " "
                echo "Performing hard reset and clean!"
                echo " "
                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                echo " "
                repoStatus = sh(returnStatus: true,
                                script: 'repo forall -c "git clean -fdx && git reset --hard " -j4 > /dev/null'
                                )

                if(repoStatus == 0)
                    echo "Hard rest and clean done!"
                else
                    echo "Hard reset and clean failed!"

            } catch (e) {
                throw(e)
            }
        }

        stage('Repo sync') {
            sh '''#!/bin/bash
                cd '''+SOURCE_DIR+'''
                rm -rf '''+SOURCE_DIR+'''/.repo/local_manifests
                repo init -u https://github.com/ArrowOS/android_manifest.git -b arrow-10.0 --depth=1 > /dev/null
                repo sync --force-sync --no-tags --no-clone-bundle -c -j4
                '''
        }
    }
}

/* -----------------------------------------------------
// Database connection stage
-------------------------------------------------------*/
environment {
    // device config holders
    def lunch_override_name = null
    def repo_paths = null
    def repo_clones = null
    def repo_clones_paths = null
    def repopick_topics = null
    def repopick_changes = null

    // device data (switch holders)
    def lunch_override_state = null
    def force_clean = null
    def test_build = null
    def is_official = null
    def buildtype = null
    def bootimage = null
    def changelog = null
    def xda_link = null
    def global_override = null
    def default_buildtype_state = null
}

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
                            global_override = configs.global_override
                            if(global_override == "yes") {
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                                echo "Fetching global overrides!"
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
                                echo "Global overrides set to ${global_override}"
                                echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

                                env.force_clean = configs.force_clean
                                env.test_build = configs.test_build
                                env.is_official = configs.is_official
                                env.bootimage = configs.bootimage
                                env.buildtype = configs.buildtype
                                env.default_buildtype_state = configs.default_buildtype_state

                                if(env.default_buildtype_state == "yes") {
                                    dbcon.eachRow("SELECT * FROM "+DEVICE+"") {
                                        env.buildtype = configs.default_buildtype
                                    }
                                }
                                return
                            }
                        }

                        env.repo_paths = isDevOvr ? configs.ovr_repo_paths : configs.repo_paths
                        env.repo_clones = isDevOvr ? configs.ovr_repo_clones : configs.repo_clones
                        env.repo_clones_paths = isDevOvr ? configs.ovr_repo_clones_paths : configs.repo_clones_paths
                        env.repopick_topics = isDevOvr ? configs.ovr_repopick_topics : configs.repopick_topics
                        env.repopick_changes = isDevOvr ? configs.ovr_repopick_changes : configs.repopick_changes
                        env.force_clean = isDevOvr ? configs.ovr_force_clean : configs.force_clean
                        env.test_build = isDevOvr ? configs.ovr_test_build : configs.test_build
                        env.is_official = isDevOvr ? configs.ovr_is_official : configs.is_official
                        env.buildtype = isDevOvr ? configs.ovr_buildtype : configs.buildtype
                        env.bootimage = isDevOvr ? configs.ovr_bootimage : configs.bootimage
                        env.changelog = isDevOvr ? configs.ovr_changelog : configs.changelog

                        if(whichDevice != "common_config") {
                            env.lunch_override_name = configs.lunch_override_name
                            env.lunch_override_state = configs.lunch_override_state
                            env.xda_link = isDevOvr ? configs.ovr_xda_link : configs.xda_link
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

public def device_config_fetch(def DEVICE) {
    try {
        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"
        echo "Fetching configuration for ${DEVICE}"
        echo "-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-*-"

        setConfigsData(true, DEVICE, false)
        if(env.lunch_override_name != null && !env.lunch_override_name.isEmpty() && env.lunch_override_state != "no") {
            echo "Lunch override set to ${env.lunch_override_name}"
            boolean lunch_override = true
            //set global overrides
            setConfigsData(false, "common_config", true)
            return
        }

        //set normal device
        setConfigsData(false, DEVICE, false)
        //set global overrides
        setConfigsData(false, "common_config", true)
    } catch(e) {
        throw(e)
    }
}