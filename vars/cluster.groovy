import retort.utils.logging.Logger
import retort.exception.RetortException
import org.yaml.snakeyaml.Yaml as Parser
import org.yaml.snakeyaml.DumperOptions
import static retort.utils.Utils.delegateParameters as getParam

/**
 * Getting parameters directly from pipeline script.
 * Support String, Array, List, Map
 * 
 * @param clusters
 *  if it is map type, support keys below
 *   - target 
 *   - credentialsId - ZCP_CLI_CREDENTIALS (default)
 */
def call(clusters, cl) {
    Logger logger = Logger.getLogger(this)
    createException.init(logger)

    logger.info("Received target clusters -> ${clusters.toString()}")
    
    def config = parseParam(clusters, logger)
    
    logger.info("Real target -> ${config.target.toString()}")

    // get login token
    def token = getToken(config, logger)
    
    // create kubeconfig in agent
    createKubeconfigs(config, token, logger)

    // execute closures
    doExecute(config, cl, logger)
}

/**
 * Can call cluster without any parameters.
 */
def call(cl) {
    call([:], cl)
}

/**
 * Getting target from jenkins build parameters.
 * 
 * Support boolean, String
 * 
 * @throws RetortException - RC601
 */
def getParamsFromBuildParameter(logger) {
    def booleanTargetPrefix = 'deployTarget.'
    def booleanTargets = []
    def stringTargetKey = 'deployTargets'
    def stringTarget
    
    logger.info 'Finding cluster parameters from build parameters'
    params.each { param ->
        // find boolean parameter. ex) deployTarget.cluster1/ns1
        if (param.key.startsWith(booleanTargetPrefix)) {
            if (param.value) {
                logger.debug "Add ${param.key} -> ${param.value}"
                booleanTargets.add(param.key.substring(booleanTargetPrefix.length()))
            } else {
                logger.debug "Skip ${param.key} -> ${param.value}"
            }
        }
        
        // find String parameter. ex) cluster1/ns1,cluster2/ns1
        if (stringTargetKey.equals(param.key)) {
            logger.debug "String target : ${param.key} -> ${param.value}"
            stringTarget = param.value
        }
    }
    
    if (booleanTargets.size() > 0) {
        logger.info "Start with boolean parameters \"${booleanTargetPrefix}*\""
        return booleanTargets
    } else if (stringTarget?.trim()) {      // stringTarget is not empty
        logger.info "Start with String parameter \"${stringTarget}\""
        return stringTarget
    } else {
        logger.error 'Cluster step is used without parameters, but no build parameter selected.'
        throw createException('RC601')
    }
}

/**
 * Parse parameter to map 
 */
private def parseParam(param, logger) {
    logger.debug "Original param : ${param.toString()}"
    
    // get params with default parameter.
    def config = getParam(param, [credentialsId: 'ZCP_CLI_CREDENTIALS'])

    if (param instanceof Map) {
        if (param.target == null) {
            def paramsFromBuildParameter = getParamsFromBuildParameter(logger)
//            logger.error "Using map type parameter, but target value is null. target must be exists.", param 
//            throw createException('RC602')
            config.target = doParse(paramsFromBuildParameter, logger)
        } else {
            config.target = doParse(param.target, logger)
        }
    } else {
        config.target = doParse(param, logger)
    }
    logger.debug "Parsed target : ${config.target}"
    
    return config
}

private def doParse(param, logger) {
    if ((param instanceof List) || param.getClass().isArray()) {
        return convertToMapList(param, logger)
    } else if (param instanceof CharSequence) {
        def targetList = param.split(',');
        return convertToMapList(targetList, logger)
    }
    logger.error "Can not parse. Not supported type", param
    throw new RetortException('RC603', param.toString())
}

/**
 * Convert List<String> or Array<String> to List<Map>
 * 
 * @param param Only support ['a/b','c']
 */
private def convertToMapList(param, logger) {
    def result = []
    param.collect(result) {
        logger.debug "Target : $it"
        if ((it instanceof CharSequence) == false) {
            throw new RetortException('RC603', it.toString())
        }
        def target = it.split('/');
        def targetMap = [:];
        
        if (target.length == 2) {
            targetMap.put('namespace', target[1]);
        } else if (target.length == 1) {
            def defaultNamespace = getDefaultNamespace()
            if (defaultNamespace == null) {
                logger.error 'If you want to use cluster without namespace, must set default namespace. ex) env.DEFAULT_NAMESPACE = mynamespace'
                throw new RetortException('RC604')
            }
            targetMap.put('namespace', defaultNamespace);
        } else {
            logger.error "target format must be 'cluster' or 'cluster/namespace' but received '${it}'"
            throw new RetortException('RC605', it.toString())
        }
        
        if (!(target[0]?.trim())) {
            logger.error "cluster name is empty - $it"
            throw new RetortException('RC605', it.toString())
        }
        targetMap.put('cluster', target[0].trim());
        
        return targetMap
    }
    return result
}

