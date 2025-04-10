
slacklib = load("pipeline-scripts/slacklib.groovy")

ocp3Versions = [
    "3.11",
]

// All buildable versions of ocp4
ocp4Versions = [
    "4.20",
    "4.19",
    "4.18",
    "4.17",
    "4.16",
    "4.15",
    "4.14",
    "4.13",
    "4.12",
]

ocpVersions = ocp4Versions + ocp3Versions

// some of our systems refer to golang's chosen architecture nomenclature;
// most use brew's nomenclature or similar. translate.
brewArches = ["x86_64", "s390x", "ppc64le", "aarch64", "multi"]
brewArchSuffixes = ["", "-s390x", "-ppc64le", "-aarch64", "-multi"]
goArches = ["amd64", "s390x", "ppc64le", "arm64", "multi"]
goArchSuffixes = ["", "-s390x", "-ppc64le", "-arm64", "-multi"]
def goArchForBrewArch(String brewArch) {
    if (brewArch in goArches) return brewArch  // allow to already be a go arch, just keep same
    if (brewArch in brewArches)
        return goArches[brewArches.findIndexOf {it == brewArch}]
    error("no such brew arch '${brewArch}' - cannot translate to golang arch")
}
def brewArchForGoArch(String goArch) {
    // some of our systems refer to golang's chosen nomenclature; translate to what we use in brew
    if (goArch in brewArches) return goArch  // allow to already be a brew arch, just keep same
    if (goArch in goArches)
        return brewArches[goArches.findIndexOf {it == goArch}]
    error("no such golang arch '${goArch}' - cannot translate to brew arch")
}
// imagestreams and file names often began without consideration for multi-arch and then
// added a suffix everywhere to accommodate arches (but kept the legacy location for x86).
def brewSuffixForArch(String arch) {
    arch = brewArchForGoArch(arch)  // translate either incoming arch style
    return brewArchSuffixes[brewArches.findIndexOf {it == arch}]
}
def goSuffixForArch(String arch) {
    arch = goArchForBrewArch(arch)  // translate either incoming arch style
    return goArchSuffixes[goArches.findIndexOf {it == arch}]
}

ocpMajorVersions = [
    "4": ocp4Versions,
    "3": ocp3Versions,
    "all": ocpVersions,
]

/**
 * Handles any common setup required by the library
 */
def initialize() {
    // https://issues.jenkins-ci.org/browse/JENKINS-33511 no longer appears relevant
}

def describeJob(name, description) {
    job = Jenkins.getInstance().getItemByFullName(env.JOB_NAME)
    if (name) {
        job.setDisplayName(name)
    }
    if (description) {
        job.setDescription(description.readLines().join('<br>\n'))
    }
}

def checkMock() {
    // User can have the job end if this is just a run to pick up parameter changes
    // (which Jenkins discovers by running the job).
    if (env.MOCK == null || MOCK.toBoolean()) {
        currentBuild.displayName = "#${currentBuild.number} - update parameters"
        currentBuild.description = "Ran in mock mode"
        error( "Ran in mock mode to pick up any new parameters" )
    }
}

def mockParam() {
    return [
        name: 'MOCK',
        description: 'Pick up changed job parameters and then exit',
        $class: 'BooleanParameterDefinition',
        defaultValue: false
    ]
}

def artToolsParam() {
    return [
        name: 'ART_TOOLS_COMMIT',
        description: 'Override the art-tools submodule; Format is ghuser@commitish e.g. jupierce@covscan-to-podman-2',
        $class: 'hudson.model.StringParameterDefinition',
        defaultValue: ''
    ]
}

def dryrunParam(description = 'Run job without side effects') {
    return [
        name: 'DRY_RUN',
        description: description,
        $class: 'BooleanParameterDefinition',
        defaultValue: false,
    ]
}

def ocpVersionParam(name='MINOR_VERSION', majorVersion='all', extraOpts=[]) {
    return [
        name: name,
        description: 'OSE Version',
        $class: 'hudson.model.ChoiceParameterDefinition',
        choices: (extraOpts + ocpMajorVersions[majorVersion]).join('\n'),
    ]
}

def enableTelemetryParam() {
    return [
        name: 'TELEMETRY_ENABLED',
        description: 'Enable or disable sending traces to otel',
        $class: 'BooleanParameterDefinition',
        defaultValue: false
    ]
}

