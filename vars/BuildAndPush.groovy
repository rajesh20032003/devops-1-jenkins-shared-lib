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
      IMAGE_TAG=ci-\${BUILD_NUMBER}
      BUILDER_NAME=ci-builder-\${SERVICE}-\${BUILD_NUMBER}

      echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
        -u "\$HARBOR_USER" --password-stdin

      # Write insecure-registry config for BuildKit
      mkdir -p /tmp/buildkit
      cat > /tmp/buildkit/buildkitd-\${SERVICE}.toml << 'TOML'
[registry."${harborRegistry}"]
  http = true
  insecure = true
TOML

      # Remove any stale builder metadata
      rm -rf \$HOME/.docker/buildx/instances/\${BUILDER_NAME}  || true
      rm -rf \$HOME/.docker/buildx/activity/\${BUILDER_NAME}   || true
      rm -rf \$HOME/.docker/buildx/refs/\${BUILDER_NAME}       || true
      docker rm -f buildx_buildkit_\${BUILDER_NAME}0            || true

      docker buildx create \\
        --name \$BUILDER_NAME \\
        --driver docker-container \\
        --driver-opt network=host \\
        --config /tmp/buildkit/buildkitd-\${SERVICE}.toml \\
        --use

      docker buildx inspect --bootstrap

      docker buildx build \\
        --builder \$BUILDER_NAME \\
        --cache-from=type=registry,ref=${harborRegistry}/${harborProject}/\${SERVICE}:buildcache \\
        --cache-to=type=registry,ref=${harborRegistry}/${harborProject}/\${SERVICE}:buildcache,mode=max \\
        -t ${harborRegistry}/${harborProject}/\${SERVICE}:\$IMAGE_TAG \\
        --push \\
        ./\${SERVICE}
    """
  }
}