private def getDefaultNamespace() {
    return env.DEFAULT_NAMESPACE
}

/**
 * Login to zcp-cli-backend, and return token 
 */
private def getToken(config, logger) {
    logger.info "Logging in backend with ${config.credentialsId}..."
    
    def LOGIN_PATH = '/api/auth/login'

    def responseBody
    try {
        withCredentials([
            usernamePassword(credentialsId: config.credentialsId, passwordVariable: 'PASSWORD', usernameVariable: 'USER_ID')
        ]) {
            def jsonBody = "{\"username\" : \"${USER_ID}\",\"password\" : \"${PASSWORD}\"}"
            responseBody = httpRequest httpMode: 'POST',
                contentType: 'APPLICATION_JSON',
                requestBody: jsonBody,
                url: "${BACKEND_URL}" + LOGIN_PATH,
                validResponseCodes: '100:599',
                quiet: true
        }
    } catch (Exception e) {
        def exceptionSimpleName =  e.getClass().getSimpleName()
        
        if ('CredentialNotFoundException'.equals(exceptionSimpleName)) {
            logger.error "Could not find Credentials named \'${config.credentialsId}\'"
            // remove cause.. was obvious  
            throw createException('RC606')
        }
        
        throw createException('RC607', e)
    }
    
    if (200 != responseBody.status) {
        logger.error "Login failed : Expected http status 200 but received ${responseBody.status}."
        logger.error "Response body -\n ${responseBody.content}."
        throw createException('RC608')
    }

    def jsonMap = readJSON text: responseBody.content
    if (! jsonMap.success) {
        logger.error "Login failed. Response body -\n ${responseBody.content}."
        throw createException('RC608')
    }
    def token = jsonMap.data
    
    logger.info "Login succeed."
    return token
}

/**
 * Get kubeconfigs of all clusters, and copy to agent workspace.
 */
private def createKubeconfigs(config, token, logger) {
    for (def target : config.target) {
        def kubeconfigMap = getKubeConfig(target.cluster, token, logger)
        def kubeconfigYaml = toYamlString(kubeconfigMap);
        
        writeFile file: "/home/jenkins/workspace/${target.cluster}", text: kubeconfigYaml
        logger.debug "Created kubeconfig - /home/jenkins/workspace/${target.cluster}"
    }
}

/**
 * Get kubeconfig from zcp-cli-backend with given token 
 */
private def getKubeConfig(cluster, token, logger) {
    logger.debug "Getting ${cluster} 's KUBECONFIG"
    def url = "${BACKEND_URL}/api/cluster/${cluster}/credential"
    
    def responseBody
    try {
        responseBody = httpRequest httpMode: 'GET',
            contentType: 'APPLICATION_JSON',
            url: url,
            customHeaders: [[name: 'zcp-cli-token', value: token]],
            validResponseCodes: '100:599',
            quiet: true
    } catch (Exception e) {
        throw createException('RC609', e)
    }

    if (200 != responseBody.status) {
        logger.error "Failed to get KUBECONFIG : Expected http status 200 but received ${responseBody.status}."
        logger.error "Response body -\n ${responseBody.content}."
        throw createException('RC610')
    }

    def jsonMap = readJSON text: responseBody.content
    if (! jsonMap.success) {
        logger.error "Failed to get KUBECONFIG. Response body -\n ${responseBody.content}."
        throw createException('RC610')
    }
    
    logger.debug "Getting ${cluster} 's KUBECONFIG -- Success"
    return jsonMap.data
}

/**
 * Execute closures
 */
private def doExecute(config, cl, logger) {
    def closures = [:]
    for (def target : config.target) {
        def curr = target
        closures.put(curr.cluster, {
            withEnv(["KUBECONFIG=/home/jenkins/workspace/${curr.cluster}"]) {
                logger.debug "Kubeconfig Filepath : KUBECONFIG=/home/jenkins/workspace/${curr.cluster}"
                cl(curr)
            }
        })
    }
    parallel closures
}

@NonCPS
private def toYamlString(map) {
    DumperOptions options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)

    return new Parser(options).dump(map)
}