def telemetryEndpointParam() {
    return [
        name: 'OTEL_EXPORTER_OTLP_ENDPOINT',
        description: 'A base endpoint URL for any signal type, with an optionally-specified port number. Helpful for when you’re sending more than one signal to the same endpoint and want one environment variable to control the endpoint',
        $class: 'hudson.model.StringParameterDefinition',
        defaultValue: ''
    ]
}

def suppressEmailParam() {
    return [
        name: 'SUPPRESS_EMAIL',
        description: 'Do not actually send email, just archive it',
        $class: 'BooleanParameterDefinition',
        defaultValue: !env.JOB_NAME.startsWith("aos-cd-builds/"),
    ]
}

def sanitizeInvisible(str) {
    // Jenkins has a tendency to put invisible breaks that people copy and paste into job parameters.
    // This causes very confusing failures.
    // First turn newlines into space to preserve separation in lists; then remove other non-printables
    return str.replaceAll(/\s/, " ").replaceAll(/[^\p{Print}]/, '')
}

def parseList(str) {
    // turn the string of a list separated by commas or spaces into a list
    str = (str == null) ? "" : str
    return sanitizeInvisible(str).replaceAll(',', ' ').tokenize()
}

def cleanCommaList(str) {
    // turn the string list separated by commas or spaces into a comma-separated string
    return parseList(str).join(',')
}

def cleanSpaceList(str) {
    // turn the string list separated by commas or spaces into a space-separated string
    return parseList(str).join(' ')
}

// A reusable way to generate a working build URL. Translates it into
// localhost for us. Then later when we get rid of that localhost
// thing we'll be able to undo this all at once. This can be directly
// used in strings. See EXAMPLES below.
//
// @param append: OPTIONAL: Location to append to the end. No '/'s
// required. For reference, here are some common ones you might use:
//
// * console - Go to the console log
// * input - Go to any pending input prompts
//
// EXAMPLES:
//
// Assign to a variable the build url or the user input page:
//     def buildURL = commonlib.buildURL()
//     def buildURL = commonlib.buildURL('input')
//
// Simply print it out:
//     echo("Jenkins job: ${commonlib.buildURL()}")
//
// Use it in an email:
//   commonlib.email(
//       to: params.MAIL_LIST_INPUT,
//       from: "aos-art-automation+input-required@redhat.com",
//       replyTo: "aos-team-art@redhat.com",
//       subject: "Input required for job ${currentBuild.number}",
//       body: """
//         Job requires input to continue:
//           Jenkins Input: ${commonlib.buildURL('input')}
//   """)
def buildURL(String append='') {
    env.BUILD_URL.replace('https://buildvm.openshift.eng.bos.redhat.com:8443', 'https://localhost:8888') + append
}

emailIndex = 0
/**
 * Wrapper to persist email as an artifact and enable suppressing actual email
 */
def email(args) {
    args.body += """
------------------------------------------------------

NOTE: These job links are only available to ART. Please contact us if
you need to see something specific from the logs.

Jenkins Job: ${buildURL()}
Console Log: ${buildURL('console')}
"""
    if (params.SUPPRESS_EMAIL) {
        def title = currentBuild.displayName ?: ""
        if (!title.contains("no email")) {
            currentBuild.displayName = "${title} [no email]"
        }
    } else {
        try {
            mail(args)
        } catch (err) { // don't usually want to fail the job for this
            echo "Failure sending email: ${err}"
        }
    }

    // now write this out and archive it
    try {
        // make a clean space for files
        if (emailIndex == 0) {
            sh("rm -rf email")
            sh("mkdir -p email")
        }

        // create a sanitized but recognizable name
        to = args.to ?: args.cc ?: args.bcc ?: "NOBODY"
        to = to.replaceAll(/[^@.\w]+/, "_")
        subject = args.get("subject", "NO SUBJECT").replaceAll(/\W+/, "_")

        def filename_ext = ".txt"
        def max_filename_len = 251 - filename_ext.size()
        filename = "email/" +
                   String.format("email%03d-%s-%s", ++emailIndex, to, subject).take(max_filename_len) +
                   filename_ext

        // this is a bit silly but writeYaml and writeFile lack finesse
        body = args.remove("body")  // unreadable when written as yaml
        writeYaml file: filename, data: args
        yaml = readFile filename
        writeFile file: filename, text: yaml + "\n\n" + body
        this.safeArchiveArtifacts([filename])
    } catch (err) {
        echo "Failure writing and archiving email file:\n${err}"
    }
}

