terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {}

resource "hcloud_network" "cluster" {
  name     = "<{ hcloud-name }>-network"
  ip_range = "<{ hcloud-network-cidr }>"

  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

resource "hcloud_network_subnet" "cluster" {
  network_id   = hcloud_network.cluster.id
  type         = "cloud"
  network_zone = "<{ hcloud-network-zone }>"
  ip_range     = "<{ hcloud-subnet-cidr }>"
}

