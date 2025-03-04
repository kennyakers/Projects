import hudson.model.*
import jenkins.model.*
import groovy.json.JsonSlurper

class PipelineUtilities extends AbstractPipeline {
    def scriptsPathRelative
    PipelineUtilities(script) {
        super(script)
        scriptsPathRelative = "src\\agilent\\sid\\cicd\\scripts\\ps"
    }
    def runPowerShell(psCmd, args, debug=true)
    {
        def output
        if (debug) script.println "[DEBUG] Powershell script: \n $psCmd"
        if (debug) script.println "[DEBUG] Arguments to powershell script: \n $args"

        try
        {
            def shellCommand = "${psCmd} ${args}"
            shellCommand = ResolveEnvVars(shellCommand)
            if(debug) script.println "[DEBUG] The following command will be executed: \n ${shellCommand}"
            output = script.powershell """${shellCommand}"""
        } catch (Exception e) {
            script.println "PipelineUtilities.runPowerShell() - caught an exception"
            script.error e.toString()
        }
        return output
    }

    //Function to resolve any environment variables in the string
    def ResolveEnvVars(String text)
    {
        script.println "Text to be resolved: " + text
        //Script that expands any existing environment variables with actual values.
        def resolvedText = script.powershell(returnStdout:true, script: """${script.env.WORKSPACE}\\${scriptsPathRelative}\\ResolveEnvVars.ps1 \"${text}\"""")
        script.println "Resolved Text: " + resolvedText
        return resolvedText
    }

    //Get current branch name
    def getBranchName() {
        def branchName = script.scm.branches[0].name.split('/').last() //Extract only branch name excluding 'origin'
        
        script.println "Branch Name: $branchName"
        return branchName        
    }

    // Checkout specified git branch from specified git repository 
    void checkoutGitBranch(String branch, String repo='ssh://git@sparksource.collaboration.agilent.com:7999/siddev/kenakers-repo.git')
    {
        script.println "Checking out source from Repository: $repo, Branch: $branch"
        script.git credentialsId: '022dccce-8996-43d7-a5c3-67473b642ccf', branch: "$branch", url: "$repo"
    }

    //Stop antivirus to speed up copy.
    void stopSymantecEP()
    {
        script.println "Stopping Symatec Endpoint Protection to speed up the file copy/download"
        runPowerShell("Start-Process", "'%ProgramFiles(x86)%\\Symantec\\Symantec Endpoint Protection\\smc' -ArgumentList -stop -Verb runAs")
    }

    //Download specified artifact from Nexus.
    void downloadCdsPkg(version, destDir, expand=false)
    {
        script.println "Downloading CDS Installer version: ${version} from Nexus"
        // Default values for 2.5 release
        def archiveName = "CDS-${version}-Release.zip"
        def nexusUrl = "http://scsgitsrv01.scs.agilent.com:8081/nexus/content/repositories/sprintreleases/Agilent/OpenLab/Installer/Packaging/CDS/${version}/${archiveName}"
        if( version.startsWith('2.4') )
        {
            //Reset values for 2.4
            archiveName = "OpenLab-${version}-CDS.zip"
            nexusUrl = "http://scsgitsrv01.scs.agilent.com:8081/nexus/service/local/repositories/releases/content/Agilent/OpenLab/Installer/OpenLab/${version}/${archiveName}"
        }
        
        if( version.startsWith('2.3'))
        {            
            //Reset values for 2.3
            archiveName = "OpenLabCDS-${version}.zip"
            nexusUrl = "http://scsgitsrv01.scs.agilent.com:8081/nexus/service/local/repositories/releases/content/Agilent/OpenLab/Installation/CDSInstaller/OpenLabCDS/${version}/${archiveName}"
        }
        
        if( version.startsWith('2.2') )
        {
            //Reset values for 2.2
            archiveName = "OpenLABCDS-${version}.zip"
            nexusUrl = "http://scsgitsrv01.scs.agilent.com:8081/nexus/service/local/repositories/releases/content/Agilent/OpenLAB/Installation/CDSInstaller/OpenLABCDS/${version}/${archiveName}"
        }
        
        //make sure deploy path is created before downloading build
        script.dir(destDir)
        {
            runPowerShell ("${script.env.WORKSPACE}\\${scriptsPathRelative}\\DownloadFile.ps1", "-URL ${nexusUrl} -FullPath ${destDir}\\${archiveName}")
        }
        //extract to same folder if caller want it
        if (expand) expandArchive("${destDir}\\${archiveName}", destDir)
    }

    //Extract contents of an archive. Extract to same directory as archive if destination is not specified
    void expandArchive(archive, destDir)
    {
        script.println "Expanding archive: ${archive} to: ${destDir}"
        //Extract from within the directory
        script.dir(destDir)
        {
            runPowerShell ("${script.env.WORKSPACE}\\${scriptsPathRelative}\\ExpandArchive.ps1", "-ArchivePath ${archive} -ExtractTo ${destDir}")
        }
    }

