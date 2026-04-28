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
      set -e
      set -x

      # FIX: Access the environment variable securely. 
      # We use \$ to let Bash handle it, or just use the env name.
      CI_TAG=\$IMAGE_TAG
      FINAL_TAG=\$IMAGE_TAG-PROD

      # Login to Harbor
      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      # Login to ECR
      aws ecr get-login-password --region ap-south-1 \\
        | docker login --username AWS \\
          --password-stdin ${ecrRegistry}

      # Pull from Harbor - Notice the backslash before CI_TAG
      docker pull ${harborRegistry}/${harborProject}/${service}:\${CI_TAG}

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