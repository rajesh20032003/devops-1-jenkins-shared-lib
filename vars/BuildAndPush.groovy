/**
 * buildAndPush(service, harborRegistry, harborProject)
 *
 * Builds a Docker image using an isolated buildx builder and pushes to Harbor.
 * Always cleans up the builder even if build or push fails.
 *
 * @param service        - service folder name (e.g. 'frontend', 'gateway')
 * @param harborRegistry - Harbor host (e.g. '34.180.10.118')
 * @param harborProject  - Harbor project (e.g. 'micro-dash')
 */
def call(String service, String harborRegistry, String harborProject) {
  // FIX 1: builderName defined as Groovy variable so finally block can use it
  def builderName = "ci-builder-${service}-${env.BUILD_NUMBER}"

  withCredentials([
    usernamePassword(
      credentialsId: 'harbor-credential',
      usernameVariable: 'HARBOR_USER',
      passwordVariable: 'HARBOR_PASS'
    )
  ]) {
    // FIX 2: try block added — wraps withCredentials body
    try {
      sh """
        set -x


        echo "\$HARBOR_PASS" | docker login ${harborRegistry} \\
          -u "\$HARBOR_USER" --password-stdin

        # Write insecure-registry config for BuildKit
        mkdir -p /tmp/buildkit
        cat > /tmp/buildkit/buildkitd-${service}.toml << 'TOML'
[registry."${harborRegistry}"]
  http = true
  insecure = true
TOML

        # Remove any stale builder metadata
        rm -rf \$HOME/.docker/buildx/instances/${builderName} || true
        rm -rf \$HOME/.docker/buildx/activity/${builderName}  || true
        rm -rf \$HOME/.docker/buildx/refs/${builderName}      || true
        docker rm -f buildx_buildkit_${builderName}0          || true

        docker buildx create \\
          --name ${builderName} \\
          --driver docker-container \\
          --driver-opt network=host \\
          --config /tmp/buildkit/buildkitd-${service}.toml \\
          --use

        docker buildx inspect --bootstrap

        docker buildx build \\
          --builder ${builderName} \\
          --cache-from=type=registry,ref=${harborRegistry}/${harborProject}/${service}:buildcache \\
          --cache-to=type=registry,ref=${harborRegistry}/${harborProject}/${service}:buildcache,mode=max \\
          -t ${harborRegistry}/${harborProject}/${service}:${env.IMAGE_TAG} \\
          --push \\
          ./${service}

        echo "Pushed ${harborRegistry}/${harborProject}/${service}:\${IMAGE_TAG}"
      """
    } finally {
      // FIX 3: finally is inside withCredentials, uses Groovy builderName variable
      sh """
        docker buildx rm ${builderName} --force || true
        docker volume rm buildx_buildkit_${builderName}_state || true
        echo "Cleaned up builder: ${builderName}"
      """
    }
  }
}