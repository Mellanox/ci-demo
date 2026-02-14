// GHA CI: add "master" label and enough executors so pipeline + Matrix parallel can run on master
import jenkins.model.Jenkins
import hudson.slaves.EnvironmentVariablesNodeProperty
import hudson.slaves.EnvironmentVariablesNodeProperty.Entry

def j = Jenkins.getInstance()
// Ensure PATH includes /usr/bin so docker CLI is findable in sh steps (Docker Pipeline plugin)
def globalProps = j.getGlobalNodeProperties()
def pathPrefix = '/opt/docker-bin:/usr/bin:/bin:/usr/local/bin'
def envProps = globalProps.getAll(EnvironmentVariablesNodeProperty.class)
if (!envProps.isEmpty()) {
  def env = envProps.get(0).getEnvVars()
  def current = env.get('PATH') ?: System.getenv('PATH') ?: ''
  if (current == null || !current.startsWith(pathPrefix)) {
    env.put('PATH', pathPrefix + ':' + (current ?: ''))
    j.save()
  }
} else {
  def current = System.getenv('PATH') ?: ''
  globalProps.add(new EnvironmentVariablesNodeProperty([ new Entry('PATH', pathPrefix + ':' + current) ]))
  j.save()
}
// Need several executors: main pipeline node + Matrix inner node + parallel matrix branches (e.g. docker build)
j.setNumExecutors(4)
j.save()

def builtIn = j.getComputer("")?.getNode()
if (builtIn == null) {
  def nodes = j.getNodes()
  if (!nodes.isEmpty()) builtIn = nodes.get(0)
}
if (builtIn != null) {
  def labels = (builtIn.getLabelString() ?: "").trim()
  if (!labels.split().contains("master")) {
    builtIn.setLabelString(labels ? labels + " master" : "master")
    j.save()
  }
}
