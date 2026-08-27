data "hcloud_network" "cluster" { name = "<{ network-name }>" }
resource "hcloud_server_network" "node" {
  server_id  = hcloud_server.node1.id
  network_id = data.hcloud_network.cluster.id
  ip         = "<{ private-ip }>"
}
output "private-ip" { value = hcloud_server_network.node.ip }
