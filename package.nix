{ lib
, stdenv
, makeWrapper
, babashka
, parinfer-rust
}:

stdenv.mkDerivation rec {
  pname = "brepl";
  version = "2.0.2";

  src = ./.;

  nativeBuildInputs = [ makeWrapper ];

  dontBuild = true;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/brepl $out/bin
    cp brepl $out/share/brepl/
    cp -r lib $out/share/brepl/
    chmod +x $out/share/brepl/brepl

    makeWrapper $out/share/brepl/brepl $out/bin/brepl \
      --prefix PATH : ${lib.makeBinPath [ babashka parinfer-rust ]}

    runHook postInstall
  '';

  meta = with lib; {
    description = "Lightweight REPL-driven development for Clojure with AI coding agents";
    longDescription = ''
      brepl enables AI-assisted Clojure development using your existing nREPL
      connection and Babashka's built-in parser. Provides automatic syntax
      validation, bracket auto-fix, and REPL synchronization with zero external
      dependencies. Also works as a fast nREPL client for command-line evaluations,
      file loading, and scripting workflows.
    '';
    homepage = "https://github.com/licht1stein/brepl";
    changelog = "https://github.com/licht1stein/brepl/releases/tag/v${version}";
    license = licenses.mpl20;
    maintainers = with maintainers; [ ]; # Add your nixpkgs maintainer name here
    platforms = babashka.meta.platforms;
    mainProgram = "brepl";
  };
}