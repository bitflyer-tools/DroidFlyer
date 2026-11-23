require "yaml"

config = YAML.safe_load_file(File.expand_path("../../.circleci/config.yml", __dir__), aliases: true)
build, test, deploy = config.fetch("jobs").values_at("build", "test", "deploy")
images = [build, test, deploy].map { |job| job.fetch("docker").first.fetch("image") }
abort "Build, test, and deploy must use the same pinned Android image" unless
  images.uniq.size == 1 && images.first.match?(/\Acimg\/android:\d{4}\.\d{2}\.\d+\z/)

abort "Expected the APK build to use the large resource class" unless build.fetch("resource_class") == "large"

build_steps = build.fetch("steps")
assemble_step = build_steps.index do |step|
  step.is_a?(Hash) && step.dig("run", "command") == "./gradlew assembleDebug assembleDebugUnitTest"
end
abort "Expected the build job to assemble the debug APK and unit test outputs" unless assemble_step

saved_caches = build_steps.drop(assemble_step + 1).grep(Hash).filter_map { |step| step["save_cache"] }
saves_by_path = saved_caches.to_h { |cache| [cache.fetch("paths"), cache.fetch("key")] }

expected_keys = {
  ["build"] => "builds-{{ .Revision }}",
  ["app/build"] => "app-builds-{{ .Revision }}"
}
expected_keys.each do |paths, key|
  abort "Expected #{paths.join(', ')} to be saved with #{key}" unless saves_by_path[paths] == key
end

abort "Build and test must share a working directory" unless build.fetch("working_directory") == test.fetch("working_directory")

test_steps = test.fetch("steps")
abort "Expected the test job to restore caches after checkout and before running Gradle" unless
  test_steps.take(2) == ["checkout", "restore_gradle_cache"] &&
  test_steps.fetch(2).fetch("run").fetch("command") == "./gradlew jacoco"

restore_steps = config.fetch("commands").fetch("restore_gradle_cache").fetch("steps")
restored_keys = restore_steps.map { |step| step.fetch("restore_cache").fetch("key") }
dependency_key = saves_by_path.fetch(["~/.gradle"])
dependency_inputs = %w[gradle/wrapper/gradle-wrapper.properties settings.gradle build.gradle app/build.gradle app/gradle.lockfile]
repo_root = File.expand_path("../..", __dir__)
cache_inputs = dependency_key.scan(/\{\{ checksum "([^"]+)" \}\}/).flatten
abort "Expected the Gradle dependency cache key to include each dependency input" unless
  cache_inputs == dependency_inputs && cache_inputs.all? { |path| File.file?(File.join(repo_root, path)) }

expected_restores = [dependency_key, *expected_keys.values]
abort "Expected each Gradle cache to be restored independently" unless restored_keys == expected_restores

workflow_jobs = config.fetch("workflows").fetch("test-and-deploy").fetch("jobs")
test_job = workflow_jobs.grep(Hash).find { |job| job.key?("test") }
abort "Expected the test job to wait for the build job" unless test_job.fetch("test").fetch("requires").include?("build")

puts "CircleCI Gradle cache configuration passed"
