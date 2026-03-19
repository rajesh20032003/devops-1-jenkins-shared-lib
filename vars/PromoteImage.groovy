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
      echo "running"
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
      COSIGN_INSECURE_IGNORE_SCTS=1 \
COSIGN_DOCKER_MEDIA_TYPES=1 \
cosign copy \
  --allow-insecure-registry \
  --allow-http-registry \
  --registry-username "$HARBOR_USER" \
  --registry-password "$HARBOR_PASS" \
  34.133.110.141:80/micro-dash/frontend:${CI_TAG} \
  760302898980.dkr.ecr.ap-south-1.amazonaws.com/frontend:${FINAL_TAG}

      echo "✅ Promoted with signatures: \${CI_TAG} → \${FINAL_TAG}"
    """
  }
}