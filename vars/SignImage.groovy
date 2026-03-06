def call(String service, String harborRegistry, String harborProject) {
  withCredentials([
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    ),
    file(credentialsId: 'cosign-private-key', variable: 'COSIGN_KEY'),
    string(credentialsId: 'cosign-password',  variable: 'COSIGN_PASSWORD')
  ]) {
    sh """
      set -x
      SERVICE=${service}
      IMAGE_TAG=ci-\${BUILD_NUMBER}
      echo "from shared lib"

      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      docker pull ${harborRegistry}/${harborProject}/\${SERVICE}:\$IMAGE_TAG

      IMAGE_DIGEST=\$(docker inspect \\
        --format='{{index .RepoDigests 0}}' \\
        ${harborRegistry}/${harborProject}/\${SERVICE}:\$IMAGE_TAG \\
        | cut -d'@' -f2)

      echo "Signing digest: \$IMAGE_DIGEST"

      COSIGN_PASSWORD=\$COSIGN_PASSWORD \\
      cosign sign \\
        --key \$COSIGN_KEY \\
        --allow-insecure-registry \\
        --allow-http-registry \\
        --yes \\
        ${harborRegistry}/${harborProject}/\${SERVICE}@\$IMAGE_DIGEST
    """
  }
}