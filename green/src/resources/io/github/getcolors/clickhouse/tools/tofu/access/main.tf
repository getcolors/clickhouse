terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {}

resource "hcloud_ssh_key" "managed" {
  name       = "<{ managed-ssh-key-name }>"
  public_key = "<{ managed-ssh-public-key }>"

  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}
