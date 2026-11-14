#!/usr/bin/env bash
# Runs every tests/*_test.groovy from the repository root with the Groovy that
# the Jenkins war bundles, which is the Groovy that Jenkins runs this library with.
# scripts/local_gha_ci.sh runs it in the CI Jenkins image. To run it elsewhere,
# set GROOVY_JAR to a Groovy jar (and JAVA, if java is not on PATH).
set -euo pipefail

java_bin=${JAVA:-java}
groovy_jar=${GROOVY_JAR:-}
tmp_dir=""
trap '[[ -z "${tmp_dir}" ]] || rm -rf "${tmp_dir}"' EXIT

if [[ -z "${groovy_jar}" ]]; then
  war=${JENKINS_WAR:-/usr/share/jenkins/jenkins.war}
  tmp_dir=$(mktemp -d)
  groovy_jar="${tmp_dir}/groovy.jar"
  python3 - "${war}" "${groovy_jar}" <<'EOF'
import re, shutil, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as war:
    jars = [n for n in war.namelist() if re.match(r"WEB-INF/lib/groovy(-all)?-[0-9][^/]*\.jar$", n)]
    if len(jars) != 1:
        sys.exit("expected one Groovy jar in %s, found %s" % (sys.argv[1], jars))
    print("Using %s from %s" % (jars[0], sys.argv[1]))
    with war.open(jars[0]) as src, open(sys.argv[2], "wb") as dst:
        shutil.copyfileobj(src, dst)
EOF
fi

shopt -s nullglob
tests=(tests/*_test.groovy)
if [[ "${#tests[@]}" -eq 0 ]]; then
  echo "ERROR: no tests/*_test.groovy found in $(pwd)" >&2
  exit 1
fi

status=0
for t in "${tests[@]}"; do
  echo "== ${t}"
  "${java_bin}" -cp "${groovy_jar}" groovy.ui.GroovyMain "${t}" || status=1
done
exit "${status}"
