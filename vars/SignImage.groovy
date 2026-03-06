def call(String service, String registry, String project) {
  withCredentials([
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    ),
    file(credentialsId: 'cosign-private-key', variable: 'COSIGN_KEY'),
    string(credentialsId: 'cosign-password', variable: 'COSIGN_PASSWORD')
  ]) {
    sh """
      set -x
      IMAGE_TAG=ci-\${BUILD_NUMBER}

      echo "\$HARBOR_PASS" | docker login ${registry} \\
        -u "\$HARBOR_USER" --password-stdin

      docker pull ${registry}/${project}/${service}:\${IMAGE_TAG}

      IMAGE_DIGEST=\$(docker inspect \\
        --format='{{index .RepoDigests 0}}' \\
        ${registry}/${project}/${service}:\${IMAGE_TAG} \\
        | cut -d'@' -f2)

      echo "Signing digest: \$IMAGE_DIGEST"

      COSIGN_PASSWORD=\$COSIGN_PASSWORD \\
      cosign sign \\
        --key \$COSIGN_KEY \\
        --allow-insecure-registry \\
        --allow-http-registry \\
        --yes \\
        ${registry}/${project}/${service}@\${IMAGE_DIGEST}
    """
  }
}