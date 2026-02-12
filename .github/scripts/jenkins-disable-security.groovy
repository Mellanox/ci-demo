// Disable security for GHA CI only (allows API access without auth)
import jenkins.model.*
def instance = Jenkins.getInstance()
instance.disableSecurity()
instance.save()
