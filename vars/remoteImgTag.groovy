import retort.utils.logging.Logger
import retort.exception.RetortException
import static retort.utils.Utils.delegateParameters as getParam
//dockerCmd.remoteTag from: 'my/image:dev-1', to: 'my/image:prd-1', registry: HARBOR_REGISTRY, credentialsId: 'DOCKER_CREDENTIALS'
//ockerCmd.remoteTag fromImage: 'my/image, fromVersion: dev-1', toImage: 'my/image, toVersion: 'prd-1', registry: HARBOR_REGISTRY, credentialsId: 'DOCKER_CREDENTIALS'

def remoteTag(ret) {
    Logger logger = Logger.getLogger(this)
    logger.info "stared remote image change tag and push"

    def config = getParam(ret)

    if(retParamErrorCheck(config, logger)) {
        def remotImgMap = convertNewMap(config, logger)
        def token = getToken(remotImgMap, logger)
        if(token) {

            def metaData = getManifestPath(token, remotImgMap, logger)
            if(metaData){
                pushRemoteImg(token, metaData, remotImgMap, logger)
            }
        }
    }
}


private def retParamErrorCheck(config, logger) {
    logger.info "starting request param error check"
    
    if (!config.registry) {
        logger.error("registry is required.")
        throw new RetortException('RC213')
    }
        
    if(!config.from) {
        logger.error("from: projectName/ImageName:tag values is required.")
        throw new RetortException('RC211')
    }

    if(!config.to) {
         logger.error("to: projectName/ImageName:tag values is required.")
        throw new RetortException('RC212')
    }

    return true
}


private def convertNewMap(config, logger) {
    logger.info "stared convertNewMap"

    def remoteImgMap = [:]

    config.each { key, value ->
        if('from'.equals(key)) {
            def fromArrValue= value.split(':')
            if(fromArrValue.length == 2) {
                remoteImgMap.put('fromImage',fromArrValue[0])
                remoteImgMap.put('fromVersion',fromArrValue[1])
            }else {
                logger.error 'Please set from: projectname/imagename:tagversion ex) from: myproject/myimage:dev-1'
                throw new RetortException('RC211')
            }
        }else if('to'.equals(key)) {
            def toArrValue = value.split(':')
            def toArrValue= value.split(':')
            if(toArrValue.length == 2) {
                remoteImgMap.put('toImage',toArrValue[0])
                remoteImgMap.put('toVersion',toArrValue[1])
            }else {
                logger.error 'Please set to: projectname/imagename:tagversion ex) to: myproject/myimage:prd-1'
                throw new RetortException('RC212')
            }
        }else {
            remoteImgMap.put(key,value)
        }
    }
    logger.debug "remoteImgMap : ${remoteImgMap}"
    return remoteImgMap;
}


// dockerCmd.push registry: HARBOR_REGISTRY, imageName: DOCKER_IMAGE, imageOldVersion: OLD_VERSION, imageNewVersion: NEW_VERSION, credentialsId: "HARBOR_CREDENTIALS"

private def getToken(remotImgMap, logger) {
    logger.info "starting get token Logging in image registry with ${remotImgMap.credentialsId}"

    def TOKEN_PATH = '/service/token?service=harbor-registry&scope=repository:'
    StringBuffer tokenUrl = new StringBuffer("https://")

    if(remotImgMap.registry) {
        tokenUrl.append("${remotImgMap.registry}")
        tokenUrl.append(TOKEN_PATH)
    }

    if(remotImgMap.fromImage) {
        tokenUrl.append("${remotImgMap.fromImage}:pull,push")
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

private def getManifestPath(token, remotImgMap, logger) {
    logger.info "starting get manifestInfo in image registry with - [ registry: ${remotImgMap.registry}, FromImage : ${remotImgMap.imageName}, tagFromVersion : ${remotImgMap.fromVersion} ]"
    def MANIFEST_TYPE = "/v2/" + remotImgMap.fromImage + "/manifests/"
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://")
    
    if(remotImgMap.registry) {
        getManifestUrl.append("${remotImgMap.registry}")
    }

    getManifestUrl.append(MANIFEST_TYPE)

    if(remotImgMap.fromVersion) {
        getManifestUrl.append("${remotImgMap.fromVersion}")
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

private void pushRemoteImg(token, metaData, remotImgMap, logger) {
    logger.info "starting get manifestInfo in image registry with - [registry: ${remotImgMap.registry}, toImage : ${remotImgMap.toImage}, tagToVersion : ${remotImgMap.toVersion} ]"
    
    def MANIFEST_TYPE = "/v2/" + remotImgMap.toImage + "/manifests/"
    def con_type = "application/vnd.docker.distribution.manifest.v2+json"
    StringBuffer getManifestUrl = new StringBuffer("https://")
    
    if(remotImgMap.registry) {
        getManifestUrl.append("${remotImgMap.registry}")
    }
    
    getManifestUrl.append(MANIFEST_TYPE)

    if(remotImgMap.toVersion) {
        getManifestUrl.append("${remotImgMap.toVersion}")
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
