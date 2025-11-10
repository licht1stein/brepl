{ lib
, stdenv
, makeWrapper
, babashka
}:

stdenv.mkDerivation rec {
  pname = "brepl";
  version = "1.4.0";

  src = ./.;

  nativeBuildInputs = [ makeWrapper ];

  dontBuild = true;

  installPhase = ''
    runHook preInstall
    
    mkdir -p $out/bin
    cp brepl $out/bin/brepl
    chmod +x $out/bin/brepl
    
    # Wrap to ensure babashka is in PATH
    wrapProgram $out/bin/brepl \
      --prefix PATH : ${lib.makeBinPath [ babashka ]}
    
    runHook postInstall
  '';

  meta = with lib; {
    description = "Fast, lightweight nREPL client for one-shot interactions with any nREPL server";
    longDescription = ''
      brepl is a lightweight Babashka-based nREPL client designed for quick,
      one-shot interactions with Clojure nREPL servers. It supports expression
      evaluation, file loading, and sending raw nREPL messages, making it
      perfect for scripting, editor integration, and command-line workflows.
    '';
    homepage = "https://github.com/licht1stein/brepl";
    changelog = "https://github.com/licht1stein/brepl/releases/tag/v${version}";
    license = licenses.mpl20;
    maintainers = with maintainers; [ ]; # Add your nixpkgs maintainer name here
    platforms = babashka.meta.platforms;
    mainProgram = "brepl";
  };
}