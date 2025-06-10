{ pkgs ? import <nixpkgs> {} }:

let
  brepl = pkgs.callPackage ./package.nix {};
in
pkgs.mkShell {
  buildInputs = with pkgs; [
    # brepl and its dependencies
    brepl
    babashka
    
    # Development tools
    clj-kondo
    nixpkgs-fmt
    
    # For running tests
    (pkgs.writeScriptBin "test-brepl" ''
      #!${pkgs.bash}/bin/bash
      echo "Testing brepl installation..."
      brepl --version
      echo ""
      echo "Starting test nREPL server..."
      ${pkgs.babashka}/bin/bb nrepl-server &
      SERVER_PID=$!
      sleep 2
      echo ""
      echo "Testing expression evaluation..."
      brepl -p 1667 -e '(println "Hello from brepl!")'
      brepl -p 1667 -e '(+ 1 2 3)'
      echo ""
      echo "Stopping server..."
      kill $SERVER_PID
    '')
  ];
  
  shellHook = ''
    echo "Development shell for brepl"
    echo "Available commands:"
    echo "  brepl       - The nREPL client"
    echo "  bb          - Babashka"
    echo "  clj-kondo   - Linter"
    echo "  nixpkgs-fmt - Nix formatter"
    echo "  test-brepl  - Run a quick test"
  '';
}