    //Install CDS Workstaion - File System.
    void installCdsWs(pkgLocation, filesystem=false, secure=false, configFilePath="")
    {
        script.println "Installing CDS from: ${pkgLocation}, Configuration file location: ${configFilePath}"
        script.println "File System Installation: " + filesystem
        script.println "Secured: " + secure
        script.println "Content Management Installation: " + !filesystem

        def fileConfig = new File(configFilePath)
        if( fileConfig.exists() ) runPowerShell ("${script.env.WORKSPACE}\\${scriptsPathRelative}\\InstallCds.ps1", "-Installer '${pkgLocation}\\Setup\\CDSInstaller.exe' -ConfigFile '${configFilePath}' -InstallProperties 'SecureFS=${secure}'")
        else { 
            if(filesystem) 
                runPowerShell ("${script.env.WORKSPACE}\\${scriptsPathRelative}\\InstallCds.ps1", "-Installer '${pkgLocation}\\Setup\\CDSInstaller.exe' -InstallProperties 'LicenseAccepted=True SecureWorkstation=True StorageFileSystem=True SecureFS=${secure}'")
            else
                runPowerShell ("${script.env.WORKSPACE}\\${scriptsPathRelative}\\InstallCds.ps1", "-Installer '${pkgLocation}\\Setup\\CDSInstaller.exe' -InstallProperties 'LicenseAccepted=True SecureWorkstation=True StorageDataStore=True OlssAdminPassword=plain-password:admin DbAdminPassword=plain-password:postgres'")
        }
    }

    //Kill all running/blocked/Pending builds for current JOB.
    void killAllBuilds()
    {
        while(script.currentBuild.rawBuild.getPreviousBuildInProgress() != null)
        {
            script.println "Killing Build: " + script.currentBuild.rawBuild.getPreviousBuildInProgress()
            script.currentBuild.rawBuild.getPreviousBuildInProgress().doKill()
        }
    }

    //Get Jankins master base URL from build URL.
    def getJenkinsMasterBaseURL(buildURL)
    {
        URI uri = new URI(buildURL)
        def port = uri.getPort()
        def baseUrl = uri.getScheme() + "://" + uri.getHost()

        //Returns http(s)://[host OR IP]:[port]
        if(port != -1) //If port is part of URL
            baseUrl = baseUrl + ":" + port

        script.println "Jenkins Master Base URL: " + baseUrl
        return baseUrl
    }

    //Get Jankins slave node's secret.
    def getJenkinsSlaveSecret(slaveNode)
    {
        //Returns specified slave node's secret
        def secret = Jenkins.getInstance().getComputer(slaveNode).getJnlpMac()
        script.println "Jenkins Slave Node secret: " + secret
        return secret
    }

    //Run Jankins slave on node.
    void startJenkinsSlave(masterURL, slaveNode)
    {
        //Get slave node's secret
        def secret = getJenkinsSlaveSecret(slaveNode)
        //run slave on remote node
        runPowerShell("${script.env.WORKSPACE}\\${scriptsPathRelative}\\StartJenkinsSlave.ps1", "-jenkinsMasterURL ${masterURL} -nodeName ${slaveNode} -slaveSecret ${secret}")
    }
}

// Abstract Class that implements Serializable and consumes script object to access pipeline steps and properties
abstract class AbstractPipeline implements Serializable {

  def script

