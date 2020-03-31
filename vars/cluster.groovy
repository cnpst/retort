import retort.utils.logging.Logger
import org.yaml.snakeyaml.Yaml as Parser
import org.yaml.snakeyaml.DumperOptions
import static retort.utils.Utils.delegateParameters as getParam

/**
 *
 *
 */
def call(clusters, cl) {
    Logger logger = Logger.getLogger(this)

    logger.info clusters.toString()
    
    def config = parseParam(clusters, logger)

    logger.info "Logging in backend..."
    def token = getToken()
    logger.info "Login succeed."
    echo "TOKEN : " + token
    
    for (def target : config.target) {
        def kubeconfigMap = getKubeConfig(target.cluster, token)
        def kubeconfigYaml = toYamlString(kubeconfigMap);
        
        echo kubeconfigYaml
//    sh "echo ${kubeconfigYaml} > /var/run/secrets/${clusters}"
        writeFile file: "/home/jenkins/workspace/${target.cluster}", text: kubeconfigYaml
    }
        
    for (def target : config.target) {
        withEnv(["KUBECONFIG=/home/jenkins/workspace/${target.cluster}"]) {
            cl(target)
        }
    }
}

private def parseParam(param, logger) {
    logger.debug "Original param : ${param.toString()}"
    
    def config = getParam(param, [:])

    if (param instanceof Map) {
        config.target = doParse(param.target, logger)
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
    throw new Exception('not supported type : ' + param.getClass().getName() )
}

private def convertToMapList(param, logger) {
    def result = []
    param.collect(result) {
        logger.debug "Target : $it"
        def target = it.split('/');
        def targetMap = [:];
        
        if (target.length == 2) {
            targetMap.put('namespace', target[1]);
        } else if (target.length == 1) {
            def defaultNamespace = getDefaultNamespace()
            if (defaultNamespace == null) {
                throw new Exception("if you want to use 'cluster', must set default namespace. ex) env.DEFAULT_NAMESPACE = mynamespace")
            }
            targetMap.put('namespace', defaultNamespace);
        } else {
            throw new Exception("target format must be 'cluster' or 'cluster/namespace' but received ${it}" )
        }
        targetMap.put('cluster', target[0].trim());
        
        return targetMap
    }
    return result
}

private def getDefaultNamespace() {
    return env.DEFAULT_NAMESPACE
}

private def getToken() {
    def LOGIN_PATH = '/api/auth/login'

    def responseBody
    withCredentials([
        usernamePassword(credentialsId: 'ZCP_CLI_CREDENTIALS', passwordVariable: 'PASSWORD', usernameVariable: 'USER_ID')
    ]) {
        def jsonBody = "{\"username\" : \"${USER_ID}\",\"password\" : \"${PASSWORD}\"}"
        echo jsonBody
        responseBody = httpRequest httpMode: 'POST',
            contentType: 'APPLICATION_JSON',
            requestBody: jsonBody,
            url: "${BACKEND_URL}" + LOGIN_PATH,
            validResponseContent: '"success":true',
            quiet: true
    }

    def jsonMap = readJSON text: responseBody.content
    def token = jsonMap.data
    return token
}

private def getKubeConfig(clusters, token) {
    def url = "${BACKEND_URL}/api/cluster/${clusters}/credential"
    
    responseBody = httpRequest httpMode: 'GET',
        contentType: 'APPLICATION_JSON',
        url: url,
        validResponseContent: '"success":true',
        customHeaders: [[name: 'zcp-cli-token', value: token]],
        quiet: true

    jsonMap = readJSON text: responseBody.content
    return jsonMap.data
}

@NonCPS
private def toYamlString(map) {
  DumperOptions options = new DumperOptions()
  options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
  
  return new Parser(options).dump(map)
}