def safeArchiveArtifacts(List patterns) {
    for (pattern in patterns) {
        try {
            archiveArtifacts allowEmptyArchive: true, artifacts: pattern
        } catch (err) {
            echo "Failed to archive artifacts like ${pattern}: ${err}"
        }
    }
}

import java.util.concurrent.atomic.AtomicInteger
shellCounter = new AtomicInteger()

@NonCPS
def getNextShellCounter() {
    return shellCounter.incrementAndGet()
}

/**
 * Wraps the Jenkins Pipeline sh step in order to get actually useful exceptions.
 * Because https://issues.jenkins-ci.org/browse/JENKINS-44930 is ridiculous.
 *
 * N.B. the returnStatus value is wrong. It's not because of the bash wrapper,
 * which propagates the correct rc fine if run outside Jenkins. Presumably it's
 * Jenkins being screwy. It does at least return an rc with the same truthiness
 * (0 for success, 1 for failure).
 * If your code really cares about the rc being right then do not use.
 *
 * @param alwaysArchive true will always archive shell artifacts; default is to do so only on failure
 * @param returnAll true causes to return map with stderr, stdout, combined, and returnStatus
 * otherwise params are same as sh() step
 * @return same as sh() command, unless returnAll set
 * @throws error (unless returnAll or returnStatus set) with useful message (and archives)
 */
def shell(arg) {
    if (arg instanceof CharSequence) {  // https://stackoverflow.com/a/13880841
        arg = [script: arg]
    }
    arg = [:] + arg  // make a copy
    def alwaysArchive = arg.remove("alwaysArchive")
    def returnAll = arg.remove("returnAll")
    def script = arg.script  // keep a copy of original script
    def truncScript = (script.size() <= 75) ? script : script[0..35] + "..." + script[-36..-1]


    def threadShellIndex = getNextShellCounter()
    def shellBase = "${env.WORKSPACE}"
    // put shell result files in a dir specific to this shell call.
    // In case we are running with changed dir, use absolute file paths.
    def shellDir = "${shellBase}/shell.${currentBuild.number}"
    // use a subdir for each shell invocation
    def shellSubdir = "${shellDir}/sh.${threadShellIndex}." + truncScript.replaceAll( /[^\w-.]+/ , "_").take(80)

    // concurrency-safe creation of output dir and removal of any previous output dirs
    sh """#!/bin/bash +x
        # Make the directory we are going to stream output to
        mkdir -p ${shellSubdir}
        # Remove any old shell content from prior jobs
        rm -rf `ls -d ${shellBase}/shell.* | grep -v shell.${currentBuild.number}`
    """

    echo "Running shell script via commonlib.shell:\n${script}"
    def filebase = "${shellSubdir}/sh.${threadShellIndex}"
    arg.script =
    """#!/bin/bash +x
        set -euo pipefail
        # many thanks to https://stackoverflow.com/a/9113604
        {
            {
                {
                    ${script}
                }  2>&3 |  tee ${filebase}.out.txt   # redirect stderr to fd 3, capture stdout
            } 3>&1 1>&2 |  tee ${filebase}.err.txt   # turn captured stderr (3) into stdout and stdout>stderr
        }               |& tee ${filebase}.combo.txt # and then capture both (though maybe out of order)
    """

    // run it, capture rc, and don't error
    def rc = sh(arg + [returnStatus: true])
    if (rc || alwaysArchive) {
        writeFile file: "${filebase}.cmd.txt", text: script  // save cmd as context for archives
        // note that archival requires the location relative to workspace
        def relFilebase = filebase.minus("${env.WORKSPACE}/")
        dir(env.WORKSPACE) {  // always archive from workspace context since we wrote it there
            safeArchiveArtifacts(["${relFilebase}.*"])
        }
    }

    try {
        results = [
            stdout: readFile("${filebase}.out.txt"),
            stderr: readFile("${filebase}.err.txt"),
            combined: readFile("${filebase}.combo.txt"),
            returnStatus: rc,
        ]
    } catch(ex) {
        error("The following shell script is malformed and broke output capture:\n${script}")
    }

    if (arg.returnStatus) { return rc }  // like sh(returnStatus: true)
    if (returnAll) { return results }  // want results even if "failed"

    if (rc) {
        // raise error like sh() would but with context; trim message if long
        def output = results.combined.split("\n")
        if (output.size() > 5) {
            output = [ "[...see full archive #${threadShellIndex}...]" ] + array_to_list(output)[-5..-1]
        }
        error(  // TODO: use a custom exception class with attrs
"""\
failed shell command: ${truncScript}
with rc=${rc} and output:
${output.join("\n")}
""")
    }

    // successful, return like sh() would
    if (arg.returnStdout) { return results.stdout }
    return  // nothing
}

