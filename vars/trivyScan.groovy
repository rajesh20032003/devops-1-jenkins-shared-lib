def call(String service, String harborRegistry, String harborProject) {
  withCredentials([
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    )
  ]) {
    sh """
      set -x
      SERVICE=${service}

      trivy image \\
        --scanners vuln \\
        --exit-code 1 \\
        --severity CRITICAL \\
        --skip-version-check \\
        --image-src remote \\
        --insecure \\
        --cache-dir /tmp/trivy-cache-${service} \\
        --username \$HARBOR_USER \\
        --password \$HARBOR_PASS \\
        ${harborRegistry}/${harborProject}/\${SERVICE}:\$IMAGE_TAG
    """
  }
}