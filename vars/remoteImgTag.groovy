import retort.utils.logging.Logger
import retort.exception.RetortException
import static retort.utils.Utils.delegateParameters as getParam

def remoteImgpush(ret) {
    Logger logger = Logger.getLogger(this)

    def config = getParam(ret)
    def token = getToken(config, logger)
    if(token) {
        def metaData = getManifestPath(token, config, logger)
        if(metaData){
            pushImgNew(token, metaData, config, logger)
        }
    }
}

// dockerCmd.push registry: HARBOR_REGISTRY, imageName: DOCKER_IMAGE, imageOldVersion: OLD_VERSION, imageNewVersion: NEW_VERSION, credentialsId: "HARBOR_CREDENTIALS"

private def getToken(config, logger) {
    def LOGIN_PATH = '/service/token?service=harbor-registry&scope=repository:'
    StringBuffer tokenUrl = new StringBuffer("https://mcm-dev-devops.cloudzcp.io")

    if(config.registry) {
        tokenUrl.append("${config.registry}/")
        tokenUrl.append(LOGIN_PATH)
    }

    if(config.imageName) {
        tokenUrl.append("${config.imageName}:pull,push")
    }

    echo tokenUrl.toString()

    def responseBody = httpRequest httpMode: 'GET',
    authentication: 'HARBOR_CREDENTIALS',
    url: tokenUrl,
    quiet: true

    def jsonMap = readJSON text: responseBody.content
    def token = jsonMap.token
    return token
}

private def getManifestPath(token, config, logger) {
    def MANIFEST_TYPE = '/v2/kshong/${config.imageName}/manifests/'
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://mcm-dev-devops.cloudzcp.io")
    getManifestUrl.append(MANIFEST_TYPE)

    if(config.imageOldVersion) {
        getManifestUrl.append("${config.imageOldVersion}")
    }

    echo getManifestUrl.toString()


    def responseBody = httpRequest httpMode: 'GET',
    contentType: 'APPLICATION_JSON',
    customHeaders: [[name: 'Authorization', value: "Bearer " + token],[name: 'Accept', value: con_type]],
    url: getManifestUrl,
    quiet: true
    echo responseBody.content

    return responseBody.content
}

private void pushImgNew(token, metaData, config, logger) {
    def MANIFEST_TYPE = '/v2/kshong/${config.imageName}/manifests/'
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://mcm-dev-devops.cloudzcp.io")
    getManifestUrl.append(MANIFEST_TYPE)

    if(config.imageNewVersion) {
        getManifestUrl.append("${config.imageNewVersion}")
    }

    echo getManifestUrl.toString()

    def responseBody = httpRequest httpMode: 'PUT',
    //contentType: con_type,
    customHeaders: [[name: 'Authorization', value: "Bearer " + token],[name: 'Content-Type', value: con_type]],
    url: REGISTRY_NAME + getManiFestUrl ,
    requestBody: metaData,
    quiet: false,
    validResponseCodes: '100:599',
    echo responseBody.content
}