/**
 * Jenkins doesn't seems to whitelist .asList(),
 * so this is an awful workaround.
 * @param array An array
 * @return Returns a list containing the elements of the array
 */
@NonCPS
def array_to_list(array) {
    l = []
    for ( def e : array ) {
        l << e
    }
    return l
}

/**
 * Given a version string x.y.z,
 * returns the x.y part.
 * e.g. "4.1.0-rc.9" => "4.1"
 */
String extractMajorMinorVersion(String version) {
    return (version =~ /^(\d+\.\d+)/)[0][1]
}

/**
 * Returns the major and minor version numbers for a given version string.
 * e.g. "4.1.0-rc.9" => [4, 1]
 */
def extractMajorMinorVersionNumbers(String version) {
    return (version =~ /^(\d+)\.(\d+)/)[0].subList(1,3).collect { it as int }
}

/**
 * Given a SemVer version string x.y.z,
 * returns true if and only if the release contains a pre-release component.
 * e.g. "4.1.0-rc.9" => true  ,  "4.1.0-ec.0" => true
 * https://semver.org/spec/v2.0.0.html#spec-item-9
 */
String isPreRelease(String version) {
    return (version =~ /^(\d+\.\d+\.\d+)(-?)/)[0][2] == "-"
}

/**
    Returns the architecture name extracted from a release name.
    Only known architecture names are recognized, defaulting to `defaultArch`.
    e.g.
        "4.8.0-0.nightly-2021-01-06-041852" => "amd64"
        "4.8.0-0.nightly-ppc64le-2021-01-06-041852" => "ppc64le"
        "4.8.0-0.nightly-s390x-2021-01-06-041852" => "s390x"
        "4.8.0-0.nightly-arm64-2021-06-06-041852" => "arm64"
*/
def extractGoArchFromReleaseName(String release, String defaultArch='amd64') {
    archs = goArches + brewArches  // should normally be go but we can identify either safely
    for(arch in goArches) {
        if(arch in release.split('-')) defaultArch = arch
    }
    return goArchForBrewArch(defaultArch)
}
def extractArchFromReleaseName(String release, String defaultArch='x86_64') {
    // get the *brew* arch from release name which is go arch nomenclature
    return brewArchForGoArch(extractGoArchFromReleaseName(release, goArchForBrewArch(defaultArch)))
}

/**
 * Attempts, for a specified duration, to claim a Jenkins lock. If claimed, the
 * lock is released before returning. Callers should be aware this leaves
 * a race condition and there is no guarantee they will get the lock themselves. Thus, this
 * method should only be used for optimization decisions and not relied on for
 * guaranteed behavior.
 * @param lockName The name of the lock to test
 * @param timeout_seconds The number of seconds to try to acquire the lock before giving up
 * @return Returns true if the lock was successfully able to be claimed.
 */
def canLock(lockName, timeout_seconds=10) {
    def claimed = false
    try {
        timeout(time: timeout_seconds, unit: 'SECONDS') {
            lock(lockName) {
                claimed = true
            }
        }
    } catch ( e ) {
        echo "Timeout waiting for lock ${lockName}"
    }
    return claimed
}

/**
 * Each OCP architecture gets its own release controller. They are hosted
 * on different routes. This method returns the golang arch name used by the
 * release controller based on the name of the
 * release stream you want to query.
 * @param releaseStreamName or nightly name - e.g. 4-stable or 4-stable-s390x or 4.9.0-0.nightly-s390x-2021-10-08-232627
 * @return Returns a golang arch name like "s390x" or "amd64"
 */
def getReleaseControllerArch(releaseStreamName) {
    def arch = 'amd64'
    def streamNameComponents = releaseStreamName.split('-') // e.g. ['4', 'stable', 's390x']  or [ '4', 'stable' ]
    for(goArch in goArches) {
        if (goArch in streamNameComponents) {
            arch = goArch
        }
    }
    return arch
}

