// Create ci-demo Pipeline job from ref job-config.b64 (base64 of substituted config.xml), copied to JENKINS_HOME
import jenkins.model.Jenkins
import java.nio.charset.StandardCharsets

def logFile = new File(Jenkins.getInstance().getRootDir(), "init-create-job.log")
def log = { String msg -> logFile.append(msg + "\n") }
try {
  def instance = Jenkins.getInstance()
  def rootDir = instance.getRootDir()
  log("rootDir=" + rootDir)
  def configFile = new File(rootDir, "job-config.b64")
  log("configFile.exists=" + configFile.exists())
  if (!configFile.exists()) {
    log("GHA CI: job-config.b64 not found, listing rootDir: " + rootDir.list()?.toList())
    return
  }
  def b64 = configFile.text.replaceAll("\\s", "")
  log("b64.length=" + b64.length())
  def xml = new String(java.util.Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)
  log("xml.length=" + xml.length())
  if (instance.getItem("ci-demo") != null) {
    instance.getItem("ci-demo").delete()
  }
  instance.createProjectFromXML("ci-demo", new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
  log("GHA CI: created job ci-demo")
} catch (Exception e) {
  def sw = new StringWriter()
  e.printStackTrace(new PrintWriter(sw))
  log("ERROR: " + e.getClass().getName() + ": " + e.message)
  log(sw.toString())
}
