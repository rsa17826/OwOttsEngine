{
  pkgs ? import <nixpkgs> { },
}:

pkgs.mkShell {
  buildInputs = with pkgs; [
    android-tools
    jdk17
    gradle
  ];

  shellHook = ''
        export JAVA_HOME="${pkgs.jdk17.home}"

        # 1. Create a local, writable Android SDK directory
        export ANDROID_HOME="$PWD/.android-sdk"
        mkdir -p "$ANDROID_HOME/licenses"

        # 2. Write exact, clean license hashes (one per line)
        cat << 'EOF' > "$ANDROID_HOME/licenses/android-sdk-license"
    89354db4fff08430774280d81fda538d5500c996
    d56f11ed6d5b31090f34dbd9738effb6de769021
    d975f251698a7e2e340a50f4c010b480e9212d61
    24333f8a63b6825ea9c5514f83c2829b004d1fee
    EOF

        cat << 'EOF' > "$ANDROID_HOME/licenses/android-sdk-preview-license"
    84831b9409646a918e30573bab4c9c91346d8abd
    g5027c5865612653fc08521328db9968a3215647
    EOF

        # 3. Symlink Nix's android-tools into our local ANDROID_HOME so Gradle can find 'sdkmanager' and tools
        # We use 'ln -sf' to avoid errors if they already exist
        ln -sf ${pkgs.android-tools}/libexec/android-sdk/platform-tools "$ANDROID_HOME/platform-tools"
        ln -sf ${pkgs.android-tools}/libexec/android-sdk/tools "$ANDROID_HOME/tools"

        echo "Android SDK licenses and tools re-configured in $ANDROID_HOME"
  '';
}
# nix-shell -p jdk17 --run "export JAVA_HOME=\$(dirname \$(dirname \$(which java))); ANDROID_HOME=\$HOME/local-android-sdk ./gradlew build -x lint -x lintAnalyzeDebug -x lintAnalyzeRelease"
