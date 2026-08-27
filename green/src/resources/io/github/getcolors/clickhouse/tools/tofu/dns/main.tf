terraform {
  required_providers {
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.0" }
  }
}
provider "cloudflare" {}
data "cloudflare_zone" "domain" { filter = { name = "<{ domain }>" } }

locals {
  records = {
    "metabase.<{ domain }>" = "10.21.0.10"
    "clickhouse.<{ domain }>" = "10.21.0.1"
    "node-1.clickhouse.<{ domain }>" = "10.21.0.1"
    "node-2.clickhouse.<{ domain }>" = "10.21.0.2"
    "node-3.clickhouse.<{ domain }>" = "10.21.0.3"
  }
}
resource "cloudflare_dns_record" "wireguard" {
  for_each = local.records
  zone_id  = data.cloudflare_zone.domain.id
  name      = each.key
  content   = each.value
  type      = "A"
  ttl       = 300
  proxied   = false
}
