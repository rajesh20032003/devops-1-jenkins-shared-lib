def call(String service, String harborRegistry, String harborProject, String ecrRegistry) {
  withCredentials([
    [$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-ecr-credentials'],
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    )
  ]) {
    sh """
      set -x
      CI_TAG=ci-\${BUILD_NUMBER}
      FINAL_TAG=\${TAG_NAME:-dev-\${BUILD_NUMBER}}

      # Login to Harbor
      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      # Login to ECR
      aws ecr get-login-password --region ap-south-1 \\
        | docker login --username AWS \\
          --password-stdin ${ecrRegistry}

      # cosign copy with HTTP registry support
      COSIGN_INSECURE_IGNORE_SCTS=1 cosign copy \\
        --allow-insecure-registry \\
        --allow-http-registry \\
        34.133.110.141:80/${harborProject}/${service}:\${CI_TAG} \\
        ${ecrRegistry}/${service}:\${FINAL_TAG}

      echo "✅ Promoted with signatures: \${CI_TAG} → \${FINAL_TAG}"
    """
  }
}