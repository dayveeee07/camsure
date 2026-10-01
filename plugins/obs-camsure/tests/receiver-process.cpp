// SPDX-License-Identifier: GPL-2.0-or-later
// Uses the real packaged receiver; no physical phone or OBS frontend involved.
#include <winsock2.h>
#include <windows.h>
#include "receiver-process.hpp"
#include <cstdio>
#include <stdexcept>
#include <memory>
static void require(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
static void wait(camsure::ReceiverProcess &process, bool listening) {
 for (int i = 0; i < 160; ++i) {
  if (listening ? process.listening() : process.finished()) return;
  if (listening && process.finished()) throw std::runtime_error(process.status());
  Sleep(50);
 }
 throw std::runtime_error("Receiver lifecycle deadline exceeded: " + process.status());
}
int main(int argc, char **argv) {
 try {
  require(argc == 2, "Provide packaged receiver executable");
  WSADATA data{}; require(WSAStartup(MAKEWORD(2, 2), &data) == 0, "Winsock init");
  auto adapters = camsure::receiver_adapters(); require(!adapters.empty(), "An up LAN adapter is needed for host tests");
  SOCKET socket = ::socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
  require(socket != INVALID_SOCKET, "Reserve port socket");
  sockaddr_in address{}; address.sin_family = AF_INET;
  require(bind(socket, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0, "Reserve test port");
  int length = sizeof(address); require(getsockname(socket, reinterpret_cast<sockaddr *>(&address), &length) == 0, "Get test port");
  camsure::ReceiverConfig config; config.adapter_key = adapters.front().key;
  config.port = ntohs(address.sin_port); config.pipe = "camsure-process-test";
  closesocket(socket);
  {
   camsure::ReceiverProcess first(argv[1], config); wait(first, true);
   camsure::ReceiverProcess duplicate(argv[1], config); wait(duplicate, false);
   require(duplicate.status().find("failure") != std::string::npos, "Port collision must fail, not replace the owner");
   require(first.listening(), "Port collision must preserve original receiver");
   first.request_stop(); wait(first, false); require(first.status() == "Stopped", "Graceful named-event Stop");
  }
  for (unsigned cycle = 0; cycle < 5; ++cycle) {
   camsure::ReceiverProcess restarted(argv[1], config); wait(restarted, true);
   // Destruction must stop the child; the next cycle immediately reuses its port.
  }
  {
   auto invalid = config; invalid.adapter_key = "unavailable";
   camsure::ReceiverProcess process(argv[1], invalid); wait(process, false);
   require(process.status().find("unavailable") != std::string::npos, "Unavailable adapter fails closed");
  }
  {
   auto invalid = config; invalid.usb = true; invalid.peer = "not-an-ip";
   camsure::ReceiverProcess process(argv[1], invalid); wait(process, false);
   require(process.status().find("failure") != std::string::npos, "Invalid USB peer fails closed");
  }
  {
   camsure::ReceiverProcess missing(std::string(argv[1]) + ".missing", config); wait(missing, false);
   require(missing.status().find("missing") != std::string::npos, "Missing package feedback");
  }
  WSACleanup();
  std::puts("PASS: real receiver readiness, collision isolation, graceful Stop, five destructor/restart cycles, unavailable adapter, USB peer and missing-package failures.");
  return 0;
 } catch (const std::exception &error) { std::fprintf(stderr, "FAIL: %s\n", error.what()); return 1; }
}
