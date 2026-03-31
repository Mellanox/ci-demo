// Create ci-demo job from job-config.xml in JENKINS_HOME (copied in by workflow)
// All logic in one scope so script console sees variables (no def method).
import jenkins.model.Jenkins

def j = Jenkins.get()
def f = new File(j.getRootDir(), "job-config.xml")
if (!f.exists()) {
  print "ERROR: job-config.xml not found in " + j.getRootDir()
  return
}

try {
  if (j.getItem("ci-demo") != null) {
    j.getItem("ci-demo").delete()
    Thread.sleep(3000)
  }
  def stream = new FileInputStream(f)
  try {
    j.createProjectFromXML("ci-demo", stream)
  } finally {
    stream.close()
  }
  print "OK"
} catch (java.io.IOException e) {
  print "Retry after: " + e.message
  Thread.sleep(3000)
  if (j.getItem("ci-demo") != null) {
    j.getItem("ci-demo").delete()
    Thread.sleep(2000)
  }
  def stream2 = new FileInputStream(f)
  try {
    j.createProjectFromXML("ci-demo", stream2)
  } finally {
    stream2.close()
  }
  print "OK"
} catch (Exception e) {
  print "EXCEPTION: " + e.getClass().getName() + ": " + e.message
  e.printStackTrace()
}
