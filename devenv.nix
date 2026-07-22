{ pkgs, lib, config, inputs, ... }:

let 
  unstable = import inputs.nixpkgs-unstable {
    system = pkgs.stdenv.system;
  };
in

{
  name = "cebelica-gateway";

  env = let
  in {
    JAVA_OPTS="--enable-native-access=ALL-UNNAMED ";
    SBT_OPTS="--enable-native-access=ALL-UNNAMED ";
    KUBECONFIG = "ogrodje-one-config";
  };

  languages.java.jdk.package = unstable.jdk25_headless;
  languages.scala = {
    enable = true;
    mill.enable = true;
    mill.package = unstable.mill;
    lsp.enable = true;
  };

  packages = [
    pkgs.k9s
    pkgs.kustomize
    pkgs.kubectl
    pkgs.kubectx
    pkgs.kubernetes-helm
  ];

  enterShell = ''
    echo "~~~ cebelica-biz ~~~"
    kubens cebelca-prod || echo "kubens change failed."
  '';
}
