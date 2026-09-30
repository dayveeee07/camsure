using System.Net;
using System.Net.Sockets;
using System.Diagnostics;

internal static class UsbNetworkSelfTest
{
    private sealed class Sink : IEncodedVideoSink
    {
        public List<EncodedVideoAccessUnit> Units { get; } = [];
        public void Submit(EncodedVideoAccessUnit unit) => Units.Add(unit);
    }
    internal static void Run()
    {
        void Require(bool value, string label) { if (!value) throw new InvalidOperationException(label); }
        var timing = new ReceiveTimingStats();
        var t = Stopwatch.Frequency;
        timing.Received(t, t, 1200);
        timing.BeforeReceive(t + Stopwatch.Frequency / 100);
        timing.Received(t, t, 1200);
        Require(timing.Report().Contains("receive-bucket-1ms-max=2400bytes"), "Receive burst accounting");
        Require(timing.Report().Contains("receive-service-max=10"), "Service pause measurement");
        timing.Idle();
        timing.BeforeReceive(t + Stopwatch.Frequency);
        Require(timing.Report().Contains("receive-service-max=10"), "Network idle excluded from service pause");
        using (var entered = new ManualResetEventSlim())
        using (var release = new ManualResetEventSlim()) {
            var writer = new LatestTelemetryWriter(_ => { entered.Set(); release.Wait(); });
            try {
                writer.Publish("first");
                Require(entered.Wait(1000), "Telemetry worker start");
                for (var i = 0; i < 1000; i++) writer.Publish("snapshot " + i);
                Require(writer.Replaced == 999, "Blocked telemetry remains bounded to one pending snapshot without blocking producer");
            } finally { release.Set(); writer.Dispose(); }
        }
        var local = IPAddress.Parse("192.0.2.1"); var peer = IPAddress.Parse("192.0.2.2");
        var selected = new UsbNetworkSelection("fixture", "Simulated", local, 24);
        Require(UsbNetworkSelection.Select([selected], "fixture", local, peer) == selected, "Explicit selection");
        void Rejected(Action action) { try { action(); } catch (ArgumentException) { return; } throw new InvalidOperationException("Unsafe selection accepted"); }
        Rejected(() => UsbNetworkSelection.Select([selected, selected], "fixture", local, peer));
        Rejected(() => UsbNetworkSelection.Select([], "fixture", local, peer));
        Rejected(() => UsbNetworkSelection.Select([selected], "fixture", local, IPAddress.Parse("192.0.2.255")));
        Rejected(() => UsbNetworkSelection.Select([selected], "fixture", local, IPAddress.Parse("198.51.100.2")));
        Require(UsbNetworkSelection.AcceptPeer(peer, new IPEndPoint(peer, 12345)), "Expected peer");
        Require(!UsbNetworkSelection.AcceptPeer(peer, new IPEndPoint(local, 12345)), "Foreign peer rejected before RTP");
        Require(Program.Options.TryParse([], out var lan, out _) && !lan.Usb && lan.BindAddress.Equals(IPAddress.Any) && lan.Port == 5004, "LAN defaults");
        Require(!Program.Options.TryParse(["--transport", "usb"], out _, out _), "USB wildcard forbidden");
        Require(!Program.Options.TryParse(["--receive-buffer-kib", "256"], out _, out _), "USB buffer override cannot alter LAN defaults");
        Require(!Program.Options.TryParse(["--receive-buffer-kib", "257"], out _, out _), "Receive buffer cap");
        Require(Program.Options.TryParse(["--transport", "usb", "--adapter", "fixture", "--bind", "192.0.2.1", "--peer", "192.0.2.2", "--receive-buffer-kib", "256"], out var trial, out _) && trial.ReceiveBufferKiB == 256, "Explicit USB burst-buffer trial");
        Require(Program.Options.TryParse(["--transport", "usb", "--adapter", "fixture", "--bind", "192.0.2.1", "--peer", "192.0.2.2"], out var usb, out _) && usb.Usb, "USB CLI");
        using (var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp)) {
            socket.Bind(new IPEndPoint(IPAddress.Loopback, 0));
            Require(((IPEndPoint)socket.LocalEndPoint!).Address.Equals(IPAddress.Loopback), "Explicit socket bind (host only)");
            Require(!socket.Poll(1000, SelectMode.SelectRead), "Readiness idle without receive cancellation");
            using var sender = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            var target = socket.LocalEndPoint!;
            for (var i = 0; i < 128; i++) {
                sender.SendTo(new byte[] { (byte)i }, target);
                Require(socket.Poll(1_000_000, SelectMode.SelectRead), "Readiness sees sent packet");
                EndPoint from = new IPEndPoint(IPAddress.Any, 0);
                var data = new byte[2];
                Require(socket.ReceiveFrom(data, ref from) == 1 && data[0] == i, "Readiness receive preserves datagram sequence");
            }
        }
        var sink = new Sink(); var receiver = new RtpH264Receiver(sink, 100, true);
        void Send(uint stream, ushort sequence, byte[] nal, bool marker) {
            var p = ReceiverSelfTest.Packet(sequence, 0, 100, nal, marker, true, stream);
            receiver.Process(p, p.Length, Stopwatch.GetTimestamp());
        }
        Send(1, 1, [0x67, 1], false); Send(1, 2, [0x68, 2], false); Send(1, 3, [0x65, 3], true);
        var first = sink.Units.Single();
        Send(2, 1, [0x67, 4], false); Send(2, 2, [0x68, 5], false); Send(2, 3, [0x65, 6], true);
        Require(sink.Units.Count == 2 && sink.Units[^1].SessionId != first.SessionId && sink.Units[^1].Discontinuity, "Fresh generation/config/IDR");
        Send(1, 4, [0x65, 9], true);
        Require(sink.Units.Count == 2, "Retired SSRC cannot revive stale video");
        var partial = ReceiverSelfTest.Packet(4, 3000, 33433, [0x7c, 0x85, 1], false, true, 2);
        var now = Stopwatch.GetTimestamp(); receiver.Process(partial, partial.Length, now);
        receiver.ExpireStaleAccessUnit(now + Stopwatch.Frequency / 5);
        Require(receiver.FormatReport(TimeSpan.FromSeconds(1)).Contains("stale-AUs=1"), "USB partial AU expires at 100ms");
        Console.WriteLine("PASS: simulated USB selection/ambiguity/subnet, peer isolation, host bind, LAN defaults, generation/discontinuity, retired SSRC and 100ms expiry. No physical USB claim.");
    }
}
