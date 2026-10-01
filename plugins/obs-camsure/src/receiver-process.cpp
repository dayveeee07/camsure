// SPDX-License-Identifier: GPL-2.0-or-later
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <iphlpapi.h>
#include "receiver-process.hpp"
#include <algorithm>
#include <filesystem>
#include <stdexcept>

namespace camsure {
namespace {
struct Handle {
 HANDLE value = nullptr;
 explicit Handle(HANDLE h = nullptr) : value(h) {}
 ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
 Handle(const Handle &) = delete;
 Handle &operator=(const Handle &) = delete;
};
std::string utf8(const wchar_t *text) {
 if (!text) return {};
 int length = WideCharToMultiByte(CP_UTF8, 0, text, -1, nullptr, 0, nullptr, nullptr);
 if (length <= 1) return {};
 std::string result(size_t(length), '\0');
 WideCharToMultiByte(CP_UTF8, 0, text, -1, result.data(), length, nullptr, nullptr);
 result.pop_back(); return result;
}
bool token(const std::string &value, size_t maximum) {
 return !value.empty() && value.size() <= maximum && std::all_of(value.begin(), value.end(), [](char c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '{' || c == '}';
 });
}
bool ipv4(const std::string &value) { IN_ADDR address{}; return InetPtonA(AF_INET, value.c_str(), &address) == 1; }
}
std::vector<ReceiverAdapter> receiver_adapters() {
 ULONG size = 16 * 1024;
 std::vector<unsigned char> buffer(size);
 ULONG result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr,
  reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()), &size);
 if (result == ERROR_BUFFER_OVERFLOW) {
  buffer.resize(size);
  result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr,
   reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()), &size);
 }
 if (result != NO_ERROR) throw std::runtime_error("Cannot enumerate PC adapters; refresh and retry.");
 std::vector<ReceiverAdapter> adapters;
 for (auto *a = reinterpret_cast<IP_ADAPTER_ADDRESSES *>(buffer.data()); a; a = a->Next) {
  if (a->OperStatus != IfOperStatusUp || (a->IfType != IF_TYPE_ETHERNET_CSMACD && a->IfType != IF_TYPE_IEEE80211)) continue;
  for (auto *u = a->FirstUnicastAddress; u; u = u->Next) {
   if (u->Address.lpSockaddr->sa_family != AF_INET) continue;
   const auto &address = reinterpret_cast<sockaddr_in *>(u->Address.lpSockaddr)->sin_addr;
   const auto *bytes = reinterpret_cast<const unsigned char *>(&address);
   if (!bytes[0] || bytes[0] == 127 || bytes[0] >= 224 || (bytes[0] == 169 && bytes[1] == 254)) continue;
   char text[INET_ADDRSTRLEN]{}; InetNtopA(AF_INET, const_cast<IN_ADDR *>(&address), text, sizeof(text));
   ReceiverAdapter entry; entry.id = a->AdapterName; entry.address = text;
   entry.key = entry.id + "|" + entry.address + "|" + std::to_string(u->OnLinkPrefixLength);
   entry.label = utf8(a->FriendlyName) + " — " + entry.address + "/" + std::to_string(u->OnLinkPrefixLength);
   entry.ethernet = a->IfType == IF_TYPE_ETHERNET_CSMACD;
   adapters.push_back(std::move(entry));
  }
 }
 return adapters;
}
ReceiverProcess::ReceiverProcess(std::string executable, ReceiverConfig config) {
 worker = std::thread([this, executable = std::move(executable), config = std::move(config)] { run(executable, config); });
}
ReceiverProcess::~ReceiverProcess() { request_stop(); if (worker.joinable()) worker.join(); }
std::string ReceiverProcess::status() const { std::lock_guard<std::mutex> lock(mutex); return message; }
std::string ReceiverProcess::diagnostics() const { std::lock_guard<std::mutex> lock(mutex); return detail; }
void ReceiverProcess::run(std::string executable, ReceiverConfig config) noexcept {
 try {
  if (config.port < 1 || config.port > 65535 || !token(config.pipe, 64)) throw std::runtime_error("Invalid media port or AU pipe name.");
  auto adapters = receiver_adapters();
  auto selected = std::find_if(adapters.begin(), adapters.end(), [&](const auto &a) { return a.key == config.adapter_key; });
  if (selected == adapters.end()) throw std::runtime_error("Selected adapter/address is unavailable. Refresh and select explicitly.");
  if (std::count_if(adapters.begin(), adapters.end(), [&](const auto &a) { return a.address == selected->address; }) != 1)
   throw std::runtime_error("PC address is assigned to multiple adapters; choose an unambiguous address.");
  if (!token(selected->id, 128)) throw std::runtime_error("Unsupported adapter identifier.");
  if (config.usb && (!selected->ethernet || !ipv4(config.peer))) throw std::runtime_error("USB requires an Ethernet adapter and the phone's tethering IPv4.");
  const auto path = std::filesystem::u8path(executable);
  if (!path.is_absolute() || !std::filesystem::is_regular_file(path)) throw std::runtime_error("Packaged receiver is missing. Install the entire CamSure package.");
  // Named events allow graceful shutdown without console signals or stdin reads.
  const auto suffix = std::to_string(GetCurrentProcessId()) + "-" + std::to_string(GetCurrentThreadId()) + "-" + std::to_string(GetTickCount64()) + "-" + config.pipe;
  const std::string stop_name = "Local\\CamSure-stop-" + suffix, ready_name = "Local\\CamSure-ready-" + suffix;
  Handle requested(CreateEventA(nullptr, TRUE, FALSE, stop_name.c_str()));
  Handle listening_event(CreateEventA(nullptr, TRUE, FALSE, ready_name.c_str()));
  Handle job(CreateJobObjectW(nullptr, nullptr));
  if (!requested.value || !listening_event.value || !job.value) throw std::runtime_error("Cannot create receiver lifecycle handles.");
  JOBOBJECT_EXTENDED_LIMIT_INFORMATION limit{}; limit.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
  if (!SetInformationJobObject(job.value, JobObjectExtendedLimitInformation, &limit, sizeof(limit))) throw std::runtime_error("Cannot protect receiver cleanup.");
  SECURITY_ATTRIBUTES security{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
  HANDLE read = nullptr, write = nullptr;
  if (!CreatePipe(&read, &write, &security, 16 * 1024)) throw std::runtime_error("Cannot capture receiver diagnostics.");
  Handle output(read), writer(write);
  SetHandleInformation(output.value, HANDLE_FLAG_INHERIT, 0);
  Handle input(CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &security, OPEN_EXISTING, 0, nullptr));
  if (input.value == INVALID_HANDLE_VALUE || !SetHandleInformation(output.value, HANDLE_FLAG_INHERIT, 0))
   throw std::runtime_error("Cannot isolate receiver I/O handles.");
  std::string args = " --bind " + selected->address + " --port " + std::to_string(config.port) + " --obs-pipe " + config.pipe +
   " --stop-event " + stop_name + " --ready-event " + ready_name;
  if (config.usb) args += " --transport usb --adapter " + selected->id + " --peer " + config.peer + " --receive-buffer-kib 256";
  auto command = L"\"" + path.wstring() + L"\"" + std::wstring(args.begin(), args.end());
  SIZE_T attributes_size = 0;
  InitializeProcThreadAttributeList(nullptr, 1, 0, &attributes_size);
  std::vector<unsigned char> attributes_buffer(attributes_size);
  auto *attributes = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attributes_buffer.data());
  if (!InitializeProcThreadAttributeList(attributes, 1, 0, &attributes_size)) throw std::runtime_error("Cannot initialize receiver handle isolation.");
  struct Attributes { LPPROC_THREAD_ATTRIBUTE_LIST value; ~Attributes() { DeleteProcThreadAttributeList(value); } } cleanup{attributes};
  HANDLE inherited[] = {input.value, writer.value};
  if (!UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited, sizeof(inherited), nullptr, nullptr))
   throw std::runtime_error("Cannot restrict inherited receiver handles.");
  STARTUPINFOEXW startup{}; startup.StartupInfo.cb = sizeof(startup); startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
  startup.StartupInfo.hStdInput = input.value; startup.StartupInfo.hStdOutput = startup.StartupInfo.hStdError = writer.value;
  startup.lpAttributeList = attributes;
  PROCESS_INFORMATION process{};
  if (!CreateProcessW(path.c_str(), command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW | CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT,
   nullptr, path.parent_path().c_str(), &startup.StartupInfo, &process)) throw std::runtime_error("Receiver launch failed (Windows error " + std::to_string(GetLastError()) + ").");
  Handle child(process.hProcess), thread(process.hThread);
  if (!AssignProcessToJobObject(job.value, child.value)) {
   TerminateProcess(child.value, 1); WaitForSingleObject(child.value, 2000);
   throw std::runtime_error("Cannot assign receiver cleanup job.");
  }
  if (ResumeThread(thread.value) == DWORD(-1)) throw std::runtime_error("Cannot resume receiver process.");
  CloseHandle(writer.value); writer.value = nullptr;
  std::string pending;
  auto drain = [&] {
   // Bound parsing work and memory; never retain an ever-growing console log.
   for (unsigned budget = 0; budget < 16; ++budget) {
    DWORD available = 0; if (!PeekNamedPipe(output.value, nullptr, 0, nullptr, &available, nullptr) || !available) break;
    char bytes[1024]; DWORD count = 0;
    if (!ReadFile(output.value, bytes, std::min<DWORD>(available, sizeof(bytes)), &count, nullptr)) break;
    for (DWORD i = 0; i < count; ++i) {
     if (bytes[i] == '\n') { std::lock_guard<std::mutex> lock(mutex); detail = pending; pending.clear(); }
     else if (bytes[i] != '\r' && pending.size() < 512) pending += bytes[i];
    }
   }
  };
  bool signalled = false, failure = false; ULONGLONG stop_time = 0, began = GetTickCount64(), checked = began;
  while (WaitForSingleObject(child.value, 50) == WAIT_TIMEOUT) {
   drain();
   if (!ready.load() && WaitForSingleObject(listening_event.value, 0) == WAIT_OBJECT_0) {
    ready.store(true); std::lock_guard<std::mutex> lock(mutex); message = "Waiting for phone — " + selected->address + ":" + std::to_string(config.port);
   }
   if (!stop.load() && GetTickCount64() - began > 15000 && !ready.load()) {
    std::lock_guard<std::mutex> lock(mutex); message = "Connection failure: receiver startup timed out. Retry."; failure = true; stop.store(true);
   }
   if (!stop.load() && GetTickCount64() - checked > 1000) {
    checked = GetTickCount64();
    auto current = receiver_adapters();
    if (std::none_of(current.begin(), current.end(), [&](const auto &a) { return a.key == config.adapter_key; })) {
     std::lock_guard<std::mutex> lock(mutex); message = "Connection failure: selected adapter/address was lost. Refresh and restart both endpoints."; failure = true; stop.store(true);
    }
   }
   if (stop.load() && !signalled) { SetEvent(requested.value); signalled = true; stop_time = GetTickCount64(); }
   if (signalled && GetTickCount64() - stop_time > 3000) { TerminateJobObject(job.value, 1); WaitForSingleObject(child.value, 2000); break; }
  }
  drain(); DWORD code = 1; GetExitCodeProcess(child.value, &code);
  std::lock_guard<std::mutex> lock(mutex);
  if (!failure) message = stop.load() ? "Stopped" : "Connection failure: receiver exited (" + std::to_string(code) + "). " + detail;
 } catch (const std::exception &error) { std::lock_guard<std::mutex> lock(mutex); message = std::string("Connection failure: ") + error.what(); }
 ready.store(false); done.store(true);
}
}
