import retort.utils.logging.Logger
import retort.exception.RetortException
import static retort.utils.Utils.delegateParameters as getParam

/**
 * docker push
 *
 * @param registry
 * @param imageName
 * @param imageVersion
 * @param credentialsId
 * @param username
 * @param password
 */
def push(ret) {
  Logger logger = Logger.getLogger(this)
  createException.init(logger)
  
  def config = getParam(ret)
  
  def command = new StringBuffer('docker push ')
  
  command.append(getFullRepository(config, logger))
  
  // login with docker credential or username/password
  // 1. credential
  // 2. username/password
  // 3. anonymous
  if (config.credentialsId) {
    pushWithCredentialsId(config, command, logger)
  } else if (config.username && config.password) {
    pushWithUsernameAndPassword(config, command, logger)                                    
  } else {
    sh command.toString()
  }

}


/**
 * docker build
 *
 * @param path
 * @param file
 * @param tag
 * @param buildArgs
 * @param options
 */
def build(ret) {
  Logger logger = Logger.getLogger(this)
  createException.init(logger)
  def config = getParam(ret) {
    // current workspace
    path = '.'
  }
  
  def command = new StringBuffer('docker build')
  
  appendCommand(config, 'file', '-f', command, logger)
  setTag(command, config, logger)
  setBuildArgs(command, config, logger)
  appendCommand(config, 'options', '', command, logger)
  appendCommand(config, 'path', '', command, logger)
  
  sh command.toString()
}

/**
 * docker tag
 *
 * @param source
 * @param target
 */
def tag(ret) {
  Logger logger = Logger.getLogger(this)
  createException.init(logger)
  def config = getParam(ret)
  
  def command = new StringBuffer('docker tag')
  
  if (!config.source) {
    logger.error("source is required. source: 'SOURCE_IMAGE[:TAG]'")
    throw createException('RC201')
  }
  
  if (!config.target) {
    logger.error("target is required. target: 'TARGET_IMAGE[:TAG]'")
    throw createException('RC202')
  }
  
  command.append " ${config.source}"
  command.append " ${config.target}"

  sh command.toString()
}


private def pushWithCredentialsId(config, command, logger) {
  def loginCommand
  def logoutCommand
  logger.debug("Login with jenkins credential : ${config.credentialsId}")
  withCredentials([usernamePassword(credentialsId: config.credentialsId, passwordVariable: 'DOCKER_PASSWORD', usernameVariable: 'DOCKER_USER')]) {
    if (config.registry) {
      logger.debug("Registry : ${config.registry}")
      loginCommand = "docker login ${config.registry} -u \"${DOCKER_USER}\" -p \"${DOCKER_PASSWORD}\""
      logoutCommand = "docker logout ${config.registry}"
    } else {
      logger.debug("Registry : docker.io")
      loginCommand = "docker login -u \"${DOCKER_USER}\" -p \"${DOCKER_PASSWORD}\""
      logoutCommand = "docker logout"
    }

    sh """
      ${loginCommand}
      ${command}
      ${logoutCommand}
    """
  }
}

private def pushWithUsernameAndPassword(config, command, logger) {
  def loginCommand
  def logoutCommand
  logger.debug("Login with username/password")
  if (config.registry) {
    logger.debug("Registry : ${config.registry}")
    loginCommand = "docker login ${config.registry} -u ${config.username} -p ${config.password}"
    logoutCommand = "docker logout ${config.registry}"
  } else {
    logger.debug("Registry : docker.io")
    loginCommand = "docker login -u ${config.username} -p ${config.password}"
    logoutCommand = "docker logout"
  }
  
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.password, var: 'foo']]]) {
    sh """
      ${loginCommand}
      ${command}
      ${logoutCommand}
    """
  }
}

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
    logger.info "starting get manifestInfo in image registry with - [ registry: ${remotImgMap.registry}, FromImage : ${remotImgMap.fromImage}, tagFromVersion : ${remotImgMap.fromVersion} ]"
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
        if(!responseBody.content) {
            logger.error "Failed to get manifest in Image Registry. [Error status code : [${responseBody.status}]"
            throw createException('RC208')
        }
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
        if(jsonMap instanceof String) {
            logger.error "failed to push the remote image to the Image Registry. [Error status code : [${responseBody.status}]] "
            throw createException('RC210')
        }
        if(!jsonMap instanceof JSON) {
            logger.error "JSON failed to push the remote image to the Image Registry. [Error status code : [${responseBody.status}]] "
            throw createException('RC210')
        }
        if (jsonMap.errors) {
            logger.error "failed to push the remote image to the Image Registry. [Error status code : [${responseBody.status}], message : ${jsonMap.errors.message}] "
            throw createException('RC210')
        }
    }
    
    logger.debug "Successfully pushed the remote image to the image registry : ${responseBody.content}"
}

@NonCPS
private def getFullRepository(config, logger) {
  //config.registry
  //config.imageName
  //config.imageVersion
  
  if (!config.imageName) {
    logger.error("imageName is required.")
    throw new RetortException('RC203')
  }
  
  StringBuffer repository = new StringBuffer()
  if (config.registry) {
    repository.append("${config.registry}/")
  }
  
  repository.append(config.imageName)
  
  if (config.imageVersion) {
    repository.append(":${config.imageVersion}")
  }

  return repository.toString()
}

@NonCPS
private def setBuildArgs(command, config, logger) {
  if (!config.buildArgs) {
    return
  }

  if (config.buildArgs instanceof Map) {
    command.append(config.buildArgs.collect { k, v ->
      logger.debug("BUILD ARG : ${k} = ${v}")
      return " --build-arg ${k}=${v}"
    }.join())
  } else {
      logger.error("buildArgs option only supports Map type parameter.")
      logger.error("example : ['key1':'value1','key2':'value2']")
      throw new RetortException('RC204')
  }
}

@NonCPS
private def setTag(command, config, logger) {
  if (!config.tag) {
    return
  }

  if ((config.tag instanceof List) || config.tag.getClass().isArray()) {
    command.append config.tag.collect { t ->
      logger.debug("TAG : ${t}")
      return " -t ${t}"
    }.join()

  } else if (config.tag instanceof CharSequence) {
    appendCommand(config, 'tag', '-t', command, logger)
  }

}

@NonCPS
private def appendCommand(config, configName, option, command, logger) {
  def value = config.get(configName)
  if (value) {
    logger.debug("${configName.toUpperCase()} : ${value}")
    if (option) {
      command.append(" ${option} ${value}")
    } else {
      command.append(" ${value}")
    }
  }
}
 