def getReleaseControllerURL(releaseStreamName) {
    def arch = getReleaseControllerArch(releaseStreamName)
    return "https://${arch}.ocp.releases.ci.openshift.org"
}

def inputRequired(slackOutput=null, cl) {
    if (!slackOutput) {
        slackOutput = slacklib.to(null)
    }
    def oldName = currentBuild.displayName
    try {
        currentBuild.displayName = "INPUT REQUIRED: ${oldName}"
        slackOutput.say('This job is waiting for input')
        cl()
    } finally {
        currentBuild.displayName = oldName
    }
}

def _retryWithOptions(goal, options, slackOutput=null, prompt='', reasonFieldDescription='', cl) {
    def success = false
    def action = ''
    def reason = ''
    if (!slackOutput) {
        slackOutput = slacklib.to(null)
    }
    while( !success ) {
        try {
            cl()
            success = true
        } catch ( retry_e ) {
            def description = "Problem encountered during: ${goal} => ${retry_e}"
            echo "${description}"
            slackOutput.failure("[INPUT REQUIRED] ${description}")
            inputRequired() {

                if ( ! prompt ) {
                    prompt = "Problem encountered during: ${goal}"
                }

                def parameters = [
                    [
                            $class     : 'hudson.model.ChoiceParameterDefinition',
                            choices    : options.join('\n'),
                            description : 'Retry this goal, Skip this goal, or Abort the pipeline',
                            name       : 'action'
                    ]
                ]
                if (reasonFieldDescription) {
                    parameters << string(
                        description: reasonFieldDescription,
                        name: 'reason',
                        trim: true,
                    )
                }

                def resp = input message: prompt, parameters: parameters

                action = (resp instanceof String)?resp:resp.action
                reason = reasonFieldDescription? resp.reason : ""

                echo "User selected: ${action}"
                def slackMessage = "User selected: ${action}"
                if (reason) {
                    slackMessage += "\nThe reason given was: ${reason}"
                }
                slackOutput.say(slackMessage)

                switch(action) {
                    case 'RETRY':
                        echo "User chose to retry"
                        break
                    case 'SKIP':
                        echo "User chose to skip."
                        if (reasonFieldDescription && !reason) {
                            error("Justification is required but not given. Aborting")
                        }
                        success = true  // fake it
                        break
                    case 'ABORT':
                        error("User chose to abort retries of: ${goal}")
                }
            }
        }
    }
    return [action, reason]
}

// WARNING: make really sure that nothing in the closure is required for
// functioning after the user chooses SKIP.
def retrySkipAbort(goal, slackOutput=null, prompt='', reasonFieldDescription='', cl) {
    return _retryWithOptions(goal, ['RETRY', 'SKIP', 'ABORT'], slackOutput, prompt, reasonFieldDescription, cl)
}

def retryAbort(goal, slackOutput=null, prompt='', cl) {
    return _retryWithOptions(goal, ['RETRY', 'ABORT'], slackOutput, prompt, cl)
}

def checkS3Path(s3_path) {
    if (s3_path.startsWith('/pub/openshift-v4/clients') ||
        s3_path.startsWith('/pub/openshift-v4/amd64') ||
        s3_path.startsWith('/pub/openshift-v4/arm64') ||
        s3_path.startsWith('/pub/openshift-v4/dependencies')) {
        error("Invalid location on s3 (${s3_path}); these are virtual/read-only locations on the s3 backed mirror. Quality your path with /pub/openshift-v4/<brew_arch_name>/ instead.")
    }
}

