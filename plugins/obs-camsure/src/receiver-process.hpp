// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace camsure {
struct ReceiverAdapter {
 std::string id, address, label, key;
 bool ethernet;
};
std::vector<ReceiverAdapter> receiver_adapters();
struct ReceiverConfig {
 bool usb = false;
 std::string adapter_key, peer, pipe;
 int port = 5004;
};
// One owner per source; no shells, global receiver, or implicit route fallback.
class ReceiverProcess {
 std::atomic<bool> stop{false}, done{false}, ready{false};
 std::thread worker;
 mutable std::mutex mutex;
 std::string message = "Connecting receiver...", detail;
 void run(std::string executable, ReceiverConfig config) noexcept;
public:
 ReceiverProcess(std::string executable, ReceiverConfig config);
 ~ReceiverProcess();
 void request_stop() { stop.store(true); }
 bool finished() const { return done.load(); }
 bool listening() const { return ready.load() && !done.load() && !stop.load(); }
 bool stopping() const { return stop.load() && !done.load(); }
 std::string status() const;
 std::string diagnostics() const;
};
}
