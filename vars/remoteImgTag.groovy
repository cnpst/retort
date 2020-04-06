import retort.utils.logging.Logger
import retort.exception.RetortException
import static retort.utils.Utils.delegateParameters as getParam

def push(ret) {
    Logger logger = Logger.getLogger(this)
    logger.info "stared remote image change tag and push"

    def config = getParam(ret)

    if(retParamErrorCheck(config, logger)) {
        def token = getToken(config, logger)
        if(token) {
            def metaData = getManifestPath(token, config, logger)
            if(metaData){
                pushImgNew(token, metaData, config, logger)
            }
        }
    }
}

private def retParamErrorCheck(config, logger) {
    logger.info "starting request param error check"

    if (!config.imageName) {
        logger.error("imageName is required.")
        throw new RetortException('RC203')
    }

    if (!config.imageOldVersion) {
        logger.error("imageOldVersion is required.")
        throw new RetortException('RC105')
    }

    if (!config.imageNewVersion) {
        logger.error("imageNewVersion is required.")
        throw new RetortException('RC106')
    }
    return true
}

// dockerCmd.push registry: HARBOR_REGISTRY, imageName: DOCKER_IMAGE, imageOldVersion: OLD_VERSION, imageNewVersion: NEW_VERSION, credentialsId: "HARBOR_CREDENTIALS"

private def getToken(config, logger) {
    logger.info "starting get token Logging in image registry with ${config.credentialsId}"

    def TOKEN_PATH = '/service/token?service=harbor-registry&scope=repository:'
    StringBuffer tokenUrl = new StringBuffer("https://")

    if(config.registry) {
        tokenUrl.append("${config.registry}")
        tokenUrl.append(TOKEN_PATH)
    }

    if(config.imageName) {
        tokenUrl.append("${config.imageName}:pull,push")
    }

    echo tokenUrl.toString()
    
    def responseBody
    try {
        responseBody = httpRequest httpMode: 'GET',
        authentication: 'HARBOR_CREDENTIALS',
        url: tokenUrl.toString(),
        quiet: false,
        validResponseCodes: '100:599'
    }catch(Exception e) {
        throw createException('RC205', e)
    }

    def jsonMap = readJSON text: responseBody.content
    def token = jsonMap.token

    if(200 != responseBody.status) {
        if (jsonMap.errors) {
            logger.error "failed to get token in Image Registry. [Error status code : [${responseBody.status}], message : ${jsonMap.errors.message}] "
            throw createException('RC206')
        }
    }
        
    logger.debug "Token : ${token}"
    return token
}

private def getManifestPath(token, config, logger) {
    logger.info "starting get manifestInfo in image registry with registry : ${config.registry}, imageName : ${config.imageName}, imageOldVersion : ${config.imageOldVersion}"
    def MANIFEST_TYPE = "/v2/" + config.imageName + "/manifests/"
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://")
    
    if(config.registry) {
        getManifestUrl.append("${config.registry}")
    }

    getManifestUrl.append(MANIFEST_TYPE)

    if(config.imageOldVersion) {
        getManifestUrl.append("${config.imageOldVersion}")
    }

    echo getManifestUrl.toString()
    def responseBody
    try {
        responseBody = httpRequest httpMode: 'GET',
        contentType: 'APPLICATION_JSON',
        customHeaders: [[name: 'Authorization', value: "Bearer " + token],[name: 'Accept', value: con_type]],
        url: getManifestUrl.toString(),
        quiet: false,
        validResponseCodes: '100:599'
    }catch(Exception e) {
        throw createException('RC207', e)
    }

    if(200 != responseBody.status) {
        def jsonMap = readJSON text: responseBody.content
        if (jsonMap.errors) {
            logger.error "Failed to get manifest in Image Registry. [Error status code : [${responseBody.status}], message : ${jsonMap.errors.message}] "
            throw createException('RC208')
        }
    }
    
    logger.debug "success to get manifest in Image Registry : ${responseBody.content}"
    return responseBody.content
}

private void pushImgNew(token, metaData, config, logger) {
    logger.info "starting get manifestInfo in image registry with registry : ${config.registry}, imageName : ${config.imageName}, imageNewVersion : ${config.imageNewVersion}"
    def MANIFEST_TYPE = "/v2/" + config.imageName + "/manifests/"
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://")
    
    if(config.registry) {
        getManifestUrl.append("${config.registry}")
    }
    
    getManifestUrl.append(MANIFEST_TYPE)

    if(config.imageNewVersion) {
        getManifestUrl.append("${config.imageNewVersion}")
    }

    def responseBody
    try {
        responseBody = httpRequest httpMode: 'PUT',
        customHeaders: [[name: 'Authorization', value: "Bearer " + token],[name: 'Content-Type', value: con_type]],
        url: getManifestUrl.toString(),
        requestBody: metaData,
        quiet: false,
        validResponseCodes: '100:599'
        echo responseBody.content
    }catch(Exception e) {
        throw createException('RC209', e)
    }
    
    if(201 != responseBody.status) {
        def jsonMap = readJSON text: responseBody.content
        if (jsonMap.errors) {
            logger.error "failed to push the remote image to the Image Registry. [Error status code : [${responseBody.status}], message : ${jsonMap.errors.message}] "
            throw createException('RC210')
        }
    }
    
    logger.debug "Successfully pushed the remote image to the image registry : ${responseBody.content}"
}