  AbstractPipeline ( script ) {
    this.script = script
  }
}
//----------------------------------
node {
    def utils = new PipelineUtilities(this)
    def publicDNS = ""
    def instanceID = ""
    def userAdmin = '\\Administrator'
    def adminPwd = 'GAjIzjTNcbpi&n!*c%6$UJv8yVS;Z8iZ' // Will need to get programmatically
    
    parameters {
        string(name: 'KEY_NAME', defaultValue: "", description: 'The name of the key pair created in AWS.')
        string(name: 'INSTANCE_NAME', defaultValue: "", description: 'The name of the instance that will be created. This will be the host name of the instance, so no spaces...')
        string(name: 'AMI', defaultValue: "", description: 'The Amazon Machine Image (AMI) ID used to specify the OS to run on the instance.')
        string(name: 'JAVA_VERSION', defaultValue: "1.8.0_212", description: 'The version of the Java 8 SDK to install on the instance - needed to connect back to Jenkins.')
        booleanParam(name: 'DEBUG', defaultValue: true, description: 'Click to enable debugging in the console output.')
    }
    try {
        echo """
        Key pair name is: ...................... ${params.KEY_NAME}
        Instance name is: ...................... ${params.INSTANCE_NAME}
        AMI is: ................................ ${params.AMI}
        Java version is: ....................... ${params.JAVA_VERSION}
        Debug is: .............................. ${params.DEBUG}
        """

        // stage('Checkout Source...') {
        //     echo "Branch name: ${utils.getBranchName()}"
        //     utils.checkoutGitBranch(utils.getBranchName())
        // }
        
        stage('Launching instance') {
            def tags = "ResourceType=instance,Tags=[{Key=Name,Value=${params.INSTANCE_NAME}}]" // To name the instance on launch
            def proc = "aws ec2 run-instances --image-id ${params.AMI} --count 1 --instance-type t2.micro --key-name ${params.KEY_NAME} --tag-specifications ${tags}".execute()
            proc.waitFor()

            def result = proc.text
            def jsonParser = new JsonSlurper()
            instanceID = jsonParser.parseText(result).Instances.InstanceId.get(0)

            if (params.DEBUG.toBoolean()) {
                println result
                println "instanceID ${instanceID}"
            }

        }

        stage('Waiting for instance to reach running state') {
            def proc = "aws ec2 wait instance-running --instance-id ${instanceID}".execute()
            def sout = new StringBuilder(), serr = new StringBuilder()
            proc.consumeProcessOutput(sout, serr)
            proc.waitFor()
            
            if (params.DEBUG.toBoolean()) {
                 println "out> $sout err> $serr"
            }        
        }

        stage('Attaching SSM IAM role') {
            def proc = "aws ec2 associate-iam-instance-profile --instance-id ${instanceID} --iam-instance-profile Name=EnablesEC2ToAccessSystemsManagerRole".execute()
            def sout = new StringBuilder(), serr = new StringBuilder()
            proc.consumeProcessOutput(sout, serr)
            proc.waitFor()
            
            if (params.DEBUG.toBoolean()) {
                 println "out> $sout err> $serr"
            }            
        }

        stage('Waiting 5 mins for IAM role assignment to take effect') {
            sleep(time:5, unit:"MINUTES")
        }

        stage('Retrieving public DNS') {
            def jsonParser = new JsonSlurper()
            def query = "Reservations[].Instances[].PublicDnsName"
            def proc = "aws ec2 describe-instances --instance-id ${instanceID} --query ${query}".execute()
            proc.waitFor()
            
            publicDNS = jsonParser.parseText(proc.text).get(0)
            
            if (params.DEBUG.toBoolean()) {
                println "publicDNS ${publicDNS}"
            }
        }

        stage ('Installing Java') {
            def result = utils.runPowerShell("${env.WORKSPACE}\\src\\agilent\\sid\\cicd\\scripts\\ps\\Install_Java.ps1", "-Java_Version ${params.JAVA_VERSION}")
        }

        stage('Attaching instance to Jenkins') { // In Progress
            utils.runPowerShell("${env.WORKSPACE}\\src\\agilent\\sid\\cicd\\scripts\\ps\\ConnectNewVirtualMachineToJenkins.ps1", "-VmName ${publicDNS} -userAdmin ${userAdmin} -adminPwd ${adminPwd}")
        }

    } catch (ex) {
        echo 'Creating instance failed'
        currentBuild.result = 'FAILURE'
        throw ex
    } finally {
        echo 'Status Of VM Creation...'
        def currentResult = currentBuild.result ?: 'SUCCESS'
        def previousResult = currentBuild.previousBuild?.result
        def title = "${currentResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
        def message
        // Override default values based on build status
        // Success or failure, always send notifications
        if (currentResult == 'FAILURE') {
            message = """<p>${currentResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'</p>
            <p>Failed to create VM: ${params.INSTANCE_NAME}, check console output at "<a href="${env.BUILD_URL}">${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>"</p>"""
        } 
        if (currentResult == 'UNSTABLE') {
            echo 'Build is Unstable'
        }
        if (previousResult != null && previousResult != currentResult) {
            echo 'Build status changed from last build'
        }
        if (currentResult == 'SUCCESS') {
            echo 'Successfully created instance'
            message = """<p>***** VM created successfully *****</p>
            <p>VM Name: ${params.INSTANCE_NAME}</p><p>Public DNS Address: ${publicDNS}</p>
            <p>Remote Desktop Connection: <a href="mstsc.exe /v:${publicDNS}">Connect to ${params.INSTANCE_NAME}</a></p>
            <p>NOTE: Outlook does not let any program run through links. So to launch remote desktop, right click on the link, copy and paste it on command prompt or windows run [Windows button + r] command, then hit 'Enter'</p>"""
        }
        // Send email notifications to the users who started the build.
        //emailext (subject: title, body: message, recipientProviders: [[$class: 'RequesterRecipientProvider']] )
    }
}
return this
