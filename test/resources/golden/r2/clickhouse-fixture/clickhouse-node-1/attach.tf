data "hcloud_network" "cluster" { name = "fixture-network" }
resource "hcloud_server_network" "node" {
  server_id  = hcloud_server.node1.id
  network_id = data.hcloud_network.cluster.id
  ip         = "10.20.1.11"
}
output "private-ip" { value = hcloud_server_network.node.ip }
