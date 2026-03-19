def call(String service, String harborRegistry, String harborProject, String ecrRegistry) {
  // Use the standard AWS credentials wrapper for the ECR side
  withAWS(credentials: 'aws-ecr-credentials', region: 'ap-south-1') {
    withCredentials([
      usernamePassword(
        credentialsId: 'harbor-credential',
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
      )
    ]) {
      sh """
        set -e
        set -x

        # Define tags
        CI_TAG="ci-${BUILD_NUMBER}"
        FINAL_TAG="${TAG_NAME ?: "dev-${BUILD_NUMBER}"}"

        # 1. Login to Harbor (HTTP)
        echo "\$HARBOR_PASS" | docker login ${harborRegistry} \
          -u "\$HARBOR_USER" --password-stdin

        # 2. Login to ECR
        aws ecr get-login-password --region ap-south-1 \
          | docker login --username AWS \
            --password-stdin ${ecrRegistry}

        # 3. Promote using Cosign Copy
        # We set DOCKER_CONFIG to ensure cosign finds the logins we just did
        export DOCKER_CONFIG="\$HOME/.docker"

        cosign copy --allow-http-registry \
          --allow-insecure-registry \
          34.133.110.141:80/${harborProject}/${service}:\${CI_TAG} \
          ${ecrRegistry}/${service}:\${FINAL_TAG}

        echo "✅ Promoted and Signed Artifact Copied: \${CI_TAG} → \${FINAL_TAG}"
      """
    }
  }
}