// GHA CI: allow API without auth (crumb disabled via JAVA_OPTS; set permissive auth)
import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration
import hudson.security.AuthorizationStrategy

def instance = Jenkins.getInstance()
// Jenkins 2.54x+ CLI handshake requires a configured root URL (X-CLI-Error otherwise).
def jenkinsUrl = (System.getenv("JENKINS_LOCAL_ROOT_URL") ?: "http://localhost:8080").trim()
if (jenkinsUrl) {
  try {
    def loc = JenkinsLocationConfiguration.get()
    if (loc != null) {
      loc.setUrl(jenkinsUrl)
      loc.save()
    }
  } catch (Exception e) {
    println("WARN: Could not set Jenkins URL: " + e.getMessage())
  }
}
try {
  instance.setCrumbIssuer(null)
} catch (Exception e) { /* crumb may already be disabled */ }
try {
  instance.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured())
} catch (Exception e) { /* fallback if class not found */ }
instance.save()