def invalidateAwsCache(s3_path, dry_run=false) {
    // https://issues.redhat.com/browse/ART-6607 on why invalidation matters when updating existing filenames.

    invalidation_path = s3_path
    if ( !invalidation_path.endsWith('/') ) {
      invalidation_path += '/'
    }
    invalidation_path += '*'

    cmd = "aws cloudfront create-invalidation --distribution-id E3RAW1IMLSZJW3 --paths ${invalidation_path}"

    if (dry_run) {
        echo "Would have run: ${cmd}"
        return
    }

    withCredentials([
        aws(credentialsId: 's3-art-srv-enterprise', accessKeyVariable: 'AWS_ACCESS_KEY_ID', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')
    ]) {
        shell(script: cmd)
    }

}

def syncRepoToS3Mirror(local_dir, s3_path, remove_old=true, timeout_minutes=60, issue_cloudfront_invalidation=true, dry_run=false) {
    try {
        checkS3Path(s3_path)
         withCredentials([
            file(credentialsId: 'aws-credentials-file', variable: 'AWS_SHARED_CREDENTIALS_FILE'),
            string(credentialsId: 's3-art-srv-enterprise-cloudflare-endpoint', variable: 'CLOUDFLARE_ENDPOINT')]) {
            retry(3) {
                timeout(time: timeout_minutes, unit: 'MINUTES') { // aws s3 sync has been observed to hang before
                    // Sync is not transactional. If we update repomd.xml before files it references are populated,
                    // users of the repo will get a 404. So we run in three passes:
                    // 1. On the first pass, exclude files like repomd.xml and do not delete any old files. This ensures that we  are only adding
                    // new rpms, filelist archives, etc.
                    def opts = '--no-progress --exact-timestamps'
                    if (dry_run) opts += ' --dryrun'
                    shell(script: "aws s3 sync ${opts} --exclude '*/repomd.xml' ${local_dir} s3://art-srv-enterprise${s3_path}") // Note that s3_path has / prefix.
                    shell(script: "aws s3 sync ${opts} --exclude '*/repomd.xml' ${local_dir} s3://art-srv-enterprise${s3_path} --profile cloudflare --endpoint-url ${env.CLOUDFLARE_ENDPOINT}")
                    // 2. On the second pass, include only the repomd.xml.
                    shell(script: "aws s3 sync ${opts} --exclude '*' --include '*/repomd.xml' ${local_dir} s3://art-srv-enterprise${s3_path}")
                    shell(script: "aws s3 sync ${opts} --exclude '*' --include '*/repomd.xml' ${local_dir} s3://art-srv-enterprise${s3_path} --profile cloudflare --endpoint-url ${env.CLOUDFLARE_ENDPOINT}")
                    if (remove_old) {
                        // For most repos, clean up the old rpms so they don't grow unbounded. Specify remove_old=false
                        // to prevent this step.
                        // Otherwise:
                        // 3. Everything should be sync'd in a consistent way -- delete anything old with --delete.
                        shell(script: "aws s3 sync ${opts} --delete ${local_dir} s3://art-srv-enterprise${s3_path}")
                        shell(script: "aws s3 sync ${opts} --delete ${local_dir} s3://art-srv-enterprise${s3_path} --profile cloudflare --endpoint-url ${env.CLOUDFLARE_ENDPOINT}")
                    }
                }
            }
            if (issue_cloudfront_invalidation) {
                invalidateAwsCache(s3_path, dry_run)
            }
        }
    } catch (e) {
        slacklib.to("#art-release").say("Failed syncing ${local_dir} repo to art-srv-enterprise S3 path ${s3_path}")
        throw e
    }
}

def syncDirToS3Mirror(local_dir, s3_path, delete_old=true, include_only='', timeout_minutes=60, issue_cloudfront_invalidation=true) {
    try {
        checkS3Path(s3_path)
        extra_args = ""
        if (include_only) {
            // --include only takes effect if files are excluded.
            extra_args = "--exclude '*' --include '${include_only}'"
        }
        if (delete_old) {
            extra_args += " --delete"
        }
         withCredentials([
            file(credentialsId: 'aws-credentials-file', variable: 'AWS_SHARED_CREDENTIALS_FILE'),
            string(credentialsId: 's3-art-srv-enterprise-cloudflare-endpoint', variable: 'CLOUDFLARE_ENDPOINT')]) {
            retry(3) {
                timeout(time: timeout_minutes, unit: 'MINUTES') { // aws s3 sync has been observed to hang before
                    shell(script: "aws s3 sync --no-progress --exact-timestamps ${extra_args} ${local_dir} s3://art-srv-enterprise${s3_path}")
                    shell(script: "aws s3 sync --no-progress --exact-timestamps ${extra_args} ${local_dir} s3://art-srv-enterprise${s3_path} --profile cloudflare --endpoint-url ${env.CLOUDFLARE_ENDPOINT}")
                }
            }
            if (issue_cloudfront_invalidation) {
                 invalidateAwsCache(s3_path)
            }
        }
    } catch (e) {
        slacklib.to("#art-release").say("Failed syncing ${local_dir} repo to art-srv-enterprise S3 path ${s3_path}")
        throw e
    }
}

return this
