// Configure Jenkins built-in node labels/executors and Kubernetes clouds.
// Inputs are passed via Jenkins container environment variables:
// - JENKINS_AGENT_LABELS (comma-separated label tokens)
// - JENKINS_AGENT_EXECUTORS (integer)
// - JENKINS_K8S_CLOUDS (comma-separated cloud names)
// - JENKINS_K8S_API_URL (Kubernetes API URL)
// - JENKINS_K8S_TOKEN (service account bearer token)

import jenkins.model.Jenkins
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud

def env = System.getenv()
def cloudNames = (env.get("JENKINS_K8S_CLOUDS") ?: "")
  .split(",")
  .collect { it.trim() }
  .findAll { it }
def labelTokens = (env.get("JENKINS_AGENT_LABELS") ?: "")
  .split(",")
  .collect { it.trim() }
  .findAll { it }
def apiUrl = env.get("JENKINS_K8S_API_URL") ?: "https://kind-control-plane:6443"
def k8sToken = env.get("JENKINS_K8S_TOKEN") ?: ""
def jenkinsUrl = env.get("JENKINS_K8S_JENKINS_URL") ?: "http://jenkins:8080"
def jenkinsTunnel = env.get("JENKINS_K8S_JENKINS_TUNNEL") ?: "jenkins:50000"
def execText = env.get("JENKINS_AGENT_EXECUTORS") ?: "8"
def requestedExecutors = 8
try {
  requestedExecutors = Integer.parseInt(execText)
} catch (Exception ignored) {
  requestedExecutors = 8
}

def credentialsId = "k8s-sa-token"
def j = Jenkins.get()
def tokenCredReady = false

if (k8sToken) {
  try {
    def cl = j.pluginManager.uberClassLoader
    def scopeClass = cl.loadClass("com.cloudbees.plugins.credentials.CredentialsScope")
    def providerClass = cl.loadClass("com.cloudbees.plugins.credentials.CredentialsProvider")
    def systemProviderClass = cl.loadClass("com.cloudbees.plugins.credentials.SystemCredentialsProvider")
    def domainClass = cl.loadClass("com.cloudbees.plugins.credentials.domains.Domain")
    def credentialsClass = cl.loadClass("com.cloudbees.plugins.credentials.Credentials")
    def secretClass = cl.loadClass("hudson.util.Secret")

    def stringCredClass
    try {
      stringCredClass = cl.loadClass("org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl")
    } catch (Throwable ignored) {
      stringCredClass = cl.loadClass("com.cloudbees.plugins.credentials.impl.StringCredentialsImpl")
    }

    def globalDomain = domainClass.getMethod("global").invoke(null)
    def lookupStoresMethod = providerClass.getMethods().find { m ->
      m.name == "lookupStores" &&
      java.lang.reflect.Modifier.isStatic(m.modifiers) &&
      m.parameterCount == 1
    }
    if (lookupStoresMethod == null) {
      throw new NoSuchMethodException("CredentialsProvider.lookupStores(<context>)")
    }
    def stores = lookupStoresMethod.invoke(null, j)
    def store = stores.find { st -> st.provider.class.name == systemProviderClass.name }
    if (store == null && stores.size() > 0) {
      store = stores[0]
    }
    if (store == null) {
      throw new IllegalStateException("No credentials store found")
    }

    def existingCreds = store.getClass().getMethod("getCredentials", domainClass).invoke(store, globalDomain)
    def existing = existingCreds.find { it.id == credentialsId }
    if (existing != null) {
      store.removeCredentials(globalDomain, existing)
    }

    def globalScope = java.lang.Enum.valueOf(scopeClass, "GLOBAL")
    def secret = secretClass.getMethod("fromString", String).invoke(null, k8sToken)
    def ctor = stringCredClass.getConstructor(scopeClass, String, String, secretClass)
    def cred = ctor.newInstance(globalScope, credentialsId, "k3s service account token", secret)

    def addM = store.getClass().getMethod("addCredentials", domainClass, credentialsClass)
    addM.invoke(store, globalDomain, cred)

    try {
      def systemProvider = systemProviderClass.getMethod("getInstance").invoke(null)
      systemProvider.getClass().getMethod("save").invoke(systemProvider)
    } catch (Throwable ignored) {
      j.save()
    }
    tokenCredReady = true
  } catch (Throwable e) {
    println("WARN: Could not create token credential for k8s cloud: " + e.getMessage())
  }
}

cloudNames.each { name ->
  def cloud = j.clouds.getByName(name)
  if (cloud != null && !(cloud instanceof KubernetesCloud)) {
    j.clouds.remove(cloud)
    cloud = null
  }
  if (cloud == null) {
    cloud = new KubernetesCloud(name)
    j.clouds.add(cloud)
  }
  cloud.serverUrl = apiUrl
  cloud.skipTlsVerify = true
  cloud.namespace = "default"
  cloud.jenkinsUrl = jenkinsUrl
  cloud.jenkinsTunnel = jenkinsTunnel
  if (tokenCredReady) {
    cloud.credentialsId = credentialsId
  }
}

def builtIn = j.getComputer("")?.getNode()
if (builtIn == null) {
  def nodes = j.getNodes()
  if (!nodes.isEmpty()) {
    builtIn = nodes.get(0)
  }
}
if (builtIn != null && !labelTokens.isEmpty()) {
  def current = (builtIn.getLabelString() ?: "").trim()
  def set = new LinkedHashSet<String>()
  current.split("\\s+").findAll { it }.each { set.add(it) }
  labelTokens.each { set.add(it) }
  builtIn.setLabelString(set.join(" "))
}

if (requestedExecutors > j.getNumExecutors()) {
  j.setNumExecutors(requestedExecutors)
}

j.save()
return "OK clouds=" + cloudNames + " labels=" + labelTokens + " executors=" + j.getNumExecutors() + " tokenCred=" + tokenCredReady
