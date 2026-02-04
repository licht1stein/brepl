{ lib
, stdenv
, makeWrapper
, babashka
}:

stdenv.mkDerivation rec {
  pname = "brepl";
  version = "2.6.2";

  src = ./.;

  nativeBuildInputs = [ makeWrapper ];

  dontBuild = true;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/bin
    cp brepl $out/bin/brepl
    chmod +x $out/bin/brepl

    # Replace shebang with simple version (env -S not reliable in Nix)
    sed -i '1s|#!/usr/bin/env.*|#!/usr/bin/env bb|' $out/bin/brepl

    # Wrap to ensure babashka is on PATH and isolate from local bb.edn
    wrapProgram $out/bin/brepl \
      --prefix PATH : ${lib.makeBinPath [ babashka ]} \
      --set BABASHKA_CLASSPATH ""

    runHook postInstall
  '';

  meta = with lib; {
    description = "Bracket-fixing REPL";
    longDescription = ''
      brepl (Bracket-fixing REPL) enables AI-assisted Clojure development by solving
      the notorious parenthesis problem. It validates syntax using Babashka's built-in
      parser and intelligently fixes bracket errors with parmezan—because AI agents
      shouldn't struggle with Lisp parentheses. Provides automatic syntax validation,
      bracket auto-fix, and REPL synchronization. Also works as a fast nREPL client for
      command-line evaluations, file loading, and scripting workflows.
    '';
    homepage = "https://github.com/licht1stein/brepl";
    changelog = "https://github.com/licht1stein/brepl/releases/tag/v${version}";
    license = licenses.mpl20;
    maintainers = with maintainers; [ ]; # Add your nixpkgs maintainer name here
    platforms = babashka.meta.platforms;
    mainProgram = "brepl";
  };
}