def call(String service, String harborRegistry,
         String harborProject, String ecrRegistry) {
  withCredentials([
    [$class: 'AmazonWebServicesCredentialsBinding',
     credentialsId: 'aws-ecr-credentials'],
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    )
  ]) {
    sh """
      set -e
      set -x

      CI_TAG=ci-\$IMAGE_TAG
      FINAL_TAG=\${TAG_NAME:-dev-\${BUILD_NUMBER}}

      # Login to Harbor
      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      # Login to ECR
      aws ecr get-login-password --region ap-south-1 \\
        | docker login --username AWS \\
          --password-stdin ${ecrRegistry}

      # Pull from Harbor
      docker pull \\
        ${harborRegistry}/${harborProject}/${service}:\${CI_TAG}

      # Tag for ECR
      docker tag \\
        ${harborRegistry}/${harborProject}/${service}:\${CI_TAG} \\
        ${ecrRegistry}/${service}:\${FINAL_TAG}

      # Push to ECR
      docker push ${ecrRegistry}/${service}:\${FINAL_TAG}

      echo "✅ Promoted: \${CI_TAG} → \${FINAL_TAG}"
    """
  }
}