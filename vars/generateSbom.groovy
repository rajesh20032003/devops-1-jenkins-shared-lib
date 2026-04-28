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
      echo "from shared-lib"
      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      SYFT_REGISTRY_INSECURE_USE_HTTP=true \\
      SYFT_REGISTRY_AUTH_USERNAME=\$HARBOR_USER \\
      SYFT_REGISTRY_AUTH_PASSWORD=\$HARBOR_PASS \\
      SYFT_REGISTRY_AUTH_AUTHORITY=${harborRegistry} \\
      syft ${harborRegistry}/${harborProject}/\${SERVICE}:\$IMAGE_TAG \\
        -o cyclonedx-json \\
        > sbom-\${SERVICE}.json
    """
    archiveArtifacts artifacts: "sbom-${service}.json", allowEmptyArchive: true
    stash name: "sbom-${service}",
          includes: "sbom-${service}.json",
          allowEmpty: true
  }
}