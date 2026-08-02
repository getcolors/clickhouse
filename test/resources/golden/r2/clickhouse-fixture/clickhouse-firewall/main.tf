terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {}

data "hcloud_server" "node_1" {
  name = "fixture-node-1"
}
data "hcloud_server" "node_2" {
  name = "fixture-node-2"
}
data "hcloud_server" "node_3" {
  name = "fixture-node-3"
}
data "hcloud_server" "metabase" {
  name = "fixture-metabase"
}

resource "hcloud_firewall" "services" {
  name = "fixture-services-firewall"

  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "22"
    source_ips = ["0.0.0.0/0", "::/0"]
  }
  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "51820"
    source_ips = ["0.0.0.0/0", "::/0"]
  }

  apply_to {
    server = data.hcloud_server.node_1.id
  }
  apply_to {
    server = data.hcloud_server.node_2.id
  }
  apply_to {
    server = data.hcloud_server.node_3.id
  }
  apply_to {
    server = data.hcloud_server.metabase.id
  }

  lifecycle {
    prevent_destroy = true
  }
}
