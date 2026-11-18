"""Verify the release credential lifecycle without contacting Google Play."""

import base64
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
PROBE = """
gradle.projectsEvaluated {
    def app = rootProject.project(':app')
    def temp = new File(System.getProperty('java.io.tmpdir'))
    def credentialDirs = { temp.listFiles().findAll { it.name.startsWith('droidflyer-release-') } }
    def assertPrivate = { File file, String permissions ->
        assert file.isFile() || file.isDirectory()
        if (file.toPath().fileSystem.supportedFileAttributeViews().contains('posix')) {
            assert java.nio.file.attribute.PosixFilePermissions.toString(
                    java.nio.file.Files.getPosixFilePermissions(file.toPath())) == permissions
        }
    }
    app.tasks.register('checkNoReleaseCredentials') {
        doLast {
            assert credentialDirs().empty
            assert !app.android.signingConfigs.release.storeFile.exists()
            assert !app.play.serviceAccountCredentials.get().asFile.exists()
        }
    }
    app.tasks.named('validateSigningRelease') {
        doFirst {
            def key = app.android.signingConfigs.release.storeFile
            assertPrivate(key, 'rw-------')
            assertPrivate(key.parentFile, 'rwx------')
            assert !new File(key.parentFile, 'play-account.json').exists()
        }
        doLast {
            if (app.hasProperty('failCredentialTest')) throw new GradleException('Intentional signing failure')
        }
    }
    app.tasks.named('bootstrapReleaseListing') {
        // Keep the plugin's task inputs and use a local read in place of its API call.
        actions = []
        doLast {
            def account = app.play.serviceAccountCredentials.get().asFile
            assertPrivate(account, 'rw-------')
            assertPrivate(account.parentFile, 'rwx------')
            assert account.text == '{"dummy":true}'
            assert !new File(account.parentFile, 'release.jks').exists()
        }
    }
}
"""


def run_gradle(temp, env, *args, expected_failure=None):
    result = subprocess.run(
        [
            str(ROOT / "gradlew"), "--console=plain", "--no-configuration-cache",
            f"-Dorg.gradle.jvmargs=-Xmx2048m -Djava.io.tmpdir={temp}",
            "-I", str(temp / "probe.gradle"), *args,
        ],
        cwd=ROOT, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    print(result.stdout, end="", flush=True)
    assert not list(temp.glob("droidflyer-release-*")), "Temporary credentials remain after Gradle exited"
    if expected_failure:
        assert result.returncode != 0 and expected_failure in result.stdout
    else:
        result.check_returncode()


def main():
    with tempfile.TemporaryDirectory() as scratch:
        temp = Path(scratch)
        key = temp / "test.jks"
        subprocess.run(
            [
                "keytool", "-genkeypair", "-keystore", str(key), "-storetype", "JKS",
                "-storepass", "test-password", "-keypass", "test-password", "-alias", "test",
                "-keyalg", "RSA", "-dname", "CN=Credential Lifecycle Test",
            ],
            check=True,
        )
        (temp / "probe.gradle").write_text(PROBE, encoding="utf-8")
        env = dict(
            os.environ,
            RELEASE_KEYSTORE_BASE64=base64.b64encode(key.read_bytes()).decode(),
            RELEASE_KEYSTORE_PASSWORD="test-password",
            RELEASE_KEYSTORE_KEY_PASSWORD="test-password",
            RELEASE_KEYSTORE_KEY_ALIAS="test",
            PLAY_ACCOUNT_JSON_BASE64=base64.b64encode(b'{"dummy":true}').decode(),
        )
        run_gradle(temp, env, ":app:checkNoReleaseCredentials")
        run_gradle(
            temp, env, ":app:validateSigningRelease", "--rerun-tasks", "-PfailCredentialTest",
            expected_failure="Intentional signing failure",
        )
        run_gradle(temp, env, ":app:bootstrapReleaseListing", "--rerun-tasks")
        run_gradle(temp, env, ":app:assembleRelease")


if __name__ == "__main__":
    main()
