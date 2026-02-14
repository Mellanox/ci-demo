// GHA CI: allow API without auth (crumb disabled via JAVA_OPTS; set permissive auth)
import jenkins.model.Jenkins
import hudson.security.AuthorizationStrategy

def instance = Jenkins.getInstance()
try {
  instance.setCrumbIssuer(null)
} catch (Exception e) { /* crumb may already be disabled */ }
try {
  instance.setAuthorizationStrategy(new AuthorizationStrategy.Unsecured())
} catch (Exception e) { /* fallback if class not found */ }
instance.save()
