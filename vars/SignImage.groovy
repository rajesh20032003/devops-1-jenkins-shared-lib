def call(String service, String ecrRegistry) {
  withCredentials([
    [$class: 'AmazonWebServicesCredentialsBinding',
     credentialsId: 'aws-ecr-credentials'],
    file(credentialsId: 'cosign-private-key',
         variable: 'COSIGN_KEY'),
    string(credentialsId: 'cosign-password',
           variable: 'COSIGN_PASSWORD')
  ]) {
    sh """
      set -x
      FINAL_TAG=\${TAG_NAME:-dev-\${BUILD_NUMBER}}

      # Login to ECR
      aws ecr get-login-password --region ap-south-1 \\
        | docker login --username AWS \\
          --password-stdin ${ecrRegistry}

      # Pull to get digest
      docker pull ${ecrRegistry}/${service}:\${FINAL_TAG}

      IMAGE_DIGEST=\$(docker inspect \\
        --format='{{index .RepoDigests 0}}' \\
        ${ecrRegistry}/${service}:\${FINAL_TAG} \\
        | cut -d'@' -f2)

      echo "Signing ECR image: \$IMAGE_DIGEST"

      # Sign ECR image!
      COSIGN_PASSWORD=\$COSIGN_PASSWORD \\
      cosign sign \\
        --key \$COSIGN_KEY \\
        --yes \\
        ${ecrRegistry}/${service}@\${IMAGE_DIGEST}

      echo "✅ ${service} signed in ECR!"
    """
  }
}